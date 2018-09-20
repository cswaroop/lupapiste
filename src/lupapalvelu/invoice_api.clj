(ns lupapalvelu.invoice-api
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clojure.set :as set]
            [lupapalvelu.action :refer [defquery defcommand defraw notify] :as action]
            [lupapalvelu.application-bulletins :as bulletins]
            [lupapalvelu.organization :as org]
            [lupapalvelu.invoices :as invoices]
            [lupapalvelu.pate.verdict-template :as template]
            [lupapalvelu.roles :as roles]
            [lupapalvelu.states :as states]
            [lupapalvelu.user :as usr]
            [sade.core :refer :all]
            [sade.util :as util]))


;; ------------------------------------------
;; Invoice API
;; ------------------------------------------

(defquery price-catalogue
  {:description "A collection of prices. Item properties:
                       id:        catalogue id
                       prices:    list of prices"
   :feature          :pate
   :user-roles       #{:authority :applicant}
   :org-authz-roles  roles/reader-org-authz-roles
   :user-authz-roles roles/all-authz-roles
   :parameters       [id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :states           states/post-submitted-states}
  [command]
  (ok :verdicts (invoices/price-catalogue command)))
