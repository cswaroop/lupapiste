(ns lupapalvelu.application
  (:use [monger.operators]
        [clojure.tools.logging]
        [lupapalvelu.core]
        [clojure.string :only [blank? join trim]]
        [sade.util :only [lower-case]]
        [clj-time.core :only [year]]
        [clj-time.local :only [local-now]]
        [lupapalvelu.i18n :only [with-lang loc]])
  (:require [clj-time.format :as timeformat]
            [lupapalvelu.mongo :as mongo]
            [monger.query :as query]
            [sade.env :as env]
            [sade.util :as util]
            [lupapalvelu.tepa :as tepa]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.xml.krysp.reader :as krysp]
            [lupapalvelu.document.commands :as commands]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.document.suunnittelutarveratkaisu-ja-poikeamis-schemas :as poischemas]
            [lupapalvelu.document.ymparisto-schemas :as ympschemas]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.document.yleiset-alueet-schemas :as yleiset-alueet]
            [lupapalvelu.security :as security]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.xml.krysp.rakennuslupa-mapping :as rl-mapping]
            [lupapalvelu.ktj :as ktj]
            [lupapalvelu.neighbors :as neighbors]
            [clj-time.format :as tf]))

;; Validators

(defn- property-id? [^String s]
  (and s (re-matches #"^[0-9]{14}$" s)))

(defn property-id-parameters [params command]
  (when-let [invalid (seq (filter #(not (property-id? (get-in command [:data %]))) params))]
    (info "invalid property id parameters:" (join ", " invalid))
    (fail :error.invalid-property-id :parameters (vec invalid))))

(defn- validate-owner-or-writer
  "Validator: current user must be owner or writer.
   To be used in commands' :validators vector."
  [command application]
  (when-not (domain/owner-or-writer? application (-> command :user :id))
    (fail :error.unauthorized)))

(defn- validate-x [{{:keys [x]} :data}]
  (when (and x (not (< 10000 (->double x) 800000)))
    (fail :error.illegal-coordinates)))

(defn- validate-y [{{:keys [y]} :data}]
  (when (and y (not (<= 6610000 (->double y) 7779999)))
    (fail :error.illegal-coordinates)))

(defn count-unseen-comment [user app]
  (let [last-seen (get-in app [:_comments-seen-by (keyword (:id user))] 0)]
    (count (filter (fn [comment]
                     (and (> (:created comment) last-seen)
                          (not= (get-in comment [:user :id]) (:id user))
                          (not (blank? (:text comment)))))
                   (:comments app)))))

(defn count-unseen-statements [user app]
  (if-not (:infoRequest app)
    (let [last-seen (get-in app [:_statements-seen-by (keyword (:id user))] 0)]
      (count (filter (fn [statement]
                       (and (> (or (:given statement) 0) last-seen)
                            (not= (lower-case (get-in statement [:person :email])) (lower-case (:email user)))))
                     (:statements app))))
    0))

(defn count-unseen-verdicts [user app]
  (if (and (= (:role user) "applicant") (not (:infoRequest app)))
    (let [last-seen (get-in app [:_verdicts-seen-by (keyword (:id user))] 0)]
      (count (filter (fn [verdict] (> (or (:timestamp verdict) 0) last-seen)) (:verdict app))))
    0))

(defn count-attachments-requiring-action [user app]
  (if-not (:infoRequest app)
    (let [count-attachments (fn [state] (count (filter #(and (= (:state %) state) (seq (:versions %))) (:attachments app))))]
      (case (keyword (:role user))
        :applicant (count-attachments "requires_user_action")
        :authority (count-attachments "requires_authority_action")
        0))
    0))

(defn count-document-modifications-per-doc [user app]
  (if (and (env/feature? :docIndicators) (= (:role user) "authority") (not (:infoRequest app)))
    (into {} (map (fn [doc] [(:id doc) (model/modifications-since-approvals doc)]) (:documents app)))
    {}))


(defn count-document-modifications [user app]
  (if (and (env/feature? :docIndicators) (= (:role user) "authority") (not (:infoRequest app)))
    (reduce + 0 (vals (:documentModificationsPerDoc app)))
    0))

(defn indicator-sum [_ app]
  (reduce + (map (fn [[k v]] (if (#{:documentModifications :unseenStatements :unseenVerdicts :attachmentsRequiringAction} k) v 0)) app)))

(def meta-fields [{:field :applicant :fn get-applicant-name}
                  {:field :neighbors :fn neighbors/normalize-negighbors}
                  {:field :documentModificationsPerDoc :fn count-document-modifications-per-doc}
                  {:field :documentModifications :fn count-document-modifications}
                  {:field :unseenComments :fn count-unseen-comment}
                  {:field :unseenStatements :fn count-unseen-statements}
                  {:field :unseenVerdicts :fn count-unseen-verdicts}
                  {:field :attachmentsRequiringAction :fn count-attachments-requiring-action}
                  {:field :indicators :fn indicator-sum}])

(defn with-meta-fields [user app]
  (reduce (fn [app {field :field f :fn}] (assoc app field (f user app))) app meta-fields))

;;
;; Query application:
;;

(defn- app-post-processor [user]
  (comp without-system-keys (partial with-meta-fields user)))

(defquery "applications" {:authenticated true :verified true} [{user :user}]
  (ok :applications (map (app-post-processor user) (mongo/select :applications (domain/application-query-for user)))))

(defn find-authorities-in-applications-organization [app]
  (mongo/select :users {:organizations (:organization app) :role "authority"} {:firstName 1 :lastName 1}))

(defquery "application"
  {:authenticated true
   :parameters [:id]}
  [{app :application user :user}]
  (if app
    (ok :application (-> app
                       ((partial with-meta-fields user))
                       without-system-keys)
        :authorities (find-authorities-in-applications-organization app))
    (fail :error.not-found)))

;; Gets an array of application ids and returns a map for each application that contains the
;; application id and the authorities in that organization.
(defquery "authorities-in-applications-organization"
  {:parameters [:id]
   :authenticated true}
  [{app :application}]
  (ok :authorityInfo (find-authorities-in-applications-organization app)))

(defn filter-repeating-party-docs [names]
  (filter
    (fn [name]
      (and (= :party (get-in (schemas/get-schemas) [name :info :type]))
        (= true (get-in (schemas/get-schemas) [name :info :repeating]))))
    names))

(defquery "party-document-names"
  {:parameters [:id]
   :authenticated true}
  [command]
  (with-application command
    (fn [application]
      (let [documents (:documents application)
            initialOp (:name (first (:operations application)))
            original-schema-names (:required ((keyword initialOp) operations/operations))
            original-party-documents (filter-repeating-party-docs original-schema-names)]
        (ok :partyDocumentNames (conj original-party-documents "hakija"))))))

;;
;; Invites
;;

(defquery "invites"
  {:authenticated true
   :verified true}
  [{{:keys [id]} :user}]
  (let [filter     {:auth {$elemMatch {:invite.user.id id}}}
        projection (assoc filter :_id 0)
        data       (mongo/select :applications filter projection)
        invites    (map :invite (mapcat :auth data))]
    (ok :invites invites)))

(defcommand "invite"
  {:parameters [:id :email :title :text :documentName :path]
   :roles      [:applicant :authority]
   :notify     "invite"
   :verified   true}
  [{created :created
    user    :user
    {:keys [id email title text documentName documentId path]} :data {:keys [host]} :web :as command}]
  (with-application command
    (fn [{application-id :id :as application}]
      (let [email (lower-case email)]
        (if (domain/invited? application email)
          (fail :invite.already-invited)
          (let [invited (security/get-or-create-user-by-email email)
                invite  {:title        title
                         :application  application-id
                         :text         text
                         :path         path
                         :documentName documentName
                         :documentId   documentId
                         :created      created
                         :email        email
                         :user         (security/summary invited)
                         :inviter      (security/summary user)}
                writer  (role invited :writer)
                auth    (assoc writer :invite invite)]
            (if (domain/has-auth? application (:id invited))
              (fail :invite.already-has-auth)
              (mongo/update
                :applications
                {:_id application-id
                 :auth {$not {$elemMatch {:invite.user.username email}}}}
                {$push {:auth auth}}))))))))

(defcommand "approve-invite"
  {:parameters [:id]
   :roles      [:applicant]
   :verified   true}
  [{user :user :as command}]
  (with-application command
    (fn [{application-id :id :as application}]
      (when-let [my-invite (domain/invite application (:email user))]
        (executed "set-user-to-document"
          (-> command
            (assoc-in [:data :documentId] (:documentId my-invite))
            (assoc-in [:data :path]       (:path my-invite))
            (assoc-in [:data :userId]     (:id user))))
        (mongo/update :applications
          {:_id application-id :auth {$elemMatch {:invite.user.id (:id user)}}}
          {$set  {:auth.$ (role user :writer)}})))))

(defcommand "remove-invite"
  {:parameters [:id :email]
   :roles      [:applicant :authority]
   :validators [validate-owner-or-writer]}
  [{{:keys [id email]} :data :as command}]
  (with-application command
    (fn [{application-id :id}]
      (let [email (lower-case email)]
        (with-user email
          (fn [_]
            (mongo/update-by-id :applications application-id
              {$pull {:auth {$and [{:username email}
                                   {:type {$ne :owner}}]}}})))))))

(defcommand "remove-auth"
  {:parameters [:id :email]
   :roles      [:applicant :authority]}
  [{{:keys [email]} :data :as command}]
  (update-application command
    {$pull {:auth {$and [{:username (lower-case email)}
                         {:type {$ne :owner}}]}}}))

(defcommand "add-comment"
  {:parameters [:id :text :target]
   :roles      [:applicant :authority]
   :notify     "new-comment"}
  [{{:keys [text target]} :data {:keys [host]} :web :keys [user created] :as command}]
  (with-application command
    (fn [{:keys [id state] :as application}]
      (update-application command
        {$set  {:modified created}
         $push {:comments {:text    text
                           :target  target
                           :created created
                           :user    (security/summary user)}}})

      (condp = (keyword state)

        ;; LUPA-XYZ (was: open-application)
        :draft  (when (not (blank? text))
                  (update-application command
                    {$set {:modified created
                           :state    :open
                           :opened   created}}))

        ;; LUPA-371
        :info (when (security/authority? user)
                (update-application command
                  {$set {:state    :answered
                         :modified created}}))

        ;; LUPA-371 (was: mark-inforequest-answered)
        :answered (when (security/applicant? user)
                    (update-application command
                      {$set {:state :info
                             :modified created}}))

        nil))))

(defcommand "mark-seen"
  {:parameters [:id :type]
   :input-validators [(fn [{{type :type} :data}] (when-not (#{"comments" "statements" "verdicts"} type) (fail :error.unknown-type)))]
   :authenticated true}
  [{:keys [data user created] :as command}]
  (update-application command {$set {(str "_" (:type data) "-seen-by." (:id user)) created}}))

(defcommand "set-user-to-document"
  {:parameters [:id :documentId :userId :path]
   :authenticated true}
  [{{:keys [documentId userId path]} :data user :user created :created :as command}]
  (with-application command
    (fn [application]
      (let [document     (domain/get-document-by-id application documentId)
            schema-name  (get-in document [:schema :info :name])
            schema       (schemas/get-schema schema-name)
            subject      (security/get-non-private-userinfo userId)
            with-hetu    (and
                           (domain/has-hetu? (:body schema) [path])
                           (security/same-user? user subject))
            henkilo      (tools/timestamped (domain/->henkilo subject :with-hetu with-hetu) created)
            full-path    (str "documents.$.data" (when-not (blank? path) (str "." path)))]
        (info "setting-user-to-document, with hetu: " with-hetu)
        (if-not document
          (fail :error.document-not-found)
          ;; TODO: update via model
          (do
            (infof "merging user %s with best effort into document %s into path %s" subject name full-path)
            (mongo/update
              :applications
              {:_id (:id application)
               :documents {$elemMatch {:id documentId}}}
              {$set {full-path henkilo
                     :modified created}})))))))


;;
;; Assign
;;

(defcommand "assign-to-me"
  {:parameters [:id]
   :roles      [:authority]}
  [{user :user :as command}]
  (update-application command
    {$set {:authority (security/summary user)}}))

(defcommand "assign-application"
  {:parameters  [:id :assigneeId]
   :roles       [:authority]}
  [{{:keys [assigneeId]} :data user :user :as command}]
  (update-application command
    (if assigneeId
      {$set   {:authority (security/summary (mongo/select-one :users {:_id assigneeId}))}}
      {$unset {:authority ""}})))

;;
;;
;;

(defcommand "cancel-application"
  {:parameters [:id]
   :roles      [:applicant]
   :notify     "state-change"
   :states     [:draft :info :open :submitted]}
  [{{id :id} :data {:keys [host]} :web created :created :as command}]
  (update-application command
    {$set {:modified  created
           :state     :canceled}}))

(defcommand "request-for-complement"
  {:parameters [:id]
   :roles      [:authority]
   :notify     "state-change"
   :states     [:sent]}
  [{{id :id} :data {host :host} :web created :created :as command}]
  (update-application command
    {$set {:modified  created
           :state :complement-needed}}))

(defcommand "approve-application"
  {:parameters [:id :lang]
   :roles      [:authority]
   :notify     "state-change"
   :states     [:submitted :complement-needed]}
  [{{:keys [host]} :web :as command}]
  (with-application command
    (fn [application]
      (let [new-state :submitted
            application-id (:id application)
            submitted-application (mongo/by-id :submitted-applications (:id application))
            organization (mongo/by-id :organizations (:organization application))]
        (if (nil? (:authority application))
          (executed "assign-to-me" command))
        (try (rl-mapping/get-application-as-krysp application (-> command :data :lang) submitted-application organization)
          (mongo/update
            :applications {:_id (:id application) :state new-state}
            {$set {:state :sent}})
          (catch org.xml.sax.SAXParseException e
            (.printStackTrace e)
            (fail (.getMessage e))))))))

(defcommand "submit-application"
  {:parameters [:id]
   :roles      [:applicant :authority]
   :states     [:draft :info :open :complement-needed]
   :notify     "state-change"
   :validators [validate-owner-or-writer]}
  [{{:keys [host]} :web :as command}]
  (with-application command
    (fn [application]
      (let [new-state :submitted
            application-id (:id application)]
        (mongo/update
          :applications
          {:_id application-id}
          {$set {:state new-state
                 :submitted (:created command) }})
        (try
          (mongo/insert
            :submitted-applications
            (assoc (dissoc application :id) :_id application-id))
          (catch com.mongodb.MongoException$DuplicateKey e
            ; This is ok. Only the first submit is saved.
            ))))))

(defcommand "save-application-shape"
  {:parameters [:id :shape]
   :roles      [:applicant :authority]
   :states     [:draft :open :complement-needed]}
  [{{:keys [shape]} :data :as command}]
  (update-application command
    {$set {:shapes [shape]}}))

(defn- make-attachments [created operation organization-id & {:keys [target]}]
  (let [organization (organization/get-organization organization-id)]
    (for [[type-group type-id] (organization/get-organization-attachments-for-operation organization operation)]
      (attachment/make-attachment created target false operation {:type-group type-group :type-id type-id}))))

(defn- schema-data-to-body [schema-data application]
  (reduce
    (fn [body [data-path data-value]]
      (let [path (if (= :value (last data-path)) data-path (conj (vec data-path) :value))
            val (if (fn? data-value) (data-value application) data-value)]
        (update-in body path (constantly val))))
    {} schema-data))

;; FIXME: existing-document is always nil
;; TODO: permit-type splitting.
(defn- make-documents [user created existing-documents op application]
  (let [op-info               (operations/operations (keyword (:name op)))
        permit-type           (keyword (domain/permit-type application))
        make                  (fn [schema-name] {:id (mongo/create-id)
                                                 :schema (schemas/get-schema schema-name)
                                                 :created created
                                                 :data (if (= schema-name (:schema op-info))
                                                         (schema-data-to-body (:schema-data op-info) application)
                                                         {})})
        existing-schema-names (set (map (comp :name :info :schema) existing-documents))
        required-schema-names (remove existing-schema-names (:required op-info))
        required-docs         (map make required-schema-names)
        op-schema-name        (:schema op-info)
        op-doc                (update-in (make op-schema-name) [:schema :info] merge {:op op :removable true})
        new-docs              (cons op-doc required-docs)
        hakija                (assoc-in (make "hakija") [:data :_selected :value] "henkilo")
        hakija-public-area    (assoc-in (make "hakija-public-area") [:data :_selected :value] "yritys")]
    (if-not user
      new-docs
      (condp = permit-type
        :YA (cons
              (assoc-in
                (assoc-in hakija-public-area [:data :henkilo] (domain/->henkilo user :with-hetu true))
                [:data :yritys]
                (domain/->yritys-public-area user))
              new-docs)
        (cons (assoc-in hakija [:data :henkilo] (domain/->henkilo user :with-hetu true)) new-docs)))))

 (defn- ->location [x y]
   {:x (->double x) :y (->double y)})

 (defn- make-application-id [municipality]
   (let [year           (str (year (local-now)))
         sequence-name  (str "applications-" municipality "-" year)
         counter        (format "%05d" (mongo/get-next-sequence-value sequence-name))]
     (str "LP-" municipality "-" year "-" counter)))

 (defn- make-op [op-name created]
   {:id (mongo/create-id)
    :name (keyword op-name)
    :created created
    :operation-type (:operation-type (operations/operations (keyword op-name)))})

 (def ktj-format (tf/formatter "yyyyMMdd"))
 (def output-format (tf/formatter "dd.MM.yyyy"))

 (defn- autofill-rakennuspaikka [application created]
   (let [rakennuspaikka   (domain/get-document-by-name application "rakennuspaikka")
         kiinteistotunnus (:propertyId application)
         ktj-tiedot       (ktj/rekisteritiedot-xml kiinteistotunnus)]
     (when ktj-tiedot
       (let [updates [[[:kiinteisto :tilanNimi]        (or (:nimi ktj-tiedot) "")]
                      [[:kiinteisto :maapintaala]      (or (:maapintaala ktj-tiedot) "")]
                      [[:kiinteisto :vesipintaala]     (or (:vesipintaala ktj-tiedot) "")]
                      [[:kiinteisto :rekisterointipvm] (or (try
                                                         (tf/unparse output-format (tf/parse ktj-format (:rekisterointipvm ktj-tiedot)))
                                                         (catch Exception e (:rekisterointipvm ktj-tiedot))) "")]]]
         (commands/persist-model-updates
           (:id application)
           rakennuspaikka
           updates
           created)))))

 (defn user-is-authority-in-organization? [user-id organization-id]
   (mongo/any? :users {$and [{:organizations organization-id} {:_id user-id}]}))

 (defn- operation-validator [{{operation :operation} :data}]
   (when-not (operations/operations (keyword operation)) (fail :error.unknown-type)))

;; TODO: separate methods for inforequests & applications for clarity.
(defcommand "create-application"
  {:parameters [:operation :x :y :address :propertyId :municipality]
   :roles      [:applicant :authority]
   :input-validators [(partial non-blank-parameters [:operation :address :municipality])
                      (partial property-id-parameters [:propertyId])
                      operation-validator]}
  [{{:keys [operation x y address propertyId municipality infoRequest messages]} :data :keys [user created] :as command}]
  (let [permit-type     (operations/permit-type-of-operation operation)
        organization-id (:id (organization/resolve-organization municipality permit-type))]
    (when-not
      (or (security/applicant? user)
          (user-is-authority-in-organization? (:id user) organization-id))
      (fail! :error.unauthorized))
    (let [id            (make-application-id municipality)
          owner         (role user :owner :type :owner)
          op            (make-op operation created)
          info-request? (boolean infoRequest)
          state         (cond
                          info-request?              :info
                          (security/authority? user) :open
                          :else                      :draft)
          make-comment  (partial assoc {:target {:type "application"}
                                        :created created
                                        :user (security/summary user)} :text)
          application   {:id            id
                         :created       created
                         :opened        (when (#{:open :info} state) created)
                         :modified      created
                         :permitType    permit-type
                         :infoRequest   info-request?
                         :operations    [op]
                         :state         state
                         :municipality  municipality
                         :location      (->location x y)
                         :organization  organization-id
                         :address       address
                         :propertyId    propertyId
                         :title         address
                         :auth          [owner]
                         :comments      (map make-comment messages)}
          application   (merge application
                          (if info-request?
                            {:attachments            []
                             :allowedAttachmentTypes [[:muut [:muu]]]
                             :documents              []}
                            {:attachments            (make-attachments created op organization-id)
                             :allowedAttachmentTypes (attachment/get-attachment-types-by-permit-type permit-type)
                             :documents              (make-documents user created nil op application)}))
          application   (domain/set-software-version application)]
      (mongo/insert :applications application)
      (autofill-rakennuspaikka application created)
      (ok :id id))))

(defcommand "add-operation"
  {:parameters [:id :operation]
   :roles      [:applicant :authority]
   :states     [:draft :open :complement-needed]
   :input-validators [operation-validator]
   :validators [operations/validate-permit-type-is-not-ya]}
  [command]
  (with-application command
    (fn [application]
      (let [id         (get-in command [:data :id])
            created    (:created command)
            documents  (:documents application)
            op-id      (mongo/create-id)
            op         (make-op (get-in command [:data :operation]) created)
            new-docs   (make-documents nil created documents op application)]
        (mongo/update-by-id :applications id {$push {:operations op}
                                              $pushAll {:documents new-docs
                                                        :attachments (make-attachments created op (:organization application))}
                                              $set {:modified created}})))))

(defcommand "change-location"
  {:parameters [:id :x :y :address :propertyId]
   :roles      [:applicant :authority]
   :states     [:draft :info :answered :open :complement-needed :submitted]
   :input-validators [(partial non-blank-parameters [:address])
                      (partial property-id-parameters [:propertyId])
                      validate-x validate-y]}
  [{{:keys [id x y address propertyId]} :data created :created application :application}]
  (if (= (:municipality application) (organization/municipality-by-propertyId propertyId))
    (mongo/update-by-id :applications id {$set {:location      (->location x y)
                                                :address       (trim address)
                                                :propertyId    propertyId
                                                :title         (trim address)
                                                :modified      created}})
    (fail :error.property-in-other-muinicipality)))

(defcommand "convert-to-application"
  {:parameters [:id]
   :roles      [:applicant]
   :states     [:draft :info :answered]}
  [{{:keys [id]} :data :keys [user created application] :as command}]
  (let [op          (first (:operations application))
        permit-type (:permitType application)]
    (mongo/update-by-id :applications id
                        {$set {:infoRequest false
                               :state :open
                               :allowedAttachmentTypes (attachment/get-attachment-types-by-permit-type permit-type)
                               :documents (make-documents user created nil op application)
                               :modified created}
           $pushAll {:attachments (make-attachments created op (:organization application))}})))

;;
;; Verdicts
;;

(defn- validate-status [{{:keys [status]} :data}]
  (when (or (< status 1) (> status 42))
    (fail :error.false.status.out.of.range.when.giving.verdict)))

(defcommand "give-verdict"
  {:parameters [:id :verdictId :status :name :given :official]
   :input-validators [validate-status]
   :states     [:submitted :complement-needed :sent]
   :notify     "verdict"
   :roles      [:authority]}
  [{{:keys [id verdictId status name given official]} :data {:keys [host]} :web created :created}]
  (mongo/update
    :applications
    {:_id id}
    {$set {:modified created
           :state    :verdictGiven}
     $push {:verdict  {:id verdictId
                       :timestamp created
                       :name name
                       :given given
                       :status status
                       :official official}}}))

;;
;; krysp enrichment
;;

(defn add-value-metadata [m meta-data]
  (reduce (fn [r [k v]] (assoc r k (if (map? v) (add-value-metadata v meta-data) (assoc meta-data :value v)))) {} m))

(defcommand "merge-details-from-krysp"
  {:parameters [:id :documentId :buildingId]
   :roles      [:applicant :authority]}
  [{{:keys [id documentId buildingId]} :data created :created :as command}]
  (with-application command
    (fn [{:keys [organization propertyId] :as application}]
      (if-let [legacy (organization/get-legacy organization)]
        (let [doc-name     "rakennuksen-muuttaminen"
              document     (domain/get-document-by-id (:documents application) documentId)
              old-body     (:data document)
              kryspxml     (krysp/building-xml legacy propertyId)
              new-body     (or (krysp/->rakennuksen-tiedot kryspxml buildingId) {})
              with-value-metadata (tools/timestamped (add-value-metadata new-body {:source :krysp}) created)]
          ;; TODO: update via model
          (mongo/update
            :applications
            {:_id (:id application)
             :documents {$elemMatch {:id documentId}}}
            {$set {:documents.$.data with-value-metadata
                   :modified created}})
          (ok))
        (fail :no-legacy-available)))))

(defcommand "get-building-info-from-legacy"
  {:parameters [:id]
   :roles      [:applicant :authority]}
  [{{:keys [id]} :data :as command}]
  (with-application command
    (fn [{:keys [organization propertyId] :as application}]
      (if-let [legacy   (organization/get-legacy organization)]
        (let [kryspxml  (krysp/building-xml legacy propertyId)
              buildings (krysp/->buildings kryspxml)]
          (ok :data buildings))
        (fail :no-legacy-available)))))

;;
;; Service point for jQuery dataTables:
;;

(def col-sources [(fn [app] (if (:infoRequest app) "inforequest" "application"))
                  (juxt :address :municipality)
                  get-application-operation
                  :applicant
                  :submitted
                  :indicators
                  :unseenComments
                  :modified
                  :state
                  :authority])

(def order-by (assoc col-sources
                     0 :infoRequest
                     1 :address
                     2 nil
                     3 nil
                     5 nil
                     6 nil))

(def col-map (zipmap col-sources (map str (range))))

(defn add-field [application data [app-field data-field]]
  (assoc data data-field (app-field application)))

(defn make-row [application]
  (let [base {"id" (:_id application)
              "kind" (if (:infoRequest application) "inforequest" "application")}]
    (reduce (partial add-field application) base col-map)))

(defn make-query [query {:keys [filter-search filter-kind filter-state filter-user]}]
  (merge
    query
    (condp = filter-kind
      "applications" {:infoRequest false}
      "inforequests" {:infoRequest true}
      "both"         nil)
    (condp = filter-state
      "all"       {:state {$ne "canceled"}}
      "active"    {:state {$nin ["draft" "canceled" "answered" "verdictGiven"]}}
      "canceled"  {:state "canceled"})
    (when-not (contains? #{nil "0"} filter-user)
      {$or [{"auth.id" filter-user}
            {"authority.id" filter-user}]})
    (when-not (blank? filter-search)
      {:address {$regex filter-search $options "i"}})))

(defn make-sort [params]
  (let [col (get order-by (:iSortCol_0 params))
        dir (if (= "asc" (:sSortDir_0 params)) 1 -1)]
    (if col {col dir} {})))

(defn applications-for-user [user params]
  (let [user-query  (domain/basic-application-query-for user)
        user-total  (mongo/count :applications user-query)
        query       (make-query user-query params)
        query-total (mongo/count :applications query)
        skip        (params :iDisplayStart)
        limit       (params :iDisplayLength)
        apps        (query/with-collection "applications"
                      (query/find query)
                      (query/sort (make-sort params))
                      (query/skip skip)
                      (query/limit limit))
        rows        (map (comp make-row (partial with-meta-fields user)) apps)
        echo        (str (Integer/parseInt (str (params :sEcho))))] ; Prevent XSS
    {:aaData                rows
     :iTotalRecords         user-total
     :iTotalDisplayRecords  query-total
     :sEcho                 echo}))

(defcommand "applications-for-datatables"
  {:parameters [:params]
   :verified true}
  [{user :user {params :params} :data}]
  (ok :data (applications-for-user user params)))

;;
;; Query that returns number of applications or info-requests user has:
;;

(defquery "applications-count"
  {:parameters [:kind]
   :authenticated true
   :verified true}
  [{user :user {kind :kind} :data}]
  (let [base-query (domain/application-query-for user)
        query (condp = kind
                "inforequests" (assoc base-query :infoRequest true)
                "applications" (assoc base-query :infoRequest false)
                "both"         base-query
                {:_id -1})]
    (ok :data (mongo/count :applications query))))
