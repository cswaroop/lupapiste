(ns lupapalvelu.user-api
  (:require [clojure.set :as set]
            [lupapalvelu.action :refer [defquery defcommand defraw email-validator] :as action]
            [lupapalvelu.activation :as activation]
            [lupapalvelu.attachment :as att]
            [lupapalvelu.attachment.type :as att-type]
            [lupapalvelu.calendar :as cal]
            [lupapalvelu.idf.idf-client :as idf]
            [lupapalvelu.mime :as mime]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.password-reset :as pw-reset]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.roles :as roles]
            [lupapalvelu.security :as security]
            [lupapalvelu.states :as states]
            [lupapalvelu.token :as token]
            [lupapalvelu.user :as usr]
            [lupapalvelu.user-utils :as uu]
            [lupapalvelu.vetuma :as vetuma]
            [monger.operators :refer :all]
            [noir.core :refer [defpage]]
            [noir.request :as request]
            [noir.response :as resp]
            [sade.core :refer :all]
            [sade.env :as env]
            [sade.session :as ssess]
            [sade.strings :as ss]
            [sade.util :refer [future*]]
            [sade.util :as util]
            [schema.core :as sc]
            [slingshot.slingshot :refer [throw+ try+]]
            [swiss.arrows :refer :all]
            [taoensso.timbre :refer [trace debug info infof warn warnf error fatal]]))

;;
;; ==============================================================================
;; Getting user and users:
;; ==============================================================================
;;

(defn- get-user [user]
  (if (usr/virtual-user? user)
    user
    (let [full-user (usr/get-user-by-id (:id user))]
      (cond-> (dissoc full-user :private)
        (usr/verified-person-id? full-user) (dissoc :personId)))))

(defquery user
  {:on-success (fn [_ {user :user}]
                 (when (:firstLogin user)
                   (mongo/update-by-id :users (:id user) {$unset {:firstLogin true}})))
   :user-roles roles/all-authenticated-user-roles}
  [{user :user}]
  (if-let [full-user (get-user user)]
    (ok :user (assoc full-user :virtual (usr/virtual-user? user)))
    (fail :error.user.not.found)))

