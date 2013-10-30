(ns lupapalvelu.document.rakennuslupa_canonical-test
  (:require [lupapalvelu.document.canonical-test-common :refer :all]
            [lupapalvelu.document.canonical-common :refer :all]
            [lupapalvelu.document.rakennuslupa_canonical :refer :all]
            [lupapalvelu.xml.emit :refer :all]
            [lupapalvelu.xml.krysp.rakennuslupa-mapping :refer :all]
            [lupapalvelu.factlet :as fl]
            [sade.util :refer [contains-value?]]
            [clojure.data.xml :refer :all]
            [clj-time.core :refer [date-time]]
            [midje.sweet :refer :all]))

;;
;; Facts
;;

(facts "Date format"
  (fact (to-xml-date (date-time 2012 1 14)) => "2012-01-14")
  (fact (to-xml-date (date-time 2012 2 29)) => "2012-02-29"))

(def municipality 753)

(def nimi {:etunimi {:value "Pena"} :sukunimi {:value "Penttil\u00e4"}})

(def henkilotiedot (assoc nimi :hetu {:value "210281-9988"} :turvakieltoKytkin {:value true}))

(def osoite {:katu {:value "katu"} :postinumero {:value "33800"} :postitoimipaikannimi {:value "Tuonela"}})

(def henkilo
  {:henkilotiedot henkilotiedot
   :yhteystiedot {:puhelin {:value "+358401234567"}
                  :email {:value "pena@example.com"}}
   :osoite osoite})

(def suunnittelija-henkilo
  (assoc henkilo :henkilotiedot (dissoc henkilotiedot :turvakieltoKytkin)))

(def yritysnimi-ja-ytunnus
  {:yritysnimi {:value "Solita Oy"} :liikeJaYhteisoTunnus {:value "1060155-5"}})

(def yritys
  (merge
    yritysnimi-ja-ytunnus
    {:osoite osoite
     :yhteyshenkilo {:henkilotiedot (dissoc henkilotiedot :hetu)
                     :yhteystiedot {:email {:value "solita@solita.fi"},
                                    :puhelin {:value "03-389 1380"}}}}))

(def hakija1
  {:id "hakija1" :schema-info {:name "hakija"
                               :version 1}
   :data {:henkilo henkilo}})

(def hakija2
  {:id "hakija2" :schema-info {:name "hakija"
                               :version 1}
   :data {:_selected {:value "yritys"}, :yritys yritys}})

(def paasuunnittelija
  {:id "50bc85e4ea3e790c9ff7cdb2"
   :schema-info {:name "paasuunnittelija"
                 :version 1}
   :data (merge
           suunnittelija-henkilo
           {:patevyys {:koulutus {:value "Arkkitehti"} :patevyysluokka {:value "ei tiedossa"}}}
           {:yritys yritysnimi-ja-ytunnus})})

(def suunnittelija1
  {:id "suunnittelija1" :schema-info {:name "suunnittelija"
                                      :version 1}
   :data (merge suunnittelija-henkilo
                {:kuntaRoolikoodi {:value "ARK-rakennussuunnittelija"}}
                {:patevyys {:koulutus {:value "Koulutus"} :patevyysluokka {:value "B"}}}
                {:yritys yritysnimi-ja-ytunnus})})

(def suunnittelija2
  {:id "suunnittelija2"  :schema-info {:name "suunnittelija"
                                       :version 1}
   :data (merge suunnittelija-henkilo
                {:kuntaRoolikoodi {:value "GEO-suunnittelija"}}
                {:patevyys {:koulutus {:value "El\u00e4m\u00e4n koulu"} :patevyysluokka {:value "AA"}}}
                {:yritys yritysnimi-ja-ytunnus})})

(def suunnittelija-old-schema-LUPA-771
  {:id "suunnittelija-old-schema-LUPA771" :schema-info {:name "suunnittelija"
                                                        :version 1}
   :data (merge suunnittelija-henkilo
                {:patevyys {:koulutus {:value "Koulutus"}
                            :kuntaRoolikoodi {:value "ARK-rakennussuunnittelija"}
                            :patevyysluokka {:value "B"}}})})

(def suunnittelija-blank-role
  {:id "suunnittelija-blank-role" :schema-info {:name "suunnittelija"
                                                :version 1}
   :data (merge suunnittelija-henkilo
                {:kuntaRoolikoodi {:value ""}}
                {:patevyys {:koulutus {:value "Koulutus"} :patevyysluokka {:value "B"}}}
                {:yritys yritysnimi-ja-ytunnus})})

(def maksaja1
  {:id "maksaja1" :schema-info {:name "maksaja"
                                :version 1}
   :data {:henkilo henkilo}})

(def maksaja2
  {:id "maksaja2" :schema-info {:name "maksaja"
                                :version 1}
   :data {:_selected {:value "yritys"}, :yritys yritys}})

