(ns lupapalvelu.xml.asianhallinta.ely-test
  (:require [midje.sweet :refer :all]
            [clojure.java.io :as io]
            [sade.strings :as ss]
            [sade.schemas :as ssc]
            [sade.xml :as xml]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.integrations.ely :as ely]
            [net.cgrand.enlive-html :as enlive]))


(def schema-xml (-> (io/resource "asianhallinta/asianhallinta_1.3.xsd")
                    (slurp)
                    (xml/parse)))

(def asian-tyypin-tarkenne-type-values
  (->>
    (-> (enlive/select schema-xml [:schema [:simpleType (enlive/attr= :name "AsianTyypinTarkenneType")]])
        first
        (enlive/select [:restriction [:enumeration]]))
    (map #(get-in % [:attrs :value]))))

(fact "Permit type statement values are allowed in asianhallinta schema"
  (doseq [[k permit-config] (permit/permit-types)
          statement-type    (get permit-config :ely-statement-types)]
    (fact {:midje/description (str (name k) " - " statement-type)}
      (some #{statement-type} asian-tyypin-tarkenne-type-values) =not=> nil)))
