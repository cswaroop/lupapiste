(ns lupapalvelu.application-test
  (:require [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [lupapalvelu.test-util :refer :all]
            [lupapalvelu.action :refer [update-application]]
            [lupapalvelu.application :refer :all]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.document.schemas :as schemas]))


(fact "update-document"
  (update-application {:application ..application.. :data {:id ..id..}} ..changes..) => nil
  (provided
    ..application.. =contains=> {:id ..id..}
    (mongo/update-by-query :applications {:_id ..id..} ..changes..) => 1))

(testable-privates lupapalvelu.application validate-x validate-y)

(facts "coordinate validation"
  (validate-x {:data {:x nil}}) => nil
  (validate-y {:data {:y nil}}) => nil
  (validate-x {:data {:x ""}}) => {:ok false :text "error.illegal-coordinates"}
  (validate-x {:data {:x "0"}}) => {:ok false :text "error.illegal-coordinates"}
  (validate-x {:data {:x "1000"}}) => {:ok false :text "error.illegal-coordinates"}
  (validate-x {:data {:x "10001"}}) => nil
  (validate-x {:data {:x "799999"}}) => nil
  (validate-x {:data {:x "800000"}}) => {:ok false :text "error.illegal-coordinates"}
  (validate-y {:data {:y ""}}) => {:ok false :text "error.illegal-coordinates"}
  (validate-y {:data {:y "0"}}) => {:ok false :text "error.illegal-coordinates"}
  (validate-y {:data {:y "6609999"}}) => {:ok false :text "error.illegal-coordinates"}
  (validate-y {:data {:y "6610000"}}) => nil
  (validate-y {:data {:y "7780000"}}) => {:ok false :text "error.illegal-coordinates"}
  (validate-y {:data {:y "7779999"}}) => nil)

(defn find-by-schema? [docs schema-name]
  (domain/get-document-by-name {:documents docs} schema-name))

(defn has-schema? [schema] (fn [docs] (find-by-schema? docs schema)))

(facts filter-repeating-party-docs
  (filter-repeating-party-docs 1 ["a" "b" "c"]) => (just "a")
  (provided (schemas/get-schemas 1) => {"a" {:info {:type :party
                                                    :repeating true}}
                                        "b" {:info {:type :party
                                                    :repeating false}}
                                        "c" {:info {:type :foo
                                                    :repeating true}}
                                        "d" {:info {:type :party
                                                    :repeating true}}}))

(facts "is-link-permit-required works correctly"
  (fact "Muutoslupa requires" (is-link-permit-required {:permitSubtype "muutoslupa"}) => truthy)
  (fact "Aloitusilmoitus requires" (is-link-permit-required {:operations [{:name "aloitusoikeus"}]}) => truthy)
  (fact "Poikkeamis not requires" (is-link-permit-required {:operations [{:name "poikkeamis"}]}) => nil))


(testable-privates lupapalvelu.application add-operation-allowed?)

(facts "Add operation allowed"
  (let [not-allowed-for #{:jatkoaika :aloitusoikeus :suunnittelijan-nimeaminen :tyonjohtajan-nimeaminen}
        error {:ok false :text "error.add-operation-not-allowed"}]
    (doseq [operation lupapalvelu.operations/operations]
      (let [op (first operation)
            type (-> operation second :permit-type)
            application {:operations [{:name (name op)}] :permitSubtype nil}
            operation-allowed (doc-result (add-operation-allowed? nil application) op)]
        (if (or (not= type "R") (not-allowed-for op))
          (fact "Add operation not allowed" operation-allowed => (doc-check = error))
          (fact "Add operation allowed" operation-allowed => (doc-check nil?)))))
    (fact "Add operation not allowed for :muutoslupa"
          (add-operation-allowed? nil {:operations [{:name "asuinrakennus"}] :permitSubtype :muutoslupa}) => error)))
