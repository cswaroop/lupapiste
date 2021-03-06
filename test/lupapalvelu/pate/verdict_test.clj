(ns lupapalvelu.pate.verdict-test
  (:require [lupapalvelu.inspection-summary :as inspection-summary]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.pate.metadata :as metadata]
            [lupapalvelu.pate.schema-helper :as helper]
            [lupapalvelu.pate.schema-util :as schema-util]
            [lupapalvelu.pate.schemas :as schemas]
            [lupapalvelu.pate.shared-schemas :as shared-schemas]
            [lupapalvelu.pate.verdict :refer :all]
            [lupapalvelu.pate.verdict-common :as vc]
            [lupapalvelu.pate.verdict-interface :as vif]
            [lupapalvelu.pate.verdict-schemas :as verdict-schemas]
            [lupapalvelu.pate.verdict-template-schemas :as template-schemas]
            [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [monger.operators :refer :all]
            [sade.shared-schemas :refer [object-id-pattern]]
            [sade.util :as util]
            [schema.core :as sc]))

(testable-privates lupapalvelu.pate.verdict
                   next-section verdict->updates
                   application-deviations
                   archive-info application-operation
                   verdict-attachment-items)

(testable-privates lupapalvelu.pate.verdict-common
                   verdict-section-string
                   title-fn verdict-string)

(testable-privates lupapalvelu.pate.verdict-template
                   template-inclusions)

(fact verdict->updates
  (verdict->updates {:data {:foo "hello"}} :data)
  => {:verdict {:data {:foo "hello"}}
      :updates {$set {:pate-verdicts.$.data {:foo "hello"}}}}
  (verdict->updates {:data {:foo "hello"}
                     :template {:inclusions [:one :two]}
                     :published 12345}
                    :template.inclusions :published)
  => {:verdict {:data {:foo "hello"}
                :template {:inclusions [:one :two]}
                :published 12345}
      :updates {$set {:pate-verdicts.$.published 12345
                      :pate-verdicts.$.template.inclusions [:one :two]}}})

(fact "Resolve verdict attachment type"
  (schemas/resolve-verdict-attachment-type {:permitType "R"})
  => {:type-group "paatoksenteko" :type-id "paatos"}
  (schemas/resolve-verdict-attachment-type {:permitType "R"} :ilmoitus)
  => {:type-group "paatoksenteko" :type-id "ilmoitus"}
  (schemas/resolve-verdict-attachment-type {:permitType "R"} :bad)
  => {}
  (schemas/resolve-verdict-attachment-type {:permitType "R"} :cv)
  => {:type-group "osapuolet" :type-id "cv"}
  (schemas/resolve-verdict-attachment-type {:permitType "BAD"})
  => {}
  (schemas/resolve-verdict-attachment-type {:permitType "YA"})
  => {:type-group "muut" :type-id "paatos"}
  (schemas/resolve-verdict-attachment-type {:permitType "R"} :paatosehdotus)
  => {:type-group "paatoksenteko" :type-id "paatosehdotus"})

(facts next-section
  (fact "all arguments given"
    (next-section "123-T" 1515151515151 :test) => "1"
    (provided (lupapalvelu.mongo/get-next-sequence-value "verdict_test_123-T_2018") => 1))

  (fact "all arguments given - year is determined by local time zone (UTC+2))"
    (next-section "123-T" 1514760000000 "test") => "99"
    (provided (lupapalvelu.mongo/get-next-sequence-value "verdict_test_123-T_2018") => 99))

  (fact "org-id is nil"
    (next-section nil 1515151515151 :test) => nil
    (provided (lupapalvelu.mongo/get-next-sequence-value irrelevant) => irrelevant :times 0))

  (fact "org-id is blank"
    (next-section "" 1515151515151 :test) => nil
    (provided (lupapalvelu.mongo/get-next-sequence-value irrelevant) => irrelevant :times 0))

  (fact "created is nil"
    (next-section "123-T" nil :test) => nil
    (provided (lupapalvelu.mongo/get-next-sequence-value irrelevant) => irrelevant :times 0))

  (fact "verdict-giver is nil"
    (next-section "123-T" 1515151515151 nil) => nil
    (provided (lupapalvelu.mongo/get-next-sequence-value irrelevant) => irrelevant :times 0))

  (fact "verdict-giver is blank"
    (next-section "123-T" 1515151515151 "") => nil
    (provided (lupapalvelu.mongo/get-next-sequence-value irrelevant) => irrelevant :times 0)))