(def tyonjohtaja
  {:id "tyonjohtaja"
   :schema-info {:name "tyonjohtaja", :version 1}
   :data (merge suunnittelija-henkilo
           {:kuntaRoolikoodi {:value "KVV-ty\u00f6njohtaja"}
            :patevyys {:koulutus {:value "Koulutus"}
                       :patevyysvaatimusluokka {:value "AA"}
                       :valmistumisvuosi {:value "2010"}
                       :tyonjohtajaHakemusKytkin {:value "hakemus"}
                       :kokemusvuodet {:value "3"}
                       :valvottavienKohteidenMaara {:value "9"}}
            :vastattavatTyotehtavat {:kiinteistonVesiJaViemarilaitteistonRakentaminen {:value true}
                                     :kiinteistonilmanvaihtolaitteistonRakentaminen {:value true}
                                     :maanrakennustyo {:value true}
                                     :rakennelmaTaiLaitos {:value true}
                                     :muuMika {:value "Muu tyotehtava"}}
            :yritys yritysnimi-ja-ytunnus})})

(def tyonjohtaja-blank-role-and-blank-qualification
  (-> tyonjohtaja
    (assoc-in [:data :kuntaRoolikoodi :value] "")
    (assoc-in [:data :patevyys :patevyysvaatimusluokka :value] "Ei tiedossa")
    (assoc-in [:data :patevyys :tyonjohtajaHakemusKytkin :value] "nimeaminen")))

(def rakennuspaikka
  {:id "rakennuspaikka" :schema-info {:name "rakennuspaikka"
                                      :version 1}
   :data {:kiinteisto {:tilanNimi {:value "Hiekkametsa"}
                       :maaraalaTunnus {:value ""}}
          :hallintaperuste {:value "oma"}
          :kaavanaste {:value "yleis"}}})

(def common-rakennus {:rakennuksenOmistajat {:0 {:_selected {:value "henkilo"}
                                                 :henkilo henkilo
                                                 :omistajalaji {:value "muu yksityinen henkil\u00f6 tai perikunta"}}}
                      :kaytto {:rakentajaTyyppi {:value "muu"}
                               :kayttotarkoitus {:value "012 kahden asunnon talot"}}
                      :mitat {:tilavuus {:value "1500"}
                              :kokonaisala {:value "1000"}
                              :kellarinpinta-ala {:value "100"}
                              :kerrosluku {:value "2"}
                              :kerrosala {:value "180"}}
                      :rakenne {:rakentamistapa {:value "elementti"}
                                :kantavaRakennusaine {:value "puu"}
                                :muuRakennusaine {:value ""}
                                :julkisivu {:value "puu"}}
                      :lammitys {:lammitystapa {:value "vesikeskus"}
                                 :lammonlahde {:value "other"}
                                 :muu-lammonlahde {:value "polttopuillahan tuo"}}
                      :varusteet {:hissiKytkin {:value true},
                                  :kaasuKytkin {:value true},
                                  :koneellinenilmastointiKytkin {:value true},
                                  :sahkoKytkin {:value true},
                                  :saunoja {:value "1"},
                                  :vaestonsuoja {:value "1"},
                                  :vesijohtoKytkin {:value true},
                                  :viemariKytkin {:value true}
                                  :lamminvesiKytkin {:value true}
                                  :aurinkopaneeliKytkin {:value true}}
                      :verkostoliittymat {:kaapeliKytkin {:value true},
                                          :maakaasuKytkin {:value true},
                                          :sahkoKytkin {:value true},
                                          :vesijohtoKytkin {:value true},
                                          :viemariKytkin {:value true}},
                      :luokitus {:paloluokka {:value "P1"}
                                 :energialuokka {:value "C"}
                                 :energiatehokkuusluku {:value "124"}
                                 :energiatehokkuusluvunYksikko {:value "kWh/m2"}}
                      :huoneistot {:0 {:muutostapa {:value "lis\u00e4ys"}
                                       :huoneistoTunnus {:porras {:value "A"} :huoneistonumero {:value "1"} :jakokirjain {:value "a"}}
                                       :huoneistonTyyppi {:huoneistoTyyppi {:value "asuinhuoneisto"}
                                                          :huoneistoala {:value "56"}
                                                          :huoneluku {:value "66"}}
                                       :keittionTyyppi {:value "keittio"}
                                       :varusteet {:parvekeTaiTerassiKytkin {:value true}, :WCKytkin {:value true}}}
                                   :1 {:muutostapa {:value "lis\u00e4ys"}
                                       :huoneistoTunnus {},
                                       :huoneistonTyyppi {:huoneistoTyyppi {:value "toimitila"}
                                                          :huoneistoala {:value "02"}
                                                          :huoneluku {:value "12"}}
                                       :keittionTyyppi {:value "keittokomero"},
                                       :varusteet {:ammeTaiSuihkuKytkin {:value true}, :saunaKytkin {:value true}, :lamminvesiKytkin {:value true}}}}})

(def uusi-rakennus
  {:id "uusi-rakennus"
   :created 2
   :schema-info {:name "uusiRakennus"
                 :version 1
                 :op {:name "asuinrakennus"}}
   :data common-rakennus})

