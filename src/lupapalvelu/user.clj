(ns lupapalvelu.user
  (:use [monger.operators]
        [lupapalvelu.core]
        [lupapalvelu.i18n :only [*lang*]]
        [clojure.string :only [trim]]
        [clojure.tools.logging])
  (:require [lupapalvelu.mongo :as mongo]
            [camel-snake-kebab :as kebab]
            [lupapalvelu.security :as security]
            [lupapalvelu.vetuma :as vetuma]
            [sade.security :as sadesecurity]
            [sade.util :as util]
            [sade.env :as env]
            [noir.session :as session]
            [lupapalvelu.token :as token]
            [lupapalvelu.notifications :as notifications]
            [noir.response :as resp]))

(defn applicationpage-for [role]
  (kebab/->kebab-case role))

;; TODO: count error trys!
(defcommand "login"
  {:parameters [:username :password] :verified false}
  [{{:keys [username password]} :data}]
  (if-let [user (security/login username password)]
    (do
      (info "login successful, username:" username)
      (session/put! :user user)
      (if-let [application-page (applicationpage-for (:role user))]
        (ok :user user :applicationpage application-page)
        (do
          (error "Unknown user role:" (:role user))
          (fail :error.login))))
    (do
      (info "login failed, username:" username)
      (fail :error.login))))

(defcommand "register-user"
  {:parameters [:stamp :email :password :street :zip :city :phone]
   :verified   true}
  [{{:keys [stamp] :as data} :data}]
  (if-let [vetuma-data (vetuma/get-user stamp)]
    (let [email (trim (:email data))]
      (if (.contains email "@")
        (try
          (infof "Registering new user: %s - details from vetuma: %s" (dissoc data :password) vetuma-data)
          (if-let [user (security/create-user (merge data vetuma-data {:email email}))]
            (do
              (future (sadesecurity/send-activation-mail-for user))
              (vetuma/consume-user stamp)
              (ok :id (:_id user)))
            (fail :error.create-user))
          (catch IllegalArgumentException e
            (fail (keyword (.getMessage e)))))
        (fail :error.email)))
    (fail :error.create-user)))

(defcommand "change-passwd"
  {:parameters [:oldPassword :newPassword]
   :authenticated true
   :verified true}
  [{{:keys [oldPassword newPassword]} :data {user-id :id :as user} :user}]
  (let [user-data (mongo/by-id :users user-id)]
    (if (security/check-password oldPassword (-> user-data :private :password))
      (do
        (debug "Password change: user-id:" user-id)
        (security/change-password (:email user) newPassword)
        (ok))
      (do
        (warn "Password change: failed: old password does not match, user-id:" user-id)
        (fail :mypage.old-password-does-not-match)))))

(defcommand "reset-password"
  {:parameters    [:email]
   :notified      true
   :authenticated false}
  [{{email :email} :data}]
  (infof "Password resert request: email=%s" email)
  (if (mongo/select-one :users {:email email :enabled true})
    (let [token (token/make-token :password-reset {:email email})]
      (infof "password reset request: email=%s, token=%s" email token)
      (notifications/send-password-reset-email! email token)
      (ok))
    (do
      (warnf "password reset request: unknown email: email=%s" email)
      (fail :email-not-found))))

(defmethod token/handle-token :password-reset [{{email :email} :data} {password :password}]
  (security/change-password email password)
  (infof "password reset performed: email=%s" email)
  (resp/status 200 (resp/json {:ok true})))

(defquery "user"
  {:authenticated true :verified true}
  [{user :user}]
  (ok :user user))

(defcommand "save-user-info"
  {:parameters [:firstName :lastName :street :city :zip :phone]
   :authenticated true
   :verified true}
  [{data :data {user-id :id} :user}]
  (mongo/update-by-id
    :users
    user-id
    {$set (select-keys data [:firstName :lastName :street :city :zip :phone])})
  (session/put! :user (security/get-non-private-userinfo user-id))
  (ok))


(env/in-dev
  (defcommand "create-apikey"
  {:parameters [:username :password]}
  [command]
  (if-let [user (security/login (-> command :data :username) (-> command :data :password))]
    (let [apikey (security/create-apikey)]
      (mongo/update
        :users
        {:username (:username user)}
        {$set {"private.apikey" apikey}})
      (ok :apikey apikey))
      (fail :error.unauthorized))))
