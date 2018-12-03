(ns lupapalvelu.price-catalogues-itest
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.format :as timeformat]
            [lupapalvelu.fixture.core :as fixture]
            [lupapalvelu.fixture.minimal :as minimal]
            [lupapalvelu.integrations-api]
            [lupapalvelu.invoice-api]
            [lupapalvelu.itest-util :refer [local-command local-query
                                            create-and-submit-application
                                            create-and-submit-local-application
                                            sonja pena sipoo sipoo-ya
                                            ok? fail?] :as itu]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.price-catalogues :as catalogues]
            [lupapalvelu.time-util :as tu]
            [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [sade.env :as env]
            [sade.util :refer [to-millis-from-local-date-string to-finnish-date]]
            [schema.core :as sc]
            [taoensso.timbre :refer [trace tracef debug info infof warn warnf error errorf fatal spy]]))

(def timestamp to-millis-from-local-date-string)

(def time-format (timeformat/formatter "dd.MM.YYYY"))

(defn today []
  (timeformat/unparse-local-date time-format (t/today)))

(defn yesterday []
  (timeformat/unparse-local-date time-format (t/plus (t/today) (t/days -1))))

(defn catalogues-belong-to-org?
  [org-id catalogues]
  (->> catalogues
       (map :organization-id)
       (every? (fn [catalogue-org-id] (= org-id catalogue-org-id)))))

(defn belong-to-org? [org-id]
  (partial catalogues-belong-to-org? org-id))

(defn ensure-exists! [collection {:keys [id] :as doc}]
  (cond
    (not id) :fail
    (mongo/by-id collection id) :ok
    :else (do
            (mongo/insert collection doc)
            (if (mongo/by-id collection id)
              :ok
              :fail))))

(def dummy-user {:id                                        "penan-id"
                 :firstName                                 "pena"
                 :lastName                                  "panaani"
                 :role                                      "authority"
                 :email                                     "pena@panaani.fi"
                 :username                                  "pena"})


(defn catalogue-request-with [properties]
  (merge {:valid-from-str "1.1.2019"
          :rows [{:code "12345"
                  :text "Taksarivi 1"
                  :unit "kpl"
                  :price-per-unit 23
                  :discount-percent nil
                  :min-total-price nil
                  :max-total-price nil
                  :operations ["toimenpide1" "toimenpide2"]}]}
         properties))

(defn catalogue-with [properties]
  (merge {:id "foo-id"
          :organization-id "753-R"
          :state "draft"
          :valid-from (timestamp "1.1.2000")
          :valid-until nil
          :rows [{:code "12345"
                  :text "Taksarivi 1"
                  :unit "kpl"
                  :price-per-unit 23
                  :min-total-price nil
                  :max-total-price nil
                  :discount-percent 50
                  :operations ["toimenpide1" "toimenpide2"]}]
          :meta {:created (timestamp "01.12.2019")
                 :created-by dummy-user}}
         properties))

