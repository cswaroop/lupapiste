(ns lupapalvelu.matti-test
  (:require [lupapalvelu.matti.schemas :as schemas]
            [lupapalvelu.matti.shared  :as shared]
            [midje.sweet :refer :all]
            [schema.core :refer [defschema] :as sc]))

(facts "Verdict template schema data"

  (fact "section"
    (:section (schemas/schema-data shared/default-verdict-template
                                   ["matti-foremen" "pdf"]))
    => (contains {:path [:pdf]}))

  (fact "date-delta"
    (:date-delta (schemas/schema-data shared/default-verdict-template
                                      ["matti-verdict" "1" "lainvoimainen" "enabled"]))
    => (contains {:path [:enabled]
                  :data {:unit :days}}))

  (fact "docgen: select"
    (:docgen (schemas/schema-data shared/default-verdict-template
                                  ["matti-verdict" "2" "giver"]))
    => (contains {:path []
                  :schema {:info {:name "matti-verdict-giver" :version 1}
                           :body '({:name "matti-verdict-giver"
                                   :type :select
                                   :body [{:name "viranhaltija"}
                                          {:name "lautakunta"}]})}
                  :data "matti-verdict-giver"}))

  (fact "docgen: checkbox"
    (:docgen (schemas/schema-data shared/default-verdict-template
                                  ["matti-buildings" "0" "0" "vss-luokka"]))
    => (contains {:path []
                  :schema {:info {:name "matti-verdict-check", :version 1}
                           :body '({:name "matti-verdict-check"
                                    :type :checkbox})}
                  :data "matti-verdict-check"}))

  (fact "reference-list: select"
    (:reference-list (schemas/schema-data shared/default-verdict-template
                                          ["matti-verdict" "2" "1" "verdict-code"]))
    => (contains {:path [:verdict-code],
                  :data (contains {:path [:settings :verdict :0 :verdict-code]
                                   :type :select})}))

  (fact "reference-list: multi-select"
    (:reference-list (schemas/schema-data shared/default-verdict-template
                                          ["matti-reviews" "0" "0" "small"]))
    => (contains {:path [:small],
                  :data (contains {:path [:settings :reviews :0 :reviews]
                                   :type :multi-select})})))

(facts "Settings template schema data"
  (fact "multi-select"
    (:multi-select (schemas/schema-data shared/r-settings
                                ["verdict" "0" "0" "verdict-code"]))
    => (contains {:path [:verdict-code]})))

(def test-template
  {:name "test"
   :sections [{:id "one"
               :grid {:columns 4
                      :rows [[{:col 2
                               :id "a"
                               :schema {:docgen "matti-verdict-check"}}]
                             {:id "row"
                              :row [{:col 2
                                     :id "b"
                                     :schema {:date-delta {:unit :years}}}]}]}}
              {:id "two"
               :grid {:columns 2
                      :rows [[{}
                              {:id "c"
                               :schema {:multi-select {:items [:foo :bar]}}}]
                             {:id "list-row"
                              :row [{:id "d"
                                     :schema {:list
                                              {:items [{:schema {:docgen "matti-string"}}
                                                       {:id "delta"
                                                        :schema {:date-delta {:unit :days}}}
                                                       {:id "ref"
                                                        :schema {:reference-list {:type :select
                                                                                  :path [:path :to :somewhere]}}}]}}}]}]}}
              {:id "three"
               :grid {:columns 4
                      :rows [{:id "docgen"
                              :row [{:id "text"
                                     :schema {:docgen "matti-verdict-text"}}
                                    {:id "select"
                                     :schema {:docgen "matti-verdict-giver"}}
                                    {:id "radio"
                                     :schema {:docgen "automatic-vs-manual"}}]}]}}]})

(facts "Test template is valid"
  (sc/validate shared/MattiVerdict test-template)
  => test-template)

