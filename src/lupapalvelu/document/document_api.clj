(ns lupapalvelu.document.document-api
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn error]]
            [clojure.set :refer [intersection union difference]]
            [monger.operators :refer :all]
            [sade.core :refer [ok fail fail! unauthorized unauthorized! now]]
            [sade.util :as util]
            [sade.strings :as ss]
            [lupapalvelu.action :refer [defquery defcommand] :as action]
            [lupapalvelu.application :as application]
            [lupapalvelu.assignment :as assignment]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.document.document :refer :all]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.document.persistence :as doc-persistence]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.roles :as roles]
            [lupapalvelu.states :as states]
            [lupapalvelu.user :as user]))


;; Action category: documents & tasks

(defn- build-document-params [{application-id :id} {document-id :id info :schema-info}]
  {:id  application-id
   :doc document-id
   :docId document-id
   :documentId document-id
   :schemaName (:name info)
   :collection "documents"})

(defn- build-task-params [{application-id :id} {document-id :id}]
  {:id  application-id
   :doc document-id
   :docId document-id
   :documentId document-id
   :collection "tasks"})

(defmethod action/allowed-actions-for-category :documents
  [command]
  (action/allowed-actions-for-collection :documents build-document-params command))

(defmethod action/allowed-actions-for-category :tasks
  [command]
  (action/allowed-actions-for-collection :tasks build-task-params command))

(defn document-in-application-validator [doc-id-key]
  (fn [{{documents :documents} :application data :data}]
    (when (and (get data doc-id-key) (not (util/find-by-id (get data doc-id-key) documents)))
      (fail :error.document-not-found))))

(defn editable-by-state?
  "Pre-check to determine if documents are editable in abnormal states"
  [data-key default-states]
  (fn [{data :data {docs :documents state :state} :application}]
    (when-let [doc-id (get data (keyword data-key))]
      (when-not (-> (domain/get-document-by-id docs doc-id)
                    (model/get-document-schema)
                    (state-valid-by-schema? :editable-in-states default-states state))
        (fail :error.document-not-editable-in-current-state)))))

(defn addable-by-state?
  [default-states]
  (fn [{{schema-name :schemaName} :data {state :state schema-version :schema-version} :application}]
    (when (ss/not-blank? schema-name)
      (when-not (-> (schemas/get-schema schema-version schema-name)
                    (state-valid-by-schema? :addable-in-states default-states state))
        (fail :error.document.post-verdict-addition)))))

(defn- validate-user-authz-by-key
  [doc-id-key {:keys [data application user] :as command}]
  {:pre [(keyword? doc-id-key)]}
  (let [doc-id (get data doc-id-key)
        schema (some-> application (domain/get-document-by-id doc-id) model/get-document-schema)]
    (when (and doc-id (not (auth/application-authority? application user)))
      (if schema
        (-> (get-in schema [:info :user-authz-roles] roles/default-authz-writer-roles)
            (domain/validate-access command))
        (fail :error.document-not-found)))))

(def validate-user-authz-by-doc (partial validate-user-authz-by-key :doc))
(def validate-user-authz-by-doc-id (partial validate-user-authz-by-key :doc-id))
(def validate-user-authz-by-document-id (partial validate-user-authz-by-key :documentId))

(defquery document
  {:parameters       [:id doc collection]
   :categories       #{:documents :tasks}
   :user-roles       #{:applicant :authority}
   :states           states/all-states
   :input-validators [doc-persistence/validate-collection]
   :user-authz-roles roles/all-authz-roles
   :org-authz-roles  roles/reader-org-authz-roles}
  [{:keys [application user]}]
  (if-let [document (doc-persistence/by-id application collection doc)]
    (ok :document (application/process-document-or-task user application document))
    (fail :error.document-not-found)))

;;
;; CRUD
;;

(defcommand create-doc
  {:parameters [:id :schemaName]
   :categories  #{:documents}
   :optional-parameters [updates fetchRakennuspaikka]
   :input-validators [(partial action/non-blank-parameters [:id :schemaName])]
   :user-roles #{:applicant :authority}
   :pre-checks [(addable-by-state? states/create-doc-states)
                create-doc-validator
                application/validate-authority-in-drafts]}
  [{{schema-name :schemaName} :data :as command}]
  (let [document (doc-persistence/do-create-doc! command schema-name updates)]
    (when fetchRakennuspaikka
      (let [property-id (or
                          (tools/get-update-item-value updates "kiinteisto.kiinteistoTunnus")
                          (get-in command [:application :propertyId]))]
        (fetch-and-persist-ktj-tiedot (:application command) document property-id (now))))
    (ok :doc (:id document))))