(facts finalize--section
  (let [args    {:application {:organization "123-T"}
                 :command     {:created 1515151515151}}
        test-fn #(finalize--section (assoc args :verdict %))]
    (fact "section is not set"
      (test-fn {:data     {}
                :template {:giver "test"}})
      => {:verdict {:data     {:verdict-section "2"}
                    :template {:giver      "test"
                               :inclusions [:verdict-section]}}
          :updates {$set {:pate-verdicts.$.data.verdict-section "2"
                          :pate-verdicts.$.template.inclusions  [:verdict-section]}}}

      (provided (lupapalvelu.mongo/get-next-sequence-value "verdict_test_123-T_2018") => 2))

    (fact "section is blank"
      (test-fn {:data     {:verdict-section ""}
                :template {:giver "test"}})
      => {:verdict {:data     {:verdict-section "1"}
                    :template {:giver      "test"
                               :inclusions [:verdict-section]}}
          :updates {$set {:pate-verdicts.$.data.verdict-section "1"
                          :pate-verdicts.$.template.inclusions  [:verdict-section]}}}

      (provided (lupapalvelu.mongo/get-next-sequence-value "verdict_test_123-T_2018") => 1))

    (fact "Distinct inclusions"
      (test-fn {:data     {:verdict-section ""}
                :template {:giver      "test"
                           :inclusions [:hello :verdict-section :foo]}})
      => {:verdict {:data     {:verdict-section "1"}
                    :template {:giver      "test"
                               :inclusions [:hello :verdict-section :foo]}}
          :updates {$set {:pate-verdicts.$.data.verdict-section "1"
                          :pate-verdicts.$.template.inclusions  [:hello :verdict-section :foo]}}}

      (provided (lupapalvelu.mongo/get-next-sequence-value "verdict_test_123-T_2018") => 1))

    (fact "section already given"
      (test-fn {:data     {:verdict-section "9"}
                :template {:giver "test"}})
      => nil

      (provided (lupapalvelu.mongo/get-next-sequence-value irrelevant) => irrelevant :times 0))

    (fact "Board verdict (lautakunta)"
      (test-fn {:data     {}
                :template {:giver "lautakunta"}})
      => nil

      (provided (lupapalvelu.mongo/get-next-sequence-value irrelevant) => irrelevant :times 0))

    (fact "Legacy verdict"
      (test-fn {:legacy?  true
                :data     {}
                :template {:giver "test"}})
      => nil

      (provided (lupapalvelu.mongo/get-next-sequence-value irrelevant) => irrelevant :times 0))))

(facts "update automatic dates"
  ;; Calculation is cumulative
  (let [refs {:date-deltas {:julkipano     {:delta 1 :unit "days"}
                            :anto          {:delta 2 :unit "days"}
                            :muutoksenhaku {:delta 3 :unit "days"}
                            :lainvoimainen {:delta 4 :unit "days"}
                            :aloitettava   {:delta 1 :unit "years"}
                            :voimassa      {:delta 2 :unit "years"}}}
        ts #(+ (* 1000 3600 12) (util/to-millis-from-local-date-string %))]
    (fact "All dates included in the verdict"
      (update-automatic-verdict-dates {:references   refs
                                       :template     {:inclusions helper/verdict-dates}
                                       :verdict-data {:automatic-verdict-dates true
                                                      :verdict-date            (ts "6.4.2018") ;; Friday
                                                      }})
      => {:julkipano     (ts "9.4.2018")
          :anto          (ts "11.4.2018")
          :muutoksenhaku (ts "16.4.2018") ;; Skips weekend
          :lainvoimainen (ts "20.4.2018")
          :aloitettava   (ts "23.4.2019")
          :voimassa      (ts "23.4.2021")})
    (fact "Calculation skips public holiday (Easter)"
      (update-automatic-verdict-dates {:references   refs
                                       :template     {:inclusions helper/verdict-dates}
                                       :verdict-data {:automatic-verdict-dates true
                                                      :verdict-date            (ts "26.3.2018")}})
      => {:julkipano     (ts "27.3.2018")
          :anto          (ts "29.3.2018")
          :muutoksenhaku (ts "3.4.2018") ;; Skips Easter
          :lainvoimainen (ts "9.4.2018") ;; Skips weekend
          :aloitettava   (ts "9.4.2019")
          :voimassa      (ts "9.4.2021")})
    (fact "Only some dates included"
      (update-automatic-verdict-dates {:references   refs
                                       :template     {:inclusions [:anto :lainvoimainen :voimassa]}
                                       :verdict-data {:automatic-verdict-dates true
                                                      :verdict-date            (ts "26.3.2018")}})
      => { :anto          (ts "29.3.2018")
          :lainvoimainen (ts "9.4.2018") ;; Skips weekend
          :voimassa      (ts "9.4.2021")})
    (fact "No automatic calculation flag"
      (update-automatic-verdict-dates {:references   refs
                                       :template     {:inclusions helper/verdict-dates}
                                       :verdict-data {:automatic-verdict-dates false
                                                      :verdict-date            (ts "26.3.2018")}})
      => nil
      (update-automatic-verdict-dates {:references   refs
                                       :template     {:inclusions helper/verdict-dates}
                                       :verdict-data {:verdict-date            (ts "26.3.2018")}})
      => nil)
    (fact "Verdict date is not set"
      (update-automatic-verdict-dates {:references   refs
                                       :template     {:inclusions helper/verdict-dates}
                                       :verdict-data {:automatic-verdict-dates true
                                                      :verdict-date ""}})
      => nil
      (fact "Verdict date is not set"
        (update-automatic-verdict-dates {:references   refs
                                         :template     {:inclusions helper/verdict-dates}
                                         :verdict-data {:automatic-verdict-dates true}})
        => nil))))

(def test-verdict
  {:version  1
   :dictionary
   {:one   {:text             {}
            :template-section :t-first}
    :two   {:text             {}
            :template-dict    :t-two}
    :three {:date          {}
            :template-dict :t-three}
    :four  {:text {}}
    :five  {:repeating {:r-one   {:text {}}
                        :r-two   {:toggle {}}
                        :r-three {:repeating {:r-sub-one {:date {}}
                                              :r-sub-two {:text {}}}}}}
    :six   {:toggle {}}
    :seven {:repeating     {:up {:text {}}}
            :template-dict :t-seven}}
   :sections [{:id   :first
               :grid {:columns 4
                      :rows    [[{:dict :one}]]}}
              {:id               :second
               :template-section :t-third
               :grid             {:columns 4
                                  :rows    [[{:dict :three}
                                             {:dict :four}]]}}
              {:id               :third
               :template-section :t-fourth
               :grid             {:columns 4
                                  :rows    [[{:dict :two}
                                             {:dict :three}]
                                            [{:grid {:columns   3
                                                     :repeating :five
                                                     :rows      [[{:dict :r-one}
                                                                  {:dict :r-two}
                                                                  {:grid {:columns   2
                                                                          :repeating :r-three
                                                                          :rows      [[{:dict :r-sub-one}
                                                                                       {:dict :r-sub-two}]]}}]]}}]]}}
              {:id               :fourth
               :template-section :t-fifth
               :grid             {:columns 1
                                  :rows    [[{:grid {:columns   1
                                                     :repeating :seven
                                                     :rows      [[{:dict :up}]]}}]]}}]})

(def mock-template
  {:dictionary {:t-one            {:text {}}
                :t-two            {:toggle {}}
                :t-three          {:date {}}
                :t-seven          {:repeating {:up {:text {}}}}
                :removed-sections {:keymap {:t-first  false
                                            :t-second false
                                            :t-third  false
                                            :t-fourth false
                                            :t-fifth  false
                                            :t-sixth  false}}}
   :sections   [{:id   :t-first
                 :grid {:columns 2
                        :rows    [[{:dict :t-one}]]} }
                {:id   :t-second
                 :grid {:columns 2
                        :rows    [[{:dict :t-two}
                                   {:dict :t-three}]]} }
                {:id   :t-third
                 :grid {:columns 2
                        :rows    [[{:dict :t-three}]]} }
                {:id   :t-sixth
                 :grid {:columns 2
                        :rows    [[{:dict :t-seven}]]} }]})

(facts "Build schemas"
  (fact "Dict missing"
    (schema-util/check-dicts (dissoc (:dictionary test-verdict) :three)
                        (:sections test-verdict))
    => (throws AssertionError))
  (fact "Dict in repeating missing"
    (schema-util/check-dicts (util/dissoc-in (:dictionary test-verdict)
                                        [:five :repeating :r-three :repeating :r-sub-two])
                        (:sections test-verdict))
    => (throws AssertionError))
  (fact "Overlapping dicts"
    (schema-util/check-overlapping-dicts [{:dictionary {:foo {:toggle {}}
                                                   :bar {:text {}}
                                                   :baz {:toggle {}}}}
                                     {:dictionary {:doo {:toggle {}}
                                                   :mar {:text {}}
                                                   :daz {:toggle {}}}}
                                     {:dictionary {:foo {:toggle {}}
                                                   :har {:text {}}
                                                   :baz {:toggle {}}}}])
    => (throws AssertionError))
  (fact "Unique section ids"
    (schema-util/check-unique-section-ids (:sections mock-template))
    => nil)
  (fact "Non-unique section ids"
    (schema-util/check-unique-section-ids (cons {:id :t-second}
                                           (:sections mock-template)))
    => (throws AssertionError))
  (fact "Combine subschemas"
    (schema-util/combine-subschemas {:dictionary {:foo {:toggle {}}
                                             :bar {:text {}}}
                                :sections   [{:id   :one
                                              :grid {:columns 1
                                                     :rows    [[{:dict :foo}]]}}
                                             {:id   :two
                                              :grid {:columns 1
                                                     :rows    [[{:dict :bar}]]}}]}
                               {:dictionary {:baz {:date {}}}
                                :section    {:id   :three
                                             :grid {:columns 1
                                                    :rows    [[{:dict :baz}]]}}})
    => {:dictionary {:foo {:toggle {}}
                     :bar {:text {}}
                     :baz {:date {}}}
        :sections   [{:id   :one
                      :grid {:columns 1
                             :rows    [[{:dict :foo}]]}}
                     {:id   :two
                      :grid {:columns 1
                             :rows    [[{:dict :bar}]]}}
                     {:id   :three
                      :grid {:columns 1
                             :rows    [[{:dict :baz}]]}}]})
  (fact "Build verdict template schema"
    (template-schemas/build-verdict-template-schema
     {:dictionary {:foo {:toggle {}}
                   :bar {:text {}}}
      :sections   [{:id   :one
                    :grid {:columns 1
                           :rows    [[{:dict :foo}]]}}
                   {:id   :two
                    :grid {:columns 1
                           :rows    [[{:dict :bar}]]}}]
      :removable? true}
     {:dictionary {:baz {:date {}}}
      :section    {:id   :three
                   :grid {:columns 1
                          :rows    [[{:dict :baz}]]}}
      :removable? true}
     {:dictionary {:dum {:date {}}}
      :section    {:id   :four
                   :grid {:columns 1
                          :rows    [[{:dict :dum}]]}}})
    => {:dictionary {:foo                       {:toggle {}}
                     :bar                       {:text {}}
                     :baz                       {:date {}}
                     :dum                       {:date {}}
                     :removed-sections          {:keymap {:one   false
                                                          :two   false
                                                          :three false}}}
        :sections   [{:id   :one
                      :grid {:columns 1
                             :rows    [[{:dict :foo}]]}}
                     {:id   :two
                      :grid {:columns 1
                             :rows    [[{:dict :bar}]]}}
                     {:id   :three
                      :grid {:columns 1
                             :rows    [[{:dict :baz}]]}}
                     {:id   :four
                      :grid {:columns 1

                             :rows    [[{:dict :dum}]]}}]}))

(facts "Verdict validation"
  (facts "Schema creation"
    (fact "OK"
      (verdict-schemas/build-verdict-schema :r 1 test-verdict)
      => (contains {:version 1})
      (provided (template-schemas/verdict-template-schema :r)
                =>     (sc/validate shared-schemas/PateVerdictTemplate
                                    mock-template)))
    (fact "Template section t-first missing"
      (verdict-schemas/build-verdict-schema :r 1 test-verdict)
      => (throws AssertionError)
      (provided (template-schemas/verdict-template-schema :r)
                => (sc/validate shared-schemas/PateVerdictTemplate
                                (util/dissoc-in mock-template
                                                [:dictionary :removed-sections :keymap :t-first]))))
    (fact "Template section t-third missing"
      (verdict-schemas/build-verdict-schema :r 1 test-verdict)
      => (throws AssertionError)
      (provided (template-schemas/verdict-template-schema :r)
                => (sc/validate shared-schemas/PateVerdictTemplate
                                (util/dissoc-in mock-template
                                                [:dictionary :removed-sections :keymap :t-third]))))
    (fact "Template dict t-two missing"
      (verdict-schemas/build-verdict-schema :r 1 test-verdict)
      => (throws AssertionError)
      (provided (template-schemas/verdict-template-schema :r)
                => (sc/validate shared-schemas/PateVerdictTemplate
                                (util/dissoc-in mock-template
                                                [:dictionary :t-two]))))))

(facts "section-dicts"
  (fact "Verdict sections"
    (schemas/section-dicts (-> test-verdict :sections first))
    => #{:one}
    (schemas/section-dicts (-> test-verdict :sections second))
    => #{:three :four}
    (schemas/section-dicts (-> test-verdict :sections (nth 2)))
    => #{:two :three :five}
    (schemas/section-dicts (-> test-verdict :sections last))
    => #{:seven})
  (fact "Verdict tmeplate section"
    (schemas/section-dicts (-> mock-template :sections first))
    => #{:t-one}
    (schemas/section-dicts (-> mock-template :sections second))
    => #{:t-two :t-three}
    (schemas/section-dicts (-> mock-template :sections (nth 2)))
    => #{:t-three}
    (schemas/section-dicts (-> mock-template :sections last))
    => #{:t-seven}))

(facts "dict-sections"
  (fact "Verdict"
    (schemas/dict-sections (:sections test-verdict))
    => {:one   #{:first}
        :two   #{:third}
        :three #{:second :third}
        :four  #{:second}
        :five  #{:third}
        :seven #{:fourth}})
  (fact "Template"
    (schemas/dict-sections (:sections mock-template))
    => {:t-one   #{:t-first}
        :t-two   #{:t-second}
        :t-three #{:t-second  :t-third}
        :t-seven #{:t-sixth}}))

(fact "dicts->kw-paths"
  (dicts->kw-paths (:dictionary test-verdict))
  => (just [:one :two :three :four
            :five.r-one :five.r-two
            :five.r-three.r-sub-one :five.r-three.r-sub-two
            :six
            :seven.up] :in-any-order)
  (dicts->kw-paths (:dictionary mock-template))
  => (just [:t-one :t-two :t-three :t-seven.up :removed-sections]
           :in-any-order))

(defn mocker [removed-sections]
  (let [m {:removed-sections removed-sections}]
    {:data       m
     :inclusions (template-inclusions {:category :r
                                       :draft    m})}))
(against-background
 [(verdict-schemas/verdict-schema :r)          => test-verdict
  (template-schemas/verdict-template-schema :r) => mock-template]
 (facts "Inclusions"
   (fact "Every template section included"
     (inclusions :r (mocker {:t-first  false
                             :t-second false
                             :t-third  false
                             :t-fourth false
                             :t-fifth  false}))
     => (just [:one :two :three :four
               :five.r-one :five.r-two
               :five.r-three.r-sub-one :five.r-three.r-sub-two
               :six :seven.up] :in-any-order)
     (inclusions :r (mocker {}))
     => (just [:one :two :three :four
               :five.r-one :five.r-two
               :five.r-three.r-sub-one :five.r-three.r-sub-two
               :six :seven.up] :in-any-order))
   (fact "Template sections t-first and t-second removed"
     (inclusions :r (mocker {:t-first  true
                             :t-second true
                             :t-third  false
                             :t-fourth false
                             :t-fifth  false}))
     => (just [:three ;; Not removed since also in :third
               :four
               :five.r-one :five.r-two
               :five.r-three.r-sub-one :five.r-three.r-sub-two
               :six :seven.up] :in-any-order))
   (fact "Template section t-third removed"
     (inclusions :r (mocker {:t-first  false
                             :t-second false
                             :t-third  true
                             :t-fourth false
                             :t-fifth  false}))
     => (just [:one :two :three ;; Not removed since also in :second
               :five.r-one :five.r-two
               :five.r-three.r-sub-one :five.r-three.r-sub-two
               :six :seven.up] :in-any-order))
   (fact "Template section t-fourth removed"
     (inclusions :r (mocker {:t-first  false
                             :t-second false
                             :t-third  false
                             :t-fourth true
                             :t-fifth  false}))
     => (just [:one
               :three ;; ;; Not removed since also in :second
               :four :six :seven.up] :in-any-order))
   (fact "Template section t-third and t-fourth removed"
     (inclusions :r (mocker {:t-first  false
                             :t-second false
                             :t-third  true
                             :t-fourth true
                             :t-fifth  false}))
     => (just [:one :six :seven.up] :in-any-order))
   (fact "Template section t-third, t-fourth and t-sixth removed"
     (inclusions :r (mocker {:t-first  false
                             :t-second false
                             :t-third  true
                             :t-fourth true
                             :t-fifth  false
                             :t-sixth  true}))
     => (just [:one :six] :in-any-order))
   (fact "Every template section removed"
     (inclusions :r (mocker {:t-first  true
                             :t-second true
                             :t-third  true
                             :t-fourth true
                             :t-fifth  true
                             :t-sixth true}))
     => (just [:six]))))

(defn templater [removed & kvs]
  (let [data             (apply hash-map kvs)
        board?           (:giver data)
        removed-sections (zipmap removed (repeat true))
        draft (assoc data
                     :giver (if board?
                              "lautakunta" "viranhaltija")
                     :removed-sections removed-sections)]
    {:category  "r"
     :published {:data draft
                 :inclusions (template-inclusions {:category :r :draft draft})
                 :settings (cond-> {:verdict-code ["osittain-myonnetty"]}
                             board? (assoc :boardname "Gate is boarding"))}}))

(against-background
 [(verdict-schemas/verdict-schema "r") => test-verdict
  (template-schemas/verdict-template-schema :r) => mock-template]
 (facts "Initialize mock verdict draft"
   (fact "default, no removed sections"
     (default-verdict-draft (templater [] :t-two "Hello"))
     => {:category   "r"
         :schema-version 1
         :template   {:inclusions [:one :two :three :four
                                   :five.r-one :five.r-two
                                   :five.r-three.r-sub-one
                                   :five.r-three.r-sub-two
                                   :six :seven.up]}
         :data       {:two "Hello"}
         :references {:verdict-code ["osittain-myonnetty"]}})
   (fact "default, :t-second removed"
     (default-verdict-draft (templater [:t-second]
                                       :t-two "Hello"
                                       :giver true))
     => {:category   "r"
         :schema-version 1
         :template   {:inclusions [:one :three :four
                                   :five.r-one :five.r-two
                                   :five.r-three.r-sub-one
                                   :five.r-three.r-sub-two
                                   :six :seven.up]}
         :data       {}
         :references {:verdict-code ["osittain-myonnetty"]
                      :boardname    "Gate is boarding"}})
   (fact "default, repeating init"
     (let [draft (default-verdict-draft (templater [:t-first :t-third :t-fourth]
                                                   :t-two "Hello"
                                                   :t-seven [{:up "These"}
                                                             {:up "are"}
                                                             {:up "terms"}]))]
       draft => (contains {:template   {:inclusions [:six :seven.up]}
                           :references {:verdict-code ["osittain-myonnetty"]}})

       (-> draft :data :seven vals) => (just [{:up "These"}
                                              {:up "are"}
                                              {:up "terms"}])
       (-> draft :data :seven keys) => (has every? (partial re-matches #"[0-9a-z]+"))))))

(def mini-verdict-template
  {:dictionary
   {:paatosteksti     {:phrase-text {:category :paatosteksti}}
    :conditions       {:repeating {:condition {:phrase-text {:category :yleinen}}}}
    :removed-sections {:keymap {:foremen     false
                                :reviews     false
                                :plans       false
                                :attachments false
                                :conditions  false
                                :deviations  false
                                :buildings   false
                                :random      false}}}
   :sections [{:id   :verdict
               :grid {:columns 4
                      :rows    [[{:dict :paatostekesti}]]}}
              {:id   :conditions
               :grid {:columns 4
                      :rows    [[{:dict :conditions}]]}}]})

(def mini-verdict
  {:version  1
   :dictionary
   {:julkipano               {:date {}}
    :anto                    {:date {}}
    :muutoksenhaku           {:date {}}
    :lainvoimainen           {:date {}}
    :aloitettava             {:date {}}
    :voimassa                {:date {}}
    :handler                 {:text {}}
    :verdict-section         {:text {}}
    :boardname               {:reference {:path :*ref.boardname}}
    :automatic-verdict-dates {:toggle {}}
    :paatosteksti            {:phrase-text   {:category :paatosteksti}
                              :template-dict :paatosteksti}
    :conditions              {:repeating     {:condition
                                              {:phrase-text {:category :yleinen}}
                                              :remove-condition {:button {:remove :conditions}}}
                              :template-dict :conditions}
    :add-condition           {:button           {:add :conditions}
                              :template-section :conditions}
    :deviations              {:phrase-text      {:category :yleinen}
                              :template-section :deviations}
    :foremen                 {:reference-list {:path :foremen
                                               :type :multi-select}}
    :foremen-included        {:toggle {}}
    :reviews-included        {:toggle {}}
    :reviews                 {:reference-list {:path :reviews
                                               :type :multi-select}}
    :plans                   {:reference-list {:path :plans
                                               :type :multi-select}}
    :plans-included          {:toggle {}}
    :upload                  {:attachments      {}
                              :template-section :attachments}
    :attachments             {:application-attachments {}
                              :template-section        :attachments}
    :buildings               {:repeating        {:rakennetut-autopaikat  {:text {}}
                                                 :kiinteiston-autopaikat {:text {}}
                                                 :autopaikat-yhteensa    {:text {}}
                                                 :vss-luokka             {:text {}}
                                                 :paloluokka             {:text {}}}
                              :template-section :buildings}
    :start-date              {:date             {}
                              :template-section :random}
    :end-date                {:date             {}
                              :template-section :random}}
   :sections []})

(defn draftee [& args]
  (let [t (apply templater args)]
    {:template t
     :draft    (default-verdict-draft t)}))

(defn check-draft [& kvs]
  (let [{:keys [inclusions giver references data]} (apply hash-map kvs)]
    (fn [{draft :draft}]
      (facts "check draft part of initmap"
       (when inclusions
         (fact "inclusions"
           (-> draft :template :inclusions)
           => (just inclusions :in-any-order)))
       (when giver
         (fact "giver"
           (-> draft :template :giver) => giver))
       (when references
         (fact "references"
           (:references draft) => (just references)))
       (when data
         (fact "data"
           (:data draft) => (just data)))))))

(defn mongo-id? [v]
  (re-matches object-id-pattern v))

(against-background
 [(template-schemas/verdict-template-schema "r") => mini-verdict-template
  (template-schemas/verdict-template-schema :r) => mini-verdict-template
  (verdict-schemas/verdict-schema "r")          => mini-verdict]
 (facts "Initialize verdict draft"
   (fact "Minis are valid"
     (sc/validate shared-schemas/PateVerdictTemplate
                  mini-verdict-template)
     => mini-verdict-template
     (sc/validate shared-schemas/PateVerdict
                  mini-verdict)
     => mini-verdict)

   (fact "init foremen: no foremen in template"
     (init--foremen (draftee [:conditions :deviations :attachments
                              :buildings]))
     => (check-draft :inclusions [:julkipano :anto :lainvoimainen
                                  :voimassa :muutoksenhaku :aloitettava
                                  :handler :paatosteksti :plans
                                  :plans-included :reviews :reviews-included
                                  :automatic-verdict-dates :verdict-section
                                  :boardname :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data {}))
   (fact "init foremen: no foremen included"
     (init--foremen (draftee [:conditions :deviations :attachments
                              :buildings]
                             :vastaava-tj false
                             :vastaava-tj-included false
                             :vv-tj true
                             :iv-tj false
                             :iv-tj-included false))
     => (check-draft :inclusions [:julkipano :anto :lainvoimainen
                                  :voimassa :muutoksenhaku :aloitettava
                                  :handler :paatosteksti :plans
                                  :plans-included :reviews :reviews-included
                                  :automatic-verdict-dates  :start-date
                                  :end-date :verdict-section :boardname]
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data {}))
   (fact "init foremen: three foremen included, one selected"
     (init--foremen (draftee [:conditions :deviations :attachments
                              :buildings]
                             :vastaava-tj false
                             :vastaava-tj-included true
                             :vv-tj true
                             :vv-tj-included true
                             :iv-tj false
                             :iv-tj-included true
                             :erityis-tj true
                             :erityis-tj-included false
                             :tj false))
     => (check-draft :inclusions [:julkipano :anto :lainvoimainen
                                  :voimassa :muutoksenhaku :aloitettava
                                  :handler :paatosteksti :plans
                                  :plans-included :reviews :reviews-included
                                  :automatic-verdict-dates
                                  :verdict-section :boardname
                                  :foremen :foremen-included
                                  :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]
                                  :foremen      (just ["vastaava-tj" "vv-tj" "iv-tj"]
                                                      :in-any-order)}
                     :data {:foremen          ["vv-tj"]
                            :foremen-included true}))
   (fact "init foremen: all foremen included, everyone selected, section removed"
     (init--foremen (draftee [:conditions :deviations :attachments
                              :buildings :foremen]
                             :vastaava-tj true
                             :vastaava-tj-included true
                             :vv-tj true
                             :vv-tj-included true
                             :iv-tj true
                             :iv-tj-included true
                             :erityis-tj true
                             :erityis-tj-included true
                             :tj true
                             :tj-included true))
     => (check-draft :inclusions [:julkipano :anto :lainvoimainen
                                  :voimassa :muutoksenhaku :aloitettava
                                  :handler :paatosteksti :plans
                                  :plans-included :reviews :reviews-included
                                  :automatic-verdict-dates
                                  :verdict-section :boardname
                                  :foremen :foremen-included
                                  :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]
                                  :foremen      (just ["vastaava-tj" "vv-tj" "iv-tj"
                                                       "erityis-tj" "tj"]
                                                      :in-any-order)}
                     :data {:foremen          (just ["vastaava-tj" "vv-tj" "iv-tj"
                                                     "erityis-tj" "tj"]
                                                    :in-any-order)
                            :foremen-included false}))

   (fact "Init requirements references: no plans"
     (init--requirements-references (draftee [:conditions :deviations :attachments
                                              :buildings])
                                    :plans)
     => (check-draft :inclusions [:julkipano :anto :lainvoimainen
                                  :voimassa :muutoksenhaku :aloitettava
                                  :handler :paatosteksti
                                  :reviews :reviews-included
                                  :foremen :foremen-included
                                  :automatic-verdict-dates
                                  :verdict-section :boardname
                                  :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data {}))
   (fact "Init requirements references: no reviews"
     (init--requirements-references (draftee [:conditions :deviations :attachments
                                              :buildings])
                                    :reviews)
     => (check-draft :inclusions [:julkipano :anto :lainvoimainen
                                  :voimassa :muutoksenhaku :aloitettava
                                  :handler :paatosteksti
                                  :plans :plans-included
                                  :foremen :foremen-included
                                  :automatic-verdict-dates
                                  :verdict-section :boardname
                                  :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data {}))
   (fact "Init requirements references: plan, not selected"
     (init--requirements-references (assoc-in (draftee [:conditions :deviations :attachments
                                                        :buildings])
                                              [:template :published :settings :plans]
                                              [{:fi "suomi" :sv "svenska" :en "english"}])
                                    :plans)
     => (check-draft :inclusions [:julkipano :anto :lainvoimainen
                                  :voimassa :muutoksenhaku :aloitettava
                                  :handler :paatosteksti
                                  :reviews :reviews-included
                                  :plans :plans-included
                                  :foremen :foremen-included
                                  :automatic-verdict-dates
                                  :verdict-section :boardname
                                  :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]
                                  :plans        (just [(just {:fi "suomi"
                                                              :sv "svenska"
                                                              :en "english"
                                                              :id mongo-id?})])}
                     :data {:plans-included true}))
   (fact "Init requirements references: two plans, one selected"
     (let [result  (init--requirements-references (assoc-in (draftee [:conditions :deviations :attachments
                                                                      :buildings])
                                                            [:template :published :settings :plans]
                                                            [{:fi "suomi" :sv "svenska" :en "english" :selected true}
                                                             {:fi "imous" :sv "aksnevs" :en "hsilgne"}])
                                                  :plans)
           find-id #(:id (util/find-by-key :fi % (get-in result [:draft :references :plans])))]
       result => (check-draft :inclusions [:julkipano :anto :lainvoimainen
                                           :voimassa :muutoksenhaku :aloitettava
                                           :handler :paatosteksti
                                           :reviews :reviews-included
                                           :plans :plans-included
                                           :foremen :foremen-included
                                           :automatic-verdict-dates
                                           :verdict-section :boardname
                                           :start-date :end-date]
                              :references {:verdict-code ["osittain-myonnetty"]
                                           :plans        (just [{:fi "suomi"
                                                                 :sv "svenska"
                                                                 :en "english"
                                                                 :id (find-id "suomi")}
                                                                {:fi "imous"
                                                                 :sv "aksnevs"
                                                                 :en "hsilgne"
                                                                 :id (find-id "imous")}] :in-any-order)}
                              :data {:plans-included true
                                     :plans          [(find-id "suomi")]})))
   (fact "Init requirements references: two reviews, both selected, section removed"
     (let [result  (init--requirements-references (assoc-in (draftee [:conditions :deviations :attachments
                                                                      :buildings :reviews])
                                                            [:template :published :settings :reviews]
                                                            [{:fi       "suomi" :sv   "svenska" :en "english"
                                                              :selected true    :type "hello"}
                                                             {:fi       "imous" :sv   "aksnevs" :en "hsilgne"
                                                              :selected true    :type "olleh"}])
                                                  :reviews)
           find-id #(:id (util/find-by-key :fi % (get-in result [:draft :references :reviews])))]
       result => (check-draft :inclusions [:julkipano :anto :lainvoimainen
                                           :voimassa :muutoksenhaku :aloitettava
                                           :handler :paatosteksti
                                           :reviews :reviews-included
                                           :plans :plans-included
                                           :foremen :foremen-included
                                           :automatic-verdict-dates
                                           :verdict-section :boardname
                                           :start-date :end-date]
                              :references {:verdict-code ["osittain-myonnetty"]
                                           :reviews      (just [{:fi   "suomi"
                                                                 :sv   "svenska"
                                                                 :en   "english"
                                                                 :type "hello"
                                                                 :id   (find-id "suomi")}
                                                                {:fi   "imous"
                                                                 :sv   "aksnevs"
                                                                 :en   "hsilgne"
                                                                 :type "olleh"
                                                                 :id   (find-id "imous")}] :in-any-order)}
                              :data {:reviews-included false
                                     :reviews          (just [(find-id "suomi") (find-id "imous")] :in-any-order)})))

   (fact "init verdict-dates"
     (init--verdict-dates (draftee [:conditions :deviations :attachments
                                    :buildings]
                                   :verdict-dates []))
     => (check-draft :inclusions [:handler :paatosteksti :foremen
                                  :foremen-included :plans
                                  :verdict-section :boardname
                                  :plans-included :reviews :reviews-included
                                   :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data       {})
     (init--verdict-dates (draftee [:conditions :deviations :attachments
                                    :buildings]
                                   :verdict-dates [:julkipano
                                                   :anto
                                                   :voimassa]))
     => (check-draft :inclusions [:handler :paatosteksti :foremen
                                  :foremen-included :plans
                                  :verdict-section :boardname
                                  :plans-included :reviews :reviews-included
                                  :julkipano :anto :voimassa
                                  :automatic-verdict-dates
                                  :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data       {}))
   (fact "init upload: upload unchecked"
     (init--upload (draftee []))
     => (check-draft :inclusions [:julkipano :anto :lainvoimainen
                                  :voimassa :muutoksenhaku :aloitettava
                                  :automatic-verdict-dates
                                  :verdict-section :boardname
                                  :handler :paatosteksti :foremen
                                  :foremen-included :plans
                                  :plans-included :reviews :reviews-included
                                  :conditions.condition
                                  :conditions.remove-condition
                                  :add-condition :deviations
                                  :attachments :buildings.rakennetut-autopaikat
                                  :buildings.kiinteiston-autopaikat
                                  :buildings.autopaikat-yhteensa
                                  :buildings.vss-luokka :buildings.paloluokka
                                  :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data {}))
   (fact "init upload: upload checked"
     (init--upload (draftee [] :upload true))
     => (check-draft :inclusions [:julkipano :anto :lainvoimainen
                                  :voimassa :muutoksenhaku :aloitettava
                                  :handler :paatosteksti :foremen
                                  :foremen-included :plans
                                  :verdict-section :boardname
                                  :automatic-verdict-dates
                                  :plans-included :reviews :reviews-included
                                  :conditions.condition
                                  :conditions.remove-condition
                                  :add-condition :deviations
                                  :attachments :upload
                                  :buildings.rakennetut-autopaikat
                                  :buildings.kiinteiston-autopaikat
                                  :buildings.autopaikat-yhteensa
                                  :buildings.vss-luokka :buildings.paloluokka
                                  :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data {}))
   (fact "init upload: upload checked, attachments removed"
     (init--upload (draftee [:attachments] :upload true))
     => (check-draft :inclusions [:julkipano :anto :lainvoimainen
                                  :voimassa :muutoksenhaku :aloitettava
                                  :automatic-verdict-dates
                                  :handler :paatosteksti :foremen
                                  :foremen-included :plans
                                  :plans-included :reviews :reviews-included
                                  :conditions.condition
                                  :verdict-section :boardname
                                  :conditions.remove-condition
                                  :add-condition :deviations
                                  :buildings.rakennetut-autopaikat
                                  :buildings.kiinteiston-autopaikat
                                  :buildings.autopaikat-yhteensa
                                  :buildings.vss-luokka :buildings.paloluokka
                                  :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data {}))
   (fact "init upload: upload unchecked, attachments removed"
     (init--upload (draftee [:attachments] :upload false))
     => (check-draft :inclusions [:julkipano :anto :lainvoimainen
                                  :voimassa :muutoksenhaku :aloitettava
                                  :automatic-verdict-dates
                                  :handler :paatosteksti :foremen
                                  :foremen-included :plans
                                  :plans-included :reviews :reviews-included
                                  :conditions.condition
                                  :conditions.remove-condition
                                  :add-condition :deviations
                                  :verdict-section :boardname
                                  :buildings.rakennetut-autopaikat
                                  :buildings.kiinteiston-autopaikat
                                  :buildings.autopaikat-yhteensa
                                  :buildings.vss-luokka :buildings.paloluokka
                                  :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data {}))
   (fact "init verdict giver type: viranhaltija"
     (init--verdict-giver-type (draftee [:conditions :deviations
                                         :attachments :buildings]))
     => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                  :lainvoimainen :voimassa :aloitettava
                                  :handler :paatosteksti :foremen
                                  :foremen-included :plans
                                  :automatic-verdict-dates
                                  :plans-included :reviews
                                  :reviews-included :start-date :end-date]
                     :giver "viranhaltija"
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data {}))
   (fact "init verdict giver type: lautakunta"
     (init--verdict-giver-type (draftee [:conditions :deviations
                                         :attachments :buildings]
                                        :giver true))
     => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                  :lainvoimainen :voimassa :aloitettava
                                  :handler :paatosteksti :foremen
                                  :foremen-included :plans
                                  :verdict-section :boardname
                                  :automatic-verdict-dates
                                  :plans-included :reviews
                                  :reviews-included :start-date :end-date]
                     :giver "lautakunta"
                     :references {:boardname    "Gate is boarding"
                                  :verdict-code ["osittain-myonnetty"]}
                     :data {}))
   (fact "init by application: handler - no handler"
     (init--dict-by-application (assoc (draftee [:conditions :deviations
                                                 :attachments :buildings])
                                       :application {})
                                :handler general-handler)
     => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                  :lainvoimainen :voimassa :aloitettava
                                  :handler :paatosteksti :foremen
                                  :foremen-included :plans
                                  :automatic-verdict-dates
                                  :verdict-section :boardname
                                  :plans-included :reviews
                                  :reviews-included :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data {:handler ""}))
   (fact "init by application: handler - Bob Builder"
     (init--dict-by-application (assoc (draftee [:conditions :deviations
                                                 :attachments :buildings])
                                       :application {:handlers [{:general   true
                                                                 :firstName "Bob"
                                                                 :lastName  "Builder"}]})
                                :handler general-handler)
     => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                  :lainvoimainen :voimassa :aloitettava
                                  :handler :paatosteksti :foremen
                                  :foremen-included :plans
                                  :automatic-verdict-dates
                                  :verdict-section :boardname
                                  :plans-included :reviews
                                  :reviews-included :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data {:handler "Bob Builder"}))
   (fact "init by application: deviations - no deviations"
     (init--dict-by-application (assoc (draftee [:conditions :attachments
                                                 :buildings])
                                       :application {:documents []})
                                :deviations application-deviations)
     => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                  :lainvoimainen :voimassa :aloitettava
                                  :handler :paatosteksti :foremen
                                  :foremen-included :plans
                                  :automatic-verdict-dates
                                  :verdict-section :boardname
                                  :plans-included :reviews
                                  :reviews-included :deviations
                                  :start-date :end-date]
                     :references {:verdict-code ["osittain-myonnetty"]}
                     :data {:deviations ""}))
      (fact "init by application: deviations - Cannot live by your rules, man!"
        (init--dict-by-application (assoc (draftee [:conditions :attachments :buildings])
                                          :application
                                          {:documents
                                           [{:schema-info {:name "hankkeen-kuvaus"}
                                             :data        {:poikkeamat {:value "Cannot live by your rules, man!"}}}]})
                                   :deviations application-deviations)
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :automatic-verdict-dates
                                     :plans-included :reviews
                                     :verdict-section :boardname
                                     :reviews-included :deviations
                                     :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {:deviations "Cannot live by your rules, man!"}))
      (fact "init by application: deviations - template section removed"
        (init--dict-by-application (assoc (draftee [:conditions :attachments
                                                    :deviations :buildings])
                                          :application
                                          {:documents
                                           [{:schema-info {:name "hankkeen-kuvaus"}
                                             :data        {:poikkeamat {:value "Cannot live by your rules, man!"}}}]})
                                   :deviations application-deviations)
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :verdict-section :boardname
                                     :automatic-verdict-dates
                                     :plans-included :reviews
                                     :reviews-included :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {}))
      (fact "init by application: operation - dict not included"
        (init--dict-by-application (assoc (draftee [:conditions :attachments
                                                    :deviations :buildings])
                                          :application
                                          {:documents
                                           [{:schema-info {:name    "hankkeen-kuvaus"
                                                           :subtype "hankkeen-kuvaus"}
                                             :data        {:kuvaus {:value "Co-operation"}}}]})
                                   :operation application-operation)
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :verdict-section :boardname
                                     :automatic-verdict-dates
                                     :plans-included :reviews
                                     :reviews-included :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {}))
      (fact "init by application: operation - dict included, R application"
        (init--dict-by-application (assoc (draftee [:conditions :attachments
                                                    :deviations :buildings])
                                          :application
                                          {:documents
                                           [{:schema-info {:name    "hankkeen-kuvaus"
                                                           :subtype "hankkeen-kuvaus"}
                                             :data        {:kuvaus {:value "Co-operation"}}}]})
                                   :handler application-operation)
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :verdict-section :boardname
                                     :automatic-verdict-dates
                                     :plans-included :reviews
                                     :reviews-included :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {:handler "Co-operation"}))
      (fact "init by application: operation - dict included, YA application"
        (init--dict-by-application (assoc (draftee [:conditions :attachments
                                                    :deviations :buildings])
                                          :application
                                          {:documents
                                           [{:schema-info {:name    "hankkeen-kuvaus-ya"
                                                           :subtype "hankkeen-kuvaus"}
                                             :data        {:kayttotarkoitus {:value "Co-operation"}}}]})
                                   :handler application-operation)
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :verdict-section :boardname
                                     :automatic-verdict-dates
                                     :plans-included :reviews
                                     :reviews-included :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {:handler "Co-operation"}))
      (fact "init by application: operation - dict included, no document"
        (init--dict-by-application (assoc (draftee [:conditions :attachments
                                                    :deviations :buildings])
                                          :application
                                          {:documents
                                           [{:schema-info {:name "some-document"}
                                             :data        {:kayttotarkoitus {:value "Co-operation"}}}]})
                                   :handler application-operation)
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :verdict-section :boardname
                                     :automatic-verdict-dates
                                     :plans-included :reviews
                                     :reviews-included :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {:handler ""}))
      (fact "init buildings: no buildings, no template data"
        (init--buildings (draftee [:conditions :deviations :attachments :buildings]))
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa
                                     :automatic-verdict-dates :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :plans-included :reviews
                                     :verdict-section :boardname
                                     :reviews-included :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {}))
      (fact "init buildings: no buildings, template data"
        (init--buildings (draftee [:conditions :deviations :attachments :buildings]
                                  :autopaikat true))
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :automatic-verdict-dates
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :verdict-section :boardname
                                     :plans-included :reviews
                                     :reviews-included :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {}))
      (fact "init buildings: buildings, no template data"
        (init--buildings (draftee [:conditions :deviations :attachments]))
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :verdict-section :boardname
                                     :automatic-verdict-dates
                                     :plans-included :reviews
                                     :reviews-included :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {}))
      (fact "init buildings: buildings, autopaikat"
        (init--buildings (draftee [:conditions :deviations :attachments]
                                  :autopaikat true))
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :plans-included :reviews
                                     :reviews-included
                                     :verdict-section :boardname
                                     :automatic-verdict-dates
                                     :buildings.rakennetut-autopaikat
                                     :buildings.kiinteiston-autopaikat
                                     :buildings.autopaikat-yhteensa
                                     :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {}))
      (fact "init buildings: buildings, vss-luokka and paloluokka"
        (init--buildings (draftee [:conditions :deviations :attachments]
                                  :vss-luokka true
                                  :paloluokka true))
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :verdict-section :boardname
                                     :plans-included :reviews
                                     :reviews-included
                                     :automatic-verdict-dates
                                     :buildings.vss-luokka
                                     :buildings.paloluokka
                                     :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {}))
      (fact "init permit period: no template section"
        (init--permit-period (assoc (draftee [:conditions :deviations :attachments
                                              :buildings :random])
                                    :application {:documents [{:schema-info {:name "tyoaika"}
                                                               :data        {:tyoaika-alkaa-ms   {:value 12345}
                                                                             :tyoaika-paattyy-ms {:value 54321}}}]}))
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :verdict-section :boardname
                                     :automatic-verdict-dates
                                     :plans-included :reviews
                                     :reviews-included]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {}))
      (fact "init permit period: all OK"
        (init--permit-period (assoc (draftee [:conditions :deviations :attachments
                                              :buildings])
                                    :application {:documents [{:schema-info {:name "tyoaika"}
                                                               :data        {:tyoaika-alkaa-ms   {:value 12345}
                                                                             :tyoaika-paattyy-ms {:value 54321}}}]}))
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :verdict-section :boardname
                                     :automatic-verdict-dates
                                     :plans-included :reviews
                                     :reviews-included
                                     :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {:start-date 12345 :end-date 54321}))
      (fact "init permit period: not integer :tyoaika-alkaa-ms"
        (init--permit-period (assoc (draftee [:conditions :deviations :attachments
                                              :buildings])
                                    :application {:documents [{:schema-info {:name "tyoaika"}
                                                               :data        {:tyoaika-alkaa-ms   {:value ""}
                                                                             :tyoaika-paattyy-ms {:value 54321}}}]}))
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :verdict-section :boardname
                                     :automatic-verdict-dates
                                     :plans-included :reviews
                                     :reviews-included
                                     :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {:end-date 54321}))
      (fact "init permit period: not-integer :tyoaika-paattyy-ms"
        (init--permit-period (assoc (draftee [:conditions :deviations :attachments
                                              :buildings])
                                    :application {:documents [{:schema-info {:name "tyoaika"}
                                                               :data        {:tyoaika-alkaa-ms {:value 12345}}}]}))
        => (check-draft :inclusions [:julkipano :anto :muutoksenhaku
                                     :lainvoimainen :voimassa :aloitettava
                                     :handler :paatosteksti :foremen
                                     :foremen-included :plans
                                     :verdict-section :boardname
                                     :automatic-verdict-dates
                                     :plans-included :reviews
                                     :reviews-included
                                     :start-date :end-date]
                        :references {:verdict-code ["osittain-myonnetty"]}
                        :data {:start-date 12345}))
      (fact "initialize-verdict-draft"
        (let [init (initialize-verdict-draft
                    (assoc (assoc-in (draftee [:foremen]
                                              :paatosteksti "This is verdict."
                                              :conditions [{:condition "Stay calm"}
                                                           {:condition "Carry on"}]
                                              :verdict-dates ["anto" "voimassa"]
                                              :vastaava-tj true
                                              :vastaava-tj-included true
                                              :tj false
                                              :tj-included true
                                              :vss-luokka true
                                              :autopaikat true)
                                     [:template :published :settings :reviews]
                                     [{:fi "foo" :sv "foo" :en "foo" :selected true}
                                      {:fi "bar" :sv "bar" :en "bar"}])
                           :application
                           {:handlers [{:general   true
                                        :firstName "Bob"
                                        :lastName  "Builder"}]
                            :documents
                            [{:schema-info {:name "hankkeen-kuvaus"}
                              :data        {:poikkeamat {:value "Cannot live by your rules, man!"}}}]}))]
          init => (check-draft :inclusions [:anto :voimassa :paatosteksti :foremen
                                            :foremen-included
                                            :automatic-verdict-dates
                                            :reviews :reviews-included :handler
                                            :deviations :conditions.condition
                                            :add-condition :conditions.remove-condition
                                            :attachments
                                            :buildings.rakennetut-autopaikat
                                            :buildings.kiinteiston-autopaikat
                                            :buildings.autopaikat-yhteensa
                                            :buildings.vss-luokka
                                            :start-date :end-date]
                               :giver       "viranhaltija"
                               :references {:verdict-code ["osittain-myonnetty"]
                                            :foremen      (just ["vastaava-tj" "tj"] :in-any-order)
                                            :reviews      (just [(just {:id mongo-id?
                                                                        :fi "foo"
                                                                        :sv "foo"
                                                                        :en "foo"})
                                                                 (just {:id mongo-id?
                                                                        :fi "bar"
                                                                        :sv "bar"
                                                                        :en "bar"})]
                                                                :in-any-order)})
          (-> init :draft :data
              :conditions vec flatten) => (just [mongo-id? {:condition "Stay calm"}
                                                 mongo-id? {:condition "Carry on"}])
          (-> init :draft :data
              (dissoc :conditions)) => (just {:foremen-included false
                                              :foremen          ["vastaava-tj"]
                                              :reviews-included true
                                              :reviews          (just [mongo-id?])
                                              :deviations       "Cannot live by your rules, man!"
                                              :handler          "Bob Builder"
                                              :paatosteksti     "This is verdict."})
          (fact "select-inclusions"
            (vc/select-inclusions (:dictionary mini-verdict)
                               [:deviations :buildings.paloluokka :foremen-included])
            => {:deviations       {:phrase-text      {:category :yleinen}
                                   :template-section :deviations}
                :buildings        {:repeating        {:paloluokka {:text {}}}
                                   :template-section :buildings}
                :foremen-included {:toggle {}}})))))

(def wrap (partial metadata/wrap "user" 12345))

(facts "archive-info"
  (let [verdict {:id             "5ac78d3e791c066eef7198a2"
                 :category       "r"
                 :schema-version 1
                 :state          (wrap "draft")
                 :modified       12345
                 :data           {:handler       "Hank Handler"
                                  :verdict-date  8765432
                                  :handler-title "Bossman"}
                 :references     {:boardname    "The Board"
                                  :verdict-code ["myonnetty"]
                                  :date-deltas  {:julkipano     {:delta 1 :unit "days"}
                                                 :anto          {:delta 2 :unit "days"}
                                                 :muutoksenhaku {:delta 3 :unit "days"}
                                                 :lainvoimainen {:delta 4 :unit "days"}
                                                 :aloitettava   {:delta 1 :unit "years"}
                                                 :voimassa      {:delta 2 :unit "years"}}}
                 :template       {:inclusions []}}]
    (fact "Board verdict"
      (let [v (assoc-in verdict [:template :giver] "lautakunta")]
        (fact "Valid according to schema"
          (sc/check schemas/PateVerdict v) => nil)
        (fact "archive info (without lainvoimainen)"
          (let [archive (archive-info v)]
            archive => {:verdict-date  8765432
                        :verdict-giver "The Board"}
            (fact "Archive schema validation"
              (sc/check schemas/PateVerdict (assoc v :archive archive))
              => nil)))
        (fact "archive info (with lainvoimainen)"
          (let [archive (archive-info (assoc-in v [:data :lainvoimainen] 99999))]
            archive => {:verdict-date  8765432
                        :lainvoimainen 99999
                        :verdict-giver "The Board"}
           (fact "Archive schema validation"
             (sc/check schemas/PateVerdict (assoc v :archive archive))
             => nil)))))
    (fact "Authority verdict"
      (let [v (assoc-in verdict [:template :giver] "viranhaltija")]
        (fact "Valid according to schema"
          (sc/check schemas/PateVerdict v) => nil)
        (fact "archive info (without lainvoimainen)"
          (archive-info v)
          => {:verdict-date  8765432
              :verdict-giver "Bossman Hank Handler"})
        (fact "archive info (with lainvoimainen)"
          (archive-info (assoc-in v [:data :lainvoimainen] 99999))
          => {:verdict-date  8765432
              :lainvoimainen 99999
              :verdict-giver "Bossman Hank Handler"})
        (fact "archive info (no title)"
          (archive-info (-> v
                            (assoc-in [:data :lainvoimainen] 99999)
                            (assoc-in [:data :handler-title] "")))
          => {:verdict-date  8765432
              :lainvoimainen 99999
              :verdict-giver "Hank Handler"}
          (archive-info (assoc-in v [:data :handler-title] nil))
          => {:verdict-date  8765432
              :verdict-giver "Hank Handler"})))))

(facts "continuation verdict"
  (fact "accepted-verdict?" ; TODO Which verdict codes are accepted??
    (accepted-verdict? {:data {:verdict-code "hyvaksytty"}}) => :hyvaksytty
    (accepted-verdict? {:data {:verdict-code "myonnetty"}}) => :myonnetty
    (accepted-verdict? {:data {:verdict-code "evatty"}}) => nil
    (accepted-verdict? {:data {}}) => nil
    (accepted-verdict? nil) => nil))

(defn publish [verdict ts]
  (-> verdict
      (assoc :state (metadata/wrap "publisher" ts "published"))
      (assoc-in [:published :published] ts)))

(facts "Verdict handling helpers"
  (fact "Find latest published pate verdict"
    (vif/latest-published-pate-verdict {:application {}})
    => nil
    (vif/latest-published-pate-verdict {:application
                                        {:pate-verdicts
                                         [(publish test-verdict 1525336290167)]}})
    => (metadata/unwrap-all (publish test-verdict 1525336290167))
    (vif/latest-published-pate-verdict {:application
                                        {:pate-verdicts
                                         [test-verdict
                                          (publish test-verdict 1525336290167)
                                          (publish test-verdict 1425330000000)
                                          (publish test-verdict 1525336290000)]}})
    => (metadata/unwrap-all (publish test-verdict 1525336290167))))

(fact "title-fn"
  (let [fun (partial format "(%s)")]
    (title-fn " hello " fun) => "(hello)"
    (title-fn " " fun) => ""
    (title-fn nil fun) => ""
    (title-fn 1 fun) => "(1)"))

(facts "verdict-string"
  (fact "legacy verdict-code"
    (verdict-string "fi"
                    {:legacy? true
                     :category "r"
                     :data {:verdict-code "8"}
                     :template {:inclusions ["verdict-code"]}}
                    :verdict-code)
    => "Ty\u00f6h\u00f6n liittyy ehto")
  (fact "modern verdict-code"
    (verdict-string "en"
                    {:category "r"
                     :data {:verdict-code "hallintopakko"}
                     :template {:inclusions ["verdict-code"]}}
                    :verdict-code)
    => "Administrative enforcement/penalty proceedings discontinued.")
  (fact "verdict-type"
    (verdict-string "fi"
                    {:category "ya"
                     :data {:verdict-type "katulupa"}
                     :template {:inclusions ["verdict-type"]}}
                    :verdict-type)
    => "Katulupa"))

(fact "verdict-section-string"
  (verdict-section-string {:category "r" :data {:verdict-section " 22 "}}) => "\u00a722"
  (verdict-section-string {:category "r" :data {:verdict-section ""}}) => ""
  (verdict-section-string {:category "r" :data {:verdict-section "\u00a722"}}) => "\u00a722")


(facts "Verdict summary"
  (let [section-strings    {"v1" "\u00a71" "v2" "\u00a72"}
        verdict            {:id         "v1"
                            :category   "r"
                            :data       {:verdict-code    "ehdollinen"
                                         :handler         "Foo Bar"
                                         :verdict-section "1"
                                         :verdict-date    876543}
                            :modified   12345
                            :template   {:inclusions ["verdict-code"
                                                      "handler"
                                                      "verdict-date"
                                                      "verdict-section"]
                                         :giver      "viranhaltija"}
                            :references {:boardname "Broad board abroad"}}
        backend-verdict    {:kuntalupatunnus "13-0185-R"
                            :paatokset       [{:paivamaarat {:aloitettava      1377993600000
                                                             :lainvoimainen    1378080000000
                                                             :voimassaHetki    1378166400000
                                                             :raukeamis        1378252800000
                                                             :anto             1378339200000
                                                             :viimeinenValitus 1536192000000
                                                             :julkipano        1378512000000}
                                               :poytakirjat [{:paatoskoodi     "myönnetty"
                                                              :paatospvm       112233
                                                              :pykala          "1"
                                                              :paatoksentekija "viranomainen"
                                                              :paatos          "Päätös 1"
                                                              :status          "1"
                                                              :urlHash         "236d9b2cfff88098d4f8ad532820c9fb93393237"}
                                                             {:paatoskoodi     "ehdollinen"
                                                              :paatospvm       998877
                                                              :pykala          "2"
                                                              :paatoksentekija "Mölli Keinonen"
                                                              :status          "6"
                                                              :urlHash         "b55ae9c30533428bd9965a84106fb163611c1a7d"}]
                                               :id          "5b99044bfb2de0f550b64e44"}
                                              {:paivamaarat {:anto 1378339200000}
                                               :poytakirjat [{:paatoskoodi     "myönnetty"
                                                              :paatospvm       445566
                                                              :paatoksentekija "johtava viranomainen"
                                                              :paatos          "Päätös 2"
                                                              :status          "1"}
                                                             {:paatospvm nil}
                                                             {}
                                                             nil]
                                               :id          "5b99044cfb2de0f550b64e4f"}]
                            :id              "backend-id"
                            :draft           false
                            :timestamp       12345}
        ya-backend-verdict (-> backend-verdict
                               (assoc :kuntalupatunnus "14-0185-YA")
                               (update :paatokset (comp vec butlast))
                               (update-in [:paatokset 0 :poytakirjat] (fn [pks]
                                                                        (map #(dissoc % :paatospvm) pks)))
                               (assoc-in [:paatokset 0 :paivamaarat :paatosdokumentinPvm] 220033))]

    (fact "Nil"
      (vc/draft? nil) => false
      (vc/published? nil) => false
      (vc/draft? {}) => false
      (vc/published? {}) => true
      (vc/verdict-summary "fi" nil nil)
      => {:category  "backing-system"
          :legacy?   false
          :proposal? false
          :title     "Luonnos"})
    (fact "Draft"
      (vc/draft? verdict) => true
      (vc/published? verdict) => false
      (vc/verdict-summary "fi" section-strings verdict)
      => {:id           "v1"
          :category     "r"
          :legacy?      false
          :modified     12345
          :proposal?    false
          :giver        "Foo Bar"
          :verdict-date 876543
          :title        "Luonnos"})
    (fact "Board draft"
      (vc/verdict-summary "fi" section-strings
                          (assoc-in verdict [:template :giver] "lautakunta"))
      => {:id           "v1"
          :category     "r"
          :legacy?      false
          :modified     12345
          :proposal?    false
          :giver        "Broad board abroad"
          :verdict-date 876543
          :title        "Luonnos"})
    (fact "Verdict proposal"
      (vc/verdict-summary "fi" section-strings
                          (-> verdict
                              (assoc-in [:template :giver] "lautakunta")
                              (assoc :state "proposal")))
      => {:id           "v1"
          :category     "r"
          :legacy?      false
          :modified     12345
          :proposal?    true
          :giver        "Broad board abroad"
          :verdict-date 876543
          :title        "P\u00e4\u00e4t\u00f6sehdotus"})
    (fact "Replacement draft"
      (vc/verdict-summary "fi" section-strings
                          (assoc-in verdict [:replacement :replaces] "v2"))
      => {:id           "v1"
          :category     "r"
          :legacy?      false
          :modified     12345
          :proposal?    false
          :giver        "Foo Bar"
          :verdict-date 876543
          :replaces     "v2"
          :title        "Luonnos (korvaa p\u00e4\u00e4t\u00f6ksen \u00a72)"})
    (fact "Published"
      (let [verdict (publish verdict 121212)]
        (vc/draft? verdict) => false
        (vc/published? verdict) => true
        (vc/verdict-summary "fi" section-strings verdict))
      => {:id           "v1"
          :category     "r"
          :legacy?      false
          :modified     12345
          :proposal?    false
          :published    121212
          :giver        "Foo Bar"
          :verdict-date 876543
          :title        "\u00a71 Ehdollinen"})
    (fact "Published, no section"
      (let [verdict (-> verdict
                        (publish 121212)
                        (assoc-in [:data :verdict-section] nil))]
        (vc/draft? verdict) => false
        (vc/published? verdict) => true
        (vc/verdict-summary "fi" {} verdict))
      => {:id           "v1"
          :category     "r"
          :legacy?      false
          :modified     12345
          :proposal?    false
          :published    121212
          :giver        "Foo Bar"
          :verdict-date 876543
          :title        "Ehdollinen"})
    (fact "Published replacement"
      (vc/verdict-summary "fi" section-strings
                          (-> verdict
                              (publish 121212)
                              (assoc-in [:replacement :replaces] "v2")))
      => {:id           "v1"
          :category     "r"
          :legacy?      false
          :modified     12345
          :proposal?    false
          :published    121212
          :giver        "Foo Bar"
          :verdict-date 876543
          :replaces     "v2"
          :title        "\u00a71 Ehdollinen (korvaa p\u00e4\u00e4t\u00f6ksen \u00a72)"})
    (fact "Published replacement, no section"
      (vc/verdict-summary "fi" (dissoc section-strings "v2")
                          (-> verdict
                              (publish 121212)
                              (assoc-in [:replacement :replaces] "v2")))
      => {:id           "v1"
          :category     "r"
          :legacy?      false
          :modified     12345
          :proposal?    false
          :published    121212
          :giver        "Foo Bar"
          :verdict-date 876543
          :replaces     "v2"
          :title        "\u00a71 Ehdollinen (korvaava p\u00e4\u00e4t\u00f6s)"})
    (fact "Legacy draft"
      (let [verdict (-> verdict
                        (assoc :legacy? true)
                        (assoc-in [:data :anto] 98765))]
        (vc/draft? verdict) => true
        (vc/published? verdict) => false
        (vc/verdict-summary "fi" section-strings verdict))
      => {:id           "v1"
          :category     "r"
          :legacy?      true
          :modified     12345
          :proposal?    false
          :giver        "Foo Bar"
          :verdict-date 98765
          :title        "Luonnos"})
    (fact "Legacy published"
      (let [verdict (-> verdict
                        (publish 676767)
                        (assoc :legacy? true)
                        (assoc-in [:data :anto] 98765)
                        (assoc-in [:data :verdict-code] "41"))]
        (vc/draft? verdict) => false
        (vc/published? verdict) => true
        (vc/verdict-summary "fi" section-strings verdict))
      => {:id           "v1"
          :category     "r"
          :legacy?      true
          :modified     12345
          :proposal?    false
          :published    676767
          :giver        "Foo Bar"
          :verdict-date 98765
          :title        "\u00a71 Ilmoitus merkitty tiedoksi"})
    (fact "latest-pk"
      (vc/latest-pk backend-verdict)
      => {:paatoskoodi     "ehdollinen"
          :paatospvm       998877
          :pykala          "2"
          :paatoksentekija "Mölli Keinonen"
          :status          "6"
          :urlHash         "b55ae9c30533428bd9965a84106fb163611c1a7d"})
    (fact "Backend verdit"
      (vc/draft? backend-verdict) => false
      (vc/published? backend-verdict) => true
      (vc/verdict-summary "fi" section-strings backend-verdict)
      => {:id           "backend-id"
          :category     "backing-system"
          :legacy?      false
          :modified     12345
          :proposal?    false
          :published    12345
          :giver        "Mölli Keinonen"
          :verdict-date 998877
          :title        "ehdollinen"})
    (fact "YA backend verdit"
      (vc/draft? ya-backend-verdict) => false
      (vc/draft? (assoc ya-backend-verdict :draft true)) => true
      (vc/published? ya-backend-verdict) => true
      (vc/verdict-summary "fi" section-strings ya-backend-verdict)
      => {:id           "backend-id"
          :category     "backing-system"
          :legacy?      false
          :modified     12345
          :proposal?    false
          :published    12345
          :giver        "Mölli Keinonen"
          :verdict-date 220033
          :title        "ehdollinen"})
    (facts "YA verdict-type"
      (let [verdict (-> verdict
                        (assoc :category "ya")
                        (assoc-in [:data :verdict-code] "annettu-lausunto")
                        (assoc-in [:data :verdict-type] "sijoituslupa")
                        (update-in [:template :inclusions] conj "verdict-type"))]
        (fact "Draft"
          (vc/verdict-summary "fi" section-strings verdict)
          => {:id           "v1"
              :category     "ya"
              :legacy?      false
              :modified     12345
              :proposal?    false
              :giver        "Foo Bar"
              :verdict-date 876543
              :title        "Luonnos"})
        (fact "Published"
          (vc/verdict-summary "fi" section-strings
                              (publish verdict 656565))
          => {:id           "v1"
              :category     "ya"
              :legacy?      false
              :modified     12345
              :proposal?    false
              :published    656565
              :giver        "Foo Bar"
              :verdict-date 876543
              :title        "\u00a71 Sijoituslupa - Annettu lausunto"})
        (fact "Published replacement"
          (vc/verdict-summary "fi" section-strings
                              (assoc (publish verdict 656565)
                                     :replacement {:replaces "v2"}))
          => {:id           "v1"
              :category     "ya"
              :legacy?      false
              :modified     12345
              :proposal?    false
              :published    656565
              :giver        "Foo Bar"
              :verdict-date 876543
              :replaces     "v2"
              :title        "\u00a71 Sijoituslupa - Annettu lausunto (korvaa p\u00e4\u00e4t\u00f6ksen \u00a72)"})))))

(defn make-verdict [& kvs]
  (let [{:keys [id code section modified published replaces]} (apply hash-map kvs)]
    {:id          id
     :category    "r"
     :schema-version 1
     :data        {:verdict-code    code
                   :handler         "Foo Bar"
                   :verdict-section section
                   :verdict-date    876543}
     :modified    (or published modified 1)
     :published   (when published {:published published})
     :replacement (when replaces {:replaces replaces})
     :template    {:inclusions ["verdict-code"
                                "handler"
                                "verdict-date"
                                "verdict-section"
                                "verdict-text"]
                   :giver      "viranhaltija"}
     :references  {:boardname "Broad board abroad"}}))

(facts "verdict-list"
  (let [v1 (make-verdict :id "v1" :code "ei-tutkittu-1" :section "11" :published 10)
        v2 (make-verdict :id "v2" :code "ei-lausuntoa" :section "" :published 20 :replaces "v1")
        v3 (make-verdict :id "v3" :code "asiakirjat palautettu" :section "33" :modified 30 :replaces "v2")
        v4 (make-verdict :id "v4" :code "ei-puollettu" :section "44" :published 25)]
    (vc/verdict-list {:lang        "fi"
                      :application {:pate-verdicts [v1 v2 v3 v4]
                                    :permitType    "R"}})
    => (just [(contains {:id       "v3"
                         :modified 30
                         :title    "Luonnos (korvaava p\u00e4\u00e4t\u00f6s)"})
              (contains {:id        "v2"
                         :modified  20
                         :replaced? true
                         :title     "Ei lausuntoa (korvaa p\u00e4\u00e4t\u00f6ksen \u00a711)"})
              (contains {:id        "v1"
                         :modified  10
                         :replaced? true
                         :title     "\u00a711 Ei tutkittu"})
              (contains {:id       "v4"
                         :modified 25
                         :title    "\u00a744 Ei puollettu"})])))

(def rakennusjateselvitys
  {:id "12345bb8e7f60415804219af"
   :created 1514376120409
   :schema-info {:name "rakennusjateselvitys"
                 :order 201
                 :editable-in-states ["archived"
                                      "closed"
                                      "foremanVerdictGiven"
                                      "constructionStarted"
                                      "agreementPrepared"
                                      "appealed"
                                      "extinct"
                                      "inUse"
                                      "final"
                                      "finished"
                                      "agreementSigned"
                                      "acknowledged"
                                      "verdictGiven"
                                      "onHold"]
                 :section-help "rakennusjate.help"
                 :blacklist ["neighbor"]
                 :version 1}
   :data {:rakennusJaPurkujate {:suunniteltuJate {:0 {:jatetyyppi {:value "puu"
                                                                   :modified nil}
                                                      :suunniteltuMaara {:value "2"
                                                                         :modified nil}
                                                      :yksikko {:value "m3"
                                                                :modified nil}
                                                      :painoT {:value "05"
                                                               :modified nil}}
                                                  :1 {:jatetyyppi {:value "betoni"
                                                                   :modified nil}
                                                      :painoT {:value "02"
                                                               :modified nil}
                                                      :yksikko {:value "m3"
                                                                :modified nil}
                                                      :suunniteltuMaara {:value "005"
                                                                         :modified nil}}
                                                   :2 {:jatetyyppi {:value "muovi"
                                                                    :modified nil}
                                                       :painoT {:value "005"
                                                                :modified nil}
                                                       :suunniteltuMaara {:value "001"
                                                                          :modified nil}
                                                       :yksikko {:value "m3"
                                                                 :modified nil}}}
                                :suunnittelematonJate {:0 {:jatetyyppi {:value nil}
                                                           :toteutunutMaara {:value ""}
                                                           :yksikko {:value nil}
                                                           :painoT {:value ""}
                                                           :jatteenToimituspaikka {:value ""}}}}
          :vaarallisetAineet {:suunniteltuJate {}
                              :suunnittelematonJate {:0 {:vaarallinenainetyyppi {:value nil}
                                                         :toteutunutMaara {:value ""}
                                                         :yksikko {:value nil}
                                                         :painoT {:value ""}
                                                         :jatteenToimituspaikka {:value ""}}}}
          :contact {:name {:value ""}
                    :phone {:value ""}
                    :email {:value ""}}
          :availableMaterials {:0 {:aines {:value ""}
                                   :maara {:value ""}
                                   :yksikko {:value nil}
                                   :saatavilla {:value nil}
                                   :kuvaus {:value ""}}}}})

(def application
  {:address "Latokuja 3",
   :primaryOperation {:id "5b34a9d2cea1d0f410db2403",
                      :name "sisatila-muutos",
                      :description nil,
                      :created 1530178002437},
   :buildings [],
   :comments [],
   :secondaryOperations [],
   :attachments [],
   :history [{:state "sent",
              :ts 1200,
              :user {:id "777777777777777777000023",
                     :username "sonja",
                     :firstName "Sonja",
                     :lastName "Sibbo",
                     :role "authority"}}],
   :operation-name "sisatila-muutos",
   :state "sent",
   :permitType "R",
   :organization "753-R",
   :modified 1530179094764,
   :documents '({:id "5b34a9d3cea1d0f410db2404",
                 :schema-info {:name "rakennuksen-muuttaminen",
                               :op {:id "5b34a9d2cea1d0f410db2403",
                                    :name "sisatila-muutos",
                                    },
                               },
                 :data {:buildingId {:value "199887766E",
                                     :source nil,
                                     :sourceValue "199887766E",
                                     :modified 1530179094764},
                        :muutostyolaji {:value "rakennukse p\u00E4\u00E4asiallinen k\u00E4ytt\u00F6tarkoitusmuutos",
                                        :modified 1530179094764,
                                        :source nil,
                                        :sourceValue "rakennukse p\u00E4\u00E4asiallinen k\u00E4ytt\u00F6tarkoitusmuutos"},
                        :rakennuksenOmistajat {:0 {:_selected {:value "yritys",
                                                               :source "krysp",
                                                               :sourceValue "yritys",
                                                               :modified 1530179094764},
                                                   :henkilo {:userId {:value nil,
                                                                      :source "krysp",
                                                                      :sourceValue nil,
                                                                      :modified 1530179094764},
                                                             :henkilotiedot {:etunimi {:value "",
                                                                                       :modified 1530179094764,
                                                                                       :sourceValue "",
                                                                                       :source "krysp"},
                                                                             :sukunimi {:value "",
                                                                                        :sourceValue "",
                                                                                        :source "krysp",
                                                                                        :modified 1530179094764},
                                                                             :hetu {:value nil,
                                                                                    :sourceValue nil,
                                                                                    :modified 1530179094764,
                                                                                    :source "krysp"},
                                                                             :turvakieltoKytkin {:value false,
                                                                                                 :modified 1530179094764,
                                                                                                 :source "krysp",
                                                                                                 :sourceValue false}},
                                                             :osoite {:katu {:value "",
                                                                             :sourceValue "",
                                                                             :modified 1530179094764,
                                                                             :source "krysp"},
                                                                      :postinumero {:value "",
                                                                                    :modified 1530179094764,
                                                                                    :sourceValue "",
                                                                                    :source "krysp"},
                                                                      :postitoimipaikannimi {:value "",
                                                                                             :sourceValue "",
                                                                                             :source "krysp",
                                                                                             :modified 1530179094764},
                                                                      :maa {:value "FIN",
                                                                            :sourceValue "FIN",
                                                                            :source "krysp",
                                                                            :modified 1530179094764}},
                                                             :yhteystiedot {:puhelin {:value "",
                                                                                      :sourceValue "",
                                                                                      :source "krysp",
                                                                                      :modified 1530179094764},
                                                                            :email {:value "",
                                                                                    :modified 1530179094764,
                                                                                    :sourceValue "",
                                                                                    :source "krysp"}},
                                                             :kytkimet {:suoramarkkinointilupa {:value false,
                                                                                                :sourceValue false,
                                                                                                :modified 1530179094764,
                                                                                                :source "krysp"}}},
                                                   :yritys {:companyId {:value nil,
                                                                        :sourceValue nil,
                                                                        :modified 1530179094764,
                                                                        :source "krysp"},
                                                            :yritysnimi {:value "Testiyritys 9242",
                                                                         :source "krysp",
                                                                         :modified 1530179094764,
                                                                         :sourceValue "Testiyritys 9242"},
                                                            :liikeJaYhteisoTunnus {:value "1234567-1",
                                                                                   :modified 1530179094764,
                                                                                   :source "krysp",
                                                                                   :sourceValue "1234567-1"},
                                                            :osoite {:katu {:value "Testikatu 1 A 9242",
                                                                            :modified 1530179094764,
                                                                            :sourceValue "Testikatu 1 A 9242",
                                                                            :source "krysp"},
                                                                     :postinumero {:value "00380",
                                                                                   :sourceValue "00380",
                                                                                   :source "krysp",
                                                                                   :modified 1530179094764},
                                                                     :postitoimipaikannimi {:value "HELSINKI",
                                                                                            :sourceValue "HELSINKI",
                                                                                            :source "krysp",
                                                                                            :modified 1530179094764},
                                                                     :maa {:value "FIN",
                                                                           :modified 1530179094764,
                                                                           :source "krysp",
                                                                           :sourceValue "FIN"}},
                                                            :yhteyshenkilo {:henkilotiedot {:etunimi {:value "",
                                                                                                      :source "krysp",
                                                                                                      :sourceValue "",
                                                                                                      :modified 1530179094764},
                                                                                            :sukunimi {:value "",
                                                                                                       :source "krysp",
                                                                                                       :modified 1530179094764,
                                                                                                       :sourceValue ""},
                                                                                            :turvakieltoKytkin {:value false,
                                                                                                                :sourceValue false,
                                                                                                                :source "krysp",
                                                                                                                :modified 1530179094764}},
                                                                            :yhteystiedot {:puhelin {:value "",
                                                                                                     :sourceValue "",
                                                                                                     :modified 1530179094764,
                                                                                                     :source "krysp"},
                                                                                           :email {:value "",
                                                                                                   :sourceValue "",
                                                                                                   :modified 1530179094764,
                                                                                                   :source "krysp"}}}},
                                                   :omistajalaji {:value nil,
                                                                  :sourceValue nil,
                                                                  :modified 1530179094764,
                                                                  :source "krysp"},
                                                   :muu-omistajalaji {:value "",
                                                                      :modified 1530179094764,
                                                                      :source "krysp",
                                                                      :sourceValue ""}},
                                               },
                        :varusteet {:viemariKytkin {:value true,
                                                    :sourceValue true,
                                                    :modified 1530179094764,
                                                    :source "krysp"},
                                    :saunoja {:value "",
                                              :modified 1530179094764,
                                              :sourceValue "",
                                              :source "krysp"},
                                    :vesijohtoKytkin {:value true,
                                                      :source "krysp",
                                                      :sourceValue true,
                                                      :modified 1530179094764},
                                    :hissiKytkin {:value false,
                                                  :source "krysp",
                                                  :modified 1530179094764,
                                                  :sourceValue false},
                                    :vaestonsuoja {:value "",
                                                   :source "krysp",
                                                   :modified 1530179094764,
                                                   :sourceValue ""},
                                    :kaasuKytkin {:value false,
                                                  :sourceValue false,
                                                  :source "krysp",
                                                  :modified 1530179094764},
                                    :aurinkopaneeliKytkin {:value false,
                                                           :modified 1530179094764,
                                                           :sourceValue false,
                                                           :source "krysp"},
                                    :liitettyJatevesijarjestelmaanKytkin {:value false,
                                                                          :modified 1530179094764,
                                                                          :source "krysp",
                                                                          :sourceValue false},
                                    :koneellinenilmastointiKytkin {:value true,
                                                                   :source "krysp",
                                                                   :sourceValue true,
                                                                   :modified 1530179094764},
                                    :sahkoKytkin {:value true,
                                                  :sourceValue true,
                                                  :source "krysp",
                                                  :modified 1530179094764},
                                    :lamminvesiKytkin {:value true,
                                                       :modified 1530179094764,
                                                       :source "krysp",
                                                       :sourceValue true}},
                        :rakennusnro {:value "002",
                                      :modified 1530179094764,
                                      :source "krysp",
                                      :sourceValue "002"},
                        :verkostoliittymat {:viemariKytkin {:value true,
                                                            :modified 1530179094764,
                                                            :sourceValue true,
                                                            :source "krysp"},
                                            :vesijohtoKytkin {:value true,
                                                              :source "krysp",
                                                              :modified 1530179094764,
                                                              :sourceValue true},
                                            :sahkoKytkin {:value true,
                                                          :source "krysp",
                                                          :sourceValue true,
                                                          :modified 1530179094764},
                                            :maakaasuKytkin {:value false,
                                                             :source "krysp",
                                                             :modified 1530179094764,
                                                             :sourceValue false},
                                            :kaapeliKytkin {:value false,
                                                            :modified 1530179094764,
                                                            :source "krysp",
                                                            :sourceValue false}},
                        :kaytto {:rakentajaTyyppi {:value nil,
                                                   :source "krysp",
                                                   :sourceValue nil,
                                                   :modified 1530179094764},
                                 :kayttotarkoitus {:value "021 rivitalot",
                                                   :source "krysp",
                                                   :sourceValue "021 rivitalot",
                                                   :modified 1530179094764}},
                        :huoneistot { :0 {:WCKytkin {:value true,
                                                     :sourceValue true,
                                                     :source "krysp",
                                                     :modified 1530179094764},
                                          :huoneistoTyyppi {:value "asuinhuoneisto",
                                                            :source "krysp",
                                                            :modified 1530179094764,
                                                            :sourceValue "asuinhuoneisto"},
                                          :keittionTyyppi {:value "keittio",
                                                           :modified 1530179094764,
                                                           :sourceValue "keittio",
                                                           :source "krysp"},
                                          :huoneistoala {:value "108",
                                                         :source "krysp",
                                                         :sourceValue "108",
                                                         :modified 1530179094764},
                                          :huoneluku {:value "4",
                                                      :sourceValue "4",
                                                      :source "krysp",
                                                      :modified 1530179094764},
                                          :jakokirjain {:value "",
                                                        :modified 1530179094764,
                                                        :sourceValue "",
                                                        :source "krysp"},
                                          :ammeTaiSuihkuKytkin {:value true,
                                                                :modified 1530179094764,
                                                                :source "krysp",
                                                                :sourceValue true},
                                          :saunaKytkin {:value true,
                                                        :sourceValue true,
                                                        :modified 1530179094764,
                                                        :source "krysp"},
                                          :huoneistonumero {:value "001",
                                                            :source "krysp",
                                                            :sourceValue "001",
                                                            :modified 1530179094764},
                                          :porras {:value "A",
                                                   :sourceValue "A",
                                                   :modified 1530179094764,
                                                   :source "krysp"},
                                          :muutostapa {:value nil,
                                                       :source nil,
                                                       :modified 1530179094764,
                                                       :sourceValue nil},
                                          :lamminvesiKytkin {:value true,
                                                             :sourceValue true,
                                                             :modified 1530179094764,
                                                             :source "krysp"},
                                          :parvekeTaiTerassiKytkin {:value true,
                                                                    :source "krysp",
                                                                    :modified 1530179094764,
                                                                    :sourceValue true}},
                                     :1 {:WCKytkin {:modified 1530179094764,
                                                    :source "krysp",
                                                    :sourceValue true,
                                                    :value true},
                                         :huoneistoTyyppi {:value "asuinhuoneisto",
                                                           :sourceValue "asuinhuoneisto",
                                                           :modified 1530179094764,
                                                           :source "krysp"},
                                         :keittionTyyppi {:sourceValue "keittio",
                                                          :source "krysp",
                                                          :modified 1530179094764,
                                                          :value "keittio"},
                                         :huoneistoala {:modified 1530179094764,
                                                        :value "106",
                                                        :source "krysp",
                                                        :sourceValue "106"},
                                         :huoneluku {:source "krysp",
                                                     :modified 1530179094764,
                                                     :sourceValue "4",
                                                     :value "4"},
                                         :ammeTaiSuihkuKytkin {:modified 1530179094764,
                                                               :sourceValue true,
                                                               :source "krysp",
                                                               :value true},
                                         :saunaKytkin {:modified 1530179094764,
                                                       :source "krysp",
                                                       :sourceValue true,
                                                       :value true},
                                         :huoneistonumero {:sourceValue "002",
                                                           :value "002",
                                                           :modified 1530179094764,
                                                           :source "krysp"},
                                         :porras {:sourceValue "A",
                                                  :source "krysp",
                                                  :value "A",
                                                  :modified 1530179094764},
                                         :lamminvesiKytkin {:value true,
                                                            :sourceValue true,
                                                            :modified 1530179094764,
                                                            :source "krysp"},
                                         :parvekeTaiTerassiKytkin {:modified 1530179094764,
                                                                   :sourceValue true,
                                                                   :source "krysp",
                                                                   :value true}},
                                     },
                        :lammitys {:lammitystapa {:value "vesikeskus",
                                                  :modified 1530179094764,
                                                  :source "krysp",
                                                  :sourceValue "vesikeskus"},
                                   :lammonlahde {:value "kevyt poltto\u00F6ljy",
                                                 :source "krysp",
                                                 :modified 1530179094764,
                                                 :sourceValue "kevyt poltto\u00F6ljy"},
                                   :muu-lammonlahde {:value "",
                                                     :modified 1530179094764,
                                                     :source "krysp",
                                                     :sourceValue ""}},
                        :kunnanSisainenPysyvaRakennusnumero {:value "",
                                                             :sourceValue "",
                                                             :source "krysp",
                                                             :modified 1530179094764},
                        :perusparannuskytkin {:value false,
                                              :sourceValue false,
                                              :source nil,
                                              :modified 1530179094764},
                        :rakennustietojaEimuutetaKytkin {:value false,
                                                         :modified 1530179094764,
                                                         :source nil,
                                                         :sourceValue false},
                        :rakenne {:rakentamistapa {:value "paikalla",
                                                   :modified 1530179094764,
                                                   :source "krysp",
                                                   :sourceValue "paikalla"},
                                  :kantavaRakennusaine {:value "tiili",
                                                        :sourceValue "tiili",
                                                        :source "krysp",
                                                        :modified 1530179094764},
                                  :muuRakennusaine {:value "",
                                                    :sourceValue "",
                                                    :source "krysp",
                                                    :modified 1530179094764},
                                  :julkisivu {:value "tiili",
                                              :sourceValue "tiili",
                                              :source "krysp",
                                              :modified 1530179094764},
                                  :muuMateriaali {:value "",
                                                  :sourceValue "",
                                                  :source "krysp",
                                                  :modified 1530179094764}},
                        :osoite {:osoitenumero2 {:value "5",
                                                 :source "krysp",
                                                 :modified 1530179094764,
                                                 :sourceValue "5"},
                                 :huoneisto {:value "",
                                             :modified 1530179094764,
                                             :sourceValue "",
                                             :source "krysp"},
                                 :jakokirjain {:value "",
                                               :sourceValue "",
                                               :source "krysp",
                                               :modified 1530179094764},
                                 :kunta {:value "245",
                                         :sourceValue "245",
                                         :source "krysp",
                                         :modified 1530179094764},
                                 :jakokirjain2 {:value "",
                                                :sourceValue "",
                                                :source "krysp",
                                                :modified 1530179094764},
                                 :postinumero {:value "04200",
                                               :source "krysp",
                                               :modified 1530179094764,
                                               :sourceValue "04200"},
                                 :porras {:value "",
                                          :source "krysp",
                                          :modified 1530179094764,
                                          :sourceValue ""},
                                 :osoitenumero {:value "3",
                                                :modified 1530179094764,
                                                :source "krysp",
                                                :sourceValue "3"},
                                 :postitoimipaikannimi {:value "KERAVA",
                                                        :source "krysp",
                                                        :modified 1530179094764,
                                                        :sourceValue "KERAVA"},
                                 :maa {:value "FIN",
                                       :sourceValue "FIN",
                                       :modified 1530179094764,
                                       :source "krysp"},
                                 :lahiosoite {:value "Kyllikintie",
                                              :modified 1530179094764,
                                              :sourceValue "Kyllikintie",
                                              :source "krysp"}},
                        :mitat {:tilavuus {:value "837",
                                           :source "krysp",
                                           :modified 1530179094764,
                                           :sourceValue "837"},
                                :kerrosala {:value "281",
                                            :source "krysp",
                                            :modified 1530179094764,
                                            :sourceValue "281"},
                                :rakennusoikeudellinenKerrosala {:value "",
                                                                 :modified 1530179094764,
                                                                 :source "krysp",
                                                                 :sourceValue ""},
                                :kokonaisala {:value "281",
                                              :modified 1530179094764,
                                              :source "krysp",
                                              :sourceValue "281"},
                                :kerrosluku {:value "2",
                                             :source "krysp",
                                             :sourceValue "2",
                                             :modified 1530179094764},
                                :kellarinpinta-ala {:value "",
                                                    :modified 1530179094764,
                                                    :source "krysp",
                                                    :sourceValue ""}},
                        :manuaalinen_rakennusnro {:value "",
                                                  :sourceValue "",
                                                  :source "krysp",
                                                  :modified 1530179094764},
                        :luokitus {:energialuokka {:value nil,
                                                   :modified 1530179094764,
                                                   :sourceValue nil,
                                                   :source "krysp"},
                                   :energiatehokkuusluku {:value "",
                                                          :source "krysp",
                                                          :modified 1530179094764,
                                                          :sourceValue ""},
                                   :energiatehokkuusluvunYksikko {:value "kWh/m2",
                                                                  :modified 1530179094764,
                                                                  :sourceValue "kWh/m2",
                                                                  :source "krysp"},
                                   :paloluokka {:value nil,
                                                :modified 1530179094764,
                                                :sourceValue nil,
                                                :source "krysp"}},
                        :valtakunnallinenNumero {:value "199887766E",
                                                 :modified 1530179094764,
                                                 :source "krysp",
                                                 :sourceValue "199887766E"}}}

                {:id "5b34a9d3cea1d0f410db2409",
                 :schema-info {:name "rakennuspaikka",
                               :version 1,
                               :type :location,
                               :approvable true,
                               :order 2,
                               :copy-action :clear},
                :created 1530178002437,
                 :data {:kiinteisto {:maaraalaTunnus {:value nil},
                                     :tilanNimi {:value ""},
                                     :rekisterointipvm {:value ""},
                                     :maapintaala {:value ""},
                                     :vesipintaala {:value ""}},
                        :hallintaperuste {:value nil},
                        :kaavanaste {:value nil},
                        :kaavatilanne {:value nil},
                        :hankkeestaIlmoitettu {:hankkeestaIlmoitettuPvm {:value nil}}}}


               {:id "5b34a9d3cea1d0f410db240c",
                :schema-info {:name "rakennusjatesuunnitelma",
                              :version 1,
                              :order 200,
                              :section-help "rakennusjate.help",
                              :blacklist [:neighbor]},
                :created 1530178002437,
                :data {:rakennusJaPurkujate {:0 {:jatetyyppi {:value "kipsi",
                                                              :modified 1530178832555},
                                                 :suunniteltuMaara {:value "1",
                                                                    :modified 1530178835793},
                                                 :yksikko {:value "tonni",
                                                           :modified 1530178840071},
                                                 :painoT {:value "1",
                                                          :modified 1530178844422}},
                                             :1 {:jatetyyppi {:value "lasi",
                                                              :modified 1530178850654},
                                                 :suunniteltuMaara {:value "20",
                                                                    :modified 1530178853738},
                                                 :yksikko {:value "kg",
                                                           :modified 1530178855943},
                                                 :painoT {:value "0",
                                                          :modified 1530178860517}}},
                       :vaarallisetAineet {:0 {:vaarallinenainetyyppi {:value "aerosolipullot",
                                                                       :modified 1530178864753},
                                               :suunniteltuMaara {:value "10",
                                                                  :modified 1530178867910},
                                               :yksikko {:value "kg",
                                                         :modified 1530178872036},
                                               :painoT {:value "0",
                                                        :modified 1530178891502}}}}}),
   :location-wgs84 [25.266 60.36938]
   :id "LP-753-2018-90008"
   :propertyId "75341600550007"
   :location [404369.304 6693806.957]
   :inspection-summaries []
   :schema-version 1})

(facts "Finalize verdict"
  (let [command {:user        {:id        "user-id" :username "user-email"
                               :firstName "Hello"   :lastName "World"}
                 :created     12345
                 :application application}
        verdict {:id         "vid"
                 :category   "r"
                 :state      "draft"
                 :data       {:verdict-code "myonnetty"
                              :handler      "Foo Bar"
                              :verdict-date 876543}
                 :modified   12300
                 :template   {:inclusions ["verdict-code"
                                           "handler"
                                           "verdict-date"]
                              :giver      "viranhaltija"}
                 :references {:boardname "Broad board abroad"}}
        c-v-a   (hash-map :command command
                          :verdict verdict
                          :application application)]
    (fact finalize--verdict
      (finalize--verdict c-v-a)
      => {:verdict (util/deep-merge verdict
                                    {:archive   {:verdict-date  876543
                                                 :verdict-giver "Foo Bar"}
                                     :published {:published 12345}
                                     :state     {:_value    "published"
                                                 :_user     "user-email"
                                                 :_modified 12345}})
          :updates {$set {:pate-verdicts.$.archive             {:verdict-date  876543
                                                                :verdict-giver "Foo Bar"}
                          :pate-verdicts.$.data.handler        "Foo Bar"
                          :pate-verdicts.$.data.verdict-code   "myonnetty"
                          :pate-verdicts.$.data.verdict-date   876543
                          :pate-verdicts.$.published.published 12345
                          :pate-verdicts.$.template.inclusions ["verdict-code"
                                                                "handler"
                                                                "verdict-date"]
                          :pate-verdicts.$.state               {:_value    "published"
                                                                :_user     "user-email"
                                                                :_modified 12345}}}})

    (fact "finalize--application-state: waste plan - If there is a waste plan but a waste report does not exist, it is added to documents"
      (-> application :documents count) => 3
      (let [{:keys [application updates commit-fn]} (finalize--application-state c-v-a)]
        application => (merge application {:state :verdictGiven})
        updates => {$push {:history {:state :verdictGiven
                                                    :ts    12345
                                                    :user  {:firstName "Hello"
                                                            :id        "user-id"
                                                            :lastName  "World"
                                                            :username  "user-email"}}}
                                   $set  {:modified     12345
                                          :state        :verdictGiven}}
        ;; assoc-in is used just to avoid using provided, which did
        ;; not like having facts inside the let binding
        (assoc-in (commit-fn (assoc c-v-a :application application) :dry-run)
                  [:mongo-updates $set "documents.3" :id]  "static-id")
        => {:mongo-updates
            {$set  {"documents.3" {:id          "static-id"
                                   :created     12345
                                   :data        {:availableMaterials  {:0 {:aines      {:value ""}
                                                                           :kuvaus     {:value ""}
                                                                           :maara      {:value ""}
                                                                           :saatavilla {:value nil}
                                                                           :yksikko    {:value nil}}}
                                                 :contact             {:email {:value ""}
                                                                       :name  {:value ""}
                                                                       :phone {:value ""}}
                                                 :rakennusJaPurkujate {:suunniteltuJate      {:0 {:jatetyyppi       {:modified nil
                                                                                                                     :value    "kipsi"}
                                                                                                  :painoT           {:modified nil
                                                                                                                     :value    "1"}
                                                                                                  :suunniteltuMaara {:modified nil
                                                                                                                     :value    "1"}
                                                                                                  :yksikko          {:modified nil
                                                                                                                     :value    "tonni"}}
                                                                                              :1 {:jatetyyppi       {:modified nil
                                                                                                                     :value    "lasi"}
                                                                                                  :painoT           {:modified nil
                                                                                                                     :value    "0"}
                                                                                                  :suunniteltuMaara {:modified nil
                                                                                                                     :value    "20"}
                                                                                                  :yksikko          {:modified nil
                                                                                                                     :value    "kg"}}}
                                                                       :suunnittelematonJate {:0 {:jatetyyppi            {:value nil}
                                                                                                  :jatteenToimituspaikka {:value ""}
                                                                                                  :painoT                {:value ""}
                                                                                                  :toteutunutMaara       {:value ""}
                                                                                                  :yksikko               {:value nil}}}}
                                                 :vaarallisetAineet   {:suunniteltuJate      {:0 {:painoT                {:modified nil
                                                                                                                          :value    "0"}
                                                                                                  :suunniteltuMaara      {:modified nil
                                                                                                                          :value    "10"}
                                                                                                  :vaarallinenainetyyppi {:modified nil
                                                                                                                          :value    "aerosolipullot"}
                                                                                                  :yksikko               {:modified nil
                                                                                                                          :value    "kg"}}}
                                                                       :suunnittelematonJate {:0 {:jatteenToimituspaikka {:value ""}
                                                                                                  :painoT                {:value ""}
                                                                                                  :toteutunutMaara       {:value ""}
                                                                                                  :vaarallinenainetyyppi {:value nil}
                                                                                                  :yksikko               {:value nil}}}}}
                                   :schema-info {:blacklist          [:neighbor]
                                                 :editable-in-states #{:acknowledged
                                                                       :agreementPrepared
                                                                       :agreementSigned
                                                                       :appealed
                                                                       :archived
                                                                       :closed
                                                                       :constructionStarted
                                                                       :extinct
                                                                       :final
                                                                       :finished
                                                                       :foremanVerdictGiven
                                                                       :inUse
                                                                       :onHold
                                                                       :ready
                                                                       :verdictGiven}
                                                 :name               "rakennusjateselvitys"
                                                 :order              201
                                                 :section-help       "rakennusjate.help"
                                                 :version            1}}}}}))

    (fact "finalize--application-state: waste report - If a waste report document exists, it is updated"
      (let [c-v-a (update-in c-v-a [:application :documents]
                             #(conj % rakennusjateselvitys))
            {:keys [application updates commit-fn]} (finalize--application-state c-v-a)
            waste-report-id (:id rakennusjateselvitys)]
        (commit-fn (assoc c-v-a :application application) :dry-run)
        => {:mongo-query {:documents {$elemMatch {:id waste-report-id}}}
            :mongo-updates {$set {"documents.$.data.rakennusJaPurkujate.suunniteltuJate.0.jatetyyppi.modified" 12345
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.0.jatetyyppi.value" "kipsi"
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.0.painoT.modified" 12345
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.0.painoT.value" "1"
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.0.suunniteltuMaara.modified" 12345
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.0.suunniteltuMaara.value" "1"
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.0.yksikko.modified" 12345
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.0.yksikko.value" "tonni"
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.1.jatetyyppi.modified" 12345
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.1.jatetyyppi.value" "lasi"
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.1.painoT.modified" 12345
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.1.painoT.value" "0"
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.1.suunniteltuMaara.modified" 12345
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.1.suunniteltuMaara.value" "20"
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.1.yksikko.modified" 12345
                                  "documents.$.data.rakennusJaPurkujate.suunniteltuJate.1.yksikko.value" "kg"
                                  "documents.$.data.vaarallisetAineet.suunniteltuJate.0.painoT.modified" 12345
                                  "documents.$.data.vaarallisetAineet.suunniteltuJate.0.painoT.value" "0"
                                  "documents.$.data.vaarallisetAineet.suunniteltuJate.0.suunniteltuMaara.modified" 12345
                                  "documents.$.data.vaarallisetAineet.suunniteltuJate.0.suunniteltuMaara.value" "10"
                                  "documents.$.data.vaarallisetAineet.suunniteltuJate.0.vaarallinenainetyyppi.modified" 12345
                                  "documents.$.data.vaarallisetAineet.suunniteltuJate.0.vaarallinenainetyyppi.value" "aerosolipullot"
                                  "documents.$.data.vaarallisetAineet.suunniteltuJate.0.yksikko.modified" 12345
                                  "documents.$.data.vaarallisetAineet.suunniteltuJate.0.yksikko.value" "kg"
                                  :modified 12345}
                            $unset {"documents.$.data.rakennusJaPurkujate.suunniteltuJate.2" ""
                                    "documents.$.meta.rakennusJaPurkujate.suunniteltuJate.2" ""}}
 :post-results []}))

    (fact "finalize--application-state: no waste plan - If there is no waste plan, waste report document is not added or updated"
      (let [application (update application :documents drop-last)
            {:keys [application updates commit-fn]} (finalize--application-state (assoc c-v-a :application application))]
        application => (merge application
                              {:state :verdictGiven})
        updates => {$push {:history {:state :verdictGiven
                                     :ts    12345
                                     :user  {:firstName "Hello"
                                             :id        "user-id"
                                             :lastName  "World"
                                             :username  "user-email"}}}
                    $set  {:modified 12345
                           :state    :verdictGiven}}
        (commit-fn (assoc c-v-a :application application) :dry-run) => nil))

    (let [verdict (-> verdict
                      (assoc-in [:data :reviews] ["5a156dd40e40adc8ee064463"
                                                  "6a156dd40e40adc8ee064463"])
                      (assoc-in [:data :reviews-included] true)
                      (assoc-in [:data :foremen] ["erityis-tj"
                                                  "iv-tj"
                                                  "vastaava-tj"
                                                  "vv-tj"])
                      (assoc-in [:data :foremen-included] true)
                      (assoc-in [:data :plans] ["5a156ddf0e40adc8ee064464"
                                                "6a156ddf0e40adc8ee064464"])
                      (assoc-in [:data :plans-included] true)
                      (assoc :references {:foremen ["erityis-tj" "iv-tj" "vastaava-tj" "vv-tj" "tj"]
                                          :plans   [{:id "5a156ddf0e40adc8ee064464"
                                                     :fi "Suunnitelmat"
                                                     :sv "Planer"
                                                     :en "Plans"}
                                                    {:id "6a156ddf0e40adc8ee064464"
                                                     :fi "Suunnitelmat2"
                                                     :sv "Planer2"
                                                     :en "Plans2"}]
                                          :reviews [{:id   "5a156dd40e40adc8ee064463"
                                                     :fi   "Katselmus"
                                                     :sv   "Syn"
                                                     :en   "Review"
                                                     :type "muu-katselmus"}
                                                    {:id   "6a156dd40e40adc8ee064463"
                                                     :fi   "Katselmus2"
                                                     :sv   "Syn2"
                                                     :en   "Review2"
                                                     :type "paikan-merkitseminen"}]})
                      (update-in [:template :inclusions] concat [:reviews :reviews-included
                                                                 :foremen :foremen-included
                                                                 :plans :plans-included]))]
      (fact "finalize--building-and-tasks: tasks and buildings"
        (finalize--buildings-and-tasks (assoc c-v-a :verdict verdict))
        => {:application (assoc application
                                :buildings '({:area           "281"
                                              :buildingId     "199887766E"
                                              :description    ""
                                              :index          "1"
                                              :localShortId   "002"
                                              :location       nil
                                              :location-wgs84 nil
                                              :nationalId     "199887766E"
                                              :operationId    "5b34a9d2cea1d0f410db2403"
                                              :propertyId     "75341600550007"
                                              :usage          "021 rivitalot"})
                                :tasks '({:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:katselmuksenLaji   {:modified 12345
                                                                             :value    "muu katselmus"}
                                                        :katselmus          {:huomautukset {:kuvaus        {:value ""}
                                                                                            :maaraAika     {:value nil}
                                                                                            :toteaja       {:value ""}
                                                                                            :toteamisHetki {:value nil}}
                                                                             :lasnaolijat  {:value ""}
                                                                             :pitaja       {:value ""}
                                                                             :pitoPvm      {:value nil}
                                                                             :poikkeamat   {:value ""}
                                                                             :tiedoksianto {:value false}
                                                                             :tila         {:value nil}}
                                                        :muuTunnus          {:value ""}
                                                        :muuTunnusSovellus  {:value ""}
                                                        :rakennus           {:0 {:rakennus {:jarjestysnumero                    {:modified 12345
                                                                                                                                 :value    "1"}
                                                                                            :kiinttun                           {:modified 12345
                                                                                                                                 :value    "75341600550007"}
                                                                                            :kunnanSisainenPysyvaRakennusnumero {:modified 12345
                                                                                                                                 :value    nil}
                                                                                            :rakennusnro                        {:modified 12345
                                                                                                                                 :value    "002"}
                                                                                            :valtakunnallinenNumero             {:modified 12345
                                                                                                                                 :value    "199887766E"}}
                                                                                 :tila     {:kayttoonottava {:modified 12345
                                                                                                             :value    false}
                                                                                            :tila           {:modified 12345
                                                                                                             :value    ""}}}}
                                                        :vaadittuLupaehtona {:modified 12345 :value true}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:i18nprefix       "task-katselmus.katselmuksenLaji"
                                                        :name             "task-katselmus"
                                                        :order            1
                                                        :section-help     "authority-fills"
                                                        :subtype          :review
                                                        :type             :task
                                                        :user-authz-roles #{}
                                                        :version          1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    nil}
                                         {:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:katselmuksenLaji   {:modified 12345
                                                                             :value    "rakennuksen paikan merkitseminen"}
                                                        :katselmus          {:huomautukset {:kuvaus        {:value ""}
                                                                                            :maaraAika     {:value nil}
                                                                                            :toteaja       {:value ""}
                                                                                            :toteamisHetki {:value nil}}
                                                                             :lasnaolijat  {:value ""}
                                                                             :pitaja       {:value ""}
                                                                             :pitoPvm      {:value nil}
                                                                             :poikkeamat   {:value ""}
                                                                             :tiedoksianto {:value false}
                                                                             :tila         {:value nil}}
                                                        :muuTunnus          {:value ""}
                                                        :muuTunnusSovellus  {:value ""}
                                                        :rakennus           {:0 {:rakennus {:jarjestysnumero                    {:modified 12345
                                                                                                                                 :value    "1"}
                                                                                            :kiinttun                           {:modified 12345
                                                                                                                                 :value    "75341600550007"}
                                                                                            :kunnanSisainenPysyvaRakennusnumero {:modified 12345
                                                                                                                                 :value    nil}
                                                                                            :rakennusnro                        {:modified 12345
                                                                                                                                 :value    "002"}
                                                                                            :valtakunnallinenNumero             {:modified 12345
                                                                                                                                 :value    "199887766E"}}
                                                                                 :tila     {:kayttoonottava {:modified 12345
                                                                                                             :value    false}
                                                                                            :tila           {:modified 12345
                                                                                                             :value    ""}}}}
                                                        :vaadittuLupaehtona {:modified 12345 :value true}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:i18nprefix       "task-katselmus.katselmuksenLaji"
                                                        :name             "task-katselmus"
                                                        :order            1
                                                        :section-help     "authority-fills"
                                                        :subtype          :review
                                                        :type             :task
                                                        :user-authz-roles #{}
                                                        :version          1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    nil}
                                         {:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:kuvaus                      {:value ""}
                                                        :maarays                     {:value ""}
                                                        :vaaditutErityissuunnitelmat {:value ""}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:name    "task-lupamaarays"
                                                        :order   20
                                                        :type    :task
                                                        :version 1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    nil}
                                         {:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:kuvaus                      {:value ""}
                                                        :maarays                     {:value ""}
                                                        :vaaditutErityissuunnitelmat {:value ""}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:name    "task-lupamaarays"
                                                        :order   20
                                                        :type    :task
                                                        :version 1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    nil}
                                         {:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:asiointitunnus {:value ""}
                                                        :osapuolena     {:value false}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                        :order   10
                                                        :subtype :foreman
                                                        :type    :task
                                                        :version 1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    "Erityisalojen ty\u00F6njohtaja"}
                                         {:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:asiointitunnus {:value ""}
                                                        :osapuolena     {:value false}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                        :order   10
                                                        :subtype :foreman
                                                        :type    :task
                                                        :version 1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    "Ilmanvaihtoty\u00F6njohtaja"}
                                         {:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:asiointitunnus {:value ""}
                                                        :osapuolena     {:value false}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                        :order   10
                                                        :subtype :foreman
                                                        :type    :task
                                                        :version 1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    "Vastaava ty\u00F6njohtaja"}
                                         {:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:asiointitunnus {:value ""}
                                                        :osapuolena     {:value false}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                        :order   10
                                                        :subtype :foreman
                                                        :type    :task
                                                        :version 1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    "Vesi- ja viem\u00E4rity\u00F6njohtaja"}))
            :updates     {$push {:tasks {$each '({:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:katselmuksenLaji   {:modified 12345
                                                                                     :value    "muu katselmus"}
                                                                :katselmus          {:huomautukset {:kuvaus        {:value ""}
                                                                                                    :maaraAika     {:value nil}
                                                                                                    :toteaja       {:value ""}
                                                                                                    :toteamisHetki {:value nil}}
                                                                                     :lasnaolijat  {:value ""}
                                                                                     :pitaja       {:value ""}
                                                                                     :pitoPvm      {:value nil}
                                                                                     :poikkeamat   {:value ""}
                                                                                     :tiedoksianto {:value false}
                                                                                     :tila         {:value nil}}
                                                                :muuTunnus          {:value ""}
                                                                :muuTunnusSovellus  {:value ""}
                                                                :rakennus           {:0 {:rakennus {:jarjestysnumero                    {:modified 12345
                                                                                                                                         :value    "1"}
                                                                                                    :kiinttun                           {:modified 12345
                                                                                                                                         :value    "75341600550007"}
                                                                                                    :kunnanSisainenPysyvaRakennusnumero {:modified 12345
                                                                                                                                         :value    nil}
                                                                                                    :rakennusnro                        {:modified 12345
                                                                                                                                         :value    "002"}
                                                                                                    :valtakunnallinenNumero             {:modified 12345
                                                                                                                                         :value    "199887766E"}}
                                                                                         :tila     {:kayttoonottava {:modified 12345
                                                                                                                     :value    false}
                                                                                                    :tila           {:modified 12345
                                                                                                                     :value    ""}}}}
                                                                :vaadittuLupaehtona {:modified 12345
                                                                                     :value    true}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:i18nprefix       "task-katselmus.katselmuksenLaji"
                                                                :name             "task-katselmus"
                                                                :order            1
                                                                :section-help     "authority-fills"
                                                                :subtype          :review
                                                                :type             :task
                                                                :user-authz-roles #{}
                                                                :version          1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    nil}
                                                 {:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:katselmuksenLaji   {:modified 12345
                                                                                     :value    "rakennuksen paikan merkitseminen"}
                                                                :katselmus          {:huomautukset {:kuvaus        {:value ""}
                                                                                                    :maaraAika     {:value nil}
                                                                                                    :toteaja       {:value ""}
                                                                                                    :toteamisHetki {:value nil}}
                                                                                     :lasnaolijat  {:value ""}
                                                                                     :pitaja       {:value ""}
                                                                                     :pitoPvm      {:value nil}
                                                                                     :poikkeamat   {:value ""}
                                                                                     :tiedoksianto {:value false}
                                                                                     :tila         {:value nil}}
                                                                :muuTunnus          {:value ""}
                                                                :muuTunnusSovellus  {:value ""}
                                                                :rakennus           {:0 {:rakennus {:jarjestysnumero                    {:modified 12345
                                                                                                                                         :value    "1"}
                                                                                                    :kiinttun                           {:modified 12345
                                                                                                                                         :value    "75341600550007"}
                                                                                                    :kunnanSisainenPysyvaRakennusnumero {:modified 12345
                                                                                                                                         :value    nil}
                                                                                                    :rakennusnro                        {:modified 12345
                                                                                                                                         :value    "002"}
                                                                                                    :valtakunnallinenNumero             {:modified 12345
                                                                                                                                         :value    "199887766E"}}
                                                                                         :tila     {:kayttoonottava {:modified 12345
                                                                                                                     :value    false}
                                                                                                    :tila           {:modified 12345
                                                                                                                     :value    ""}}}}
                                                                :vaadittuLupaehtona {:modified 12345
                                                                                     :value    true}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:i18nprefix       "task-katselmus.katselmuksenLaji"
                                                                :name             "task-katselmus"
                                                                :order            1
                                                                :section-help     "authority-fills"
                                                                :subtype          :review
                                                                :type             :task
                                                                :user-authz-roles #{}
                                                                :version          1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    nil}
                                                 {:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:kuvaus                      {:value ""}
                                                                :maarays                     {:value ""}
                                                                :vaaditutErityissuunnitelmat {:value ""}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:name    "task-lupamaarays"
                                                                :order   20
                                                                :type    :task
                                                                :version 1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    nil}
                                                 {:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:kuvaus                      {:value ""}
                                                                :maarays                     {:value ""}
                                                                :vaaditutErityissuunnitelmat {:value ""}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:name    "task-lupamaarays"
                                                                :order   20
                                                                :type    :task
                                                                :version 1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    nil}
                                                 {:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:asiointitunnus {:value ""}
                                                                :osapuolena     {:value false}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                                :order   10
                                                                :subtype :foreman
                                                                :type    :task
                                                                :version 1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    "Erityisalojen ty\u00F6njohtaja"}
                                                 {:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:asiointitunnus {:value ""}
                                                                :osapuolena     {:value false}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                                :order   10
                                                                :subtype :foreman
                                                                :type    :task
                                                                :version 1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    "Ilmanvaihtoty\u00F6njohtaja"}
                                                 {:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:asiointitunnus {:value ""}
                                                                :osapuolena     {:value false}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                                :order   10
                                                                :subtype :foreman
                                                                :type    :task
                                                                :version 1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    "Vastaava ty\u00F6njohtaja"}
                                                 {:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:asiointitunnus {:value ""}
                                                                :osapuolena     {:value false}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                                :order   10
                                                                :subtype :foreman
                                                                :type    :task
                                                                :version 1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    "Vesi- ja viem\u00E4rity\u00F6njohtaja"})}}
                          $set  {:buildings '({:area           "281"
                                               :buildingId     "199887766E"
                                               :description    ""
                                               :index          "1"
                                               :localShortId   "002"
                                               :location       nil
                                               :location-wgs84 nil
                                               :nationalId     "199887766E"
                                               :operationId    "5b34a9d2cea1d0f410db2403"
                                               :propertyId     "75341600550007"
                                               :usage          "021 rivitalot"})}}}
        (provided (lupapalvelu.mongo/create-id) => "id"))
      (fact "finalize--building-and-tasks: tasks, no buildings"
        (let [application (update application :documents rest)]
          (finalize--buildings-and-tasks (assoc c-v-a
                                                :application application
                                                :verdict verdict))
          => {:application (assoc application
                                  :tasks '({:assignee    {}
                                            :closed      nil
                                            :created     12345
                                            :data        {:katselmuksenLaji   {:modified 12345
                                                                               :value    "muu katselmus"}
                                                          :katselmus          {:huomautukset {:kuvaus        {:value ""}
                                                                                              :maaraAika     {:value nil}
                                                                                              :toteaja       {:value ""}
                                                                                              :toteamisHetki {:value nil}}
                                                                               :lasnaolijat  {:value ""}
                                                                               :pitaja       {:value ""}
                                                                               :pitoPvm      {:value nil}
                                                                               :poikkeamat   {:value ""}
                                                                               :tiedoksianto {:value false}
                                                                               :tila         {:value nil}}
                                                          :muuTunnus          {:value ""}
                                                          :muuTunnusSovellus  {:value ""}
                                                          :rakennus           {}
                                                          :vaadittuLupaehtona {:modified 12345 :value true}}
                                            :duedate     nil
                                            :id          "id"
                                            :schema-info {:i18nprefix       "task-katselmus.katselmuksenLaji"
                                                          :name             "task-katselmus"
                                                          :order            1
                                                          :section-help     "authority-fills"
                                                          :subtype          :review
                                                          :type             :task
                                                          :user-authz-roles #{}
                                                          :version          1}
                                            :source      {:id "vid" :type "verdict"}
                                            :state       :requires_user_action
                                            :taskname    nil}
                                           {:assignee    {}
                                            :closed      nil
                                            :created     12345
                                            :data        {:katselmuksenLaji   {:modified 12345
                                                                               :value    "rakennuksen paikan merkitseminen"}
                                                          :katselmus          {:huomautukset {:kuvaus        {:value ""}
                                                                                              :maaraAika     {:value nil}
                                                                                              :toteaja       {:value ""}
                                                                                              :toteamisHetki {:value nil}}
                                                                               :lasnaolijat  {:value ""}
                                                                               :pitaja       {:value ""}
                                                                               :pitoPvm      {:value nil}
                                                                               :poikkeamat   {:value ""}
                                                                               :tiedoksianto {:value false}
                                                                               :tila         {:value nil}}
                                                          :muuTunnus          {:value ""}
                                                          :muuTunnusSovellus  {:value ""}
                                                          :rakennus           {}
                                                          :vaadittuLupaehtona {:modified 12345 :value true}}
                                            :duedate     nil
                                            :id          "id"
                                            :schema-info {:i18nprefix       "task-katselmus.katselmuksenLaji"
                                                          :name             "task-katselmus"
                                                          :order            1
                                                          :section-help     "authority-fills"
                                                          :subtype          :review
                                                          :type             :task
                                                          :user-authz-roles #{}
                                                          :version          1}
                                            :source      {:id "vid" :type "verdict"}
                                            :state       :requires_user_action
                                            :taskname    nil}
                                           {:assignee    {}
                                            :closed      nil
                                            :created     12345
                                            :data        {:kuvaus                      {:value ""}
                                                          :maarays                     {:value ""}
                                                          :vaaditutErityissuunnitelmat {:value ""}}
                                            :duedate     nil
                                            :id          "id"
                                            :schema-info {:name    "task-lupamaarays"
                                                          :order   20
                                                          :type    :task
                                                          :version 1}
                                            :source      {:id "vid" :type "verdict"}
                                            :state       :requires_user_action
                                            :taskname    nil}
                                           {:assignee    {}
                                            :closed      nil
                                            :created     12345
                                            :data        {:kuvaus                      {:value ""}
                                                          :maarays                     {:value ""}
                                                          :vaaditutErityissuunnitelmat {:value ""}}
                                            :duedate     nil
                                            :id          "id"
                                            :schema-info {:name    "task-lupamaarays"
                                                          :order   20
                                                          :type    :task
                                                          :version 1}
                                            :source      {:id "vid" :type "verdict"}
                                            :state       :requires_user_action
                                            :taskname    nil}
                                           {:assignee    {}
                                            :closed      nil
                                            :created     12345
                                            :data        {:asiointitunnus {:value ""}
                                                          :osapuolena     {:value false}}
                                            :duedate     nil
                                            :id          "id"
                                            :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                          :order   10
                                                          :subtype :foreman
                                                          :type    :task
                                                          :version 1}
                                            :source      {:id "vid" :type "verdict"}
                                            :state       :requires_user_action
                                            :taskname    "Erityisalojen ty\u00F6njohtaja"}
                                           {:assignee    {}
                                            :closed      nil
                                            :created     12345
                                            :data        {:asiointitunnus {:value ""}
                                                          :osapuolena     {:value false}}
                                            :duedate     nil
                                            :id          "id"
                                            :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                          :order   10
                                                          :subtype :foreman
                                                          :type    :task
                                                          :version 1}
                                            :source      {:id "vid" :type "verdict"}
                                            :state       :requires_user_action
                                            :taskname    "Ilmanvaihtoty\u00F6njohtaja"}
                                           {:assignee    {}
                                            :closed      nil
                                            :created     12345
                                            :data        {:asiointitunnus {:value ""}
                                                          :osapuolena     {:value false}}
                                            :duedate     nil
                                            :id          "id"
                                            :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                          :order   10
                                                          :subtype :foreman
                                                          :type    :task
                                                          :version 1}
                                            :source      {:id "vid" :type "verdict"}
                                            :state       :requires_user_action
                                            :taskname    "Vastaava ty\u00F6njohtaja"}
                                           {:assignee    {}
                                            :closed      nil
                                            :created     12345
                                            :data        {:asiointitunnus {:value ""}
                                                          :osapuolena     {:value false}}
                                            :duedate     nil
                                            :id          "id"
                                            :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                          :order   10
                                                          :subtype :foreman
                                                          :type    :task
                                                          :version 1}
                                            :source      {:id "vid" :type "verdict"}
                                            :state       :requires_user_action
                                            :taskname    "Vesi- ja viem\u00E4rity\u00F6njohtaja"}))
              :updates     {$push {:tasks {$each '({:assignee    {}
                                                    :closed      nil
                                                    :created     12345
                                                    :data        {:katselmuksenLaji   {:modified 12345
                                                                                       :value    "muu katselmus"}
                                                                  :katselmus          {:huomautukset {:kuvaus        {:value ""}
                                                                                                      :maaraAika     {:value nil}
                                                                                                      :toteaja       {:value ""}
                                                                                                      :toteamisHetki {:value nil}}
                                                                                       :lasnaolijat  {:value ""}
                                                                                       :pitaja       {:value ""}
                                                                                       :pitoPvm      {:value nil}
                                                                                       :poikkeamat   {:value ""}
                                                                                       :tiedoksianto {:value false}
                                                                                       :tila         {:value nil}}
                                                                  :muuTunnus          {:value ""}
                                                                  :muuTunnusSovellus  {:value ""}
                                                                  :rakennus           {}
                                                                  :vaadittuLupaehtona {:modified 12345
                                                                                       :value    true}}
                                                    :duedate     nil
                                                    :id          "id"
                                                    :schema-info {:i18nprefix       "task-katselmus.katselmuksenLaji"
                                                                  :name             "task-katselmus"
                                                                  :order            1
                                                                  :section-help     "authority-fills"
                                                                  :subtype          :review
                                                                  :type             :task
                                                                  :user-authz-roles #{}
                                                                  :version          1}
                                                    :source      {:id "vid" :type "verdict"}
                                                    :state       :requires_user_action
                                                    :taskname    nil}
                                                   {:assignee    {}
                                                    :closed      nil
                                                    :created     12345
                                                    :data        {:katselmuksenLaji   {:modified 12345
                                                                                       :value    "rakennuksen paikan merkitseminen"}
                                                                  :katselmus          {:huomautukset {:kuvaus        {:value ""}
                                                                                                      :maaraAika     {:value nil}
                                                                                                      :toteaja       {:value ""}
                                                                                                      :toteamisHetki {:value nil}}
                                                                                       :lasnaolijat  {:value ""}
                                                                                       :pitaja       {:value ""}
                                                                                       :pitoPvm      {:value nil}
                                                                                       :poikkeamat   {:value ""}
                                                                                       :tiedoksianto {:value false}
                                                                                       :tila         {:value nil}}
                                                                  :muuTunnus          {:value ""}
                                                                  :muuTunnusSovellus  {:value ""}
                                                                  :rakennus           {}
                                                                  :vaadittuLupaehtona {:modified 12345
                                                                                       :value    true}}
                                                    :duedate     nil
                                                    :id          "id"
                                                    :schema-info {:i18nprefix       "task-katselmus.katselmuksenLaji"
                                                                  :name             "task-katselmus"
                                                                  :order            1
                                                                  :section-help     "authority-fills"
                                                                  :subtype          :review
                                                                  :type             :task
                                                                  :user-authz-roles #{}
                                                                  :version          1}
                                                    :source      {:id "vid" :type "verdict"}
                                                    :state       :requires_user_action
                                                    :taskname    nil}
                                                   {:assignee    {}
                                                    :closed      nil
                                                    :created     12345
                                                    :data        {:kuvaus                      {:value ""}
                                                                  :maarays                     {:value ""}
                                                                  :vaaditutErityissuunnitelmat {:value ""}}
                                                    :duedate     nil
                                                    :id          "id"
                                                    :schema-info {:name    "task-lupamaarays"
                                                                  :order   20
                                                                  :type    :task
                                                                  :version 1}
                                                    :source      {:id "vid" :type "verdict"}
                                                    :state       :requires_user_action
                                                    :taskname    nil}
                                                   {:assignee    {}
                                                    :closed      nil
                                                    :created     12345
                                                    :data        {:kuvaus                      {:value ""}
                                                                  :maarays                     {:value ""}
                                                                  :vaaditutErityissuunnitelmat {:value ""}}
                                                    :duedate     nil
                                                    :id          "id"
                                                    :schema-info {:name    "task-lupamaarays"
                                                                  :order   20
                                                                  :type    :task
                                                                  :version 1}
                                                    :source      {:id "vid" :type "verdict"}
                                                    :state       :requires_user_action
                                                    :taskname    nil}
                                                   {:assignee    {}
                                                    :closed      nil
                                                    :created     12345
                                                    :data        {:asiointitunnus {:value ""}
                                                                  :osapuolena     {:value false}}
                                                    :duedate     nil
                                                    :id          "id"
                                                    :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                                  :order   10
                                                                  :subtype :foreman
                                                                  :type    :task
                                                                  :version 1}
                                                    :source      {:id "vid" :type "verdict"}
                                                    :state       :requires_user_action
                                                    :taskname    "Erityisalojen ty\u00F6njohtaja"}
                                                   {:assignee    {}
                                                    :closed      nil
                                                    :created     12345
                                                    :data        {:asiointitunnus {:value ""}
                                                                  :osapuolena     {:value false}}
                                                    :duedate     nil
                                                    :id          "id"
                                                    :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                                  :order   10
                                                                  :subtype :foreman
                                                                  :type    :task
                                                                  :version 1}
                                                    :source      {:id "vid" :type "verdict"}
                                                    :state       :requires_user_action
                                                    :taskname    "Ilmanvaihtoty\u00F6njohtaja"}
                                                   {:assignee    {}
                                                    :closed      nil
                                                    :created     12345
                                                    :data        {:asiointitunnus {:value ""}
                                                                  :osapuolena     {:value false}}
                                                    :duedate     nil
                                                    :id          "id"
                                                    :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                                  :order   10
                                                                  :subtype :foreman
                                                                  :type    :task
                                                                  :version 1}
                                                    :source      {:id "vid" :type "verdict"}
                                                    :state       :requires_user_action
                                                    :taskname    "Vastaava ty\u00F6njohtaja"}
                                                   {:assignee    {}
                                                    :closed      nil
                                                    :created     12345
                                                    :data        {:asiointitunnus {:value ""}
                                                                  :osapuolena     {:value false}}
                                                    :duedate     nil
                                                    :id          "id"
                                                    :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                                  :order   10
                                                                  :subtype :foreman
                                                                  :type    :task
                                                                  :version 1}
                                                    :source      {:id "vid" :type "verdict"}
                                                    :state       :requires_user_action
                                                    :taskname    "Vesi- ja viem\u00E4rity\u00F6njohtaja"})}}
                            $set  {:buildings []}}}
          (provided (lupapalvelu.mongo/create-id) => "id")))
      (fact "finalize--building-and-tasks: reviews not included, plans, foremen, buildings"
        (finalize--buildings-and-tasks (assoc c-v-a
                                              :verdict (assoc-in verdict [:data :reviews-included] false)))
        => {:application (assoc application
                                :buildings '({:area           "281"
                                              :buildingId     "199887766E"
                                              :description    ""
                                              :index          "1"
                                              :localShortId   "002"
                                              :location       nil
                                              :location-wgs84 nil
                                              :nationalId     "199887766E"
                                              :operationId    "5b34a9d2cea1d0f410db2403"
                                              :propertyId     "75341600550007"
                                              :usage          "021 rivitalot"})
                                :tasks '({:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:kuvaus                      {:value ""}
                                                        :maarays                     {:value ""}
                                                        :vaaditutErityissuunnitelmat {:value ""}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:name    "task-lupamaarays"
                                                        :order   20
                                                        :type    :task
                                                        :version 1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    nil}
                                         {:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:kuvaus                      {:value ""}
                                                        :maarays                     {:value ""}
                                                        :vaaditutErityissuunnitelmat {:value ""}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:name    "task-lupamaarays"
                                                        :order   20
                                                        :type    :task
                                                        :version 1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    nil}
                                         {:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:asiointitunnus {:value ""}
                                                        :osapuolena     {:value false}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                        :order   10
                                                        :subtype :foreman
                                                        :type    :task
                                                        :version 1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    "Erityisalojen ty\u00F6njohtaja"}
                                         {:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:asiointitunnus {:value ""}
                                                        :osapuolena     {:value false}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                        :order   10
                                                        :subtype :foreman
                                                        :type    :task
                                                        :version 1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    "Ilmanvaihtoty\u00F6njohtaja"}
                                         {:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:asiointitunnus {:value ""}
                                                        :osapuolena     {:value false}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                        :order   10
                                                        :subtype :foreman
                                                        :type    :task
                                                        :version 1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    "Vastaava ty\u00F6njohtaja"}
                                         {:assignee    {}
                                          :closed      nil
                                          :created     12345
                                          :data        {:asiointitunnus {:value ""}
                                                        :osapuolena     {:value false}}
                                          :duedate     nil
                                          :id          "id"
                                          :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                        :order   10
                                                        :subtype :foreman
                                                        :type    :task
                                                        :version 1}
                                          :source      {:id "vid" :type "verdict"}
                                          :state       :requires_user_action
                                          :taskname    "Vesi- ja viem\u00E4rity\u00F6njohtaja"}))
            :updates     {$push {:tasks {$each '({:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:kuvaus                      {:value ""}
                                                                :maarays                     {:value ""}
                                                                :vaaditutErityissuunnitelmat {:value ""}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:name    "task-lupamaarays"
                                                                :order   20
                                                                :type    :task
                                                                :version 1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    nil}
                                                 {:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:kuvaus                      {:value ""}
                                                                :maarays                     {:value ""}
                                                                :vaaditutErityissuunnitelmat {:value ""}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:name    "task-lupamaarays"
                                                                :order   20
                                                                :type    :task
                                                                :version 1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    nil}
                                                 {:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:asiointitunnus {:value ""}
                                                                :osapuolena     {:value false}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                                :order   10
                                                                :subtype :foreman
                                                                :type    :task
                                                                :version 1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    "Erityisalojen ty\u00F6njohtaja"}
                                                 {:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:asiointitunnus {:value ""}
                                                                :osapuolena     {:value false}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                                :order   10
                                                                :subtype :foreman
                                                                :type    :task
                                                                :version 1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    "Ilmanvaihtoty\u00F6njohtaja"}
                                                 {:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:asiointitunnus {:value ""}
                                                                :osapuolena     {:value false}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                                :order   10
                                                                :subtype :foreman
                                                                :type    :task
                                                                :version 1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    "Vastaava ty\u00F6njohtaja"}
                                                 {:assignee    {}
                                                  :closed      nil
                                                  :created     12345
                                                  :data        {:asiointitunnus {:value ""}
                                                                :osapuolena     {:value false}}
                                                  :duedate     nil
                                                  :id          "id"
                                                  :schema-info {:name    "task-vaadittu-tyonjohtaja"
                                                                :order   10
                                                                :subtype :foreman
                                                                :type    :task
                                                                :version 1}
                                                  :source      {:id "vid" :type "verdict"}
                                                  :state       :requires_user_action
                                                  :taskname    "Vesi- ja viem\u00E4rity\u00F6njohtaja"})}}
                          $set  {:buildings '({:area           "281"
                                               :buildingId     "199887766E"
                                               :description    ""
                                               :index          "1"
                                               :localShortId   "002"
                                               :location       nil
                                               :location-wgs84 nil
                                               :nationalId     "199887766E"
                                               :operationId    "5b34a9d2cea1d0f410db2403"
                                               :propertyId     "75341600550007"
                                               :usage          "021 rivitalot"})}}}
        (provided (lupapalvelu.mongo/create-id) => "id"))

      (fact "finalize--building-and-tasks: buildings, no tasks"
        (finalize--buildings-and-tasks (assoc c-v-a
                                              :verdict (-> verdict
                                                           (assoc-in [:data :reviews] [])
                                                           (assoc-in [:data :plans-included] false)
                                                           (assoc-in [:data :foremen-included] false))))
        => {:application (assoc application
                                :buildings '({:area           "281"
                                              :buildingId     "199887766E"
                                              :description    ""
                                              :index          "1"
                                              :localShortId   "002"
                                              :location       nil
                                              :location-wgs84 nil
                                              :nationalId     "199887766E"
                                              :propertyId     "75341600550007"
                                              :operationId    "5b34a9d2cea1d0f410db2403"
                                              :usage          "021 rivitalot"}))
            :updates     {$set {:buildings '({:area           "281"
                                              :buildingId     "199887766E"
                                              :description    ""
                                              :index          "1"
                                              :localShortId   "002"
                                              :location       nil
                                              :location-wgs84 nil
                                              :nationalId     "199887766E"
                                              :propertyId     "75341600550007"
                                              :operationId    "5b34a9d2cea1d0f410db2403"
                                              :usage          "021 rivitalot"})}}}))

    (fact "finalize--inspection-summary: inspection summaries disabled"
      (inspection-summary/finalize--inspection-summary c-v-a) => nil
      (provided (lupapalvelu.organization/get-organization "753-R")
                => {:inspection-summaries-enabled false}))
    (fact "finalize--inspection-summary: inspection summaries enabled"
      (inspection-summary/finalize--inspection-summary c-v-a)
      => {:application (assoc
                        application
                        :inspection-summaries [{:id      "id"
                                                :name    "Inspector Template"
                                                :op      {:description nil
                                                          :id          "5b34a9d2cea1d0f410db2403"
                                                          :name        "sisatila-muutos"}
                                                :targets '({:finished    false
                                                            :id          "id"
                                                            :target-name "First item"}
                                                           {:finished    false
                                                            :id          "id"
                                                            :target-name "Second item"})}])
          :updates     {$push {:inspection-summaries {:id      "id"
                                                      :name    "Inspector Template"
                                                      :op      {:description nil
                                                                :id          "5b34a9d2cea1d0f410db2403"
                                                                :name        "sisatila-muutos"}
                                                      :targets '({:finished    false
                                                                  :id          "id"
                                                                  :target-name "First item"}
                                                                 {:finished    false
                                                                  :id          "id"
                                                                  :target-name "Second item"})}}}}
      (provided (lupapalvelu.mongo/create-id) => "id"
                (lupapalvelu.organization/get-organization "753-R")
                => {:inspection-summaries-enabled true
                    :inspection-summary           {:operations-templates
                                                   {:sisatila-muutos "5b35d34ecea1d0863491149c"}}}
                (lupapalvelu.inspection-summary/settings-for-organization "753-R")
                => {:templates [{:name     "Inspector Template",
                                 :modified 1530254158347,
                                 :id       "5b35d34ecea1d0863491149c",
                                 :items    ["First item" "Second item"]}]}))

    (let [att-paatosote {:id            "att1"
                         :type          {:type-id    "paatosote"
                                         :type-group "paatoksenteko"}
                         :target        {:type "verdict"
                                         :id   (:id verdict)}
                         :latestVersion {:fileId "a"}}
          att-ilmoitus  {:id            "att2"
                         :type          {:type-id    "ilmoitus"
                                         :type-group "paatoksenteko"}
                         :latestVersion {:fileId "b"}}
          att-empty     {:id   "att3"
                         :type {:type-id    "ilmoitus"
                                :type-group "paatoksenteko"}}
          att-ilmoitus2 {:id            "att4"
                         :type          {:type-id    "ilmoitus"
                                         :type-group "paatoksenteko"}
                         :target        {:type "verdict"
                                         :id   (:id verdict)}
                         :latestVersion {:fileId "c"}}
          att-ilmoitus3 {:id            "att5"
                         :type          {:type-id    "ilmoitus"
                                         :type-group "paatoksenteko"}
                         :latestVersion {:fileId "d"}}
          att-paatos    {:id            "att6"
                         :type          {:type-id    "paatos"
                                         :type-group "paatoksenteko"}
                         :latestVersion {:fileId "e"}}
          att-paatos2   {:id            "att7"
                         :type          {:type-id    "paatos"
                                         :type-group "paatoksenteko"}
                         :target        {:type "verdict"
                                         :id   (:id verdict)}
                         :latestVersion {:fileId "f"}}]
      (fact "finalize--attachments: no attachments"
        (finalize--attachments c-v-a)
        => (contains {:application (assoc application :attachments [])
                      :updates     {$set {:pate-verdicts.$.data.attachments
                                          '({:amount     1
                                             :type-group "paatoksenteko"
                                             :type-id    "paatos"})}}
                      :verdict     (assoc-in verdict
                                             [:data :attachments]
                                             '({:amount     1
                                                :type-group "paatoksenteko"
                                                :type-id    "paatos"}))})
        (provided (lupapalvelu.attachment/attachment-array-updates
                   "LP-753-2018-90008" anything :readOnly true :locked true :target {:type "verdict", :id "vid"})
                  => nil
                  (lupapalvelu.attachment/attachment-array-updates
                   "LP-753-2018-90008" anything :metadata.nakyvyys "julkinen" :metadata.draftTarget false)
                  => nil))
      (fact "verdict-attachment-items"
        (verdict-attachment-items {:application {:attachments [att-paatosote att-ilmoitus att-empty]}}
                                  {:id   (:id verdict)
                                   :data {:atts ["att2" "att3"]}}
                                  :atts)
        => (just [{:id "att1" :type-group "paatoksenteko" :type-id "paatosote"}
                  {:id "att2" :type-group "paatoksenteko" :type-id "ilmoitus"}]
                 :in-any-order))
      (fact "attachment-items"
        (let [{:keys [update-fn]} (attachment-items
                                   {:application {:attachments [att-paatosote att-ilmoitus att-empty
                                                                att-ilmoitus2 att-ilmoitus3 att-paatos
                                                                att-paatos2]
                                                  :permitType  "R"}}
                                   {:id   (:id verdict)
                                    :data {:attachments ["att2" "att3" "gone" "att5" "att6"]}})]
          (update-fn {:foo 8})
          => (just {:foo         8
                    :attachments (just [{:type-group "paatoksenteko" :type-id "paatosote" :amount 1}
                                        {:type-group "paatoksenteko" :type-id "ilmoitus" :amount 3}
                                        {:type-group "paatoksenteko" :type-id "paatos" :amount 3}]
                                       :in-any-order)})))
      (fact "finalize--attachments: new, selected and empty"
        (finalize--attachments (-> c-v-a
                                   (assoc-in [:command :application :attachments]
                                             [att-paatosote att-ilmoitus att-empty
                                              att-ilmoitus2 att-ilmoitus3 att-paatos
                                              att-paatos2])
                                   (assoc-in [:verdict :data :attachments]
                                             ["att2" "att3"  "gone" "att5" "att6"])))
        => (contains {:updates (just {$set (just {:pate-verdicts.$.data.attachments
                                                  (just [{:amount     3
                                                          :type-group "paatoksenteko"
                                                          :type-id    "paatos"}
                                                         {:amount     1
                                                          :type-group "paatoksenteko"
                                                          :type-id    "paatosote"}
                                                         {:amount     3
                                                          :type-group "paatoksenteko"
                                                          :type-id    "ilmoitus"}]
                                                        :in-any-order)})})})
        (provided (lupapalvelu.attachment/attachment-array-updates
                   "LP-753-2018-90008" anything :readOnly true :locked true :target {:type "verdict", :id "vid"})
                  => nil
                  (lupapalvelu.attachment/attachment-array-updates
                   "LP-753-2018-90008" anything :metadata.nakyvyys "julkinen" :metadata.draftTarget false)
                  => nil)))

    (fact "finalize--pdf"
      (get-in (finalize--pdf c-v-a) [:updates $set :pate-verdicts.$.published.tags])
      => string?
      (provided (lupapalvelu.organization/get-organization-name "753-R" nil)
                => "Sipoon rakennusvalvonta"))))

