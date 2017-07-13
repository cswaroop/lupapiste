(ns lupapalvelu.application-api
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof
                                                warnf warn error errorf]]
            [clj-time.core :refer [year]]
            [clj-time.local :refer [local-now]]
            [slingshot.slingshot :refer [try+]]
            [monger.operators :refer :all]
            [sade.coordinate :as coord]
            [sade.core :refer :all]
            [sade.env :as env]
            [sade.util :as util]
            [sade.strings :as ss]
            [sade.property :as prop]
            [lupapalvelu.action :refer [defraw defquery defcommand
                                        update-application notify] :as action]
            [lupapalvelu.application :as app]
            [lupapalvelu.application-utils :as app-utils]
            [lupapalvelu.application-meta-fields :as meta-fields]
            [lupapalvelu.assignment :as assignment]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.comment :as comment]
            [lupapalvelu.company :as company]
            [lupapalvelu.document.document :as doc]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.drawing :as draw]
            [lupapalvelu.foreman :as foreman]
            [lupapalvelu.logging :as logging]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.open-inforequest :as open-inforequest]
            [lupapalvelu.operations :as op]
            [lupapalvelu.organization :as org]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.roles :as roles]
            [lupapalvelu.states :as states]
            [lupapalvelu.state-machine :as sm]
            [lupapalvelu.suti :as suti]
            [lupapalvelu.user :as usr]
            [lupapalvelu.xml.krysp.application-as-krysp-to-backing-system :as krysp-output]
            [lupapalvelu.ya :as ya])
  (:import (java.net SocketTimeoutException)))

(defn- return-to-draft-model [{{:keys [text]} :data :as command} conf recipient]
  (assoc (notifications/create-app-model command conf recipient)
    :text text))

(defn return-to-draft-recipients
  "the notification is sent to applicants in auth array and application owners"
  [{{:keys [auth documents] :as application} :application}]
  (let [applicant-in-auth? (->> auth (remove :invite) (remove :unsubscribed) (map :id) set)
        applicant-ids (->> (domain/get-applicant-documents documents)
                           (map (comp :value :userId :henkilo :data))
                           (filter applicant-in-auth?))
        owner-ids (->> (auth/get-auths-by-role application :owner)
                       (remove :invite)
                       (remove :unsubscribed)
                       (map :id))]
    (map (comp usr/non-private usr/get-user-by-id)
         (distinct (remove nil? (concat applicant-ids owner-ids))))))

(notifications/defemail :application-return-to-draft
  {:subject-key "return-to-draft"
   :recipients-fn return-to-draft-recipients
   :model-fn return-to-draft-model})

;; Validators

(defn operation-validator [{{operation :operation} :data}]
  (when-not (op/operations (keyword operation)) (fail :error.unknown-type)))

(defquery application
  {:parameters       [:id]
   :states           states/all-states
   :user-roles       #{:applicant :authority :oirAuthority}
   :user-authz-roles roles/all-authz-roles
   :org-authz-roles  roles/reader-org-authz-roles}
  [{:keys [application user] :as command}]
  (if application
    (ok :application (app/post-process-app command)
        :permitSubtypes (app/resolve-valid-subtypes application))
    (fail :error.application-not-found)))

