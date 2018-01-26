(ns lupapalvelu.integrations-api
  "API for commands/functions working with integrations (ie. KRYSP, Asianhallinta)"
  (:require [taoensso.timbre :refer [infof info error errorf]]
            [clojure.java.io :as io]
            [noir.response :as resp]
            [monger.operators :refer [$in $set $unset $push $each $elemMatch]]
            [lupapalvelu.action :refer [defcommand defquery defraw update-application] :as action]
            [lupapalvelu.application :as app]
            [lupapalvelu.application-state :as app-state]
            [lupapalvelu.application-meta-fields :as meta-fields]
            [lupapalvelu.autologin :as autologin]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.document.persistence :as doc-persistence]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.mime :as mime]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.organization :as org]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.rest.config :as config]
            [lupapalvelu.roles :as roles]
            [lupapalvelu.states :as states]
            [lupapalvelu.state-machine :as sm]
            [lupapalvelu.user :as user]
            [lupapalvelu.xml.krysp.application-as-krysp-to-backing-system :as mapping-to-krysp]
            [lupapalvelu.xml.krysp.building-reader :as building-reader]
            [lupapalvelu.xml.asianhallinta.core :as ah]
            [lupapalvelu.ya-extension :as yax]
            [sade.core :refer :all]
            [sade.env :as env]
            [sade.http :as http]
            [sade.strings :as ss]
            [sade.util :as util]
            [sade.validators :as validators]
            [lupapalvelu.foreman :as foreman]))

(defn- has-asianhallinta-operation [{{:keys [primaryOperation]} :application}]
  (when-not (operations/get-operation-metadata (:name primaryOperation) :asianhallinta)
    (fail :error.integration.asianhallinta-disabled)))

(defn- asianhallinta-enabled [{:keys [organization application]}]
  (when-not (-> (org/resolve-organization-scope (:municipality application) (:permitType application) @organization)
                ah/asianhallinta-enabled?)
    (fail :error.integration.asianhallinta-disabled)))

(defn- asianhallinta-unavailable-for-application [command]
  (when-not ((some-fn has-asianhallinta-operation asianhallinta-enabled) command)
    (fail :error.integration.asianhallinta-available)))