(facts "Copy old verdict as base of replacement verdict"
  (let [verdictId (mongo/create-id)
        verdict (make-verdict :id verdictId :code "myonnetty" :section "123")]

    (copy-verdict-draft {:application (assoc application :pate-verdicts [verdict])
                         :created 123456789
                         :user {:id (mongo/create-id) :username "sonja"}}
                        verdictId) => string?))

(facts "user-person-name"
  (user-person-name nil) => ""
  (user-person-name "") => ""
  (user-person-name {:firstName "Hello"}) => "Hello"
  (user-person-name {:lastName "World"}) => "World"
  (user-person-name {:firstName "  Hello  "}) => "Hello"
  (user-person-name {:lastName "  World  "}) => "World"
  (user-person-name {:firstName "  Hello  "
                     :lastName  "  World  "}) => "Hello World"
  (user-person-name {:firstName "    "
                     :lastName  "    "}) => "")

(facts "Signature requests"
  (let [verdictId   (mongo/create-id)
        verdict     (make-verdict :id verdictId :code "myonnetty" :section "123")
        verdict     (assoc verdict :signatures [{:user-id "123"
                                                 :name    "Signer one"
                                                 :date    1536138000000}])
        verdict     (assoc verdict :signature-requests [{:user-id "111"
                                                         :name    "Signer four"
                                                         :date    1536138000000}])
        application (assoc application :auth [{:id "123" :username "user1" :firstName "signer" :lastName "one"}
                                              {:id "456" :username "user2" :firstName "signer" :lastName "two"}
                                              {:id "789" :username "user3" :firstName "signer" :lastName "three"}
                                              {:id "111" :username "user4" :firstName "signer" :lastName "four"}
                                              {:id "222" :username "user5" :firstName "signer" :lastName "five"}])
        application (assoc application :pate-verdicts [verdict])]

    (fact "Should find correct parties into selection")
    (parties {:application application :data {:verdict-id verdictId}})) => [{:text "signer two" :value "456"}
                                                                            {:text "signer three" :value "789"}
                                                                            {:text "signer five" :value "222"}])