(env/with-feature-value :invoices true
  (mongo/connect!)

  (mongo/with-db itu/test-db-name
    (lupapalvelu.fixture.core/apply-fixture "minimal")

    (defn dummy-submitted-application []
      (create-and-submit-local-application
       pena
       :operation "pientalo"
       :x "385770.46" :y "6672188.964"
       :address "Kaivokatu 1"))

    (fact "organization-price-catalogues"

          (fact "should return unauthorized response when user is not an organization admin"
                (let [response (-> (local-query sonja :organization-price-catalogues
                                                :organization-id "753-R"))]
                  response => fail?
                  (:text response) => "error.unauthorized"))

          (fact "should return unauthorized response when user is an or organization admin or another org"
                (let [response (-> (local-query sipoo-ya :organization-price-catalogues
                                                :organization-id "753-R"))]
                  response => fail?
                  (:text response) => "error.unauthorized"))

          (fact "should return empty collection when no price catalogues found for org-id"
                (let [response (-> (local-query sipoo :organization-price-catalogues
                                                :organization-id "753-R"))]
                  (:price-catalogues response) => []))

          (fact "should return one price catalogue when one inserted in db for the org-id"
                (let [test-price-catalogue (catalogue-with {:organization-id "753-R"
                                                            :valid-from (timestamp "1.1.1980")} )]
                  (ensure-exists! "price-catalogues" test-price-catalogue) => :ok
                  (let [response (local-query sipoo :organization-price-catalogues
                                              :organization-id "753-R")]
                    response => ok?
                    (:price-catalogues response) => (belong-to-org? "753-R")))))

    (fact "publish-price-catalogue command"

          (let [catalogue-request {:valid-from-str "1.1.2030"
                                   :rows [{:code "12345"
                                           :text "Taksarivi 1"
                                           :unit "kpl"
                                           :min-total-price 1
                                           :max-total-price 100
                                           :price-per-unit 23
                                           :discount-percent 50
                                           :operations ["toimenpide1" "toimenpide2"]}]}]




            (fact "should return unauthorized response when user is not an organization admin"
                  (let [response (-> (local-command sonja :publish-price-catalogue
                                                    :organization-id "753-R"
                                                    :price-catalogue catalogue-request))]
                    response => fail?
                    (:text response) => "error.unauthorized"))

            (fact "should return unauthorized response when user is an or organization admin or another org"
                  (let [response (-> (local-command sipoo-ya :publish-price-catalogue
                                                    :organization-id "753-R"
                                                    :price-catalogue catalogue-request))]
                    response => fail?
                    (:text response) => "error.unauthorized"))

            (fact "should return invalid-price-catalogue response when request data in not valid"
                  (let [response (-> (local-command sipoo :publish-price-catalogue
                                                    :organization-id "753-R"
                                                    :price-catalogue {:announcement "I'm not valid price catalogue request data" }))]
                    response => fail?
                    (:text response) => "error.invalid-price-catalogue"))

            (fact "should return invalid response when valid-from-str is today"
                  (let [response (-> (local-command sipoo :publish-price-catalogue
                                                    :organization-id "753-R"
                                                    :price-catalogue (assoc catalogue-request :valid-from-str (today))))]
                    response => fail?
                    (:text response) => "error.price-catalogue.incorrect-date"))

            (fact "should return invalide response when valid-from-str is yesterday"
                  (let [response (-> (local-command sipoo :publish-price-catalogue
                                                    :organization-id "753-R"
                                                    :price-catalogue (assoc catalogue-request :valid-from-str (yesterday))))]
                    response => fail?
                    (:text response) => "error.price-catalogue.incorrect-date"))


            (fact "should save the price catalogue to db and return an ok response when"

                  (fact "all fields have values"
                        (let [response (local-command sipoo :publish-price-catalogue
                                                      :organization-id "753-R"
                                                      :price-catalogue catalogue-request)]
                          response => ok?

                          (let [{:keys [valid-from rows] :as new-catalogue} (mongo/by-id "price-catalogues" (:price-catalogue-id response))]
                            (sc/validate catalogues/PriceCatalogue new-catalogue)
                            (to-finnish-date valid-from) => (:valid-from-str catalogue-request)
                            rows => (:rows catalogue-request))))

                  (fact "optional fields have nil value"
                        (let [catalogue-request {:valid-from-str "1.1.2019"
                                                 :rows [{:code "12345"
                                                         :text "Taksarivi 1"
                                                         :unit "kpl"
                                                         :price-per-unit 23
                                                         :discount-percent nil
                                                         :min-total-price nil
                                                         :max-total-price nil
                                                         :operations ["toimenpide1" "toimenpide2"]}]}
                              response (local-command sipoo :publish-price-catalogue
                                                      :organization-id "753-R"
                                                      :price-catalogue catalogue-request)]
                          response => ok?
                          (let [new-catalogue (mongo/by-id "price-catalogues" (:price-catalogue-id response))]
                            (sc/validate catalogues/PriceCatalogue new-catalogue)

                            (to-finnish-date (:valid-from new-catalogue)) => (:valid-from-str catalogue-request)
                            (:rows new-catalogue) => (:rows catalogue-request))))

                  (fact "operations is empty"
                        (let [catalogue-request {:valid-from-str "1.1.2019"
                                                 :rows [{:code "12345"
                                                         :text "Taksarivi 1"
                                                         :unit "kpl"
                                                         :price-per-unit 23
                                                         :discount-percent 50
                                                         :min-total-price 10
                                                         :max-total-price 100
                                                         :operations []}]}
                              response (local-command sipoo :publish-price-catalogue
                                                      :organization-id "753-R"
                                                      :price-catalogue catalogue-request)]
                          response => ok?

                          (let [new-catalogue (mongo/by-id "price-catalogues" (:price-catalogue-id response))]
                            (sc/validate catalogues/PriceCatalogue new-catalogue)
                            (to-finnish-date (:valid-from new-catalogue)) => (:valid-from-str catalogue-request)
                            (:rows new-catalogue) => (:rows catalogue-request)))))

            (fact  "should set the valid-until field for the previous price catalogue as the day before new catalogues valid-from"

                   (let [previous-catalogue (catalogue-with {:id "previous-1"
                                                             :valid-from (timestamp "1.2.2020")
                                                             :valid-until nil})
                         new-catalogue-req (catalogue-request-with {:valid-from-str "10.2.2020"})]

                     (ensure-exists! "price-catalogues" previous-catalogue) => :ok

                     (let [response (local-command sipoo :publish-price-catalogue
                                                   :organization-id "753-R"
                                                   :price-catalogue new-catalogue-req)]
                       response => ok?

                       (let [new-catalogue (mongo/by-id "price-catalogues" (:price-catalogue-id response))
                             previous-catalogue-in-db (mongo/by-id "price-catalogues" (:id previous-catalogue))]

                         (sc/validate catalogues/PriceCatalogue new-catalogue)
                         (sc/validate catalogues/PriceCatalogue previous-catalogue-in-db)
                         (to-finnish-date (:valid-until previous-catalogue-in-db)) => "9.2.2020"))))

            ;;TODO test the case where there is a catalogue with the same startig date (valid-from)
            ;;     In that case, the previous one should be replaced with the new one

            ;;TODO test the case where there is previous catalogue that
            ;;     has a valid-until that is before this catalogues' valid-from
            ;;     meaning that that catalogue has ended and there is no need to update it
            ;;     or set the valid-until of this catalogue

            ;;TODO test the case where there is previous catalogue that
            ;;     has a valid-until that is after this catalogues' valid-from
            ;;     meaning that there has to be a "next" or future catalogue to this cataloge.
            ;;     In that case, this catalogues valid-until needs to be set before the valid-from value
            ;;     of that catalogue
            ))))
