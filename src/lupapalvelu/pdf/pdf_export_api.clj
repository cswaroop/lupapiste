(ns lupapalvelu.pdf.pdf-export-api
  (:require [taoensso.timbre :as timbre :refer [info]]
            [ring.util.response :as response]
            [sade.files :as files]
            [lupapalvelu.action :as action :refer [defraw]]
            [lupapalvelu.application-bulletins-api :as bulletins-api]
            [lupapalvelu.application-bulletins :as bulletins]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.pdf.libreoffice-conversion-client :as libre]
            [lupapalvelu.pdf.pdf-export :as pdf-export]
            [lupapalvelu.roles :as roles]
            [lupapalvelu.states :as states]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.application-utils :as app-utils]))

(defn ok [body]
  {:status  200
   :headers {"Content-Type" "application/pdf"}
   :body    body})

(def not-found
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body "404"})

(defraw pdf-export
  {:parameters       [:id]
   :user-roles       #{:applicant :authority :oirAuthority}
   :user-authz-roles roles/all-authz-roles
   :org-authz-roles  roles/all-org-authz-roles
   :states           states/all-states
   :pre-checks       [permit/is-not-archiving-project]}
  [{:keys [user application lang]}]
  (if application
    (ok (-> application (app-utils/with-masked-person-ids user) (pdf-export/generate lang)))
    not-found))

(defraw submitted-application-pdf-export
  {:parameters       [:id]
   :user-roles       #{:applicant :authority :oirAuthority}
   :user-authz-roles roles/all-authz-roles
   :org-authz-roles  roles/all-org-authz-roles
   :states           states/all-states}
  [{:keys [user application lang]}]
  (if-let [submitted-application (mongo/by-id :submitted-applications (:id application))]
    (ok (-> submitted-application (app-utils/with-masked-person-ids user) (pdf-export/generate lang)))
    not-found))

(defraw bulletin-pdf-export
  {:parameters [bulletinId]
   :user-roles #{:anonymous}
   :input-validators [bulletins-api/bulletin-exists]
   :states     states/all-states}
  [{:keys [lang user]}]
  (if-let [bulletin (bulletins/get-bulletin bulletinId {})]
    (ok (-> (last (:versions bulletin)) (app-utils/with-masked-person-ids user) (pdf-export/generate lang)))
    not-found))

(defraw pdfa-casefile
  {:parameters       [:id]
   :user-roles       #{:applicant :authority :oirAuthority}
   :user-authz-roles roles/all-authz-roles
   :org-authz-roles  roles/all-org-authz-roles
   :input-validators [(partial action/non-blank-parameters [:lang])]
   :states           states/all-states}
  [{:keys [user application lang]}]
  (if application
    (files/with-temp-file libre-file
      (-> (libre/generate-casefile-pdfa application lang libre-file)
          response/response
          (response/content-type "application/pdf")))
    not-found))
