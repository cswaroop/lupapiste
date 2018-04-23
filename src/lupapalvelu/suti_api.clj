(ns lupapalvelu.suti-api
  "Suti is an external service that provides prerequisite documents/products."
  (:require [sade.core :refer :all]
            [sade.strings :as ss]
            [monger.operators :refer :all]
            [lupapalvelu.action :refer [defquery defcommand] :as action]
            [lupapalvelu.organization :as org]
            [lupapalvelu.operations :as op]
            [lupapalvelu.roles :as roles]
            [lupapalvelu.user :as usr]
            [lupapalvelu.states :as states]
            [lupapalvelu.suti :as suti]))

(defcommand suti-toggle-enabled
  {:description      "Enable/disable Suti support."
   :parameters       [flag]
   :input-validators [(partial action/boolean-parameters [:flag])]
   :user-roles       #{:authorityAdmin}}
  [{user :user}]
  (org/toggle-group-enabled (usr/authority-admins-organization-id user) :suti flag))

(defcommand suti-toggle-operation
  {:description      "Toggles operation either requiring Suti or not."
   :parameters       [operationId flag]
   :input-validators [(partial op/visible-operation :operationId)
                      (partial action/boolean-parameters [:flag])]
   :user-roles       #{:authorityAdmin}}
  [{user :user}]
  (org/toggle-group-operation (usr/authority-admins-organization user)
                              :suti
                              (ss/trim operationId)
                              flag))

(defquery suti-admin-details
  {:description "Suti details for the current authority admin's organization."
   :user-roles  #{:authorityAdmin}}
  [{user :user}]
  (ok :suti (suti/organization-details (usr/authority-admins-organization user))))

(defquery suti-operations
  {:description "Suti operations for the current authority admin's organization."
   :user-roles  #{:authorityAdmin}}
  [{user :user}]
  (ok :operations (-> user usr/authority-admins-organization :suti :operations)))

(defcommand suti-www
  {:description      "Public Suti URL. Not to be confused with the Suti backend."
   :parameters       [www]
   :input-validators [(partial action/validate-optional-url :www)]
   :user-roles       #{:authorityAdmin}}
  [{user :user}]
  (suti/set-www (usr/authority-admins-organization user) (ss/trim www)))

(defquery suti-application-data
  {:description      "Fetches the Suti results for the given application."
   :parameters       [id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:authority :applicant}
   :user-authz-roles roles/all-authz-roles
   :org-authz-roles  roles/reader-org-authz-roles
   :states           states/all-application-states}
  [{application :application organization :organization}]
  (ok :data (suti/application-data application @organization)))

(defquery suti-application-products
  {:description      "Fetches the Suti backend products for the given application."
   :parameters       [id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:authority :applicant}
   :user-authz-roles roles/all-authz-roles
   :org-authz-roles  roles/reader-org-authz-roles
   :states           states/all-application-states}
  [{application :application organization :organization}]
  (ok :data (suti/application-products application @organization)))

(defcommand suti-update-id
  {:description      "Mechanism for updating Suti id property."
   :parameters       [id sutiId]
   :input-validators [(partial action/non-blank-parameters [:id])
                      (partial action/string-parameters [:sutiId])]
   :user-roles       #{:authority :applicant}
   :states           states/all-application-states}
  [command]
  (action/update-application command {$set {:suti.id (ss/trim sutiId)}}))

(defcommand suti-update-added
  {:description      "Mechanism for updating Suti added property."
   :parameters       [id added]
   :input-validators [(partial action/non-blank-parameters [:id])
                      (partial action/boolean-parameters [:added])]
   :user-roles       #{:authority :applicant}
   :states           states/all-application-states}
  [command]
  (action/update-application command {$set {:suti.added added}}))

(defquery suti-pre-sent-state
  {:description      "Pseudo query for checking the application state."
   :parameters       [id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:authority :applicant}
   :states           states/pre-sent-application-states})