(facts "Id and index are interchangeable for value paths"
  (fact "No row id"
    (let [result {:schema {:info {:name "matti-verdict-check" :version 1}
                           :body '({:name "matti-verdict-check"
                                    :type :checkbox})}
                  :data   "matti-verdict-check"
                  :path []}]
      (:docgen (schemas/schema-data test-template ["one" "0" "0"]))
      => result
      (:docgen (schemas/schema-data test-template ["one" "0" "a"]))
      => result
      (:docgen (schemas/schema-data test-template ["0" "0" "0"]))
      => result))
  (fact "Row id"
    (let [result (contains {:date-delta (contains {:path []
                                                   :data {:unit :years}})})]
      (schemas/schema-data test-template ["one" "row" "b"]) => result
      (schemas/schema-data test-template ["0" "row" "b"]) => result
            (schemas/schema-data test-template ["0" "1" "b"]) => result
      (schemas/schema-data test-template ["0" "1" "0"]) => result
      (schemas/schema-data test-template ["0" "row" "0"]) => result

      (fact "Index can be string, keyword or number. Id can be string or keyword."
        (schemas/schema-data test-template [0 :1 "b"]) => result
        (schemas/schema-data test-template [:0 "1" :b]) => result
        (schemas/schema-data test-template [0 1 :b]) => result))))

(fact "Multi-select"
  (:multi-select (schemas/schema-data test-template ["two" 0 "c"]))
  => (contains {:path []
                :data {:items [:foo :bar]}}))

(fact "List items"
  (:docgen (schemas/schema-data test-template [:two :list-row "d" 0]))
  => (contains {:path []
                :data "matti-string"})
  (:date-delta (schemas/schema-data test-template [:two :list-row "d" :delta :enabled]))
  => (contains {:path [:enabled]
                :data {:unit :days}})
  (:reference-list (schemas/schema-data test-template [:two :list-row 0 2]))
  => (contains {:path []
                :data {:path [:path :to :somewhere]
                       :type :select}}))

(facts "Bad paths"
  (fact "Nil path"
    (schemas/schema-data test-template nil) => nil)
    (fact "Empty path"
      (schemas/schema-data test-template []) => nil)
    (fact "Bad branch"
      (schemas/schema-data test-template ["one" "foo" "bar"]) => nil)
    (fact "Bad leaves are sometimes allowed"
      (schemas/schema-data test-template ["one" "bad"])
      => (contains {:section (contains {:path [:bad]})})
      (schemas/schema-data test-template ["one" 0 "a" "bad"])
      => (contains {:docgen (contains {:path [:bad] :data "matti-verdict-check"})}))
    (fact "Bad section"
      (schemas/schema-data test-template ["bad" "row" "b"]) => nil
      (schemas/schema-data test-template [20 "row" "b"]) => nil
      (schemas/schema-data test-template [-1 "row" "b"]) => nil)
    (fact "Bad row"
      (schemas/schema-data test-template ["one" "bad" "b"]) => nil
      (schemas/schema-data test-template ["one" 30 "b"]) => nil
      (schemas/schema-data test-template ["one" -1 "b"]) => nil)
    (fact "Bad column"
      (schemas/schema-data test-template ["one" "row" "bad"]) => nil
      (schemas/schema-data test-template ["one" "row" 40]) => nil
      (schemas/schema-data test-template ["one" "row" -1]) => nil)
    (fact "Collections in path"
      (schemas/schema-data test-template [[]]) => nil
      (schemas/schema-data test-template [:one [:row] :b]) => nil
      (schemas/schema-data test-template [[:one :row :b]]) => nil))

