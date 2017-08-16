(ns lupapalvelu.authorization
  (:require [clojure.set :refer [union]]
            [lupapalvelu.user :as usr]
            [lupapalvelu.roles :refer :all]
            [lupapalvelu.application-schema :as aps]
            [lupapalvelu.document.tools :as doc-tools]
            [schema.core :refer [defschema] :as sc]
            [sade.schemas :as ssc]
            [sade.strings :as ss]
            [sade.util :as util]))



;;
;; Schema
;;

(defschema Invite
  {(sc/optional-key :role)           (apply sc/enum all-authz-roles)
   (sc/optional-key :path)           sc/Str
   :email                            ssc/Email
   (sc/optional-key :application)    aps/ApplicationId ; FIXME delete key after prod is migrated
   :created                          ssc/Timestamp
   :inviter                          usr/SummaryUser
   (sc/optional-key :documentName)   sc/Str
   (sc/optional-key :documentId)     ssc/ObjectIdStr
   :user                             usr/SummaryUser
   (sc/optional-key :title)          sc/Str
   (sc/optional-key :text)           sc/Str})

(defschema CompanyInvite
  {:user {:id usr/Id}})

(defschema Auth
  {:id                               usr/Id
   :username                         ssc/Username
   :firstName                        sc/Str
   :lastName                         sc/Str
   :role                             (apply sc/enum all-authz-roles)
   (sc/optional-key :type)           (sc/enum :company :owner)
   (sc/optional-key :name)           sc/Str
   (sc/optional-key :y)              ssc/FinnishY
   (sc/optional-key :unsubscribed)   sc/Bool
   (sc/optional-key :statementId)    ssc/ObjectIdStr
   (sc/optional-key :invite)         (sc/if :email Invite CompanyInvite)
   (sc/optional-key :inviteAccepted) ssc/Timestamp
   (sc/optional-key :inviter)        (sc/if map? usr/SummaryUser usr/Id)})

;;
;; Auth utils
;;

(defn get-auths-by-roles
  "Returns sequence of all auth-entries in an application with the
  given roles. Each role can be keyword or string."
  [{auth :auth} roles]
  (let [role-set (->> roles (map name) set)]
    ;; Roles in auths can also be keywords or strings.
    ;; (name nil) causes NPE so default value is needed.
    (filter #(contains? role-set (name (get % :role ""))) auth)))

(defn get-auths-by-role
  [application role]
  (get-auths-by-roles application [role]))

(defn get-auths [{auth :auth} user-id]
  (filter #(= (:id %) user-id) auth))

(defn get-auth [application user-id]
  (first (get-auths application user-id)))

(defn has-auth? [{auth :auth} user-id]
  (or (some (partial = user-id) (map :id auth)) false))

(defn has-auth-role? [{auth :auth} user-id role]
  (has-auth? {:auth (get-auths-by-role {:auth auth} role)} user-id))

(defn has-some-auth-role? [{auth :auth} user-id roles]
  (has-auth? {:auth (get-auths-by-roles {:auth auth} roles)} user-id))

(defn auth-via-company [{auth :auth} user-id]
  (if-let [company (get (usr/get-user-by-id user-id) :company)]
    (let [company-auth (util/find-by-id (:id company) auth)]
      (when (some #{(:role company-auth)} #{"writer" "owner"})
        company-auth))))

(defn has-auth-via-company? [application user-id]
  (or (auth-via-company application user-id) false))

(defn create-invite-auth [inviter invited application-id role timestamp & [text document-name document-id path]]
  {:pre [(seq inviter) (seq invited) application-id role timestamp]}
  (let [invite (cond-> {:created      timestamp
                        :email        (:email invited)
                        :role         role
                        :user         (usr/summary invited)
                        :inviter      (usr/summary inviter)}
                 (not (nil? path))               (assoc :path path)
                 (not (ss/blank? text))          (assoc :text text)
                 (not (ss/blank? document-name)) (assoc :documentName document-name)
                 (not (ss/blank? document-id))   (assoc :documentId document-id))]
    (assoc (usr/user-in-role invited :reader) :invite invite)))

;;
;; Authz checkers
;;

(defn user-authz? [roles application user]
  {:pre [(set? roles)]}
  (let [roles-in-app  (map (comp keyword :role) (get-auths application (:id user)))]
    (some roles roles-in-app)))

(defn org-authz
  "Returns user's org authz in given organization, nil if not found"
  [organization-id user]
  (get-in user [:orgAuthz (keyword organization-id)]))

(defn has-organization-authz-roles?
  "Returns true if user has requested roles in organization"
  [requested-authz-roles organization-id user]
  (and (or (usr/authority? user) (usr/authority-admin? user))
       requested-authz-roles
       (some requested-authz-roles (org-authz organization-id user))))

(defn application-authority?
  "Returns true if the user is an authority in the organization that processes the application"
  [application user]
  (boolean (has-organization-authz-roles? #{:authority :approver} (:organization application) user)))

(defn application-role [application user]
  (if (application-authority? application user) :authority :applicant))

;;
;; Enrich auth array
;;

(defn party-document? [doc]
  (= :party (doc-tools/doc-type doc)))

(defn- enrich-auth-info-with-parties [sorted-parties-docs auth-info]
  (->> (filter (comp #{(:id auth-info)} doc-tools/party-doc-user-id) sorted-parties-docs)
       (map doc-tools/party-doc->user-role)
       (assoc auth-info :party-roles)))

(defn- enrich-authority-auth-info [authority-id auth-info]
  (if (and authority-id (= (:id auth-info) authority-id))
    (update auth-info :other-roles conj :authority)
    auth-info))

(defn enrich-auth-information [{auth :auth docs :documents {authority-id :id} :authority}]
  (let [parties-docs (->> (filter party-document? docs)
                          (sort-by (comp :order :schema-info)))]
    (->> auth
         (map (partial enrich-auth-info-with-parties parties-docs))
         (map (partial enrich-authority-auth-info authority-id)))))
