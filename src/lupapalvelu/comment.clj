(ns lupapalvelu.comment
  (:require [monger.operators :refer :all]
            [clojure.set :refer [rename-keys]]
            [sade.strings :as ss]
            [sade.util :as util]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.comment-html :as comment-html]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.user :as usr]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.pdf.html-template-common :as common]
            [lupapalvelu.pdf.html-muuntaja-client :as muuntaja]))

(defn- enrich-attachment-comment [attachments {{target-type :type target-id :id :as target} :target :as comment}]
  (if (and (= (keyword target-type) :attachment) target-id)
    (assoc comment :target (-> (util/find-by-id target-id attachments)
                               (select-keys [:id :type])
                               (rename-keys {:id :attachmentId :type :attachmentType})
                               (merge target)))
    comment))

(defn- enrich-user-information [auth {user :user :as comment}]
  (let [user-auths  (filter (comp #{(:id user)} :id) auth)
        party-roles (:party-roles (first user-auths))
        other-roles (:other-roles (first user-auths))
        auth-roles  (map (comp keyword :role) user-auths)]
    (->> (or (some (set other-roles) [:authority])
             (first party-roles)
             (some (set auth-roles) [:foreman :statementGiver])
             (when (not-empty auth-roles) :other-auth)
             (when (usr/authority? user)  :authority)
             :other-auth)
         (assoc-in comment [:user :application-role]))))

(defn enrich-comments [{comments :comments attachments :attachments :as application}]
  (->> comments
       (map (partial enrich-attachment-comment attachments))
       (map (partial enrich-user-information (auth/enrich-auth-information application)))))

(defn comment-mongo-update
  ([current-app-state text target type mark-answered user to-user timestamp]
    (comment-mongo-update current-app-state text target type mark-answered user to-user timestamp [:applicant :authority]))
  ([current-app-state text target type mark-answered user to-user timestamp roles]
    {:pre [current-app-state (not (nil? mark-answered))]}

    (let [answered? (and mark-answered (usr/authority? user))]
      (util/deep-merge
        {$set  {:modified timestamp}}

        (when-not (and answered? (ss/blank? text))
          {$push {:comments (domain/->comment text target type user to-user timestamp roles)}})

        (case (keyword current-app-state)
          ;; LUPA-371, LUPA-745
          :info (when answered? {$set {:state :answered}})

          ;; LUPA-371
          :answered (when (usr/applicant? user) {$set {:state :info}})

          nil)))))

(defn get-comments-as-pdf [lang application]
  (let [org (organization/get-organization (:organization application))
        {body :body} (comment-html/comment-html lang org application)
        header (common/apply-page common/basic-header)
        footer (common/apply-page common/basic-application-footer application)]
    (muuntaja/convert-html-to-pdf (:id application)
                                  "conversation-pdf"
                                  body
                                  header
                                  footer)))