(defcommand remove-doc
  {:parameters  [id docId]
   :categories  #{:documents}
   :input-validators [(partial action/non-blank-parameters [:id :docId])]
   :user-roles #{:applicant :authority}
   :pre-checks [(document-in-application-validator :docId)
                (editable-by-state? :docId #{:draft :answered :open :submitted :complementNeeded})
                application/validate-authority-in-drafts
                validate-user-authz-by-doc-id
                (doc-disabled-validator :docId)
                remove-doc-validator]}
  [{:keys [application created] :as command}]
  (if-let [document (domain/get-document-by-id application docId)]
    (do
      (doc-persistence/remove! command docId "documents")
      (assignment/remove-target-from-assignments id docId)
      (ok))
    (fail :error.document-not-found)))

(defn- update-document-assignment-statuses [app-id doc-id doc-status]
  (if (= doc-status "disabled")
    (assignment/update-assignments {$and [{:application.id app-id}
                                          {:targets {$size 1}}
                                          {:targets.id doc-id}]}
                                   {$set {:status "canceled"}})
    (assignment/set-assignment-status app-id doc-id "active")))

(defcommand set-doc-status
  {:description "Set document status to disabled: true or disabled: false"
   :parameters [id docId value]
   :categories  #{:documents}
   :input-validators [(partial action/non-blank-parameters [:id :docId])
                      (partial action/select-parameters [:value] #{"enabled" "disabled"})]
   :user-roles #{:applicant :authority}
   :states     states/post-verdict-states
   :pre-checks [(document-in-application-validator :docId)
                (editable-by-state? :docId nil)            ; edition defined solely by document schema
                (validate-disableable-schema :docId)
                validate-document-is-pre-verdict-or-approved
                validate-user-authz-by-doc-id]}
  [command]
  (if (domain/get-document-by-id (:application command) docId)
    (do
      (doc-persistence/set-disabled-status command docId value)
      (update-document-assignment-statuses id docId value)
      (ok))
    (fail :error.document-not-found)))

(defcommand update-doc
  {:parameters [id doc updates]
   :categories #{:documents}
   :user-roles #{:applicant :authority}
   :user-authz-roles (conj roles/default-authz-writer-roles :foreman)
   :input-validators [(partial action/non-blank-parameters [:id :doc])
                      (partial action/vector-parameters [:updates])]
   :pre-checks [(document-in-application-validator :doc)
                (editable-by-state? :doc states/update-doc-states)
                validate-user-authz-by-doc
                application/validate-authority-in-drafts
                (doc-disabled-validator :doc)
                (validate-created-after-verdict :doc)
                (validate-post-verdict-not-approved :doc)]}
  [command]
  (doc-persistence/update! command doc updates "documents"))

(defcommand update-task
  {:parameters [id doc updates]
   :categories #{:tasks}
   :user-roles #{:applicant :authority}
   :states     (states/all-application-states-but (conj states/terminal-states :sent))
   :input-validators [(partial action/non-blank-parameters [:id :doc])
                      (partial action/vector-parameters [:updates])]
   :pre-checks [validate-user-authz-by-doc
                application/validate-authority-in-drafts]}
  [command]
  (doc-persistence/update! command doc updates "tasks"))

(defcommand remove-document-data
  {:parameters       [id doc path collection]
   :categories       #{:documents :tasks}
   :user-roles       #{:applicant :authority}
   :user-authz-roles (conj roles/default-authz-writer-roles :foreman)
   :input-validators [doc-persistence/validate-collection]
   :pre-checks       [(document-in-application-validator :doc)
                      (editable-by-state? :doc #{:draft :answered :open :submitted :complementNeeded})
                      validate-user-authz-by-doc
                      application/validate-authority-in-drafts
                      (doc-disabled-validator :doc)]}
  [command]
  (doc-persistence/remove-document-data command doc [path] collection))

;;
;; Document validation
;;

(defquery validate-doc
  {:parameters       [:id doc collection]
   :categories       #{:documents :tasks}
   :user-roles       #{:applicant :authority}
   :states           states/all-states
   :input-validators [doc-persistence/validate-collection]
   :user-authz-roles roles/all-authz-roles
   :org-authz-roles  roles/reader-org-authz-roles}
  [{:keys [application]}]
  (let [document (doc-persistence/by-id application collection doc)]
    (when-not document (fail! :error.document-not-found))
    (ok :results (model/validate application document))))

(defquery fetch-validation-errors
  {:parameters       [:id]
   :user-roles       #{:applicant :authority}
   :user-authz-roles roles/all-authz-roles
   :org-authz-roles  roles/reader-org-authz-roles
   :states           states/all-states}
  [{app :application}]
  (ok :results (application/pertinent-validation-errors app)))

;;
;; Document approvals
;;

(defcommand approve-doc
  {:parameters [:id :doc :path :collection]
   :categories       #{:documents :tasks}
   :input-validators [(partial action/non-blank-parameters [:id :doc :collection])
                      doc-persistence/validate-collection]
   :pre-checks [(document-in-application-validator :doc)
                (editable-by-state? :doc states/approve-doc-states)
                (doc-disabled-validator :doc)]
   :user-roles #{:authority}}
  [command]
  (ok :approval (approve command "approved")))

(defcommand reject-doc
  {:parameters [:id :doc :path :collection]
   :categories       #{:documents :tasks}
   :input-validators [(partial action/non-blank-parameters [:id :doc :collection])
                      doc-persistence/validate-collection]
   :pre-checks [(document-in-application-validator :doc)
                (editable-by-state? :doc states/approve-doc-states)
                (doc-disabled-validator :doc)]
   :user-roles #{:authority}}
  [command]
  (ok :approval (approve command "rejected")))

(defcommand reject-doc-note
  {:description "Explanatory note regarding the reject reason. Adding
  note updates application modified timestamp but not the approval
  timestamp"
   :parameters       [:id :doc :path :collection note]
   :categories       #{:documents :tasks}
   :input-validators [(partial action/non-blank-parameters [:id :doc :collection])
                      doc-persistence/validate-collection]
   :pre-checks       [(document-in-application-validator :doc)
                      (editable-by-state? :doc states/approve-doc-states)]
   :user-roles       #{:authority}}
  [command]
  (set-rejection-note command note)
  (ok))

;;
;; Set party to document
;;

(defcommand set-user-to-document
  {:parameters [id documentId userId path]
   :categories #{:documents}
   :user-roles #{:applicant :authority}
   :user-authz-roles (conj roles/default-authz-writer-roles :foreman)
   :input-validators [(partial action/non-blank-parameters [:id :documentId])]
   :pre-checks [(document-in-application-validator :documentId)
                (editable-by-state? :documentId states/update-doc-states)
                user-can-be-set-validator
                validate-user-authz-by-document-id
                application/validate-authority-in-drafts
                (doc-disabled-validator :documentId)]}
  [{:keys [created application] :as command}]
  (doc-persistence/do-set-user-to-document application documentId userId path created))

(defcommand set-current-user-to-document
  {:parameters [id documentId path]
   :categories #{:documents}
   :user-roles #{:applicant :authority}
   :user-authz-roles (conj roles/default-authz-writer-roles :foreman)
   :input-validators [(partial action/non-blank-parameters [:id :documentId])]
   :pre-checks [(document-in-application-validator :documentId)
                (editable-by-state? :documentId states/update-doc-states)
                domain/validate-owner-or-write-access
                validate-user-authz-by-document-id
                application/validate-authority-in-drafts
                (doc-disabled-validator :documentId)]}
  [{:keys [created application user] :as command}]
  (doc-persistence/do-set-user-to-document application documentId (:id user) path created))

(defcommand set-company-to-document
  {:description "Updates company information on the document.The
  contact person information is filled only if either the current user
  is a company member or the application has a company member in the
  auth array. Otherwise, left empty."
   :parameters       [id documentId companyId path]
   :categories       #{:documents}
   :user-roles       #{:applicant :authority}
   :user-authz-roles (conj roles/default-authz-writer-roles :foreman)
   :input-validators [(partial action/non-blank-parameters [:id :documentId])]
   :pre-checks       [(document-in-application-validator :documentId)
                      (editable-by-state? :documentId states/update-doc-states)
                      validate-user-authz-by-document-id
                      application/validate-authority-in-drafts
                      (doc-disabled-validator :documentId)]}
  [{:keys [user created application] :as command}]
  (if-let [document (domain/get-document-by-id application documentId)]
    (doc-persistence/do-set-company-to-document application document companyId path (user/get-user-by-id (:id user)) created)
    (fail :error.document-not-found)))