(def rakennuksen-muuttaminen
  {:id "muuttaminen"
   :created 1
   :schema-info {:name "rakennuksen-muuttaminen"
                 :version 1
                 :op {:name "muu-laajentaminen"}}
   :data (conj {:rakennusnro {:value "001"}
                :perusparannuskytkin {:value true}
                :muutostyolaji {:value "muut muutosty\u00f6t"}} common-rakennus)})

(def laajentaminen
  {:id "laajennus"
   :created 3
   :schema-info {:name "rakennuksen-laajentaminen"
                 :version 1
                 :op {:name "laajentaminen"}}
   :data (conj {:rakennusnro {:value "001"}
                :manuaalinen_rakennusnro {:value "002"}
                :laajennuksen-tiedot {:perusparannuskytkin {:value true}
                                      :mitat {:tilavuus {:value "1500"}
                                              :kerrosala {:value "180"}
                                              :kokonaisala {:value "150"}
                                              :huoneistoala {:0 {:pintaAla {:value "150"}
                                                                 :kayttotarkoitusKoodi {:value "asuntotilaa(ei vapaa-ajan asunnoista)"}}
                                                             :1 {:pintaAla {:value "10"}
                                                                 :kayttotarkoitusKoodi {:value "varastotilaa"}}}}}} common-rakennus)})


(def purku {:id "purku"
            :created 4
            :schema-info {:name "purku"
                          :version 1
                          :op {:name "purkaminen"}}
            :data (conj {:rakennusnro {:value "001"}
                         :poistumanAjankohta {:value "17.04.2013"},
                         :poistumanSyy {:value "tuhoutunut"}} common-rakennus)})

(def aidan-rakentaminen {:data {:kokonaisala {:value "0"}
                                :kuvaus { :value "Aidan rakentaminen rajalle"}}
                         :id "aidan-rakentaminen"
                         :created 5
                         :schema-info {:removable true
                                       :op {:id  "5177ac76da060e8cd8348e07"
                                            :name "aita"}
                                       :name "kaupunkikuvatoimenpide"
                                       :version 1}})

(def puun-kaataminen {:created 6
                      :data { :kuvaus {:value "Puun kaataminen"}}
                      :id "puun kaataminen"
                      :schema-info {:removable true
                                    :op {:id "5177ad63da060e8cd8348e32"
                                         :name "puun-kaataminen"
                                         :created  1366797667137}
                                    :name "maisematyo"
                                    :version 1}})

(def hankkeen-kuvaus {:id "Hankeen kuvaus" :schema-info {:name "hankkeen-kuvaus"
                                                         :version 1
                                                         :order 1}
                      :data {:kuvaus {:value "Uuden rakennuksen rakentaminen tontille."}
                             :poikkeamat {:value "Ei poikkeamisia"}}})

(def lisatieto {:id "lisatiedot" :schema-info {:name "lisatiedot"
                                               :version 1}
                :data {:suoramarkkinointikielto {:value true}}})

;TODO LIITETIETO

(def documents
  [hankkeen-kuvaus
   hakija1
   hakija2
   paasuunnittelija
   suunnittelija1
   suunnittelija2
   maksaja1
   maksaja2
   tyonjohtaja
   rakennuspaikka
   rakennuksen-muuttaminen
   uusi-rakennus
   laajentaminen
   aidan-rakentaminen
   puun-kaataminen
   purku
   lisatieto])

(fact "Meta test: hakija1"          hakija1          => valid-against-current-schema?)
(fact "Meta test: hakija2"          hakija2          => valid-against-current-schema?)
(fact "Meta test: paasuunnittelija" paasuunnittelija => valid-against-current-schema?)
(fact "Meta test: suunnittelija1"   suunnittelija1   => valid-against-current-schema?)
(fact "Meta test: suunnittelija2"   suunnittelija2   => valid-against-current-schema?)
(fact "Meta test: maksaja1"         maksaja1         => valid-against-current-schema?)
(fact "Meta test: maksaja2"         maksaja2         => valid-against-current-schema?)
(fact "Meta test: tyonjohtaja"      tyonjohtaja      => valid-against-current-schema?)
(fact "Meta test: rakennuspaikka"   rakennuspaikka   => valid-against-current-schema?)
(fact "Meta test: uusi-rakennus"    uusi-rakennus    => valid-against-current-schema?)
(fact "Meta test: lisatieto"        lisatieto        => valid-against-current-schema?)
(fact "Meta test: hankkeen-kuvaus"  hankkeen-kuvaus  => valid-against-current-schema?)

;; In case a document was added but forgot to write test above
(validate-all-documents documents)

