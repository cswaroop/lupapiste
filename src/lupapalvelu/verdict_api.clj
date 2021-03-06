(ns lupapalvelu.verdict-api
  "Backing system verdicts. The API for manually created verdicts
  resides in `pate/verdict_api.clj`."
  (:require [taoensso.timbre :refer [trace debug debugf info infof warn warnf error fatal]]
            [monger.operators :refer :all]
            [sade.core :refer [ok fail fail! ok?]]
            [sade.util :as util]
            [lupapalvelu.action :refer [defcommand notify] :as action]
            [lupapalvelu.application-state :as app-state]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.backing-system.allu.core :as allu]
            [lupapalvelu.organization :as org]
            [lupapalvelu.pate.verdict :as pate-verdict :refer [backing-system-verdict command->backing-system-verdict]]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.state-machine :as sm]
            [lupapalvelu.states :as states]
            [lupapalvelu.verdict :as verdict]
            [lupapalvelu.backing-system.allu.contract :as allu-contract]))

(defn application-has-verdict-given-state [{:keys [application]}]
  (when-not (and application (some (partial sm/valid-state? application) states/verdict-given-states))
    (fail :error.command-illegal-state)))

(defn- backing-system-is-defined [{:keys [application organization]}]
  (when-let [permit-type (:permitType application)]
    (when-not (or (org/resolve-krysp-wfs @organization permit-type)
                  (allu/allu-application? (:organization application) permit-type))
      (fail :error.no-legacy-available))))

(defcommand check-for-verdict
  {:description "Fetches verdicts from municipality backend system.
  If the command is run more than once, existing verdicts are replaced
  by the new ones. Note: everything related to old backing system
  verdicts (tasks, attaachments, appeals, appealVerdicts) are
  deleted."
   :parameters  [:id]
   :states      (conj states/give-verdict-states :constructionStarted) ; states reviewed 2015-10-12
   :user-roles  #{:authority}
   :notified    true
   :pre-checks  [application-has-verdict-given-state
                 verdict/no-sent-backing-system-verdict-tasks
                 backing-system-is-defined]
   :on-success  (notify :application-state-change)}
  [{:keys [application created user] :as command}]
  (let [result (if (allu/allu-application? (:organization application) (permit/permit-type application))
                 (ok :verdicts [(allu-contract/fetch-allu-contract command)])
                 (verdict/do-check-for-verdict command))]
    (cond
      (nil? result) (fail :info.no-verdicts-found-from-backend)
      (ok? result)  (ok :verdictCount (count (:verdicts result))
                        :taskCount (count (:tasks result)))
      :else         result)))

(defcommand delete-verdict
  {:parameters       [id verdict-id]
   :input-validators [(partial action/non-blank-parameters [:id :verdict-id])]
   :states           states/give-verdict-states
   :notified         true
   :categories       #{:pate-verdicts}
   :user-roles       #{:authority}
   :pre-checks       [backing-system-verdict]}
  [{:keys [application] :as command}]
  (verdict/delete-verdict command
                          (command->backing-system-verdict command)
                          true))
