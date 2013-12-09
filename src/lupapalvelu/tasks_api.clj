(ns lupapalvelu.tasks-api
  (:require [taoensso.timbre :as timbre :refer [trace debug info infof warn warnf error fatal]]
            [lupapalvelu.core :refer :all]
            [lupapalvelu.action :refer [defquery defcommand defraw non-blank-parameters update-application]]
            [lupapalvelu.mongo :as mongo]
            [monger.operators :refer :all]))

;; Helpers

(defn- task-state-in [states {{task-id :taskId} :data } {tasks :tasks}]
  (if-let [task (some #(when (= (:id %) id) %) tasks)]
    (when-not ((set states) (keyword (:state task)))
      (fail :error.command-illegal-state))
    (fail :error.task-not-found)))

(defn- set-state [{created :created :as command} task-id state]
  (update-application command
    {:tasks {$elemMatch {:id task-id}}}
    {$set {:tasks.$.state state :modified created}}))

;; API

(defcommand delete-task
  {:parameters [id taskId]
   :input-validators [(partial non-blank-parameters [:id :taskId])
                      (partial task-state-in [:requires_user_action :requires_authority_action :ok])]
   :roles [:authority]}
  [{created :created :as command}]
  (update-application command
    {$pull {:tasks {:id taskId}}
     $set  {:modified  created}}))

(defcommand approve-task
  {:description "Authority can approve task, moves to ok"
   :parameters  [id taskId]
   :input-validators [(partial non-blank-parameters [:id :taskId])]
   :roles       [:authority]}
  [command]
  (set-state command taskId :ok))

(defcommand reject-task
  {:description "Authority can reject task, requires user action."
   :parameters  [id taskId]
   :input-validators [(partial non-blank-parameters [:id :taskId])]
   :roles       [:authority]}
  [command]
  (set-state command taskId :requires_user_action))

(defcommand send-task
  {:description "Authority can send task info to municipality backend system."
   :parameters  [id taskId]
   :input-validators [(partial non-blank-parameters [:id :taskId])
                      (partial task-state-in [:ok])]
   :roles       [:authority]}
  [command]
  ; TODO form KRYSP message
  (set-state command taskId :sent))
