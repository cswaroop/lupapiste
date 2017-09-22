(ns lupapalvelu.xml.asianhallinta.verdict
  (:require [sade.core :refer [ok fail fail! error-and-fail!] :as core]
            [sade.common-reader :as cr]
            [sade.util :as util]
            [sade.xml :as xml]
            [taoensso.timbre :refer [error]]
            [monger.operators :refer :all]
            [lupapalvelu.application-state :as app-state]
            [lupapalvelu.xml.asianhallinta.attachment :as ah-att]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.organization :as org]
            [lupapalvelu.action :as action]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.state-machine :as sm]))


(defn- build-verdict [{:keys [AsianPaatos]} timestamp]
  {:id              (mongo/create-id)
   :kuntalupatunnus (:AsianTunnus AsianPaatos)
   :timestamp timestamp
   :source    "ah"
   :paatokset [{:paatostunnus (:PaatoksenTunnus AsianPaatos)
                :paivamaarat  {:anto (cr/to-timestamp (:PaatoksenPvm AsianPaatos))}
                :poytakirjat  [{:paatoksentekija (:PaatoksenTekija AsianPaatos)
                                :paatospvm       (cr/to-timestamp (:PaatoksenPvm AsianPaatos))
                                :pykala          (:Pykala AsianPaatos)
                                :paatoskoodi     (or (:PaatosKoodi AsianPaatos) (:PaatoksenTunnus AsianPaatos)) ; PaatosKoodi is not required
                                :id              (mongo/create-id)}]}]})


(defn- check-ftp-user-has-right-to-modify-app! [ftp-user {application-id :id municipality :municipality permit-type :permitType}]
  (when-not (-> (org/resolve-organization-scope municipality permit-type)
                (get-in [:caseManagement :ftpUser])
                (= ftp-user))
    (error-and-fail!
     (str "FTP user " ftp-user " is not allowed to make changes to application " application-id)
     :error.integration.asianhallinta.unauthorized)))

(defn- check-application-is-in-correct-state! [{application-id :id current-state :state :as application}]
  (when-not (#{:constructionStarted :sent (sm/verdict-given-state application)} (keyword current-state))
    (error-and-fail!
     (str "Application " application-id " in wrong state (" current-state ") for asianhallinta verdict")
     :error.integration.asianhallinta.wrong-state)))


(defn process-ah-verdict [parsed-xml unzipped-path ftp-user system-user timestamp]
  (let [xml-edn   (xml/xml->edn parsed-xml)
        application-id (get-in xml-edn [:AsianPaatos :HakemusTunnus])
        attachments (-> (get-in xml-edn [:AsianPaatos :Liitteet])
                        (util/ensure-sequential :Liite)
                        :Liite)]
    (when-not application-id
      (error-and-fail!
        (str "ah-verdict - Application id is nil for user " ftp-user)
        :error.integration.asianhallinta.no-application-id))
    (let [application (domain/get-application-no-access-checking application-id)
          verdict-given-state (sm/verdict-given-state application)]

      (check-ftp-user-has-right-to-modify-app! ftp-user application)
      (check-application-is-in-correct-state! application)

      ;; -> build update clause
      ;; -> update-application
      (let [new-verdict   (build-verdict xml-edn timestamp)
            command       (assoc (action/application->command application) :action :process-ah-verdict)
            poytakirja-id (get-in new-verdict [:paatokset 0 :poytakirjat 0 :id])
            update-clause (util/deep-merge
                            {$push {:verdicts new-verdict}, $set  {:modified timestamp}}
                            (when (= :sent (keyword (:state application)))
                              (app-state/state-transition-update verdict-given-state timestamp application system-user)))]

        (action/update-application command update-clause)
        (doseq [attachment attachments]
          (ah-att/insert-attachment!
            unzipped-path
            application
            attachment
            {:type-group "muut" :type-id "paatos"}
            {:type "verdict" :id (:id new-verdict) :poytakirjaId poytakirja-id}
            (:Kuvaus attachment)
            timestamp
            system-user))
        (notifications/notify! :application-state-change command)
        (ok)))))

