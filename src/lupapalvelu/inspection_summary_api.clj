(ns lupapalvelu.inspection-summary-api
  (:require [lupapalvelu.inspection-summary :as inspection-summary]
            [lupapalvelu.user :as usr]
            [lupapalvelu.action :as action :refer [defquery defcommand]]
            [sade.core :refer [ok fail fail! now unauthorized]]
            [sade.strings :as ss]
            [lupapalvelu.states :as states]
            [lupapalvelu.application :as app]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.document.schemas :as schemas]
            [sade.util :as util]))

(defquery organization-inspection-summary-settings
  {:description "Inspection summary templates for given organization."
   :pre-checks [inspection-summary/inspection-summary-api-auth-admin-pre-check]
   :user-roles #{:authorityAdmin}}
  [{user :user}]
  (ok (inspection-summary/settings-for-organization (usr/authority-admins-organization-id user))))

(defcommand modify-inspection-summary-template
  {:description "CRUD API endpoint for inspection summary templates in the given organization."
   :parameters  [func]
   :input-validators [(partial action/select-parameters [:func] #{"create" "update" "delete"})]
   :pre-checks [inspection-summary/inspection-summary-api-auth-admin-pre-check]
   :user-roles #{:authorityAdmin}}
  [{user :user {:keys [templateId templateText name]} :data}]
  (let [organizationId (usr/authority-admins-organization-id user)]
    (when (and (ss/blank? templateId) (#{"update" "delete"} func))
      (fail! :error.missing-parameters :parameters "templateId"))
    (when (and (ss/blank? templateText) (#{"create" "update"} func))
      (fail! :error.missing-parameters :parameters "templateText"))
    (when (and (ss/blank? name) (#{"create" "update"} func))
      (fail! :error.missing-parameters :parameters "name"))
    (condp = func
      "create" (inspection-summary/create-template-for-organization organizationId name templateText)
      "update" (if (= (inspection-summary/update-template organizationId templateId name templateText) 1)
                 (ok)
                 (fail :error.not-found))
      "delete" (if (= (inspection-summary/delete-template organizationId templateId) 1)
                 (ok)
                 (fail :error.not-found))
      (fail :error.illegal-function-code))))

(defcommand set-inspection-summary-template-for-operation
  {:description "Toggles operation either requiring section or not."
   :parameters [operationId templateId]
   :input-validators [(partial action/non-blank-parameters [:operationId :templateId])]
   :pre-checks [inspection-summary/inspection-summary-api-auth-admin-pre-check]
   :user-roles #{:authorityAdmin}}
  [{user :user}]
  (let [organizationId (usr/authority-admins-organization-id user)]
    (inspection-summary/set-default-template-for-operation organizationId operationId templateId)))

(defn- map-operation-to-frontend [app op]
  (let [document (domain/get-document-by-operation app op)
        {identifier-field :name} (schemas/find-identifier-field-from (get-in document [:schema-info :name]))]
    (assoc (select-keys op [:id :name :description])
      :op-identifier (or (get-in document [:data (keyword identifier-field) :value])
                         (get-in document [:data :valtakunnallinenNumero :value])))))

(defquery inspection-summaries-for-application
  {:pre-checks [(action/some-pre-check
                  inspection-summary/inspection-summary-api-authority-pre-check
                  inspection-summary/inspection-summary-api-applicant-pre-check)]
   :parameters [:id]
   :states states/post-verdict-states
   :user-roles #{:authority :applicant}}
  [{app :application}]
  (ok :templates (-> (inspection-summary/settings-for-organization (:organization app)) :templates)
      :summaries (:inspection-summaries app)
      :operations (->> (app/get-operations app)
                       (map (partial map-operation-to-frontend app))
                       (remove nil?))))

(defcommand create-inspection-summary
  {:pre-checks [inspection-summary/inspection-summary-api-authority-pre-check]
   :parameters [:id templateId operationId]
   :input-validators [(partial action/non-blank-parameters [:operationId :templateId])]
   :user-roles #{:authority}}
  [{app :application}]
  (ok :id (inspection-summary/new-summary-for-operation
            app
            (util/find-by-key :id operationId (app/get-operations app))
            templateId)))