(def application
  {:permitType "R",
   :municipality municipality,
   :auth
   [{:lastName "Panaani",
     :firstName "Pena",
     :username "pena",
     :type "owner",
     :role "owner",
     :id "777777777777777777000020"}],
   :state "open"
   :opened 1354532324658
   :location {:x 408048, :y 6693225},
   :attachments [],
   :authority {:id "777777777777777777000023",
               :username "sonja",
               :firstName "Sonja",
               :lastName "Sibbo",
               :role "authority"},
   :title "s",
   :created 1354532324658,
   :documents documents,
   :propertyId "21111111111111"
   :modified 1354532324691,
   :address "Katutie 54",
   :id "50bc85e4ea3e790c9ff7cdb0"
   :statements [{:given 1368080324142
                 :id "518b3ee60364ff9a63c6d6a1"
                 :person {:text "Paloviranomainen"
                          :name "Sonja Sibbo"
                          :email "sonja.sibbo@sipoo.fi"
                          :id "516560d6c2e6f603beb85147"}
                 :requested 1368080102631
                 :status "condition"
                 :text "Savupiippu pit\u00e4\u00e4 olla."}]})

(defn- validate-minimal-person [person]
  (fact person => (contains {:nimi {:etunimi "Pena" :sukunimi "Penttil\u00e4"}})))

(defn- validate-address [address]
  (let [person-katu (:teksti (:osoitenimi address))
        person-postinumero (:postinumero address)
        person-postitoimipaikannimi (:postitoimipaikannimi address)]
    (fact address => truthy)
    (fact person-katu => "katu")
    (fact person-postinumero =>"33800")
    (fact person-postitoimipaikannimi => "Tuonela")))

(defn- validate-contact [m]
  (fact m => (contains {:puhelin "+358401234567"
                        :sahkopostiosoite "pena@example.com"})))

(defn- validate-person-wo-ssn [person]
  (validate-minimal-person person)
  (validate-contact person)
  (validate-address (:osoite person)))

(defn- validate-person [person]
  (validate-person-wo-ssn person)
  (fact (:henkilotunnus person) => "210281-9988"))

(defn- validate-minimal-company [company]
  (fact company => (contains {:nimi "Solita Oy" :liikeJaYhteisotunnus "1060155-5"}))
  ; postiosoite is required in KRYSP Rakennusvalvonta
  (validate-address (:postiosoite company)))

(defn- validate-company [company]
  (validate-minimal-company company)
  (fact "puhelin" (:puhelin company) => "03-389 1380")
  (fact "sahkopostiosoite" (:sahkopostiosoite company) => "solita@solita.fi"))

(facts "Canonical hakija/henkilo model is correct"
  (let [hakija-model (get-osapuoli-data (:data hakija1) :hakija)
        henkilo (:henkilo hakija-model)
        ht (:henkilotiedot henkilo)
        yritys (:yritys hakija-model)]
    (fact "model" hakija-model => truthy)
    (fact "kuntaRooliKoodi" (:kuntaRooliKoodi hakija-model) => "Rakennusvalvonta-asian hakija")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi hakija-model) => "hakija")
    (fact "turvakieltoKytkin" (:turvakieltoKytkin hakija-model) => true)
    (validate-person henkilo)
    (fact "yritys is nil" yritys => nil)))

(facts "Canonical hakija/yritys model is correct"
  (let [hakija-model (get-osapuoli-data (:data hakija2) :hakija)
        henkilo (:henkilo hakija-model)
        yritys (:yritys hakija-model)]
    (fact "model" hakija-model => truthy)
    (fact "kuntaRooliKoodi" (:kuntaRooliKoodi hakija-model) => "Rakennusvalvonta-asian hakija")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi hakija-model) => "hakija")
    (fact "turvakieltoKytkin" (:turvakieltoKytkin hakija-model) => true)
    (validate-minimal-person henkilo)
    (validate-company yritys)))

(fact "Empty body"
  (empty? (get-parties-by-type
    {"paasuunnittelija" [{:data {}}]} :Suunnittelija ["paasuunnittelija"] get-suunnittelija-data)) => truthy)

(facts "Canonical paasuunnittelija/henkilo+yritys model is correct"
  (let [suunnittelija-model (get-suunnittelija-data (:data paasuunnittelija) :paasuunnittelija)
        henkilo (:henkilo suunnittelija-model)
        yritys (:yritys suunnittelija-model)]
    (fact "model" suunnittelija-model => truthy)
    (fact "suunnittelijaRoolikoodi" (:suunnittelijaRoolikoodi suunnittelija-model) => "p\u00e4\u00e4suunnittelija")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi suunnittelija-model) => "p\u00e4\u00e4suunnittelija")
    (fact "koulutus" (:koulutus suunnittelija-model) => "Arkkitehti")
    (fact "patevyysvaatimusluokka" (:patevyysvaatimusluokka suunnittelija-model) => "ei tiedossa")
    (validate-person henkilo)
    (validate-minimal-company yritys)))

(facts "Canonical suunnittelija1 model is correct"
  (let [suunnittelija-model (get-suunnittelija-data (:data suunnittelija1) :suunnittelija)]
    (fact "model" suunnittelija-model => truthy)
    (fact "suunnittelijaRoolikoodi" (:suunnittelijaRoolikoodi suunnittelija-model) => "ARK-rakennussuunnittelija")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi suunnittelija-model) => "rakennussuunnittelija")
    (fact "koulutus" (:koulutus suunnittelija-model) => "Koulutus")
    (fact "patevyysvaatimusluokka" (:patevyysvaatimusluokka suunnittelija-model) => "B")
    (fact "henkilo" (:henkilo suunnittelija-model) => truthy)
    (fact "yritys" (:yritys suunnittelija-model) => truthy)))

