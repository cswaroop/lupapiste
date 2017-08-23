(ns lupapalvelu.financial
  (:require
    [monger.operators :refer :all]
    [sade.core :refer :all]
    [lupapalvelu.user :as usr]
    [lupapalvelu.token :as token]
    [lupapalvelu.ttl :as ttl]
    [lupapalvelu.authorization-api :as auth]
    [lupapalvelu.action :as action]
    [lupapalvelu.notifications :as notifications]
    [sade.env :as env]
    [sade.util :as util]))

(defn get-financial-user []
  (usr/get-user {:role "financialAuthority"}))

(defn create-financial-handler [user-data caller]
  [caller user-data]
  (let [_ (clojure.pprint/pprint user-data)
        user (usr/create-new-user caller user-data :send-email false)
        token (token/make-token :password-reset caller {:email (:email user)} :ttl ttl/create-user-token-ttl)]
    (ok :id (:id user)
        :user user
        :linkFi (str (env/value :host) "/app/fi/welcome#!/setpw/" token)
        :linkSv (str (env/value :host) "/app/sv/welcome#!/setpw/" token))))

(defn invite-financial-handler [command]
  (let [financial-authority (get-financial-user)
        updated-data (assoc (:data command) :email (:email financial-authority))
        updated-data (assoc updated-data :text "")
        updated-data (assoc updated-data :documentName "")
        updated-data (assoc updated-data :role "financialAuthority")
        updated-data (assoc updated-data :notification "invite-financial-authority")
        command (assoc command :data updated-data)
        caller (:user command)]
    (auth/send-invite! command)
    (action/update-application command
                        {:auth {$elemMatch {:invite.user.id (:id financial-authority)}}}
                        {$set {:modified (now)
                               :auth.$   (util/assoc-when-pred financial-authority util/not-empty-or-nil? :inviter caller)}})))

(defn remove-financial-handler-invitation [command]
  (let [financial-authority (get-financial-user)
        username (:username financial-authority)]
    (auth/do-remove-auth command username)
    (notifications/notify! :remove-financial-authority-invitation (assoc command :recipients [financial-authority]))))
