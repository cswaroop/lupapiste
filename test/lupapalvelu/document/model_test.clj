(ns lupapalvelu.document.model-test
  (:use [lupapalvelu.document.model]
        [lupapalvelu.document.schemas]
        [midje.sweet]))

;; Simple test schema:

(def schema {:info {:name "test-model"
                    :version 1}
             :body [{:name "1" :type :group
                     :body [{:name "11" :type :string}
                            {:name "12" :type :string :min-len 2 :max-len 3}
                            {:name "2" :type :group
                             :body [{:name "21" :type :string :min-len 2}
                                    {:name "22" :type :boolean}]}
                            {:name "3" :type :list
                             :body [{:name "31" :type :string}
                                    {:name "32" :type :checkbox}]}]}]})

;; Tests for internals:

(def find-by-name #'lupapalvelu.document.model/find-by-name)

(facts "Facts about internals"
  (fact (find-by-name (:body schema) ["1"]) => (-> schema :body first))
  (fact (find-by-name (:body schema) ["1" "11"]) => {:name "11" :type :string})
  (fact (find-by-name (:body schema) ["1" "2" "22"]) => {:name "22" :type :boolean})
  (fact (find-by-name (:body schema) ["1" "2" "23"]) => nil))

;; Validation tests:

(facts "Simple validations"
  (fact (validate-updates schema [["1.12" "foo"]]) => [])
  (fact (validate-updates schema [["1.12" "f"]]) => [["1.12" :warn "illegal-value:too-short"]])
  (fact (validate-updates schema [["1.12" "foooo"]]) => [["1.12" :err "illegal-value:too-long"]])
  (fact (validate-updates schema [["1.12" "f"] ["1.12" "foooo"]]) => [["1.12" :warn "illegal-value:too-short"] ["1.12" :err "illegal-value:too-long"]]))

(facts "with real schemas - important field for paasuunnittelija"
  (let [schema (schemas "paasuunnittelija")]
    (fact (validate-updates schema [["henkilo.henkilotiedot.etunimi" "Tauno"]])   => [])
    (fact (validate-updates schema [["henkilo.henkilotiedot.etunimiz" "Tauno"]])  => [["henkilo.henkilotiedot.etunimiz" :err "illegal-key"]])
    (fact (validate-updates schema [["henkilo.henkilotiedot.sukunimi" "Palo"]])   => [])
    (fact (validate-updates schema [["henkilo.henkilotiedot.etunimi" "Tauno"] ["henkilo.henkilotiedot.sukunimi"  "Palo"]])  => [])
    (fact (validate-updates schema [["henkilo.henkilotiedot.etunimi" "Tauno"] ["henkilo.henkilotiedot.sukunimiz" "Palo"]])  => [["henkilo.henkilotiedot.sukunimiz" :err "illegal-key"]])
    (fact (validate-updates schema [["henkilo.yhteystiedot.email" "tauno@example.com"]]) => [])
    (fact (validate-updates schema [["henkilo.yhteystiedot.puhelin" "050"]]) =>        [])))

(facts "Facts about validation-status"
 (fact (validation-status []) => :ok)
 (fact (validation-status [["foo" :warn "bar"]]) => :warn)
 (fact (validation-status [["foo" :warn "bar"] ["foo2" :warn "bar2"]]) => :warn)
 (fact (validation-status [["foo" :warn "bar"] ["foo2" :err "bar2"]]) => :err))