(defquery application-authorities
  {:user-roles #{:authority}
   :states     (states/all-states-but :draft)
   :parameters [:id]}
  [{app :application}]
  (ok :authorities (app/application-org-authz-users app #{"authority"})))

(defquery application-commenters
  {:user-roles #{:authority}
   :states     (states/all-states-but :draft)
   :parameters [:id]}
  [{app :application}]
  (ok :authorities (app/application-org-authz-users app #{"authority" "commenter"})))

(defn validate-authority-in-applications-org
  [{:keys [user application]}]
  (when-not (usr/user-is-authority-in-organization? user (:organization application))
    (fail :error.unauthorized
          :source ::validate-authority-in-applications-org)))

(defquery enable-accordions
  {:description "Pseudo-query for checking if accordions should be open or
                 closed"
   :user-roles roles/all-authenticated-user-roles
   :pre-checks [(action/some-pre-check
                  (action/and-pre-check
                    (permit/validate-permit-type-is :YA)
                    usr/validate-authority
                    validate-authority-in-applications-org)
                  (action/not-pre-check
                     usr/validate-authority
                    :error.unauthorized :source ::enable-accordions))]}
  [_])

(defquery party-document-names
  {:parameters [:id]
   :user-roles #{:applicant :authority}
   :states     states/all-application-states}
  [{{:keys [documents schema-version state] :as application} :application}]
  (let [op-meta (op/get-primary-operation-metadata application)
        original-schema-names   (->> (select-keys op-meta [:required :optional]) vals (apply concat))
        original-party-schemas  (app/filter-party-docs schema-version original-schema-names false)
        repeating-party-schemas (app/filter-party-docs schema-version original-schema-names true)
        current-schema-name-set (->> documents (filter app/party-document?) (map (comp name :name :schema-info)) set)
        missing-schema-names    (remove current-schema-name-set original-party-schemas)
        candidate-schema-names  (conj
                                  (concat missing-schema-names repeating-party-schemas)
                                  (op/get-applicant-doc-schema-name application))
        remove-by-state-fn      (fn [schemaName]
                                  (let [schema (schemas/get-schema schema-version schemaName)]
                                    (doc/state-valid-by-schema? schema :addable-in-states states/create-doc-states state)))]
    (ok :partyDocumentNames (-> (filter remove-by-state-fn candidate-schema-names)
                                distinct))))

(defcommand mark-seen
  {:parameters       [id type]
   :input-validators [(fn [{{type :type} :data}] (when-not (app/collections-to-be-seen type) (fail :error.unknown-type)))]
   :user-roles       #{:applicant :authority :oirAuthority}
   :user-authz-roles roles/all-authz-roles
   :org-authz-roles roles/reader-org-authz-roles  ;; For info-links
   :states           states/all-states
   :pre-checks       [app/validate-authority-in-drafts]}
  [{:keys [data user created] :as command}]
  (update-application command {$set (app/mark-collection-seen-update user created type)}))

(defcommand mark-everything-seen
  {:parameters [:id]
   :user-roles #{:authority :oirAuthority}
   :states     (states/all-states-but [:draft])}
  [{:keys [application user created] :as command}]
  (update-application command {$set (app/mark-indicators-seen-updates application user created)}))

;;
;; Assign
;;

(defn- validate-handler-role [{{role-id :roleId} :data org :organization}]
  (when (and role-id (not (util/find-by-id role-id (:handler-roles @org))))
    (fail :error.unknown-handler)))

(defn- validate-handler-role-not-in-use
  "Pre-check for setting application handler. Validates that handler role is not already set on application."
  [{{role-id :roleId handler-id :handlerId} :data {application-handlers :handlers} :application}]
  (when (and role-id (->> (remove (comp #{handler-id} :id) application-handlers)
                          (util/find-by-key :roleId role-id)))
    (fail :error.duplicate-handler-role)))

(defn- validate-handler-id-in-application [{{handler-id :handlerId} :data {application-handlers :handlers} :application}]
  (when (and handler-id (not (util/find-by-id handler-id application-handlers)))
    (fail :error.unknown-handler)))

(defn- validate-handler-in-organization [{{user-id :userId} :data {application-org :organization} :application}]
  (when (and user-id (not (usr/find-user {:id user-id (util/kw-path :orgAuthz application-org) "authority" :enabled true})))
    (fail :error.unknown-handler)))

(defcommand upsert-application-handler
  {:parameters [id userId roleId]
   :optional-parameters [handlerId]
   :pre-checks [validate-handler-role
                validate-handler-role-not-in-use
                validate-handler-id-in-application
                validate-handler-in-organization]
   :input-validators [(partial action/non-blank-parameters [:id :userId :roleId])]
   :user-roles #{:authority}
   :states     (states/all-states-but :draft :canceled)}
  [{created :created {handlers :handlers application-org :organization} :application user :user :as command}]
  (let [handler (->> (usr/find-user {:id userId (util/kw-path :orgAuthz application-org) "authority"})
                     (usr/create-handler handlerId roleId))]
    (update-application command (app/handler-upsert-updates handler handlers created user))
    (assignment/change-assignment-recipient id roleId handler)
    (ok :id (:id handler))))

(defcommand remove-application-handler
  {:parameters [id handlerId]
   :pre-checks [validate-handler-id-in-application]
   :input-validators [(partial action/non-blank-parameters [:id :handlerId])]
   :user-roles #{:authority}
   :states     (states/all-states-but :draft :canceled)}
  [{created :created {handlers :handlers} :application user :user :as command}]
  (let [result   (update-application command
                                     {$set  {:modified created}
                                      $pull {:handlers {:id handlerId}}
                                      $push {:history  (app/handler-history-entry {:id handlerId :removed true} created user)}})]
    (assignment/remove-assignment-recipient id handlerId)
    result))



;;
;; Cancel
;;



(defcommand cancel-inforequest
  {:parameters       [id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:applicant :authority :oirAuthority}
   :notified         true
   :on-success       (notify :application-state-change)
   :pre-checks       [(partial sm/validate-state-transition :canceled)]}
  [command]
  (app/cancel-inforequest command))

(defcommand cancel-application
  {:parameters       [id text lang]
   :input-validators [(partial action/non-blank-parameters [:id :lang])]
   :user-roles       #{:applicant :authority}
   :user-authz-roles (conj roles/default-authz-writer-roles :foreman)
   :notified         true
   :on-success       (notify :application-state-change)
   :states           #{:draft :info :open :submitted}
   :pre-checks       [(partial sm/validate-state-transition :canceled)
                      action/outside-authority-only
                      foreman/allow-foreman-only-in-foreman-app]}
  [command]
  (app/cancel-application command))

(defcommand cancel-application-authority
  {:parameters       [id text lang]
   :input-validators [(partial action/non-blank-parameters [:id :lang])]
   :user-roles       #{:authority}
   :notified         true
   :on-success       (notify :application-state-change)
   :pre-checks       [app/validate-authority-in-drafts
                      (partial sm/validate-state-transition :canceled)]}
  [command]
  (app/cancel-application command))

(defcommand undo-cancellation
  {:parameters       [id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:authority :applicant}
   :user-authz-roles (conj roles/default-authz-writer-roles :foreman)
   :pre-checks       [(fn [{:keys [application]}]
                        (when-not (= :canceled
                                     ((comp keyword :state) (app/last-history-item application)))
                          (fail :error.latest-state-not-canceled)))
                      (fn [{:keys [application]}]
                        (when-not (states/all-states (app/get-previous-app-state application))
                          (fail :error.illegal-state)))
                      (fn [{:keys [application user]}]
                        (when-not (usr/authority? user)
                          (let [canceled-entry (app/last-history-item application)]
                            (when-not (= (:username user) (get-in canceled-entry [:user :username]))
                              (fail :error.undo-only-for-canceler)))))]
   :on-success       (notify :undo-cancellation)
   :states           #{:canceled}}
  [command]
  (app/undo-cancellation command))


(defcommand request-for-complement
  {:parameters       [:id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:authority}
   :notified         true
   :on-success       (notify :application-state-change)
   :pre-checks       [(partial sm/validate-state-transition :complementNeeded)]}
  [{:keys [created user application] :as command}]
  (update-application command (util/deep-merge (app/state-transition-update :complementNeeded created application user))))

(defcommand cleanup-krysp
  {:description      "Removes application KRYSP messages. The cleanup
  criteria depends on the message contents."
   :parameters       [:id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:authority}
   :states           #{:complementNeeded}}
  [{:keys [application]}]
  (krysp-output/cleanup-output-dir application))

(notifications/defemail :neighbor-hearing-requested
  {:pred-fn       (fn [command] (get-in command [:application :options :municipalityHearsNeighbors]))
   :recipients-fn (fn [{application :application org :organization}]
                    (let [organization (or (and org @org) (org/get-organization (:organization application)))
                          emails (get-in organization [:notifications :neighbor-order-emails])]
                      (map (fn [e] {:email e, :role "authority"}) emails)))
   :tab-fn (constantly "statement")})

(notifications/defemail :organization-on-submit
  {:recipients-fn (fn [{application :application org :organization}]
                    (let [organization (or (and org @org) (org/get-organization (:organization application)))
                          emails (get-in organization [:notifications :submit-notification-emails])]
                      (map (fn [e] {:email e, :role "authority"}) emails)))
   :model-fn (fn [{app :application :as command} conf recipient]
               (assoc (notifications/create-app-model command conf recipient)
                 :applicants (reduce #(str %1 ", " %2) (:_applicantIndex app))))})

(defn submit-validation-errors [{:keys [application] :as command}]
  (remove nil? (conj []
                     (foreman/validate-application application)
                     (app/validate-link-permits application)
                     (app/validate-fully-formed application)
                     (ya/validate-digging-permit application)
                     (when-not (company/cannot-submit command)
                       (fail :company.user.cannot.submit))
                     (suti/suti-submit-validation command))))

(defquery application-submittable
  {:description "Query for frontend, to display possible errors regarding application submit"
   :parameters [id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:applicant :authority}
   :states           #{:draft :open}}
  [command]
  (let [command (assoc command :application (meta-fields/enrich-with-link-permit-data (:application command)))]
    (if-some [errors (seq (submit-validation-errors command))]
      (fail :error.cannot-submit-application :errors errors)
      (ok))))

(defcommand submit-application
  {:parameters       [id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:applicant :authority}
   :user-authz-roles (conj roles/default-authz-writer-roles :foreman)
   :states           #{:draft :open}
   :notified         true
   :on-success       [(notify :application-state-change)
                      (notify :neighbor-hearing-requested)
                      (notify :organization-on-submit)]
   :pre-checks       [(action/some-pre-check
                       domain/validate-owner-or-write-access
                       usr/validate-authority-in-organization)
                      foreman/allow-foreman-only-in-foreman-app
                      app/validate-authority-in-drafts
                      (partial sm/validate-state-transition :submitted)]}
  [{:keys [application] :as command}]
  (let [command (assoc command :application (meta-fields/enrich-with-link-permit-data application))]
    (if-some [errors (seq (submit-validation-errors command))]
      (fail :error.cannot-submit-application :errors errors)
      (app/submit command))))

(defcommand refresh-ktj
  {:parameters [:id]
   :user-roles #{:authority}
   :states     (states/all-application-states-but (conj states/terminal-states :draft))}
  [{:keys [application created]}]
  (app/autofill-rakennuspaikka application created)
  (ok))

(defcommand save-application-drawings
  {:parameters       [:id drawings]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:applicant :authority :oirAuthority}
   :states           #{:draft :info :answered :open :submitted :complementNeeded}
   :pre-checks       [app/validate-authority-in-drafts]}
  [{:keys [created] :as command}]
  (when (sequential? drawings)
    (update-application command
                        {$set {:modified created
                               :drawings (map (fn [drawing] (assoc drawing :geometry-wgs84 (draw/wgs84-geometry drawing))) drawings)}})))

(defn- make-marker-contents [id lang {:keys [location] :as app}]
  (merge
    {:id        (:id app)
     :title     (:title app)
     :location  {:x (first location) :y (second location)}
     :operation (app-utils/operation-description app lang)
     :authName  (-> app
                    (auth/get-auths-by-role :owner)
                    first
                    (#(str (:firstName %) " " (:lastName %))))
     :comments  (->> (:comments app)
                     (filter #(not (= "system" (:type %))))
                     (map #(identity {:name (str (-> % :user :firstName) " " (-> % :user :lastName))
                                      :type (:type %)
                                      :time (:created %)
                                      :text (:text %)})))}
    (when-not (= id (:id app))
      {:link (str (env/value :host) "/app/" (name lang) "/authority#!/inforequest/" (:id app))})))

(defn- remove-irs-by-id [target-irs irs-to-be-removed]
  (remove (fn [ir] (some #(= (:id ir) (:id %)) irs-to-be-removed)) target-irs))

(defquery inforequest-markers
          {:parameters       [id lang x y]
           :user-roles       #{:authority :oirAuthority}
           :states           states/all-inforequest-states
           :input-validators [(partial action/non-blank-parameters [:id :x :y])]}
          [{:keys [application user]}]
          (let [x (util/->double x)
                y (util/->double y)
                inforequests (mongo/select :applications
                                           (merge
                                             (domain/application-query-for user)
                                             {:infoRequest true})
                                           [:title :auth :location :primaryOperation :secondaryOperations :comments])

                same-location-irs (filter
                                    #(and (== x (-> % :location first)) (== y (-> % :location second)))
                                    inforequests)

                inforequests (remove-irs-by-id inforequests same-location-irs)

                application-op-name (-> application :primaryOperation :name)

                same-op-irs (filter
                              (fn [ir]
                                (some #(= application-op-name (:name %)) (app/get-operations ir)))
                              inforequests)

                others (remove-irs-by-id inforequests same-op-irs)

                same-location-irs (map (partial make-marker-contents id lang) same-location-irs)
                same-op-irs (map (partial make-marker-contents id lang) same-op-irs)
                others (map (partial make-marker-contents id lang) others)]

            (ok :sameLocation same-location-irs :sameOperation same-op-irs :others others)
            ))

(notifications/defemail :inforequest-invite
  {:recipients-fn (fn [{application :application org :organization}]
                    (let [organization (or (and org @org) (org/get-organization (:organization application)))
                          emails (get-in organization [:notifications :inforequest-notification-emails])]
                      (map (fn [e] {:email e, :role "authority"}) emails)))})


(defcommand create-application
  {:parameters       [:operation :x :y :address :propertyId]
   :user-roles       #{:applicant :authority}
   :notified         true                                   ; info requests (also oir)
   :input-validators [(partial action/non-blank-parameters [:operation :address :propertyId])
                      (partial action/property-id-parameters [:propertyId])
                      coord/validate-x coord/validate-y
                      operation-validator]}
  [{{:keys [infoRequest]} :data :keys [created] :as command}]
  (let [created-application (app/do-create-application command)]
    (logging/with-logging-context {:applicationId (:id created-application)}
      (app/insert-application created-application)
      (when (boolean infoRequest)
        ; Notify organization about new inforequest
        (if (:openInfoRequest created-application)
          (open-inforequest/new-open-inforequest! created-application)
          (notifications/notify! :inforequest-invite {:application created-application})))
      (try
        (try+
         (app/autofill-rakennuspaikka created-application created)
         (catch [:sade.core/type :sade.core/fail] {:keys [cause text] :as exp}
           (warnf "Could not get KTJ data for the new application, cause: %s, text: %s. From %s:%s"
                  cause
                  text
                  (:sade.core/file exp)
                  (:sade.core/line exp)))
         (catch SocketTimeoutException _
           (warn "Socket timeout from KTJ when creating application")))
        (catch Exception e
          (warn "Exception when creating application: " (.getMessage e))))
      (ok :id (:id created-application)))))

(defn- add-operation-allowed? [{application :application}]
  (let [op (-> application :primaryOperation :name keyword)
        permit-subtype (keyword (:permitSubtype application))]
    (when-not (and (or (nil? op) (:add-operation-allowed (op/operations op)))
                   (not= permit-subtype :muutoslupa))
      (fail :error.add-operation-not-allowed))))

(defcommand add-operation
  {:parameters       [id operation]
   :user-roles       #{:applicant :authority}
   :states           states/pre-sent-application-states
   :input-validators [operation-validator]
   :pre-checks       [add-operation-allowed?
                      app/validate-authority-in-drafts]}
  [{{app-state :state
     tos-function :tosFunction :as application} :application
    organization :organization
    created :created :as command}]
  (let [op (app/make-op operation created)
        new-docs (app/make-documents nil created @organization op application)
        attachments (:attachments (domain/get-application-no-access-checking id {:attachments true}))
        new-attachments (app/make-attachments created op @organization app-state tos-function :existing-attachments-types (map :type attachments))
        attachment-updates (app/multioperation-attachment-updates op @organization attachments)]
    (update-application command {$push {:secondaryOperations  op
                                        :documents   {$each new-docs}
                                        :attachments {$each new-attachments}}
                                 $set  {:modified created}})
    ;; Cannot update existing array and push new items into it same time with one update
    (when (not-empty attachment-updates) (update-application command attachment-updates))))

(defcommand update-op-description
  {:parameters [id op-id desc]
   :categories #{:documents} ; edited from document header
   :input-validators [(partial action/non-blank-parameters [:id :op-id])
                      (partial action/string-parameters [:desc])]
   :user-roles #{:applicant :authority}
   :states     states/pre-sent-application-states
   :pre-checks [app/validate-authority-in-drafts]}
  [{:keys [application] :as command}]
  (if (= (get-in application [:primaryOperation :id]) op-id)
    (update-application command {$set {"primaryOperation.description" desc}})
    (update-application command {"secondaryOperations" {$elemMatch {:id op-id}}} {$set {"secondaryOperations.$.description" desc}})))

(defcommand change-primary-operation
  {:parameters [id secondaryOperationId]
   :categories #{:documents} ; edited from document header
   :input-validators [(partial action/non-blank-parameters [:id :secondaryOperationId])]
   :user-roles #{:applicant :authority}
   :states states/pre-sent-application-states
   :pre-checks [app/validate-authority-in-drafts]}
  [{:keys [application] :as command}]
  (let [old-primary-op (:primaryOperation application)
        old-secondary-ops (:secondaryOperations application)
        new-primary-op (first (filter #(= secondaryOperationId (:id %)) old-secondary-ops))
        secondary-ops-without-old-primary-op (remove #{new-primary-op} old-secondary-ops)
        new-secondary-ops (if old-primary-op ; production data contains applications with nil in primaryOperation
                            (conj secondary-ops-without-old-primary-op old-primary-op)
                            secondary-ops-without-old-primary-op)]
    (when-not (= (:id old-primary-op) secondaryOperationId)
      (when-not new-primary-op
        (fail! :error.unknown-operation))
      ;; TODO update also :app-links apptype if application is linked to other apps (loose WriteConcern ok?)
      (update-application command {$set {:primaryOperation    new-primary-op
                                         :secondaryOperations new-secondary-ops}}))
    (ok)))

(defcommand change-permit-sub-type
  {:parameters       [id permitSubtype]
   :user-roles       #{:applicant :authority}
   :user-authz-roles (conj roles/default-authz-writer-roles :foreman)
   :states           states/pre-sent-application-states
   :input-validators [(partial action/non-blank-parameters [:id :permitSubtype])]
   :pre-checks       [app/validate-has-subtypes
                      app/pre-check-permit-subtype
                      foreman/allow-foreman-only-in-foreman-app
                      ya/authority-only
                      app/validate-authority-in-drafts]}
  [{:keys [application created] :as command}]
  (update-application command {$set {:permitSubtype permitSubtype, :modified created}})
  (ok))

(defn authority-if-post-verdict-state [{user :user app :application}]
  (when-not (or (usr/authority? user)
                (states/pre-verdict-states (keyword (:state app))))
    (fail :error.unauthorized)))

(defcommand change-location
  {:parameters       [id x y address propertyId]
   :user-roles       #{:applicant :authority :oirAuthority}
   :states           (states/all-states-but (conj states/terminal-states :sent))
   :input-validators [(partial action/non-blank-parameters [:address])
                      (partial action/property-id-parameters [:propertyId])
                      coord/validate-x coord/validate-y]
   :pre-checks       [authority-if-post-verdict-state
                      app/validate-authority-in-drafts]}
  [{:keys [created application] :as command}]
  (if (= (:municipality application) (prop/municipality-id-by-property-id propertyId))
    (do
      (update-application command
                          {$set {:location   (app/->location x y)
                                 :location-wgs84 (coord/convert "EPSG:3067" "WGS84" 5 (app/->location x y))
                                 :address    (ss/trim address)
                                 :propertyId propertyId
                                 :title      (ss/trim address)
                                 :modified   created}
                           $unset {:propertyIdSource true}})
      (try (app/autofill-rakennuspaikka (mongo/by-id :applications id) (now))
           (catch Exception e (warn "KTJ data was not updated after location changed"))))
    (fail :error.property-in-other-muinicipality)))

(defcommand change-application-state
  {:description      "Changes application state. The tranistions happen
  between post-verdict (excluding verdict given)states. In addition,
  the transition from appealed to a verdict given state is supported."
   :parameters       [id state]
   :input-validators [(partial action/non-blank-parameters [:state])]
   :user-roles       #{:authority}
   :states           states/post-verdict-states
   :pre-checks       [permit/valid-permit-types-for-state-change app/valid-new-state]
   :notified         true
   :on-success       (notify :application-state-change)}
  [{:keys [user application] :as command}]
  (let [organization    (deref (:organization command))
        application     (:application command)
        krysp?          (org/krysp-integration? organization (permit/permit-type application))
        warranty?       (and (permit/is-ya-permit (permit/permit-type application)) (util/=as-kw state :closed) (not krysp?))]
    (if warranty?
      (update-application command (util/deep-merge
                                    (app/state-transition-update (keyword state) (:created command) application user)
                                    {$set (app/warranty-period (:created command))}))
      (update-application command (app/state-transition-update (keyword state) (:created command) application user)))))

(defcommand return-to-draft
  {:description "Returns the application to draft state."
   :parameters       [id text lang]
   :input-validators [(partial action/non-blank-parameters [:id :lang])]
   :user-roles #{:authority}
   :states #{:submitted}
   :pre-checks [(partial sm/validate-state-transition :draft)]
   :on-success (notify :application-return-to-draft)}
  [{{:keys [role] :as user}         :user
    {:keys [state] :as application} :application
    created                         :created
    :as command}]
  (->> (util/deep-merge
        (app/state-transition-update :draft created application user)
        (when (seq text)
          (comment/comment-mongo-update state text {:type "application"} role false user nil created))
        {$set {:submitted nil}})
       (update-application command)))

(defcommand change-warranty-start-date
  {:description      "Changes warranty start date"
   :parameters       [id startDate]
   :input-validators [(partial action/number-parameters [:startDate])
                      (partial action/positive-number-parameters [:startDate])]
   :user-roles       #{:authority}
   :states           states/post-verdict-states}
   [{:keys [application] :as command}]
  (update-application command {$set {:warrantyStart startDate}})
  (ok))

(defcommand change-warranty-end-date
  {:description      "Changes warranty end date"
   :parameters       [id endDate]
   :input-validators [(partial action/number-parameters [:endDate])
                      (partial action/positive-number-parameters [:endDate])]
   :user-roles       #{:authority}
   :states           states/post-verdict-states}
  [{:keys [application] :as command}]
  (update-application command {$set {:warrantyEnd endDate}})
  (ok))

(defquery change-application-state-targets
  {:description "List of possible target states for
  change-application-state transitions."
   :user-roles  #{:authority}
   :pre-checks  [permit/valid-permit-types-for-state-change]
   :states      states/post-verdict-states}
  [{application :application}]
  (ok :states (app/change-application-state-targets application)))

;;
;; Link permits
;;

(defquery link-permit-required
          {:description "Dummy command for UI logic: returns falsey if link permit is not required."
           :parameters  [:id]
           :user-roles  #{:applicant :authority}
           :states      states/pre-sent-application-states
           :pre-checks  [(fn [{application :application}]
                           (when-not (app/validate-link-permits application)
                             (fail :error.link-permit-not-required)))]})

(defquery app-matches-for-link-permits
  {:parameters [id]
   :description "Retuns a list of application IDs that can be linked to current application."
   :user-roles #{:applicant :authority}
   :states     (states/all-application-states-but (conj states/terminal-states :sent))}
  [{{:keys [propertyId] :as application} :application user :user :as command}]
  (let [application (meta-fields/enrich-with-link-permit-data application)
        ;; exclude from results the current application itself, and the applications that have a link-permit relation to it
        ignore-ids (-> application
                       (#(concat (:linkPermitData %) (:appsLinkingToUs %)))
                       (#(map :id %))
                       (conj id))
        results (mongo/select :applications
                              (merge (domain/application-query-for user) {:_id             {$nin ignore-ids}
                                                                          :infoRequest     false
                                                                          ; Backend systems support only the same kind of link permits.
                                                                          ; We COULD filter the other kinds from XML messages in the future...
                                                                          :permitType      (:permitType application)
                                                                          :secondaryOperations.name {$nin ["ya-jatkoaika"]}
                                                                          :primaryOperation.name {$nin ["ya-jatkoaika"]}})

                              [:permitType :address :propertyId])
        ;; add the text to show in the dropdown for selections
        enriched-results (map
                           (fn [r] (assoc r :text (str (:address r) ", " (:id r))))
                           results)
        ;; sort the results
        same-property-id-fn #(= propertyId (:propertyId %))
        with-same-property-id (vec (filter same-property-id-fn enriched-results))
        without-same-property-id (sort-by :text (vec (remove same-property-id-fn enriched-results)))
        organized-results (flatten (conj with-same-property-id without-same-property-id))
        final-results (map #(select-keys % [:id :text :propertyId]) organized-results)]
    (ok :app-links final-results)))

(defn- validate-linking [{app :application :as command}]
  (let [link-permit-id (ss/trim (get-in command [:data :linkPermitId]))
        {:keys [appsLinkingToUs linkPermitData]} (meta-fields/enrich-with-link-permit-data app)
        max-outgoing-link-permits (op/get-primary-operation-metadata app :max-outgoing-link-permits)
        links    (concat appsLinkingToUs linkPermitData)
        illegal-apps (conj links app)]
    (cond
      (and link-permit-id (util/find-by-id link-permit-id illegal-apps))
      (fail :error.link-permit-already-having-us-as-link-permit)

      (and max-outgoing-link-permits (= max-outgoing-link-permits (count linkPermitData)))
      (fail :error.max-outgoing-link-permits))))

(defcommand add-link-permit
  {:parameters       ["id" linkPermitId]
   :user-roles       #{:applicant :authority}
   :user-authz-roles (conj roles/default-authz-writer-roles :foreman)
   :states           (states/all-application-states-but (conj states/terminal-states :sent)) ;; Pitaako olla myos 'sent'-tila?
   :pre-checks       [validate-linking
                      app/validate-authority-in-drafts]
   :input-validators [(partial action/non-blank-parameters [:linkPermitId])
                      (fn [{data :data}] (when (= (:id data) (ss/trim (:linkPermitId data))) (fail :error.link-permit-self-reference)))
                      (fn [{data :data}] (when-not (mongo/valid-key? (:linkPermitId data)) (fail :error.invalid-db-key)))]}
  [{application :application}]
  (app/do-add-link-permit application (ss/trim linkPermitId))
  (ok))

(defcommand remove-link-permit-by-app-id
  {:parameters [id linkPermitId]
   :input-validators [(partial action/non-blank-parameters [:id :linkPermitId])]
   :user-roles #{:applicant :authority}
   :states     (states/all-application-states-but (conj states/terminal-states :sent))
   :pre-checks [app/validate-authority-in-drafts ;; Pitaako olla myos 'sent'-tila?
                app/authorized-to-remove-link-permit]}
  [{application :application}]
  (if (mongo/remove :app-links (app/make-mongo-id-for-link-permit id linkPermitId))
    (ok)
    (fail :error.unknown)))

(defquery all-operations-in
  {:description "Return all operation names in operation tree for given paths."
   :optional-parameters [path]
   :user-roles          #{:authority :oirAuthority :applicant}
   :input-validators    [(partial action/string-parameters [:path])]}
  [command]
  (ok :operations (op/operations-in (ss/split (not-empty path) #"\."))))

;;
;; Change permit
;;

(defcommand create-change-permit
  {:parameters ["id"]
   :user-roles #{:applicant :authority}
   :states     #{:verdictGiven :constructionStarted :appealed :inUse :onHold}
   :pre-checks [(permit/validate-permit-type-is permit/R)]}
  [{:keys [created user application] :as command}]
  (let [muutoslupa-app-id (app/make-application-id (:municipality application))
        primary-op (:primaryOperation application)
        secondary-ops (:secondaryOperations application)
        op-id-mapping (into {} (map
                                 #(vector (:id %) (mongo/create-id))
                                 (conj secondary-ops primary-op)))
        state (if (usr/authority? user) :open :draft)
        muutoslupa-app (merge domain/application-skeleton
                              (select-keys application
                                           [:propertyId :location
                                            :location-wgs84
                                            :schema-version
                                            :address :title
                                            :foreman :foremanRole
                                            :applicant :_applicantIndex
                                            :municipality :organization
                                            :drawings
                                            :metadata])
                              {:auth (remove #(util/=as-kw (:role %) :statementGiver)
                                             (:auth application))}
                              {:id            muutoslupa-app-id
                               :permitType    permit/R
                               :permitSubtype :muutoslupa
                               :created       created
                               :opened        (when (usr/authority? user) created)
                               :modified      created
                               :documents     (into [] (map
                                                         (fn [doc]
                                                           (let [doc (-> doc
                                                                         (assoc :id (mongo/create-id))
                                                                         (util/dissoc-in [:meta :_approved]))]
                                                             (if (-> doc :schema-info :op)
                                                               (update-in doc [:schema-info :op :id] op-id-mapping)
                                                               doc)))
                                                         (:documents application)))
                               :state         state

                               :history [(app/history-entry state created user)]
                               :infoRequest false
                               :openInfoRequest false
                               :convertedToApplication nil

                               :primaryOperation (assoc primary-op :id (op-id-mapping (:id primary-op)))
                               :secondaryOperations (mapv #(assoc % :id (op-id-mapping (:id %))) secondary-ops)})]

    (app/do-add-link-permit muutoslupa-app (:id application))
    (app/insert-application muutoslupa-app)
    (ok :id muutoslupa-app-id)))


;;
;; Continuation period permit
;;

(defn- get-tyoaika-alkaa-from-ya-app [app]
  (let [mainostus-viitoitus-tapahtuma-doc (:data (domain/get-document-by-name app "mainosten-tai-viitoitusten-sijoittaminen"))
        tapahtuma-name-key (when mainostus-viitoitus-tapahtuma-doc
                             (-> mainostus-viitoitus-tapahtuma-doc :_selected :value keyword))
        tapahtuma-data (when tapahtuma-name-key
                         (mainostus-viitoitus-tapahtuma-doc tapahtuma-name-key))]
    (if (:started app)
      (util/to-local-date (:started app))
      (or
        (-> app (domain/get-document-by-name "tyoaika") :data :tyoaika-alkaa-ms :value (util/to-local-date))
        (-> tapahtuma-data :tapahtuma-aika-alkaa-pvm :value)
        (util/to-local-date (:submitted app))))))

(defn- validate-not-jatkolupa-app [{:keys [application]}]
  (when (= :ya-jatkoaika (-> application :primaryOperation :name keyword))
    (fail :error.cannot-apply-jatkolupa-for-jatkolupa)))

(defcommand create-continuation-period-permit
  {:parameters ["id"]
   :user-roles #{:applicant :authority}
   :states     #{:verdictGiven :constructionStarted}
   :pre-checks [(permit/validate-permit-type-is permit/YA) validate-not-jatkolupa-app]}
  [{:keys [created user application] :as command}]

  (let [continuation-app (app/do-create-application
                           (assoc command :data {:operation    "ya-jatkoaika"
                                                 :x            (-> application :location first)
                                                 :y            (-> application :location second)
                                                 :address      (:address application)
                                                 :propertyId   (:propertyId application)
                                                 :municipality (:municipality application)
                                                 :infoRequest  false
                                                 :messages     []}))
        continuation-app (merge continuation-app {:authority (:authority application)})
        ;;
        ;; ************
        ;; Lain mukaan hankeen aloituspvm on hakupvm + 21pv, tai kunnan paatospvm jos se on tata aiempi.
        ;; kts.  http://www.finlex.fi/fi/laki/alkup/2005/20050547 ,  14 a pykala
        ;; ************
        ;;
        tyoaika-alkaa-pvm (get-tyoaika-alkaa-from-ya-app application)
        tyo-aika-for-jatkoaika-doc (-> continuation-app
                                       (domain/get-document-by-name "tyo-aika-for-jatkoaika")
                                       (assoc-in [:data :tyoaika-alkaa-pvm :value] tyoaika-alkaa-pvm))
        docs (concat
               [(domain/get-document-by-name continuation-app "hankkeen-kuvaus-jatkoaika") tyo-aika-for-jatkoaika-doc]
               (map #(-> (domain/get-document-by-name application %) model/without-user-id) ["hakija-ya" "yleiset-alueet-maksaja"]))
        continuation-app (assoc continuation-app :documents docs)]

    (app/do-add-link-permit continuation-app (:id application))
    (app/insert-application continuation-app)
    (ok :id (:id continuation-app))))


(defn- validate-new-applications-enabled [{{:keys [permitType municipality] :as application} :application}]
  (when application
    (let [scope (org/resolve-organization-scope municipality permitType)]
      (when-not (:new-application-enabled scope)
        (fail :error.new-applications-disabled)))))

(defcommand convert-to-application
  {:parameters [id]
   :user-roles #{:applicant :authority}
   :states     states/all-inforequest-states
   :pre-checks [validate-new-applications-enabled]}
  [{user :user created :created {state :state op :primaryOperation tos-fn :tosFunction :as app} :application org :organization :as command}]
  (update-application command
                      (util/deep-merge
                       (app/state-transition-update :open created (assoc app :infoRequest false) user)
                       {$set  {:infoRequest            false
                               :openInfoRequest        false
                               :convertedToApplication created
                               :documents              (app/make-documents user created @org op app)
                               :modified               created}
                        $push {:attachments {$each (app/make-attachments created op @org state tos-fn)}}}))
  (try (app/autofill-rakennuspaikka app created)
       (catch Exception e (warn "KTJ data was not updated to inforequest when converted to application"))))

(defn- validate-organization-backend-urls [{organization :organization}]
  (when-let [org (and organization @organization)]
    (if-let [conf (:vendor-backend-redirect org)]
      (->> (vals conf)
           (remove ss/blank?)
           (some action/validate-url))
      (fail :error.vendor-urls-not-set))))

(defn get-vendor-backend-id [verdicts]
  (->> verdicts
       (remove :draft)
       (some :kuntalupatunnus)))

(defn- get-backend-and-lp-urls [org]
  (-> (:vendor-backend-redirect org)
      (util/select-values [:vendor-backend-url-for-backend-id
                           :vendor-backend-url-for-lp-id])))

(defn- correct-urls-configured [{{:keys [verdicts] :as application} :application organization :organization}]
  (when application
    (let [vendor-backend-id          (get-vendor-backend-id verdicts)
          [backend-id-url lp-id-url] (get-backend-and-lp-urls @organization)
          lp-id-url-missing?         (ss/blank? lp-id-url)
          both-urls-missing?         (and lp-id-url-missing?
                                          (ss/blank? backend-id-url))]
      (if vendor-backend-id
        (when both-urls-missing?
          (fail :error.vendor-urls-not-set))
        (when lp-id-url-missing?
          (fail :error.vendor-urls-not-set))))))

(defraw redirect-to-vendor-backend
  {:parameters [id]
   :user-roles #{:authority}
   :states     states/post-sent-states
   :pre-checks [validate-organization-backend-urls
                correct-urls-configured]}
  [{{:keys [verdicts]} :application organization :organization}]
  (let [vendor-backend-id          (get-vendor-backend-id verdicts)
        [backend-id-url lp-id-url] (get-backend-and-lp-urls @organization)
        url-parts                  (if (and vendor-backend-id
                                            (not (ss/blank? backend-id-url)))
                                     [backend-id-url vendor-backend-id]
                                     [lp-id-url id])
        redirect-url               (apply str url-parts)]
    (info "Redirecting from" id "to" redirect-url)
    {:status 303 :headers {"Location" redirect-url}}))

(defquery application-handlers
  {:parameters       [id]
   :user-authz-roles roles/all-authz-roles
   :user-roles       #{:authority :applicant :oirAuthority}
   :states           states/all-states}
  [{:keys [application lang organization]}]
  (ok :handlers (map (fn [{role-name :name :as handler}]
                       (-> handler
                           (assoc :roleName ((keyword lang) role-name))
                           (dissoc :name)))
                     (:handlers application))))

(defquery application-organization-handler-roles
  {:description "Every handler defined in the organization, including
  the disabled ones."
   :parameters  [id]
   :user-roles  #{:authority}
   :states      states/all-states}
  [{:keys [organization]}]
  (ok :handlerRoles (:handler-roles @organization)))

(defquery application-organization-archive-enabled
  {:description "Permanent archive flag check as pseudo query. Depends
  on the (delayed) organization parameter and thus implicitly from the
  application id parameter as well."
   :parameters [:id]
   :user-authz-roles roles/all-authz-roles
   :org-authz-roles roles/reader-org-authz-roles
   :user-roles #{:applicant :authority :oirAuthority}
   :states states/all-states
   :pre-checks  [(fn [{organization :organization}]
                   (when-not (some-> organization deref :permanent-archive-enabled)
                     (fail :error.archive-not-enabled)))]}
  [_])