(facts "Canonical suunnittelija2 model is correct"
  (let [suunnittelija-model (get-suunnittelija-data (:data suunnittelija2) :suunnittelija)]
    (fact "model" suunnittelija-model => truthy)
    (fact "suunnittelijaRoolikoodi" (:suunnittelijaRoolikoodi suunnittelija-model) => "GEO-suunnittelija")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi suunnittelija-model) => "erityissuunnittelija")
    (fact "koulutus" (:koulutus suunnittelija-model) => "El\u00e4m\u00e4n koulu")
    (fact "patevyysvaatimusluokka" (:patevyysvaatimusluokka suunnittelija-model) => "AA")
    (fact "henkilo" (:henkilo suunnittelija-model) => truthy)
    (fact "yritys" (:yritys suunnittelija-model) => truthy)))

(facts "Transforming old sunnittelija schema to canonical model is correct"
  (let [suunnittelija-model (get-suunnittelija-data (:data suunnittelija-old-schema-LUPA-771) :suunnittelija)]
    (fact "model" suunnittelija-model => truthy)
    (fact "suunnittelijaRoolikoodi" (:suunnittelijaRoolikoodi suunnittelija-model) => "ARK-rakennussuunnittelija")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi suunnittelija-model) => "rakennussuunnittelija")
    (fact "koulutus" (:koulutus suunnittelija-model) => "Koulutus")
    (fact "patevyysvaatimusluokka" (:patevyysvaatimusluokka suunnittelija-model) => "B")))

(facts "Canonical suunnittelija-blank-role model is correct"
  (let [suunnittelija-model (get-suunnittelija-data (:data suunnittelija-blank-role) :suunnittelija)]
    (fact "model" suunnittelija-model => truthy)
    (fact "suunnittelijaRoolikoodi" (:suunnittelijaRoolikoodi suunnittelija-model) => "ei tiedossa")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi suunnittelija-model) => "ei tiedossa")))

(facts "Canonical tyonjohtaja model is correct"
  (let [tyonjohtaja-model (get-tyonjohtaja-data (:data tyonjohtaja) :tyonjohtaja)
        henkilo (:henkilo tyonjohtaja-model)
        yritys (:yritys tyonjohtaja-model)]
    (fact "model" tyonjohtaja-model => truthy)
    (fact "tyonjohtajaRooliKoodi" (:tyonjohtajaRooliKoodi tyonjohtaja-model) => "KVV-ty\u00f6njohtaja")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi tyonjohtaja-model) => "ty\u00f6njohtaja")
    (fact "koulutus" (:koulutus tyonjohtaja-model) => "Koulutus")
    (fact "valmistumisvuosi" (:valmistumisvuosi tyonjohtaja-model) => "2010")
    (fact "patevyysvaatimusluokka" (:patevyysvaatimusluokka tyonjohtaja-model) => "AA")
    (fact "kokemusvuodet" (:kokemusvuodet tyonjohtaja-model) => "3")
    (fact "valvottavienKohteidenMaara" (:valvottavienKohteidenMaara tyonjohtaja-model) => "9")
    (fact "tyonjohtajaHakemusKytkin" (:tyonjohtajaHakemusKytkin tyonjohtaja-model) => true)
    (fact "vastattavatTyotehtavat" (:vastattavatTyotehtavat tyonjohtaja-model) =>
      "kiinteistonilmanvaihtolaitteistonRakentaminen,rakennelmaTaiLaitos,maanrakennustyo,kiinteistonVesiJaViemarilaitteistonRakentaminen,Muu tyotehtava")
    (fact "henkilo" (:henkilo tyonjohtaja-model) => truthy)
    (fact "yritys" (:yritys tyonjohtaja-model) => truthy)
    (validate-person henkilo)
    (validate-minimal-company yritys)))

(facts "Canonical tyonjohtaja-blank-role-and-blank-qualification model is correct"
  (let [tyonjohtaja-model (get-tyonjohtaja-data (:data tyonjohtaja-blank-role-and-blank-qualification) :tyonjohtaja)]
    (fact "model" tyonjohtaja-model => truthy)
    (fact "tyonjohtajaRooliKoodi" (:tyonjohtajaRooliKoodi tyonjohtaja-model) => "ei tiedossa")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi tyonjohtaja-model) => "ei tiedossa")
    (fact "patevyysvaatimusluokka" (:patevyysvaatimusluokka tyonjohtaja-model) => "Ei tiedossa")
    (fact "tyonjohtajaHakemusKytkin" (:tyonjohtajaHakemusKytkin tyonjohtaja-model) => false)))

(facts "Canonical maksaja/henkilo model is correct"
  (let [maksaja-model (get-osapuoli-data (:data maksaja1) :maksaja)
        henkilo (:henkilo maksaja-model)
        yritys (:yritys maksaja-model)]
    (fact "model" maksaja-model => truthy)
    (fact "kuntaRooliKoodi" (:kuntaRooliKoodi maksaja-model) => "Rakennusvalvonta-asian laskun maksaja")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi maksaja-model) => "maksaja")
    (fact "turvakieltoKytkin" (:turvakieltoKytkin maksaja-model) => true)
    (validate-person henkilo)
    (fact "yritys is nil" yritys => nil)))

