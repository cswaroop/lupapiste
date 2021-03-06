(ns lupapalvelu.backing-system.krysp.krysp-http-itest
  (:require [midje.sweet :refer :all]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.fixture.core :as fixture]
            [sade.core :refer [now]]
            [sade.env :as env]
            [sade.strings :as ss]
            [sade.shared-util :as util]))

(def jms-test-db (str "test_krysp_http_jms" (now)))

(defn get-integration-messages [app-id]
  (->> (mongo/select :integration-messages {:application.id app-id})
       (remove #(= "KuntaGML hakemus-path" (:messageType %))))) ; remove messages logged by /dev/krysp dummy endpoint

(when (env/feature? :jms)
  (mongo/connect!)
  (mongo/with-db jms-test-db
    (fixture/apply-fixture "minimal")
    (against-background [(sade.http/post (str (env/server-address) "/dev/krysp/receiver/hakemus-path") anything) => nil]
      (facts "Sending KuntaGML via JMS and HTTP"            ; Tampere is configured to use HTTP krysp in minimal
      (let [{application-id :id} (create-local-app pena
                                                   :x 329072
                                                   :y 6823200
                                                   :propertyId "83712103620001"
                                                   :address "Pub Harald")
            application (query-application local-query pena application-id)]
        (generate-documents application pena true)
        (local-command pena :submit-application :id application-id) => ok?
        (let [resp (local-command veikko :approve-application :id application-id :lang "fi")]
          (fact "Veikko moves to backing system via HTTP"
            resp => ok?
            (:integrationAvailable resp) => true))
        (Thread/sleep 100)
        (loop [retries 5
               msgs (get-integration-messages application-id)]
          (let [sent-message (util/find-first (fn [msg] (= (:messageType msg) "KuntaGML application")) msgs)]
            (if-not (or (zero? retries) (= (:status sent-message) "done"))
              (do
                (Thread/sleep 1000)
                (recur (dec retries) (get-integration-messages application-id)))
              (facts "integration-messages"
                (count msgs) => 3                               ; 2x state-change 1x KuntaGML
                (fact "message is delivered via queue"
                  (:messageType sent-message) => "KuntaGML application"
                  (:direction sent-message) => "out"
                  (fact "is processed by consumer"
                    (:status sent-message) => "done")))))))))))


(apply-remote-minimal)
(def original-jms-feature-flag (get-in (query pena :features) [:features :jms] false))
(facts "Sending KuntaGML via HTTP"                    ; Tampere is configured to use HTTP krysp in minimal
  (when (true? original-jms-feature-flag)
    (fact "Disable JMS for this test"
      (command pena :set-feature :feature "jms" :value false) => ok?))
  (let [application-id (create-app-id pena
                                      :x 329072
                                      :y 6823200
                                      :propertyId "83712103620001"
                                      :address "Pub Harald")
        application (query-application pena application-id)
        ts (now)]
    (generate-documents application pena)
    (command pena :submit-application :id application-id) => ok?

    (let [resp (command veikko :approve-application :id application-id :lang "fi")]
      (fact "Veikko moves to backing system via HTTP"
        resp => ok?
        (:integrationAvailable resp) => true))

    (let [msgs (filter #(ss/contains? (:messageType %) "KuntaGML") (integration-messages application-id :test-db-name test-db-name))
          sent-message (first msgs)
          received-message (second msgs)]
      (facts "integration-messages"
        (count msgs) => 2
        (fact "sent message is saved"
          (:messageType sent-message) => "KuntaGML application"
          (:direction sent-message) => "out"
          (fact "after success, message is acknowledged"
            (> (or (:acknowledged sent-message) 0) ts) => true
            (:status sent-message) => "done"))
        (fact "received message is saved (dummy receiver)"
          ; 'hakemus-path' below is deduced in web.clj as :path, which is actually defined in organization in minimal fixture
          (:messageType received-message) => "KuntaGML hakemus-path"
          (:direction received-message) => "in"
          (ss/starts-with (:data received-message) "<?xml") => truthy
          (ss/contains? (:data received-message) "Pub Harald") => true)))

    (fact "Sending fails without proper http endpoint"
      (command veikko :request-for-complement :id application-id) => ok?
      (command admin :set-kuntagml-http-endpoint :url "http://invalid" :organization "837-R" :permitType "R") => ok?
      (command veikko :approve-application :id application-id :lang "fi") => fail?
      (command admin :set-kuntagml-http-endpoint :partner "matti"
               :url (str (server-address) "/dev/krysp/receiver") :organization "837-R" :permitType "R"
               :username "kuntagml" :password "invalid") => ok?
      (command veikko :approve-application :id application-id :lang "fi") => fail?)
    (fact "updating correct creds makes integration work again"
      (command admin :set-kuntagml-http-endpoint
               :url (str (server-address) "/dev/krysp/receiver") :organization "837-R" :permitType "R"
               :username "kuntagml" :password "kryspi" :partner "matti") => ok?
      (command veikko :approve-application :id application-id :lang "fi") => ok?))
  (when (true? original-jms-feature-flag)
    (fact "Restore JMS feature"
      (command pena :set-feature :feature "jms" :value original-jms-feature-flag) => ok?)))
