(ns lupapalvelu.exports-test
  (:require [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [clojure.set :refer [difference]]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [lupapalvelu.exports :as exports :refer [exported-application kayttotarkoitus-hinnasto price-classes-for-operation permit-type-price-codes]]
            [lupapalvelu.application :as app]
            [lupapalvelu.operations :as ops]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.permit :as permit]
            [lupapiste-commons.usage-types :as usages]))

(testable-privates lupapalvelu.exports resolve-price-class)

(def keyset (comp set keys))

(fact "Every operation has price class definition"
  (difference (keyset ops/operations) (keyset price-classes-for-operation) ) => empty?)

(fact "Every kayttotarkoitus has price class"
  (let [every-kayttotarkoitus (map :name usages/rakennuksen-kayttotarkoitus)]
    (difference (set every-kayttotarkoitus) (keyset @kayttotarkoitus-hinnasto))) => empty?)

(fact "Every permit type has price code"
  (doseq [permit-type (keys (permit/permit-types))]
    (fact {:midje/description permit-type}
      (get permit-type-price-codes permit-type) => number?)))

(fact "Uusi kerrostalo-rivitalo"
  (let [application (app/make-application "LP-123" "kerrostalo-rivitalo" 0 0 "address" "01234567891234" "location-service" "753" {:id "753-R"} false false [] {} 123 nil)
        uusi-rakennus (domain/get-document-by-name application "uusiRakennus")]

    (fact "Default value '021 rivitalot' = B"
      (let [op (resolve-price-class application (:primaryOperation application))]
        (:priceClass op) => "B"
        (:priceCode op) => 900
        (:use op) => "021 rivitalot"
        (:useFi op) => "021 rivitalot"
        (:useSv op) => "021 radhus"
        (:usagePriceCode op) => 906))

    #_(fact "Missing value defaults to C"
      (let [doc (assoc-in uusi-rakennus [:data :kaytto :kayttotarkoitus] {})
            application (assoc application :documents [doc])
            op (resolve-price-class application (:primaryOperation application))]
        (:priceClass op) => "C"
        (:usagePriceCode op) => 907))

    #_(fact "Empty value defaults to C"
      (let [doc (assoc-in uusi-rakennus [:data :kaytto :kayttotarkoitus :value] "")
            application (assoc application :documents [doc])
            op (resolve-price-class application (:primaryOperation application))]
        (:priceClass op) => "C"
        (:usagePriceCode op) => 907))

    #_(fact "021 rivitalot = B"
      (let [doc (assoc-in uusi-rakennus [:data :kaytto :kayttotarkoitus :value] "021 rivitalot")
            application (assoc application :documents [doc])
            op (resolve-price-class application (:primaryOperation application))]
        (:priceClass op) => "B"
        (:usagePriceCode op) => 906))

    #_(fact "041 vapaa-ajan asuinrakennukset = C"
      (let [doc (assoc-in uusi-rakennus [:data :kaytto :kayttotarkoitus :value] "041 vapaa-ajan asuinrakennukset")
            application (assoc application :documents [doc])
            op (resolve-price-class application (:primaryOperation application))]
        (:priceClass op) => "C"
        (:usagePriceCode op) => 907))

    #_(fact "121 hotellit yms = A"
      (let [doc (assoc-in uusi-rakennus [:data :kaytto :kayttotarkoitus :value] "121 hotellit yms")
            application (assoc application :documents [doc])
            op (resolve-price-class application (:primaryOperation application))]
        (:priceClass op) => "A"
        (:usagePriceCode op) => 905))

    #_(fact "999 muualla luokittelemattomat rakennukset = D"
      (let [doc (assoc-in uusi-rakennus [:data :kaytto :kayttotarkoitus :value] "999 muualla luokittelemattomat rakennukset")
            application (assoc application :documents [doc])
            op (resolve-price-class application (:primaryOperation application))]
        (:priceClass op) => "D"
        (:usagePriceCode op) => 908))))

(facts "YA jatkoaika"
  (let [application {:permitType "YA"}
        op (resolve-price-class application {:name "ya-jatkoaika"})]
    (:priceClass op) => "D"
    (:priceCode op) => 902
    (:usagePriceCode op) => 908
    (:use op) => nil))

(fact "Paperilupa"
  (let [application {:permitType "R"}
        op (resolve-price-class application {:name "aiemmalla-luvalla-hakeminen"})]
    (:priceClass op) => "D"
    (:priceCode op) => nil
    (:usagePriceCode op) => 908
    (:use op) => nil))

(fact "verdict"
  (let [application {:verdicts [(domain/->paatos {:verdictId 1 :backendId "kuntalupatunnus1" :text "paatosteksti" :timestamp 10 :name "Viranomainen" :given 9 :status :1 :official 11})]}
        {verdics :verdicts} (exported-application application)
        verdict (first verdics)]
    (count verdics) => 1
    verdict => {:id 1
                :kuntalupatunnus "kuntalupatunnus1"
                :timestamp 10
                :paatokset [{:paatoksentekija "Viranomainen"
                             :paatos "paatosteksti"
                             :paatospvm 9
                             :anto 9
                             :lainvoimainen 11}]}))

;;
;; Export Onkalo API usage
;;
(defn to-long [& args]
  (-> (apply t/date-time args) tc/to-long))

(facts timestamp->end-of-month-date-string
  (fact "returns timestamp string representing the last day of the timestamp's month"
    (exports/timestamp->end-of-month-date-string (to-long 2018 5 3 9 45 54))
    => "2018-05-31")
  (fact "handles leap days"
    (exports/timestamp->end-of-month-date-string (to-long 2016 2 21 10 11 0))
    => "2016-02-29"
    (exports/timestamp->end-of-month-date-string (to-long 2016 2 29 10 11 0))
    => "2016-02-29")
  (fact "interprets timestamps in Helsinki time zone"
    (exports/timestamp->end-of-month-date-string (to-long 2018 5 31 23 59 0))
    => "2018-06-30"))

(facts onkalo-log-entries->salesforce-export-entries
  (fact "no log entries results in no export entries"
    (exports/onkalo-log-entries->salesforce-export-entries []) => [])
  (fact "single entry results in single entry"
    (exports/onkalo-log-entries->salesforce-export-entries [{:organization "753-R"
                                                             :timestamp (to-long 2018 5 3)}])
    => [{:id "753-R" :date "2018-05-31" :quantity 1}])
  (fact "quantity shows the number of entries for given organization in given month"
    (exports/onkalo-log-entries->salesforce-export-entries [{:organization "753-R" :timestamp (to-long 2018 5 3)}
                                                            {:organization "753-R" :timestamp (to-long 2018 5 3)}])
    => [{:id "753-R" :date "2018-05-31" :quantity 2}])
  (fact "each organization has own entries"
    (exports/onkalo-log-entries->salesforce-export-entries [{:organization "753-R" :timestamp (to-long 2018 5 3)}
                                                            {:organization "091-R" :timestamp (to-long 2018 5 3)}])
    => (contains [{:id "753-R" :date "2018-05-31" :quantity 1} {:id "091-R" :date "2018-05-31" :quantity 1}]
                 :in-any-order))
  (fact "each month has own entries"
    (exports/onkalo-log-entries->salesforce-export-entries [{:organization "753-R" :timestamp (to-long 2018 5 3)}
                                                            {:organization "753-R" :timestamp (to-long 2018 6 3)}])
    => (contains [{:id "753-R" :date "2018-05-31" :quantity 1} {:id "753-R" :date "2018-06-30" :quantity 1}]
                 :in-any-order)))