(facts "Canonical maksaja/yritys model is correct"
  (let [maksaja-model (get-osapuoli-data (:data maksaja2) :maksaja)
        henkilo (:henkilo maksaja-model)
        yritys (:yritys maksaja-model)]
    (fact "model" maksaja-model => truthy)
    (fact "kuntaRooliKoodi" (:kuntaRooliKoodi maksaja-model) => "Rakennusvalvonta-asian laskun maksaja")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi maksaja-model) => "maksaja")
    (validate-minimal-person henkilo)
    (validate-company yritys)))

(def get-handler #'lupapalvelu.document.canonical-common/get-handler)

(facts "Handler is sonja"
  (let [handler (get-handler application)
        name (get-in handler [:henkilo :nimi])]
    (fact "handler" handler => truthy)
    (fact "etunimi" (:etunimi name) => "Sonja")
    (fact "sukunimi" (:sukunimi name) => "Sibbo")))

(def get-actions #'lupapalvelu.document.rakennuslupa_canonical/get-operations)

(facts "Toimenpiteet"
  (let [documents (by-type (:documents application))
        actions (get-actions documents application)]
    ;(clojure.pprint/pprint actions)
    (fact "actions" (seq actions) => truthy)))

(def get-huoneisto-data #'lupapalvelu.document.rakennuslupa_canonical/get-huoneisto-data)

(facts "Huoneisto is correct"
  (let [huoneistot (get-huoneisto-data (get-in uusi-rakennus [:data :huoneistot]))
        h1 (first huoneistot), h2 (last huoneistot)]
    (fact "h1 huoneistot count" (count huoneistot) => 2)
    (fact "h1 muutostapa" (:muutostapa h1) => "lis\u00e4ys")
    (fact "h1 huoneluku" (:huoneluku h1) => "66")
    (fact "h1 keittionTyyppi" (:keittionTyyppi h1) => "keittio")
    (fact "h1 huoneistoala" (:huoneistoala h1) => "56")
    (fact "h1 huoneistonTyyppi" (:huoneistonTyyppi h1) => "asuinhuoneisto")
    (fact "h1 varusteet: WCKytkin" (-> h1 :varusteet :WCKytkin) => true)
    (fact "h1 varusteet: ammeTaiSuihkuKytkin" (-> h1 :varusteet :ammeTaiSuihkuKytkin) => false)
    (fact "h1 varusteet: saunaKytkin" (-> h1 :varusteet :saunaKytkin) => false)
    (fact "h1 varusteet: parvekeTaiTerassiKytkin" (-> h1 :varusteet :parvekeTaiTerassiKytkin) => true)
    (fact "h1 varusteet: lamminvesiKytkin" (-> h1 :varusteet :lamminvesiKytkin) => false)
    (fact "h1 huoneistotunnus" (:huoneistotunnus h1) => truthy)
    (fact "h1 huoneistotunnus: porras" (-> h1 :huoneistotunnus :porras) => "A")
    (fact "h1 huoneistotunnus: huoneistonumero" (-> h1 :huoneistotunnus :huoneistonumero) => "001")
    (fact "h1 huoneistotunnus: jakokirjain" (-> h1 :huoneistotunnus :jakokirjain) => "a")

    (fact "h2 muutostapa" (:muutostapa h2) => "lis\u00e4ys")
    (fact "h2 huoneluku" (:huoneluku h2) => "12")
    (fact "h2 keittionTyyppi" (:keittionTyyppi h2) => "keittokomero")
    (fact "h2 huoneistoala" (:huoneistoala h2) => "02")
    (fact "h2 huoneistonTyyppi" (:huoneistonTyyppi h2) => "toimitila")
    (fact "h2 varusteet: WCKytkin" (-> h2 :varusteet :WCKytkin) => false)
    (fact "h2 varusteet: ammeTaiSuihkuKytkin" (-> h2 :varusteet :ammeTaiSuihkuKytkin) => true)
    (fact "h2 varusteet: saunaKytkin" (-> h2 :varusteet :saunaKytkin) => true)
    (fact "h2 varusteet: parvekeTaiTerassiKytkin" (-> h2 :varusteet :parvekeTaiTerassiKytkin) => false)
    (fact "h2 varusteet: lamminvesiKytkin" (-> h2 :varusteet :lamminvesiKytkin) => true)
    (fact "h2 huoneistotunnus" (:huoneistotunnus h2) => falsey)))

(def get-rakennus #'lupapalvelu.document.rakennuslupa_canonical/get-rakennus)

(facts "When muu-lammonlahde is empty, lammonlahde is used"
  (let [rakennus (get-rakennus {:lammitys {:lammitystapa {:value nil}
                                           :lammonlahde  {:value "turve"}
                                           :muu-lammonlahde {:value nil}} }
                               {:id "123" :created nil} application)]
    (fact (:polttoaine (:lammonlahde (:rakennuksenTiedot rakennus))) => "turve")))

(facts "When muu-lammonlahde is specified, it is used"
  (let [rakennus (get-rakennus {:lammitys {:lammitystapa {:value nil}
                                           :lammonlahde  {:value "other"}
                                           :muu-lammonlahde {:value "fuusioenergialla"}} }
                               {:id "123" :created nil} application)]
    (fact (:muu (:lammonlahde (:rakennuksenTiedot rakennus))) => "fuusioenergialla")))

(fl/facts* "Canonical model is correct"
  (let [canonical (application-to-canonical application "sv") => truthy
        rakennusvalvonta (:Rakennusvalvonta canonical) => truthy
        rakennusvalvontaasiatieto (:rakennusvalvontaAsiatieto rakennusvalvonta) => truthy
        rakennusvalvontaasia (:RakennusvalvontaAsia rakennusvalvontaasiatieto) => truthy

        toimituksenTiedot (:toimituksenTiedot rakennusvalvonta) => truthy
        aineistonnimi (:aineistonnimi toimituksenTiedot ) => "s"
        lausuntotieto (first (:lausuntotieto rakennusvalvontaasia))  => truthy
        Lausunto (:Lausunto lausuntotieto) => truthy
        viranomainen (:viranomainen Lausunto) => "Paloviranomainen"
        pyyntoPvm (:pyyntoPvm Lausunto) => "2013-05-09"
        lausuntotieto (:lausuntotieto Lausunto) => truthy
        LL (:Lausunto lausuntotieto) => truthy  ;Lausunto oli jo kaytossa, siksi LL
        viranomainen (:viranomainen LL) => "Paloviranomainen"
        lausunto (:lausunto LL) => "Savupiippu pit\u00e4\u00e4 olla."
        lausuntoPvm (:lausuntoPvm LL) => "2013-05-09"
        puoltotieto (:puoltotieto LL) => truthy
        Puolto (:Puolto puoltotieto) => truthy
        puolto (:puolto Puolto) => "ehdoilla"

        osapuolettieto (:osapuolettieto rakennusvalvontaasia) => truthy
        osapuolet (:Osapuolet osapuolettieto) => truthy
        osapuolitieto-hakija (first (:osapuolitieto osapuolet)) => truthy
        hakija-osapuoli1 (:Osapuoli osapuolitieto-hakija) => truthy
        suunnittelijat (:suunnittelijatieto osapuolet) => truthy
        paasuunnitelija (:Suunnittelija (last suunnittelijat)) => truthy
        tyonjohtajat (:tyonjohtajatieto osapuolet) => truthy
        tyonjohtajatieto (:Tyonjohtaja (last tyonjohtajat)) => truthy
        rakennuspaikkatiedot (:rakennuspaikkatieto rakennusvalvontaasia) => truthy
        rakennuspaikkatieto (first rakennuspaikkatiedot) => truthy
        rakennuspaikka (:Rakennuspaikka rakennuspaikkatieto) => truthy
        rakennuspaikanKiinteistotieto (:rakennuspaikanKiinteistotieto rakennuspaikka) => truthy
        RakennuspaikanKiinteistotieto (:RakennuspaikanKiinteisto rakennuspaikanKiinteistotieto) => truthy
        kiinteistotieto (:kiinteistotieto RakennuspaikanKiinteistotieto) => truthy
        Kiinteisto (:Kiinteisto kiinteistotieto) => truthy
        toimenpiteet(:toimenpidetieto rakennusvalvontaasia) => truthy
        toimenpide (:Toimenpide (nth toimenpiteet 1)) => truthy
        muu-muutostyo (:Toimenpide (nth toimenpiteet 0)) => truthy
        laajennus-t (:Toimenpide (nth toimenpiteet 2)) => truthy
        purku-t (:Toimenpide (nth toimenpiteet 3)) => truthy
        kaupunkikuva-t (:Toimenpide (nth toimenpiteet 4)) => truthy
        rakennustieto (:rakennustieto toimenpide) => truthy
        rakennus (:Rakennus rakennustieto) => truthy
        rakennuksen-omistajatieto (:Omistaja(first (:omistajatieto rakennus))) => truthy
        rakennuksentiedot (:rakennuksenTiedot rakennus) => truthy
        lisatiedot (:lisatiedot rakennusvalvontaasia) => truthy
        Lisatiedot (:Lisatiedot lisatiedot) => truthy
        kayttotapaus (:kayttotapaus rakennusvalvontaasia) => truthy
        asianTiedot (:asianTiedot rakennusvalvontaasia) => truthy
        Asiantiedot (:Asiantiedot asianTiedot) => truthy
        vahainen-poikkeaminen (:vahainenPoikkeaminen Asiantiedot) => truthy
        rakennusvalvontasian-kuvaus (:rakennusvalvontaasianKuvaus Asiantiedot) => truthy
        luvanTunnisteTiedot (:luvanTunnisteTiedot rakennusvalvontaasia) => truthy
        LupaTunnus (:LupaTunnus luvanTunnisteTiedot) => truthy
        muuTunnustieto (:muuTunnustieto LupaTunnus) => truthy
        MuuTunnus (:MuuTunnus muuTunnustieto) => truthy]

    ;(clojure.pprint/pprint canonical)

    (fact "contains nil" (contains-value? canonical nil?) => falsey)
    (fact "paasuunnitelija" paasuunnitelija => (contains {:suunnittelijaRoolikoodi "p\u00e4\u00e4suunnittelija"}))
    (fact "Osapuolien maara" (+ (count suunnittelijat) (count tyonjohtajat) (count (:osapuolitieto osapuolet))) => 8)
    (fact "rakennuspaikkojen maara" (count rakennuspaikkatiedot) => 1)
    (fact "tilanNimi" (:tilannimi Kiinteisto) => "Hiekkametsa")
    (fact "kiinteistotunnus" (:kiinteistotunnus Kiinteisto) => "21111111111111")
    (fact "maaraalaTunnus" (:maaraAlaTunnus Kiinteisto) => nil)
    (fact "kokotilakytkin" (:kokotilaKytkin RakennuspaikanKiinteistotieto) => truthy)
    (fact "hallintaperuste" (:hallintaperuste RakennuspaikanKiinteistotieto) => "oma")

    (fact "Toimenpidetieto"  (count toimenpiteet) => 5)
    (fact "rakentajaTyyppi" (:rakentajatyyppi rakennus) => "muu")
    (fact "kayttotarkoitus" (:kayttotarkoitus rakennuksentiedot) => "012 kahden asunnon talot")
    (fact "rakentamistapa" (:rakentamistapa rakennuksentiedot) => "elementti")
    (fact "rakennuksen omistajalaji" (:omistajalaji (:omistajalaji rakennuksen-omistajatieto)) => "muu yksityinen henkil\u00f6 tai perikunta")
    (fact "KuntaRooliKoodi" (:kuntaRooliKoodi rakennuksen-omistajatieto) => "Rakennuksen omistaja")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi rakennuksen-omistajatieto) => "rakennuksen omistaja")
    (fact "Lisatiedot suoramarkkinointikielto" (:suoramarkkinointikieltoKytkin Lisatiedot) => true)
    (fact "Lisatiedot asiointikieli" (:asioimiskieli Lisatiedot) => "ruotsi")
    (fact "rakennusvalvontasian-kuvaus" rakennusvalvontasian-kuvaus =>"Uuden rakennuksen rakentaminen tontille.\n\nPuun kaataminen:Puun kaataminen")
    (fact "kayttotapaus" kayttotapaus => "Uusi hakemus")

    (fact "Muu tunnus" (:tunnus MuuTunnus) => "50bc85e4ea3e790c9ff7cdb0")
    (fact "Sovellus" (:sovellus MuuTunnus) => "Lupapiste")
    (fact "Toimenpiteen kuvaus" (-> toimenpide :uusi :kuvaus) => "Asuinrakennuksen rakentaminen")
    (fact "Toimenpiteen kuvaus" (-> muu-muutostyo :muuMuutosTyo :kuvaus) => "Muu rakennuksen muutosty\u00f6")
    (fact "Muu muutostyon perusparannuskytkin" (-> muu-muutostyo :muuMuutosTyo :perusparannusKytkin) => true)
    (fact "Muutostyon laji" (-> muu-muutostyo :muuMuutosTyo :muutostyonLaji) => "muut muutosty\u00f6t")
    (fact "Laajennuksen kuvaus" (-> laajennus-t :laajennus :kuvaus) => "Rakennuksen laajentaminen tai korjaaminen")
    (fact "muu muutostyon rakennuksen tunnus" (-> muu-muutostyo :rakennustieto :Rakennus :rakennuksenTiedot :rakennustunnus :jarjestysnumero) => 2)
    (fact "Laajennuksen rakennuksen tunnus" (-> laajennus-t :rakennustieto :Rakennus :rakennuksenTiedot :rakennustunnus :jarjestysnumero) => 3)
    (fact "Laajennuksen rakennuksen kiintun" (-> laajennus-t :rakennustieto :Rakennus :rakennuksenTiedot :rakennustunnus :kiinttun) => "21111111111111")
    (fact "Laajennuksen pintaalat" (count (-> laajennus-t :laajennus :laajennuksentiedot :huoneistoala )) => 2)
    (fact "Purkamisen kuvaus" (-> purku-t :purkaminen :kuvaus) => "Rakennuksen purkaminen")
    (fact "Poistuma pvm" (-> purku-t :purkaminen :poistumaPvm) => "2013-04-17")
    (fact "Kaupunkikuvatoimenpiteen kuvaus" (-> kaupunkikuva-t :kaupunkikuvaToimenpide :kuvaus) => "Aidan rakentaminen")
    (fact "Kaupunkikuvatoimenpiteen rakennelman kuvaus" (-> kaupunkikuva-t :rakennelmatieto :Rakennelma :kuvaus :kuvaus) => "Aidan rakentaminen rajalle")))