(facts "Verdict proposal"
  (let [proposal-verdict (-> (make-verdict :id "proposal-1" :code "myonnetty" :section "1")
                             (assoc-in [:template :giver] "lautakunta")
                             (assoc :state "proposal"))
        c-v-a            (hash-map :command {:user        {:id        "user-id" :username "user-email"
                                                           :firstName "Hello"   :lastName "World"}
                                             :created     12345
                                             :application application}
                                   :verdict proposal-verdict
                                   :application application)]
    (fact "proposal?"
      (vc/proposal? proposal-verdict) => true
      (vc/proposal? (dissoc proposal-verdict :category)) => false
      (vc/proposal? (assoc proposal-verdict :state "draft")) => false)

    (fact "proposal-filled? - verdict not"
      (let [proposal (util/dissoc-in proposal-verdict [:data :verdict-code])]
        (proposal-filled? {:data        {:verdict-id "proposal-1"}
                           :application {:pate-verdicts [proposal]}}) => true
        (verdict-filled? {:data        {:verdict-id "proposal-1"}
                          :application {:pate-verdicts [proposal]}}) => false))

    (fact "finalize--proposal"
      (finalize--proposal c-v-a) => {:updates {"$set" {:pate-verdicts.$.data.handler "Foo Bar"
                                                       :pate-verdicts.$.data.verdict-code "myonnetty"
                                                       :pate-verdicts.$.data.verdict-date 876543
                                                       :pate-verdicts.$.data.verdict-section "1"
                                                       :pate-verdicts.$.proposal.proposed 12345
                                                       :pate-verdicts.$.state {:_modified 12345
                                                                               :_user "user-email"
                                                                               :_value "proposal"}}}
                                     :verdict {:category "r"
                                               :data {:handler "Foo Bar"
                                                      :verdict-code "myonnetty"
                                                      :verdict-date 876543
                                                      :verdict-section "1"}
                                               :id "proposal-1"
                                               :modified 1
                                               :proposal {:proposed 12345}
                                               :published nil
                                               :references {:boardname "Broad board abroad"}
                                               :replacement nil
                                               :schema-version 1
                                               :state {:_modified 12345 :_user "user-email" :_value "proposal"}
                                               :template {:giver "lautakunta"
                                                          :inclusions ["verdict-code"
                                                                       "handler"
                                                                       "verdict-date"
                                                                       "verdict-section"
                                                                       "verdict-text"]}}})

    (fact "finalize--proposal-pdf"
      (get-in (finalize--proposal-pdf c-v-a) [:updates $set :pate-verdicts.$.proposal.tags])
      => string?
      (provided (lupapalvelu.organization/get-organization-name "753-R" nil) => "Sipoon rakennusvalvonta"))))