(facts "Path value validation"
  (fact "Bad paths"
    (schemas/validate-path-value test-template ["foo" "bar"] 88)
    => :error.invalid-value-path
    (schemas/validate-path-value test-template [] 88)
    => :error.invalid-value-path
    (schemas/validate-path-value test-template nil 88)
    => :error.invalid-value-path)
  (facts "Section data"
    (fact "No schema override"
      (schemas/validate-path-value test-template ["one" :pdf] true)
      => :error.invalid-value-path)
    (fact "Section schema override"
      (schemas/validate-path-value test-template ["one" :pdf] true {:schema-overrides {:section shared/MattiVerdictSection}})
      => nil)
    (fact "Section schema override, but bad value"
      (schemas/validate-path-value test-template ["one" :pdf] "bad" {:schema-overrides {:section shared/MattiVerdictSection}})
      => :error.invalid-value)
    (fact "No schema override, non-primitive value"
      (schemas/validate-path-value test-template ["one" :pdf] {:foo "bar"})
      => :error.invalid-value
      (schemas/validate-path-value test-template ["one" :pdf] [:foo "bar"])
      => :error.invalid-value
      (schemas/validate-path-value test-template ["one" :pdf] #{1 2})
      => :error.invalid-value
      (schemas/validate-path-value test-template ["one" :pdf] '(1 2))
      => :error.invalid-value))
  (facts "Date delta"
    (schemas/validate-path-value test-template [:one :row :b :delta] 4)
    => nil
    (schemas/validate-path-value test-template [:one :row :b :delta] 0)
    => nil
    (schemas/validate-path-value test-template [:one :row :b :enabled] true)
    => nil
    (schemas/validate-path-value test-template [:one :row :b :enabled] false)
    => nil
    (fact "Bad paths"
      (schemas/validate-path-value test-template [:one :row :b :delta :foobar] 4)
      => :error.invalid-value-path
      (schemas/validate-path-value test-template [:one :row :b :bad] 4)
      => :error.invalid-value-path
      (schemas/validate-path-value test-template [:one :row :b] 4)
      => :error.invalid-value-path)
    (fact "Bad value"
      (schemas/validate-path-value test-template [:one :row :b :delta] -4)
      => :error.invalid-value
      (schemas/validate-path-value test-template [:one :row :b :delta] "hiihoo")
      => :error.invalid-value
      (schemas/validate-path-value test-template [:one :row :b :delta] [8 9])
      => :error.invalid-value
      (schemas/validate-path-value test-template [:one :row :b :delta] nil)
      => :error.invalid-value
      (schemas/validate-path-value test-template [:one :row :b :enabled] "hiihoo")
      => :error.invalid-value)
    (fact "Unit cannot be changed"
      (schemas/validate-path-value test-template [:one :row :b :unit] :years)
      => :error.invalid-value-path)
    (fact "Automatic number conversion"
      (schemas/validate-path-value test-template [:one :row :b :delta] "8")
      => nil))
  (facts "Multi select"
    (schemas/validate-path-value test-template ["two" 0 "c"] [])
    => nil
    (schemas/validate-path-value test-template ["two" 0 "c"] [:foo])
    => nil
    (schemas/validate-path-value test-template ["two" 0 "c"] ["bar"])
    => nil
    (schemas/validate-path-value test-template ["two" 0 "c"] ["foo" :bar])
    => nil
    (fact "Items cannot be part of the path"
      (schemas/validate-path-value test-template ["two" 0 "c" :items] [])
      => :error.invalid-value-path)
    (fact "Invalid items"
      (schemas/validate-path-value test-template ["two" 0 "c"] ["foo" :bar :baz])
      => :error.invalid-value
      (schemas/validate-path-value test-template ["two" 0 "c"] [:baz])
      => :error.invalid-value
      (schemas/validate-path-value test-template ["two" 0 "c"] [:baz :doh])
      => :error.invalid-value)
    (fact "Duplicate items"
      (schemas/validate-path-value test-template ["two" 0 "c"] ["foo" :foo])
      => :error.duplicate-items
      (schemas/validate-path-value test-template ["two" 0 "c"] [:foo :bar :bar])
      => :error.duplicate-items
      (schemas/validate-path-value test-template ["two" 0 "c"] [:bad :bad])
      => :error.invalid-value))
  (facts "Reference list"
    (let [opts {:references {:path {:to {:somewhere ["ref1" "ref2" "ref3"]}}}}
          path ["two" :list-row "d" :ref]]
      (schemas/validate-path-value test-template path ["ref1" "ref2"] opts)
      => nil
      (schemas/validate-path-value test-template path [:ref1 "ref2"] opts)
      => nil
      (schemas/validate-path-value test-template path [] opts) => nil
      (fact "Bad path"
        (schemas/validate-path-value test-template (conj path :items) [] opts)
        => :error.invalid-value-path
        (schemas/validate-path-value test-template (conj path :items)
                                     ["two" :list-row "d"] opts)
        => :error.invalid-value-path)
      (fact "Invalid items"
        (schemas/validate-path-value test-template path ["ref1" "bad"] opts)
        => :error.invalid-value
        (schemas/validate-path-value test-template path ["ref1" :bad] opts)
        => :error.invalid-value
        (schemas/validate-path-value test-template path ["ref1" nil] opts)
        => :error.invalid-value
        (fact "Duplicate items"
          (schemas/validate-path-value test-template path ["ref1" :ref1] opts)
          => :error.duplicate-items
          (schemas/validate-path-value test-template path
                                       ["ref2" :ref3 "ref2"] opts)
          => :error.duplicate-items))))
  (facts "Docgen"
    (facts "checkbox"
      (schemas/validate-path-value test-template ["one" "0" "0"] true)
      => nil
      (schemas/validate-path-value test-template ["one" "0" "0"] false)
      => nil
      (fact "bad path"
        (schemas/validate-path-value test-template ["one" "0" "0" :bad] true)
        => :error.invalid-value-path)
      (fact "bad value"
        (schemas/validate-path-value test-template ["one" "0" "0"] nil)
        => :error.invalid-value
        (schemas/validate-path-value test-template ["one" "0" "0"] "bad")
        => :error.invalid-value))
    (facts "string"
      (schemas/validate-path-value test-template [:two :list-row "d" 0] "hello")
      => nil
      (schemas/validate-path-value test-template [:two :list-row "d" 0] "")
      => nil
      (fact "bad path"
        (schemas/validate-path-value test-template [:two :list-row "d" 0 :bad] "hello")
        => :error.invalid-value-path)
      (fact "bad value"
        (schemas/validate-path-value test-template [:two :list-row "d" 0] 1234)
        => :error.invalid-value
        (schemas/validate-path-value test-template [:two :list-row "d" 0] nil)
        => :error.invalid-value))
    (facts "text"
      (schemas/validate-path-value test-template [:three :docgen :text] "hello")
      => nil
      (schemas/validate-path-value test-template [:three :docgen :text] "")
      => nil
      (fact "bad path"
        (schemas/validate-path-value test-template [:three :docgen :text :bad] "hello")
        => :error.invalid-value-path)
      (fact "bad value"
        (schemas/validate-path-value test-template [:three :docgen :text] 1234)
        => :error.invalid-value
        (schemas/validate-path-value test-template [:three :docgen :text] nil)
        => :error.invalid-value))
    (facts "select"
      (schemas/validate-path-value test-template [:three :docgen :select] "viranhaltija")
      => nil
      (schemas/validate-path-value test-template [:three :docgen :select] :lautakunta)
      => nil
      (schemas/validate-path-value test-template [:three :docgen :select] nil)
      => nil
      (fact "bad path"
        (schemas/validate-path-value test-template [:three :docgen :select :bad] "hello")
        => :error.invalid-value-path)
      (fact "bad value"
        (schemas/validate-path-value test-template [:three :docgen :select] "")
        => :error.invalid-value
        (schemas/validate-path-value test-template [:three :docgen :select] 1234)
        => :error.invalid-value
        (schemas/validate-path-value test-template [:three :docgen :select] [:lautakunta :viranhaltija])
        => :error.invalid-value))
    (facts "radio group"
      (schemas/validate-path-value test-template [:three :docgen :radio] "automatic")
      => nil
      (schemas/validate-path-value test-template [:three :docgen :radio] :manual)
      => nil
      (fact "bad path"
        (schemas/validate-path-value test-template [:three :docgen :radio :bad] "hello")
        => :error.invalid-value-path)
      (fact "bad value"
        (schemas/validate-path-value test-template [:three :docgen :radio] "")
        => :error.invalid-value
        (schemas/validate-path-value test-template [:three :docgen :radio] 1234)
        => :error.invalid-value
        (schemas/validate-path-value test-template [:three :docgen :radio] [:automatic :manual])
        => :error.invalid-value
        (schemas/validate-path-value test-template [:three :docgen :radio] nil)
        => :error.invalid-value))))