(ns lupapalvelu.backing-system.allu-test
  "Unit tests for lupapalvelu.backing-system.allu. No side-effects."
  (:require [cheshire.core :as json]
            [schema.core :as sc :refer [defschema Bool]]
            [sade.core :refer [def-]]
            [sade.env :as env]
            [sade.schemas :refer [NonBlankStr Kiinteistotunnus ApplicationId]]
            [sade.schema-generators :as sg]
            [sade.municipality :refer [municipality-codes]]
            [lupapalvelu.attachment :refer [Attachment]]
            [lupapalvelu.document.data-schema :as dds]
            [lupapalvelu.document.canonical-common :refer [ya-operation-type-to-schema-name-key]]
            [lupapalvelu.i18n :refer [localize]]
            [lupapalvelu.integrations.geojson-2008-schemas :as geo]
            [lupapalvelu.organization :refer [PermitType]]

            [midje.sweet :refer [facts fact => contains]]
            [midje.util :refer [testable-privates]]
            [clojure.test.check :refer [quick-check]]
            [com.gfredericks.test.chuck.properties :refer [for-all]]
            [com.gfredericks.test.chuck.generators :refer [string-from-regex]]
            [lupapalvelu.test-util :refer [passing-quick-check catch-all]]

            [lupapalvelu.backing-system.allu :as allu :refer [PlacementContract]]))

(testable-privates lupapalvelu.backing-system.allu application->allu-placement-contract
                   application-cancel-request placement-creation-request placement-update-request attachment-send)

;;; TODO: Instead of testing internals, just use mocks.

;;;; Refutation Utilities
;;;; ===================================================================================================================

(defschema ValidPlacementApplication
  {:id               ApplicationId
   :permitSubtype    NonBlankStr
   :organization     NonBlankStr
   :propertyId       Kiinteistotunnus
   :municipality     (apply sc/enum municipality-codes)
   :address          NonBlankStr
   :primaryOperation {:name allu/SijoituslupaOperation}
   :documents        [(sc/one (dds/doc-data-schema "hakija-ya" true) "applicant")
                      (sc/one (dds/doc-data-schema "yleiset-alueet-hankkeen-kuvaus-sijoituslupa" true) "description")
                      (sc/one (dds/doc-data-schema "yleiset-alueet-maksaja" true) "payee")]
   :location-wgs84   [(sc/one sc/Num "longitude") (sc/one sc/Num "latitude")]
   :drawings         [{:geometry-wgs84 geo/SingleGeometry}]})

(def- organizations (string-from-regex #"\d{3}-(R|YA|YMP)"))

(def- invalid-placement-application? (comp not nil? (partial sc/check ValidPlacementApplication)))

;;;; Actual Tests
;;;; ==================================================================================================================

(env/with-feature-value :allu true
  (facts "allu-application?"
    (fact "Use ALLU integration for Helsinki YA."
      (allu/allu-application? {:id "091-YA"} "YA") => true)

    (fact "Do not use ALLU integration for anything else."
      (quick-check 10
                   (for-all [org-id organizations
                             permit-type (sg/generator PermitType)
                             :when (not (and (= org-id "091-YA") (= permit-type "YA")))]
                     (not (allu/allu-application? {:id org-id} permit-type))))
      => passing-quick-check))

  (facts "application->allu-placement-contract"
    (fact "Valid applications produce valid inputs for ALLU."
      (quick-check 10
                   (for-all [application (sg/generator ValidPlacementApplication)]
                     (nil? (sc/check PlacementContract
                                     (application->allu-placement-contract (sg/generate Bool) application)))))
      => passing-quick-check))

  (facts "application-cancel-request"
    (let [allu-id "23"
          app (assoc-in (sg/generate ValidPlacementApplication) [:integrationKeys :ALLU :id] allu-id)
          [endpoint request] (application-cancel-request "https://example.com/api/v1" "foo.bar.baz" app)]
      (fact "endpoint" endpoint => (str "https://example.com/api/v1/applications/" allu-id "/cancelled"))
      (fact "request" request => {:headers {:authorization "Bearer foo.bar.baz"}})))

  (facts "placement-creation-request"
    (let [app (sg/generate ValidPlacementApplication)
          [endpoint request] (placement-creation-request "https://example.com/api/v1" "foo.bar.baz" app)]
      (fact "endpoint" endpoint => "https://example.com/api/v1/placementcontracts")
      (fact "request" request => {:headers      {:authorization "Bearer foo.bar.baz"}
                                  :content-type :json
                                  :form-params  (application->allu-placement-contract true app)})))

  (facts "attachment-send"
    (let [allu-id "23"
          application (-> (sg/generate ValidPlacementApplication) (assoc-in [:integrationKeys :ALLU :id] allu-id))
          {{:keys [type-group type-id]} :type :keys [latestVersion] :as attachment} (sg/generate Attachment)
          contents "You will be assimilated."
          [endpoint request] (attachment-send "https://example.com/api/v1" "foo.bar.baz" application attachment
                                              contents)]
      (fact "endpoint" endpoint => (str "https://example.com/api/v1/applications/" allu-id "/attachments"))
      (fact "request"
        (:headers request) => {:authorization "Bearer foo.bar.baz"}
        (-> request (get-in [:multipart 0]) (select-keys [:name :mime-type :encoding]))
        => {:name "metadata", :mime-type "application/json", :encoding "UTF-8"}
        (-> request (get-in [:multipart 0 :content]) (json/decode true))
        => {:name        (or (:contents attachment) "")
            :description (localize "fi" :attachmentType type-group type-id)
            :mimeType    (:contentType latestVersion)}
        (get-in request [:multipart 1]) => {:name      "file"
                                            :mime-type (:contentType latestVersion)
                                            :content   contents})))

  (facts "placement-update-request"
    (let [allu-id "23"
          app (assoc-in (sg/generate ValidPlacementApplication) [:integrationKeys :ALLU :id] allu-id)]
      (doseq [pending-on-client [true false]
              :let [[endpoint request]
                    (placement-update-request pending-on-client "https://example.com/api/v1" "foo.bar.baz" app)]]
        (fact "endpoint" endpoint => (str "https://example.com/api/v1/placementcontracts/" allu-id))
        (fact "request"
          request => {:headers      {:authorization "Bearer foo.bar.baz"}
                      :content-type :json
                      :form-params  (application->allu-placement-contract pending-on-client app)})))))