(defquery users
  {:user-roles #{:admin}}
  [{{:keys [role]} :user data :data}]
  (let [base-query (-> data
                     (set/rename-keys {:userId :id})
                     (select-keys [:id :role :email :username :firstName :lastName :enabled :allowDirectMarketing]))
        org-ids (cond
                  (:organization data) [(:organization data)]
                  (:organizations data) (:organizations data))
        query (if (seq org-ids)
                {$and [base-query, (usr/org-authz-match org-ids)]}
                base-query)
        users (usr/find-users query)]
    (ok :users (map (comp usr/with-org-auth usr/non-private) users))))

(defquery users-in-same-organizations
  {:user-roles #{:authority}}
  [{user :user}]
  (if-let [organization-ids (seq (usr/organization-ids user))]
    (let [query {$and [{:role "authority"}, (usr/org-authz-match organization-ids)]}
          users (usr/find-users query)]
      (ok :users (map usr/summary-for-search-filter users)))
    (ok :users [])))

(env/in-dev
 (defquery user-by-email
   {:parameters [email]
    :input-validators [(partial action/non-blank-parameters [:email])]
    :user-roles #{:admin}}
   [_]
   (ok :user (usr/get-user-by-email email))))

(defquery users-for-datatables
  {:user-roles #{:admin :authorityAdmin}}
  [{caller :user {params :params} :data}]
  (ok :data (usr/users-for-datatables caller params)))



;;
;; ==============================================================================
;; Creating users:
;; ==============================================================================
;;

(defn- create-authority-user-with-organization [caller new-organization email firstName lastName roles]
  (let [org-authz {new-organization (into #{} roles)}
        user-data {:email email :orgAuthz org-authz :role :authority :enabled true :firstName firstName :lastName lastName}
        new-user (usr/create-new-user caller user-data :send-email false)]
    (infof "invitation for new authority user: email=%s, organization=%s" email new-organization)
    (uu/notify-new-authority new-user caller new-organization)
    (ok :operation "invited")))

(defcommand create-user
  {:parameters [:email role]
   :input-validators [(partial action/non-blank-parameters [:email])
                      action/email-validator]
   :notified true
   :user-roles #{:admin :authorityAdmin}}
  [{user-data :data caller :user}]
  (uu/create-and-notify-user caller user-data))

(defcommand create-rest-api-user
  {:description "Creates REST API user for organization. Admin only."
   :parameters [username]
   :input-validators [(partial action/string-parameters [:username])
                      (partial action/ascii-parameters  [:username])]
   :user-roles #{:admin}}
  [{user-data :data caller :user :as command}]
  (let [rest-user-email (str username "@example.com")
        user-data       (assoc user-data :email rest-user-email)]
    (if-not (usr/get-user-by-email rest-user-email)
      (ok :user (usr/create-rest-user user-data))
      (fail :email-in-use))))

;;
;; ==============================================================================
;; Updating user data:
;; ==============================================================================
;;

;;
;; General changes:
;;

(def- user-data-editable-fields [:firstName :lastName :street :city :zip :phone :language
                                 :architect :degree :graduatingYear :fise :fiseKelpoisuus
                                 :companyName :companyId :allowDirectMarketing :personId])

(defn- validate-update-user! [caller user-data]
  (let [caller-email    (:email caller)
        user-email      (:email user-data)]

    (if (usr/admin? caller) ; TODO: Admin is not allowed to update userdata since restruction fromUserUpdate schema. How this should work?
      (when (= user-email caller-email)    (fail! :error.unauthorized :desc "admin may not change his/her own data"))
      (when (not= user-email caller-email) (fail! :error.unauthorized :desc "can't edit others data")))

    true))

;; Define schema for update data
(def- UserUpdate (dissoc usr/User :id :role :email :username :enabled))

(defn- validate-updatable-user [{user-data :data}]
  (when (sc/check UserUpdate user-data)
    (fail :error.invalid-user-data)))

(defn- validate-registrable-user [{user-data :data}]
  (when (sc/check usr/RegisterUser user-data)
    (fail :error.invalid-user-data)))

(defn- validate-person-id-update-is-allowed! [{{person-id :personId email :email} :data {caller-email :email} :user}]
  (when (and person-id (-> (or email caller-email)
                           usr/get-user-by-email
                           usr/verified-person-id?))
    (fail :error.user.trying-to-update-verified-person-id)))

(defcommand update-user
  {:user-roles #{:applicant :authority :authorityAdmin :admin}
   :input-validators [validate-updatable-user]
   :pre-checks [validate-person-id-update-is-allowed!]}
  [{caller :user {person-id :personId :as user-data} :data :as command}]
  (let [email     (ss/canonize-email (or (:email user-data) (:email caller)))
        user-data (assoc user-data :email email)]
    (validate-update-user! caller user-data)
    (if (= 1 (mongo/update-n :users {:email email} {$set (cond-> (select-keys user-data user-data-editable-fields)
                                                           person-id (assoc :personIdSource :user))}))
      (if (= email (:email caller))
        (ssess/merge-to-session command (ok) {:user (usr/session-summary (usr/get-user-by-id (:id caller)))})
        (ok))
      (fail :not-found :email email))))

(defcommand applicant-to-authority
  {:parameters [email]
   :user-roles #{:admin}
   :input-validators [(partial action/non-blank-parameters [:email])
                      action/email-validator]
   :description "Changes applicant or dummy account into authority"}
  [_]
  (let [user (usr/get-user-by-email email)]
    (if (#{"dummy" "applicant"} (:role user))
      (mongo/update :users {:email email} {$set {:role "authority"}})
      (fail :error.user-not-found))))

;;
;; Saved search filters
;;

(def filter-storage-key
  {"application" :applicationFilters
   "company" :companyApplicationFilters
   "foreman" :foremanFilters})

(def default-filter-storage-key
  {"application" "id"
   "foreman" "foremanFilterId"
   "company" "companyFilterId"})

(defn- validate-filter-type [{{filter-type :filterType} :data}]
  (when-not (contains? filter-storage-key filter-type)
    (fail :error.invalid-type)))

(defcommand update-default-application-filter
  {:parameters       [filterId filterType]
   :user-roles       #{:authority :applicant}
   :input-validators [validate-filter-type
                      (fn [{{filter-id :filterId filter-type :filterType} :data {user-id :id} :user}]
                        (let [user (usr/get-user-by-id user-id)
                              filters (get user (filter-storage-key filter-type))]
                          (when-not (or (nil? filter-id) (util/find-by-id filter-id filters))
                            (fail :error.filter-not-found))))]
   :description      "Adds/Updates users default filter for the application search"}
  [{{user-id :id} :user}]

  (let [id-key           (default-filter-storage-key filterType)]
    (mongo/update-by-id :users user-id {$set {(str "defaultFilter." id-key) filterId}})))

(defcommand save-application-filter
  {:parameters       [title :filter sort filterType]
   :user-roles       #{:authority :applicant}
   :input-validators [validate-filter-type
                      (partial action/non-blank-parameters [:title])
                      (partial action/map-parameters [:filter])
                      (partial action/map-parameters-with-required-keys [:sort] [:field :asc])
                      (fn [{{filter-id :filterId} :data}]
                        (when (and filter-id (not (mongo/valid-key? filter-id)))
                          (fail :error.illegal-key)))]
   :description      "Adds/updates application filter for the user"}
  [{{user-id :id} :user {filter-data :filter filter-id :filterId} :data}]
  (let [filter-id        (or filter-id (mongo/create-id))
        storage-key      (filter-storage-key filterType)
        id-key           (default-filter-storage-key filterType)
        user             (usr/get-user-by-id user-id)
        filters          (get user storage-key)
        title-collision? (->> filters
                              (filter #(= (:title %) title))
                              (map :id)
                              (some (partial not= filter-id)))
        search-filter    {:id filter-id :title title :filter filter-data :sort sort}
        ; enable editing existing filter
        updated-filters  (if (empty? filters)
                           [search-filter]
                           (as-> filters $
                                (zipmap (map :id $) (range))
                                (get $ filter-id (count $))
                                (assoc-in filters [$] search-filter)))
        update {$set (merge {storage-key updated-filters}
                       (when (empty? filters)
                         {(str "defaultFilter." id-key) filter-id}))}]

    (when title-collision?
      (fail! :error.filter-title-collision))

    (doseq [filter updated-filters]
      ; Should always  pass, but if not, throws exception which will be caught
      ; by our action framework. User gets an unexpected error.
      (sc/validate usr/SearchFilter filter))

    (mongo/update-by-id :users user-id update)
    (ok :filter search-filter)))

(defcommand remove-application-filter
  {:parameters [filterId filterType]
   :user-roles #{:authority :applicant}
   :input-validators [validate-filter-type
                      (partial action/non-blank-parameters [:filterId])]
   :description "Removes users application filter"}
  [{{user-id :id} :user}]
  (let [user (usr/get-user-by-id user-id)
        storage-key (filter-storage-key filterType)
        id-key (default-filter-storage-key filterType)
        update (merge {$pull {storage-key {:id filterId}}}
                      (when (= (get-in user [:defaultFilter id-key]) filterId)
                        {$set {(str "defaultFilter." id-key) ""}}))]
    (mongo/update-by-id :users user-id update)))

(defquery saved-application-filters
  {:user-roles roles/all-authenticated-user-roles
   :description "Returns search filters for external services. The same data is provided by user query."}
  [{user :user}]
  (if-let [full-user (get-user user)]
    (ok (select-keys full-user [:applicationFilters :foremanFilters :defaultFilter]))
    (fail)))

;;
;; Change organization data:
;;

(defn- valid-organization-operation? [{data :data}]
  (when-not (#{"add" "remove"} (:operation data))
    (fail :bad-request :desc (str "illegal organization operation: '" (:operation data) "'"))))

(defn- allowed-roles [allowed-roles command]
  (let [roles (get-in command [:data :roles])
        pred (set (map name allowed-roles))]
    (when-not (every? pred roles)
      (fail :invalid.roles))))

(defcommand update-user-organization
  {:parameters       [email firstName lastName roles]
   :input-validators [(partial action/non-blank-parameters [:email :firstName :lastName])
                      (partial action/vector-parameters-with-at-least-n-non-blank-items 1 [:roles])
                      action/email-validator
                      (partial allowed-roles organization/authority-roles)]
   :notified true
   :user-roles #{:authorityAdmin}}
  [{caller :user}]
  (let [organization-id (usr/authority-admins-organization-id caller)
        actual-roles    (organization/filter-valid-user-roles-in-organization organization-id roles)
        email           (ss/canonize-email email)
        result          (usr/update-user-by-email email {:role "authority"} {$set {(str "orgAuthz." organization-id) actual-roles}})]
    (if (ok? result)
      (do
        (uu/notify-authority-added email organization-id)
        (ok :operation "add"))
      (if-not (usr/get-user-by-email email)
        (create-authority-user-with-organization caller organization-id email firstName lastName actual-roles)
        (fail :error.user-not-found)))))

(defcommand remove-user-organization
  {:parameters       [email]
   :input-validators [(partial action/non-blank-parameters [:email])
                      action/email-validator]
   :user-roles       #{:authorityAdmin}}
  [{caller :user}]
  (let [organization-id (usr/authority-admins-organization-id caller)]
    (usr/update-user-by-email email {:role "authority"} {$unset {(str "orgAuthz." organization-id) ""}})))

(defcommand update-user-roles
  {:parameters [email roles]
   :input-validators [(partial action/non-blank-parameters [:email])
                      (partial action/vector-parameters-with-at-least-n-non-blank-items 1 [:roles])
                      (partial allowed-roles organization/authority-roles)]
   :user-roles #{:authorityAdmin}}
  [{caller :user}]
  (let [organization-id (usr/authority-admins-organization-id caller)
        actual-roles    (organization/filter-valid-user-roles-in-organization organization-id roles)]
    (usr/update-user-by-email email {:role "authority"} {$set {(str "orgAuthz." organization-id) actual-roles}})))

(defmethod token/handle-token :authority-invitation [{{:keys [email organization caller-email]} :data} {password :password}]
  (infof "invitation for new authority: email=%s: processing..." email)
  (let [caller (usr/get-user-by-email caller-email)]
    (when-not caller (fail! :not-found))
    (usr/change-password email password)
    (infof "invitation was accepted: email=%s, organization=%s" email organization)
    (ok)))

;;
;; Change and reset password:
;;

(defcommand check-password
  {:parameters [password]
   :user-roles #{:applicant :authority}
   :input-validators [(partial action/non-blank-parameters [:password])]}
  [{user :user}]
  (if (security/check-password password
                               (some-<>> user
                                        :id
                                        (mongo/by-id :users <> {:private.password true})
                                        :private
                                        :password))
    (ok)
    (fail :error.password)))

(defcommand change-passwd
  {:parameters [oldPassword newPassword]
   :input-validators [(partial action/non-blank-parameters [:oldPassword :newPassword])]
   :user-roles #{:applicant :authority :authorityAdmin :admin :financialAuthority}}
  [{{user-id :id :as user} :user}]
  (let [user-data (mongo/by-id :users user-id)]
    (if (security/check-password oldPassword (-> user-data :private :password))
      (do
        (debug "Password change: user-id:" user-id)
        (usr/change-password (:email user) newPassword)
        (ok))
      (do
        (warn "Password change: failed: old password does not match, user-id:" user-id)
        ; Throttle giving information about incorrect password
        (Thread/sleep 2000)
        (fail :mypage.old-password-does-not-match)))))

(defcommand reset-password
  {:parameters    [email]
   :user-roles #{:anonymous}
   :input-validators [(partial action/non-blank-parameters [:email])
                      action/email-validator]
   :notified      true}
  [_]
  (let [user (usr/get-user-by-email email) ]
    (if (and user (not (usr/dummy? user)))
      (do
        (pw-reset/reset-password user)
        (ok))
      (do
        (warnf "password reset request: unknown email: email=%s" email)
        (fail :error.email-not-found)))))

(defcommand admin-reset-password
  {:parameters    [email]
   :user-roles #{:admin}
   :input-validators [(partial action/non-blank-parameters [:email])
                      action/email-validator]
   :notified      true}
  [_]
  (let [user (usr/get-user-by-email email) ]
    (if (and user (not (usr/dummy? user)))
      (ok :link (pw-reset/reset-link (or (:language user) "fi") (pw-reset/reset-password user)))
      (fail :error.email-not-found))))

(defmethod token/handle-token :password-reset [{data :data} {password :password}]
  (let [email (ss/canonize-email (:email data))]
    (usr/change-password email password)
    (infof "password reset performed: email=%s" email)
    (ok)))

;;
;; enable/disable:
;;

(defcommand set-user-enabled
  {:parameters    [email enabled]
   :input-validators [(partial action/non-blank-parameters [:email])
                      action/email-validator]
   :user-roles #{:admin}}
  [_]
  (let [email (ss/canonize-email email)
       enabled (contains? #{true "true"} enabled)]
   (infof "%s user: email=%s" (if enabled "enable" "disable") email)
   (if (= 1 (mongo/update-n :users {:email email} {$set {:enabled enabled}}))
     (ok)
     (fail :not-found))))

;;
;; ==============================================================================
;; Login:
;; ==============================================================================
;;


(defcommand login
  {:parameters [username password]
   :input-validators [(partial action/non-blank-parameters [:username :password])]
   ; Failed login count is written to DB but we'll allow this in readonly mode
   :allowed-in-lockdown true
   :user-roles #{:anonymous}}
  [command]
  (if (usr/throttle-login? username)
    (do
      (info "login throttled, username:" username)
      (fail :error.login-trottle))
    (if-let [user (usr/get-user-with-password username password)]
      (do
        (info "login successful, username:" username)
        (usr/clear-logins username)
        (if-let [application-page (usr/applicationpage-for (:role user))]
          (ssess/merge-to-session
            command
            (ok :user (-> user usr/with-org-auth usr/non-private)
                :applicationpage application-page
                :lang (:language user))
            {:user (usr/session-summary user)})
          (do
            (error "Unknown user role:" (:role user))
            (fail :error.login))))
      (do
        (info "login failed, username:" username)
        (usr/login-failed username)
        (fail :error.login)))))

(defquery redirect-after-login
  {:user-roles roles/all-authenticated-user-roles}
  [{session :session}]
  (ok :url (get session :redirect-after-login "")))

(defcommand impersonate-authority
  {:parameters [organizationId role password]
   :user-roles #{:admin}
   :input-validators [(partial action/non-blank-parameters [:organizationId])
                      (fn [{data :data}] (when-not (#{"approver" "authorityAdmin" "archivist"} (:role data)) (fail :error.invalid-role)))]
   :description "Changes admin session into authority session with access to given organization"}
  [{user :user :as command}]
  (if (usr/get-user-with-password (:username user) password)
    (let [kw-role (keyword role)
          main-role (if (= :authorityAdmin kw-role) :authorityAdmin :authority)
          role-set (set [kw-role main-role])
          imposter (assoc user :impersonating true :role (name main-role) :orgAuthz {(keyword organizationId) role-set})]
      (ssess/merge-to-session command (ok) {:user imposter}))
    (fail :error.login)))

;;
;; ==============================================================================
;; Registering:
;; ==============================================================================
;;

(defn- register [user stamp rakentajafi]
  (activation/send-activation-mail-for user)
  (vetuma/consume-user stamp)
  (when rakentajafi
    (util/future* (idf/send-user-data user "rakentaja.fi")))
  user)

(defn- vetuma-data-to-user [vetuma-data {:keys [architect email] :as data}]
  (merge
    (set/rename-keys vetuma-data {:userid :personId})
    (select-keys data [:password :language :street :zip :city :phone :allowDirectMarketing])
    (when architect
      (select-keys data [:architect :degree :graduatingYear :fise :fiseKelpoisuus]))
    {:email (ss/canonize-email email) :role "applicant" :enabled false :personIdSource :identification-service}))

(defcommand register-user
  {:parameters       [stamp email password street zip city phone allowDirectMarketing rakentajafi]
   :optional-parameters [language]
   :user-roles       #{:anonymous}
   :input-validators [action/email-validator validate-registrable-user]}
  [{data :data}]
  (if-let [vetuma-data (vetuma/get-user stamp)]
    (try
      (infof "Registering new user: %s - details from vetuma: %s" (dissoc data :password) vetuma-data)
      (if-let [user (usr/create-new-user nil (vetuma-data-to-user vetuma-data data))]
        (ok :id (:id (register user stamp rakentajafi)))
        (fail :error.create-user))

      (catch IllegalArgumentException e
        (fail (keyword (.getMessage e)))))
    (fail :error.create-user)))

(defcommand confirm-account-link
  {:parameters [stamp tokenId email password street zip city phone]
   :user-roles #{:anonymous}
   :input-validators [(partial action/non-blank-parameters [:tokenId :password])
                      action/email-validator]}
  [{data :data}]
  (let [vetuma-data (vetuma/get-user stamp)
        email (ss/canonize-email email)
        token (token/get-token tokenId)]
    (when-not (and vetuma-data
                (= (:token-type token) :activate-linked-account)
                (= email (get-in token [:data :email])))
      (fail! :error.create-user))
    (try
      (infof "Confirm linked account: %s - details from vetuma: %s" (dissoc data :password) vetuma-data)
      (if-let [user (usr/create-new-user
                      nil
                      (merge data vetuma-data {:email email :role "applicant"
                                               :enabled true :personIdSource :identification-service})
                      :send-email false)]
        (do
          (vetuma/consume-user stamp)
          (token/get-token tokenId :consume true)
          (ok :id (:id user)))
        (fail :error.create-user))
      (catch IllegalArgumentException e
        (fail (keyword (.getMessage e)))))))

(defcommand retry-rakentajafi
  {:parameters [email]
   :user-roles #{:admin}
   :input-validators [(partial action/non-blank-parameters [:email])
                      action/email-validator]
   :description "Admin can retry sending data to rakentaja.fi, if account is not linked"}
  [_]
  (if-let [user (usr/get-user-by-email email)]
    (when-not (get-in user [:partnerApplications :rakentajafi])
      (if (idf/send-user-data user "rakentaja.fi")
        (ok)
        (fail :error.unknown)))
    (fail :error.user-not-found)))

;;
;; ==============================================================================
;; User attachments:
;; ==============================================================================
;;

(defquery user-attachments
  {:user-roles #{:applicant :authority :authorityAdmin :admin}}
  [{user :user}]
  (if-let [current-user (usr/get-user-by-id (:id user))]
    (ok :attachments (:attachments current-user))
    (fail :error.user-not-found)))

(defn- add-user-attachment-allowed? [user] (usr/applicant? user))

(defquery add-user-attachment-allowed
  {:description "Dummy command for UI logic: returns falsey if current user is not allowed to add \"user attachments\"."
   :pre-checks [(fn [command]
                  (when-not (add-user-attachment-allowed? (:user command))
                    unauthorized))]
   :user-roles #{:anonymous}})

(defpage [:post "/api/upload/user-attachment"] {[{:keys [tempfile filename size]}] :files attachmentType :attachmentType}
  (try+
    (let [user              (usr/current-user (request/ring-request))
          filename          (mime/sanitize-filename filename)
          attachment-type   (att-type/parse-attachment-type attachmentType)
          attachment-id     (mongo/create-id)
          content-type      (mime/mime-type filename)
          file-info         {:attachment-type  attachment-type
                             :attachment-id    attachment-id
                             :file-name        filename
                             :content-type     content-type
                             :size             size
                             :created          (now)}]

      (when-not (add-user-attachment-allowed? user) (throw+ {:status 401 :body "forbidden"}))

      (info "upload/user-attachment" (:username user) ":" attachment-type "/" filename size "id=" attachment-id)
      (when-not ((set att-type/osapuolet) (:type-id attachment-type)) (fail! :error.illegal-attachment-type))
      (when-not (mime/allowed-file? filename) (fail! :error.file-upload.illegal-file-type))

      (mongo/upload attachment-id filename content-type tempfile :user-id (:id user))
      (mongo/update-by-id :users (:id user) {$push {:attachments file-info}})
      (resp/json (assoc file-info :ok true)))
    (catch [:sade.core/type :sade.core/fail] {:keys [text] :as all}
      (resp/json (fail text)))
    (catch Exception e
      (error e "exception while uploading user attachment" (class e) (str e))
      (resp/json (fail :error.unknown)))))

(defraw download-user-attachment
  {:parameters [attachment-id]
   :input-validators [(partial action/non-blank-parameters [:attachment-id])]
   :user-roles #{:applicant}}
  [{user :user}]
  (when-not user (throw+ {:status 401 :body "forbidden"}))
  (if-let [attachment (mongo/download-find {:id attachment-id :metadata.user-id (:id user)})]
    {:status 200
     :body ((:content attachment))
     :headers {"Content-Type" (:contentType attachment)
               "Content-Length" (str (:size attachment))
               "Content-Disposition" (format "attachment;filename=\"%s\"" (ss/encode-filename (:filename attachment)))}}
    {:status 404
     :body (str "Attachment not found: id=" attachment-id)}))

(defcommand remove-user-attachment
  {:parameters [attachment-id]
   :input-validators [(partial action/non-blank-parameters [:attachment-id])]
   :user-roles #{:applicant}}
  [{user :user}]
  (info "Removing user attachment: attachment-id:" attachment-id)
  (mongo/update-by-id :users (:id user) {$pull {:attachments {:attachment-id attachment-id}}})
  (mongo/delete-file {:id attachment-id :metadata.user-id (:id user)})
  (ok))

(defn- allowed-state?
  "Check possible attachments against application state.
   pre-verdict states are all true, post-verdict states are checked by attachment's applicationState.
   Similar to attachment-editable-by-application-state pre-check"
  [app-state {create-state :applicationState}]
  (if (states/post-verdict-states (keyword app-state))
    (not (nil? (states/post-verdict-states (keyword create-state))))
    true))

(defn allowed-attachments-same-type
  "Return attachments of given type, that are allowed in current state and not flagged readOnly / locked."
  [application attachment-type]
  (->> (att/get-attachments-by-type application attachment-type)
       (filter (partial allowed-state? (:state application)))
       (remove #(or (att/attachment-is-readOnly? %) (att/attachment-is-locked? %)))))

(defn user-attachments-exists [{user :user}]
  (when (empty? (:attachments (mongo/by-id :users (:id user) {:attachments true})))
    (fail :error.no-user-attachments)))

(defcommand copy-user-attachments-to-application
  {:parameters [id]
   :user-roles #{:applicant}
   :user-authz-roles (conj roles/default-authz-writer-roles :foreman)
   :states     (states/all-application-states-but states/terminal-states)
   :pre-checks [(fn [command]
                  (when-not (-> command :user :architect)
                    unauthorized))
                user-attachments-exists]}
  [{application :application user :user :as command}]
  (doseq [attachment (:attachments (mongo/by-id :users (:id user) {:attachments true}))]
    (let [application-id id
          user-id (:id user)
          {:keys [attachment-type attachment-id file-name content-type size]} attachment
          attachment             (mongo/download-find {:id attachment-id :metadata.user-id user-id})
          maybe-attachment-id    (str application-id "." user-id "." attachment-id)               ; proposed attachment id (if empty placeholder is not found)
          same-attachments       (allowed-attachments-same-type application attachment-type)      ; attachments of same type
          old-user-attachment-id (some (hash-set maybe-attachment-id) (map :id same-attachments)) ; if id is already present, use it

          attachment-id (or old-user-attachment-id
                            (-> (remove :latestVersion same-attachments) first :id) ; upload user attachment to empty placeholder
                            maybe-attachment-id)]
      (when (zero? (mongo/count :applications {:_id application-id
                                               :attachments {$elemMatch {:id attachment-id ; skip upload when user attachment as already been uploaded
                                                                         :latestVersion.type attachment-type}}}))
        (att/upload-and-attach! command
                                {:attachment-id attachment-id
                                 :attachment-type attachment-type
                                 :group {:groupType (get-in (att-type/attachment-type attachment-type) [:metadata :grouping])}
                                 :created (now)
                                 :required false
                                 :locked false}
                                {:content ((:content attachment))
                                 :filename file-name
                                 :content-type content-type
                                 :size size}))))
  (ok))

(defquery email-in-use
  {:parameters       [email]
   :input-validators [email-validator]
   :user-roles       #{:anonymous}}
  [_]
  (if (usr/email-in-use? email)
    (ok)
    (fail :email-not-in-use)))

(defcommand remove-user-notification
  {:user-roles #{:applicant :authority}}
  [{{id :id} :user}]
  (mongo/update-by-id :users id {$unset {:notification 1}}))

(defquery enable-foreman-search
  {:user-roles #{:authority}
   :pre-checks [(fn [{:keys [user]}]
                  (let [org-ids (->> (usr/organization-ids user)
                                     (filter
                                       (fn [oid]
                                         (some roles/reader-org-authz-roles (get-in user [:orgAuthz (keyword oid)]))) ))]
                    (when-not (pos? (mongo/count :organizations {:_id {$in org-ids} :scope.permitType permit/R }))
                      unauthorized)))]}
  [_])

(defquery calendars-enabled
  {:user-roles #{:authority :authorityAdmin :applicant}
   :pre-checks [(fn [_]
                  (when-not (and (env/value :ajanvaraus :host) (env/value :ajanvaraus :username) (env/value :ajanvaraus :password))
                    unauthorized))
                (partial cal/calendars-enabled-api-pre-check #{:authority :authorityAdmin :applicant})]
   :feature    :ajanvaraus}
  [_])