(facts "buildings"
  (let [op1  {:id "op1" :name "op-one" :description "desc-one"}
        op2  {:id "op2" :name "op-two" :description "desc-two"}
        op3  {:id "op3" :name "op-three" :description "desc-three"}
        doc1 {:id          "doc1"
              :schema-info {:op {:id "op1"}}
              :data        {:valtakunnallinenNumero {:value "national1"}
                            :tunnus                 {:value "tag1"}}}
        doc2 {:id          "doc2"
              :schema-info {:op {:id "op2"}}
              :data        {:valtakunnallinenNumero  " "
                            :manuaalinen_rakennusnro "manual2"
                            :tunnus                  "tag2"}}
        doc3 {:id          "doc3"
              :schema-info {:op {:id          "op3"
                                 :description "doc3-description"}}
              :data        {}}
        doc4 {:id          "doc4"
              :schema-info {}
              :data        {:valtakunnallinenNumero  "national4"
                            :manuaalinen_rakennusnro "manual4"}}]
    (fact "Primary operation only"
      (buildings {:primaryOperation op1 :documents [doc1 doc2 doc3 doc4]})
      => {:op1 {:operation   "op-one"
                :description "desc-one"
                :building-id "national1"
                :tag         "tag1"
                :order       "0"}})
    (fact "Primary and secondary operations"
      (buildings {:primaryOperation    op2
                  :secondaryOperations [op1 op3]
                  :documents           [doc1 doc2 doc3 doc4]})
      => {:op1 {:operation   "op-one"
                :description "desc-one"
                :building-id "national1"
                :tag         "tag1"
                :order       "1"}
          :op2 {:operation   "op-two"
                :description "desc-two"
                :building-id "manual2"
                :tag         "tag2"
                :order       "0"}
          :op3 {:operation   "op-three"
                :description "desc-three"
                :building-id ""
                :tag         ""
                :order       "1"}})
    (fact "Both building numbers"
      (buildings {:primaryOperation op1
                  :documents        [(assoc-in doc4
                                               [:schema-info :op :id]
                                               "op1")]})
      => {:op1 {:operation   "op-one"
                :description "desc-one"
                :building-id "national4"
                :tag         ""
                :order       "0"}})))