(defn- temporary-approve-prechecks
    "Temporary fix: case management and regular approve mechanism can
  coexist for environmental applications."
  [command]
  (when-let [cm-fail (asianhallinta-unavailable-for-application command)]
    (when-not (-> command :application :permitType keyword #{:YI :YL :YM :VVVL :MAL})
      cm-fail)))
;;
;; Application approval
;;

(defn get-transfer-item [type {:keys [created user]} & [data-map]]
  (let [transfer {:type type
                  :user (select-keys user [:id :role :firstName :lastName])
                  :timestamp created}]
    (if-not (and (map? data-map) (seq (:data data-map)))
      transfer
      (assoc transfer (:data-key data-map) (:data data-map)))))

(defn- do-approve [{:keys [application organization created] :as command} id current-state lang do-rest-fn]
  (if (org/krysp-integration? @organization (permit/permit-type application))
    (or
      (app/validate-link-permits application)
      (let [all-attachments (:attachments (domain/get-application-no-access-checking (:id application) [:attachments]))
            sent-file-ids   (let [submitted-application (mongo/by-id :submitted-applications id)]
                              (mapping-to-krysp/save-application-as-krysp command lang submitted-application :current-state current-state))
            attachments-updates (or (attachment/create-sent-timestamp-update-statements all-attachments sent-file-ids created) {})]
        (do-rest-fn attachments-updates)))
    ;; Integration details not defined for the organization -> let the approve command pass
    (do-rest-fn nil)))

(defn- ensure-general-handler-is-set [handlers user organization]
  (let [general-id (org/general-handler-id-for-organization organization)]
    (if (or (nil? general-id) (util/find-by-key :roleId general-id handlers))
      handlers
      (conj handlers (user/create-handler nil general-id user)))))

(defn- ensure-bulletin-op-description-is-set-if-needed
  [{{organizationId :organization permit-type :permitType municipality :municipality primaryOperation :primaryOperation
     bulletinOpDescription :bulletinOpDescription :as application} :application}]
  (when organizationId
    (let [{:keys [enabled descriptions-from-backend-system]} (org/bulletin-settings-for-scope (org/get-organization organizationId) permit-type municipality)]
      (when (and enabled
                 (not descriptions-from-backend-system)
                 (not (foreman/foreman-app? application))
                 (not (= (:name primaryOperation) "suunnittelijan-nimeaminen"))
                 (ss/blank? bulletinOpDescription))
        (fail :error.invalid-value)))))

(defcommand approve-application
  {:parameters       [id lang]
   :pre-checks       [temporary-approve-prechecks
                      ensure-bulletin-op-description-is-set-if-needed]
   :input-validators [(partial action/non-blank-parameters [:id :lang])]
   :user-roles       #{:authority}
   :notified         true
   :on-success       (action/notify :application-state-change)
   :states           #{:submitted :complementNeeded}
   :org-authz-roles  #{:approver}}
  [{:keys [application created user organization] :as command}]
  (let [current-state  (:state application)
        next-state   (if (yax/ya-extension-app? application)
                       (sm/verdict-given-state application)
                       (sm/next-state application))
        _           (assert (sm/valid-state? application next-state))

        handlers    (ensure-general-handler-is-set (:handlers application) user @organization)
        application (-> application
                        (assoc :handlers handlers)
                        (app/post-process-app-for-krysp @organization))
        mongo-query {:state {$in ["submitted" "complementNeeded"]}}
        indicator-updates (app/mark-indicators-seen-updates application user created)
        transfer (get-transfer-item :exported-to-backing-system {:created created :user user})
        do-update (fn [attachments-updates]
                    (update-application (assoc command :application application)
                      mongo-query
                      (util/deep-merge
                        {$push {:transfers transfer}}
                        {$set (util/deep-merge
                                (when handlers
                                  {:handlers handlers})
                                attachments-updates
                                indicator-updates)}
                        (app-state/state-transition-update next-state created application user)))
                    (ok :integrationAvailable (not (nil? attachments-updates))))]

    (do-approve (assoc command :application application) id current-state lang do-update)))

(defn- application-already-exported [type]
  (fn [{application :application}]
    (when-not (= "aiemmalla-luvalla-hakeminen" (get-in application [:primaryOperation :name]))
      (let [export-ops #{:exported-to-backing-system :exported-to-asianhallinta}
            filtered-transfers (filter (comp export-ops keyword :type) (:transfers application))]
        (when-not (= (keyword (:type (last filtered-transfers))) type)
          (fail :error.application-not-exported))))))

(defn- has-unsent-attachments
  "Attachment is unsent, if a) it has a file, b) the file has not been
  sent, c) attachment type is neither statement nor verdict."
  [{{attachments :attachments} :application}]
  (when-not (some (fn [{:keys [sent versions target]}]
                    (and (not-empty versions)
                         (not (#{:statement :verdict} (-> target :type keyword)))
                         (or (not sent) (> (-> versions last :created) sent))))
                  attachments)
    (fail :error.no-unsent-attachments)))

(defcommand move-attachments-to-backing-system
  {:parameters [id lang attachmentIds]
   :input-validators [(partial action/non-blank-parameters [:id :lang])
                      (partial action/vector-parameter-of :attachmentIds string?)]
   :user-roles #{:authority}
   :pre-checks [(permit/validate-permit-type-is permit/R)
                (application-already-exported :exported-to-backing-system)
                has-unsent-attachments
                mapping-to-krysp/http-not-allowed]
   :states     (conj states/post-verdict-states :sent)
   :description "Sends such selected attachments to backing system that are not yet sent."}
  [{:keys [created application user organization] :as command}]

  (let [all-attachments (:attachments (domain/get-application-no-access-checking id [:attachments]))
        attachments-wo-sent-timestamp (filter
                                        #(and
                                          (-> % :versions count pos?)
                                          (or
                                            (not (:sent %))
                                            (> (-> % :versions last :created) (:sent %)))
                                          (not (#{"verdict" "statement"} (-> % :target :type)))
                                          (some #{(:id %)} attachmentIds))
                                        all-attachments)
        command (assoc-in command [:application :attachments] attachments-wo-sent-timestamp)]
    (if (pos? (count attachments-wo-sent-timestamp))
      (let [sent-file-ids (mapping-to-krysp/save-unsent-attachments-as-krysp command lang)
            data-argument (attachment/create-sent-timestamp-update-statements all-attachments sent-file-ids created)
            attachments-data {:data-key :attachments
                              :data (map :id attachments-wo-sent-timestamp)}
            transfer      (get-transfer-item :exported-to-backing-system command attachments-data)]
        (update-application command {$push {:transfers transfer}
                                     $set data-argument})
        (ok))
      (fail :error.sending-unsent-attachments-failed))))

(defcommand parties-as-krysp
  {:description "Sends new designers to backing system after verdict"
   :parameters [id lang]
   :input-validators [(partial action/non-blank-parameters [:id :lang])]
   :user-roles #{:authority}
   :pre-checks [(permit/validate-permit-type-is permit/R)
                (application-already-exported :exported-to-backing-system)
                mapping-to-krysp/http-not-allowed]
   :states     states/post-verdict-states}
  [{:keys [application organization] :as command}]
  (let [sent-document-ids (mapping-to-krysp/save-parties-as-krysp command lang)
        transfer-item     (get-transfer-item :parties-to-backing-system command {:data-key :party-documents
                                                                                 :data sent-document-ids})]
    (update-application command {$push {:transfers transfer-item}})
    (ok :sentDocuments sent-document-ids)))

;;
;; krysp enrichment
;;

(def krysp-enrichment-states (states/all-application-states-but (conj states/terminal-states :sent :verdictGiven :constructionStarted)))

(defn add-value-metadata [m meta-data]
  (reduce (fn [r [k v]] (assoc r k (if (map? v) (add-value-metadata v meta-data) (assoc meta-data :value v)))) {} m))

(defn- load-building-data [url credentials property-id building-id overwrite-all?]
  (let [all-data (building-reader/->rakennuksen-tiedot (building-reader/building-xml url credentials property-id) building-id)]
    (if overwrite-all?
      all-data
      (select-keys all-data (keys building-reader/empty-building-ids)))))

(defcommand merge-details-from-krysp
  {:parameters [id documentId path buildingId overwrite collection]
   :input-validators [doc-persistence/validate-collection
                      (partial action/non-blank-parameters [:documentId :path])
                      (partial action/boolean-parameters [:overwrite])]
   :user-roles #{:applicant :authority}
   :states     krysp-enrichment-states
   :pre-checks [app/validate-authority-in-drafts]}
  [{created :created {:keys [organization propertyId] :as application} :application :as command}]
  (let [{url :url credentials :credentials} (org/get-building-wfs application)
        clear-ids?   (or (ss/blank? buildingId) (= "other" buildingId))]
    (if (or clear-ids? url)
      (let [document     (doc-persistence/by-id application collection documentId)
            schema       (schemas/get-schema (:schema-info document))
            converted-doc (when overwrite ; don't clean data if user doesn't wish to override
                            (model/convert-document-data ; remove old krysp data
                                     (fn [_ value] ; pred
                                       (= "krysp" (:source value)))
                                     (fn [schema value] ; emitter sets default values
                                       (-> value
                                         (dissoc :source :sourceValue :modified)
                                         (assoc :value (tools/default-values schema))))
                                     document
                                     nil))
            cleared-data (dissoc (:data converted-doc) :buildingId) ; buildingId is set below explicitly

            buildingId-updates (doc-persistence/->model-updates [[path buildingId]])
            buildingId-update-map (doc-persistence/validated-model-updates application collection document buildingId-updates created :source nil)

            clearing-updates (tools/path-vals (tools/unwrapped cleared-data))
            clearing-update-map (when-not (util/empty-or-nil? clearing-updates) ; create updates only when there is data
                                  (doc-persistence/validated-model-updates application collection document clearing-updates created :source nil))

            krysp-updates (filter
                            (fn [[path _]] (model/find-by-name (:body schema) path))
                            (tools/path-vals
                              (if clear-ids?
                                building-reader/empty-building-ids
                                (load-building-data url credentials propertyId buildingId overwrite))))
            krysp-update-map (doc-persistence/validated-model-updates application collection document krysp-updates created :source "krysp")

            {:keys [mongo-query mongo-updates]} (util/deep-merge
                                                  clearing-update-map
                                                  buildingId-update-map
                                                  krysp-update-map)]
        (update-application command mongo-query mongo-updates)
        (ok))
      (fail :error.no-legacy-available))))

;;
;; Building info
;;

(defquery get-building-info-from-wfs
  {:parameters [id]
   :user-roles #{:applicant :authority}
   :org-authz-roles roles/all-org-authz-roles
   :user-authz-roles roles/all-authz-roles
   :states     states/all-application-states
   :pre-checks [app/validate-authority-in-drafts]}
  [{{:keys [organization municipality propertyId] :as application} :application}]
  (if-let [{url :url credentials :credentials} (org/get-building-wfs application)]
    (ok :data (building-reader/building-info-list url credentials propertyId))
    (ok)))

;;
;; Asianhallinta
;;

(defn- fetch-linked-kuntalupatunnus
  "Fetch kuntalupatunnus from application's link permit's verdicts"
  [application]
  (when-let [link-permit-app (first (app/get-link-permit-apps application))]
    (-> link-permit-app :verdicts first :kuntalupatunnus)))

(defn- update-kuntalupatunnus [application]
  (if-let [kuntalupatunnus (fetch-linked-kuntalupatunnus application)]
    (update-in application
               [:linkPermitData]
               conj {:id kuntalupatunnus
                     :type "kuntalupatunnus"})
    application))

(defcommand application-to-asianhallinta
  {:parameters [id lang]
   :input-validators [(partial action/non-blank-parameters [:id :lang])]
   :user-roles #{:authority}
   :notified   true
   :on-success (action/notify :application-state-change)
   :pre-checks [has-asianhallinta-operation
                asianhallinta-enabled]
   :states     #{:submitted :complementNeeded}}
  [{orig-app :application created :created user :user org :organization :as command}]
  (let [application (-> (meta-fields/enrich-with-link-permit-data orig-app)
                        update-kuntalupatunnus)
        submitted-application (mongo/by-id :submitted-applications id)
        all-attachments (:attachments (domain/get-application-no-access-checking id [:attachments]))
        app-updates {:modified created,
                     :handlers (ensure-general-handler-is-set (:handlers application) user @org)}
        indicator-updates (app/mark-indicators-seen-updates application user created)
        file-ids (ah/save-as-asianhallinta application lang submitted-application @org) ; Writes to disk
        attachments-updates (or (attachment/create-sent-timestamp-update-statements all-attachments file-ids created) {})
        transfer (get-transfer-item :exported-to-asianhallinta command)]
    (update-application command
                        (util/deep-merge
                          (app-state/state-transition-update (sm/next-state application) created orig-app user)
                          {$push {:transfers transfer}
                           $set (util/deep-merge app-updates attachments-updates indicator-updates)}))
    (ok)))

(defn- application-already-in-asianhallinta [_ application]
  (let [filtered-transfers (filter #(some #{(:type %)} "to-backing-system to-asianhallinta" ) (:transfers application))]
    (when-not (= (:type (last filtered-transfers)) "to-asianhallinta")
      (fail :error.application.not-in-asianhallinta))))

(defcommand attachments-to-asianhallinta
  {:parameters [id lang attachmentIds]
   :input-validators [(partial action/non-blank-parameters [:id :lang])
                      (partial action/vector-parameter-of :attachmentIds string?)]
   :user-roles #{:authority}
   :pre-checks [has-asianhallinta-operation
                asianhallinta-enabled
                (application-already-exported :exported-to-asianhallinta)
                has-unsent-attachments]
   :states     (conj states/post-verdict-states :sent)
   :description "Sends such selected attachments to backing system that are not yet sent."}
  [{:keys [created application user] :as command}]

  (let [all-attachments (:attachments (domain/get-application-no-access-checking (:id application) [:attachments]))
        attachments-wo-sent-timestamp (filter
                                        #(and
                                          (-> % :versions count pos?)
                                          (or
                                            (not (:sent %))
                                            (> (-> % :versions last :created) (:sent %)))
                                          (not (#{"verdict" "statement"} (-> % :target :type)))
                                          (some #{(:id %)} attachmentIds))
                                        all-attachments)
        attachments-transfer-data {:data-key :attachments
                                   :data (map :id attachments-wo-sent-timestamp)}
        transfer (get-transfer-item :exported-to-asianhallinta command attachments-transfer-data)]
    (if (pos? (count attachments-wo-sent-timestamp))
      (let [application (meta-fields/enrich-with-link-permit-data application)
            application (update-kuntalupatunnus application)
            sent-file-ids (ah/save-as-asianhallinta-asian-taydennys application attachments-wo-sent-timestamp lang)
            data-argument (attachment/create-sent-timestamp-update-statements all-attachments sent-file-ids created)]
        (update-application command {$push {:transfers transfer}
                                     $set data-argument})
        (ok))
      (fail :error.sending-unsent-attachments-failed))))

(defquery external-api-enabled
  {:description "Dummy query to check if external API use is configured. Organization from application or user orgs."
   :parameters [id]
   :user-roles #{:authority}
   :states     states/all-states
   :pre-checks [(fn [{{ip :client-ip} :web user :user {:keys [organization]} :application}]
                  (if organization
                    (when-not (autologin/allowed-ip? ip organization)
                      (fail :error.ip-not-allowed))
                    (when-not (some
                                (partial autologin/allowed-ip? ip)
                                (user/organization-ids user))
                      (fail :error.ip-not-allowed))))]})

(defn- list-dir [path pattern]
  (->>
    path
    io/file
    (.listFiles)
    (filter (fn [^java.io.File f] (re-matches pattern (.getName f))))
    (map (fn [^java.io.File f] {:name (.getName f), :modified (.lastModified f)}))))

(defn- list-integration-dirs [output-dir id]
  (if output-dir
    (let [xml-pattern (re-pattern (str "^" id "_.*\\.xml"))
          id-pattern  (re-pattern (str "^" id "_.*"))]
      {:waiting (list-dir output-dir xml-pattern)
       :ok (list-dir (str output-dir env/file-separator "archive") xml-pattern)
       :error (list-dir (str output-dir env/file-separator "error") id-pattern)})
    {:waiting []
     :ok []
     :error []}))

(defquery integration-messages
  {:parameters      [id]
   :user-roles      #{:authority}
   :org-authz-roles #{:approver}
   :pre-checks      [(fn [{app :application organization :organization}]
                       (when-let [org (and organization @organization)]
                         (when-not (or (org/krysp-integration? org (permit/permit-type app))
                                       (ah/asianhallinta-enabled?
                                         (org/resolve-organization-scope (:municipality app) (permit/permit-type app) org)))
                           (fail :error.sftp.user-not-set))))]
   :states          #{:sent :complementNeeded}}
  [{{municipality :municipality permit-type :permitType} :application org :organization}]
  (let [organization     @org
        krysp-output-dir (mapping-to-krysp/resolve-output-directory organization permit-type)
        ah-scope         (org/resolve-organization-scope municipality permit-type organization)
        ah-output-dir    (when (ah/asianhallinta-enabled? ah-scope) (ah/resolve-output-directory ah-scope))]
    (ok :krysp (list-integration-dirs krysp-output-dir id)
        :ah    (list-integration-dirs ah-output-dir id))))

(defn transferred-file-response [filename content-str]
  (->>
    (ss/replace content-str (re-pattern validators/finnish-hetu-str) "******x****")
    (resp/content-type (mime/mime-type filename))
    (resp/set-headers (assoc http/no-cache-headers "Content-Disposition" (format "filename=\"%s\"" filename)))
    (resp/status 200)))

(defn validate-integration-message-filename [{{:keys [id filename]} :data}]
  ; Action pipeline checks that the curren user has access to application.
  ; Check that the file is related to that application
  ; and that a directory traversal is not attempted.
  (when (or (not (re-matches #"^([\w_\-\.]+)\.(txt|xml)$" filename))
            (not (ss/starts-with filename id)))
    (fail :error.invalid-filename)))

(defn- resolve-integration-message-file
  "Resolves organization's integration output directory and returns a File from there."
  [application organization transfer-type file-type filename]
  (let [{municipality :municipality permit-type :permitType} application
        dir (case transfer-type
              "krysp" (mapping-to-krysp/resolve-output-directory organization permit-type)
              "ah" (ah/resolve-output-directory
                     (org/resolve-organization-scope municipality permit-type organization)))
        subdir (case file-type
                 "ok" (str env/file-separator "archive" env/file-separator)
                 "error" (str env/file-separator "error" env/file-separator))
        ; input validator doesn't allow slashes, but sanitize anyway
        sanitized (mime/sanitize-filename filename)]
    (io/file (str dir subdir sanitized))))

(defraw integration-message
  {:parameters [id transferType fileType filename]
   :user-roles #{:authority}
   :org-authz-roles  #{:approver}
   :input-validators [(fn [{data :data}]
                        (when-not (#{"ok" "error"} (:fileType data))
                          (fail :error.unknown-type)))
                      (fn [{data :data}]
                        (when-not (#{"krysp" "ah"} (:transferType data))
                          (fail :error.unknown-type)))
                      (fn [{data :data}]
                        (when-not (validators/application-id? (:id data))
                          (fail :error.invalid-key)))
                      validate-integration-message-filename]
   :states #{:sent :complementNeeded}}
  [{application :application org :organization :as command}]
  (let [f (resolve-integration-message-file application @org transferType fileType filename)]
    (assert (ss/starts-with (.getName f) (:id application))) ; Can't be too paranoid...
    (if (.exists f)
      (transferred-file-response (.getName f) (slurp f))
      (resp/status 404 "File Not Found"))))

(defquery current-configuration
  {:description "Returns current configuration values for specified keys"
   :user-roles #{:anonymous}}
  [_]
  (ok (config/current-configuration)))
