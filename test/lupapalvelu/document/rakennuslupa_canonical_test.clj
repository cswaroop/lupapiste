(ns lupapalvelu.document.rakennuslupa-canonical-test
  (:require [clj-time.core :refer [date-time]]
            [clojure.data.xml :refer :all]
            [lupapalvelu.document.canonical-common :refer :all]
            [lupapalvelu.document.canonical-test-common :as ctc]
            [lupapalvelu.document.data-schema :as doc-data-schema]
            [lupapalvelu.document.rakennuslupa-canonical :refer :all]
            [lupapalvelu.document.schemas :as doc-schemas]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.factlet :as fl]
            [lupapalvelu.operations]
            [lupapalvelu.organization :as org]
            [lupapalvelu.xml.emit :refer :all]
            [lupapalvelu.backing-system.krysp.rakennuslupa-mapping :refer :all]
            [lupapalvelu.rakennuslupa-canonical-util :refer :all]
            [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [sade.core :refer :all]
            [sade.schema-generators :as ssg]
            [sade.strings :as ss]
            [sade.util :as util]))

(testable-privates lupapalvelu.operations r-operations)

(facts "Date format"
  (fact (util/to-xml-date (.getMillis (date-time 2012 1 14))) => "2012-01-14")
  (fact (util/to-xml-date (.getMillis (date-time 2012 2 29))) => "2012-02-29"))

(fact "get-rakennustunnus"
  (let [application {:primaryOperation {:id "1" :description "desc"}}
        document {:op {:id "1"}}
        base-result {:jarjestysnumero nil
                     :kiinttun nil
                     :muuTunnustieto [{:MuuTunnus {:sovellus "toimenpideId", :tunnus "1"}}
                                      {:MuuTunnus {:sovellus "Lupapiste", :tunnus "1"}}]}
        building-info {:buildingId "not-other"
                       :rakennusnro "001"
                       :valtakunnallinenNumero "122334455R"
                       :manuaalinen_rakennusnro "123"}]
    (get-rakennustunnus {} {} document) => base-result
    (get-rakennustunnus {:tunnus "B"} application document) => (assoc base-result :rakennuksenSelite "B: desc")
    (get-rakennustunnus {:tunnus ""} application document) => (assoc base-result :rakennuksenSelite "desc")
    (get-rakennustunnus {:tunnus "B"} {} document) => (assoc base-result :rakennuksenSelite "B")
    (get-rakennustunnus building-info {} document) => (contains {:rakennusnro "001"
                                                                 :valtakunnallinenNumero "122334455R"})
    (get-rakennustunnus (assoc building-info :buildingId "other") {} document)
    => (contains {:rakennusnro "123"
                  :valtakunnallinenNumero "122334455R"})))



(ctc/validate-all-documents application-rakennuslupa)

(def application-rakennuslupa-ilman-ilmoitusta (assoc application-rakennuslupa :documents documents-ilman-ilmoitusta))

(ctc/validate-all-documents application-rakennuslupa-ilman-ilmoitusta)

(def application-tyonjohtajan-nimeaminen
  (merge application-rakennuslupa {:id "LP-753-2013-00002"
                                   :organization "753-R"
                                   :state "submitted"
                                   :submitted 1426247899490
                                   :primaryOperation {:name "tyonjohtajan-nimeaminen"
                                                      :id "5272668be8db5aaa01084601"
                                                      :created 1383229067483}
                                   :permitSubtype "tyonjohtaja-hakemus"
                                   :documents [hakija-henkilo
                                               maksaja-henkilo
                                               tyonjohtaja
                                               hankkeen-kuvaus-minimum]
                                   :linkPermitData [link-permit-data-kuntalupatunnus]
                                   :appsLinkingToUs [app-linking-to-us]}))

(ctc/validate-all-documents application-tyonjohtajan-nimeaminen)


(def application-tyonjohtajan-nimeaminen-v2
  (merge application-tyonjohtajan-nimeaminen  {:id "LP-753-2013-00003"
                                               :submitted 1426247899491
                                               :primaryOperation {:name "tyonjohtajan-nimeaminen-v2"
                                                                  :id "5272668be8db5aaa01084601"
                                                                  :created 1383229067483}
                                               :documents [hakija-tj-henkilo
                                                           maksaja-henkilo
                                                           tyonjohtaja-v2
                                                           hankkeen-kuvaus-minimum]}))

(ctc/validate-all-documents application-tyonjohtajan-nimeaminen-v2)

(def application-suunnittelijan-nimeaminen
  (merge application-rakennuslupa {:id "LP-753-2013-00003"
                                   :organization "753-R"
                                   :state "submitted"
                                   :submitted 1426247899490
                                   :propertyId "75341600550007"
                                   :primaryOperation {:name "suunnittelijan-nimeaminen"
                                                      :id "527b3392e8dbbb95047a89de"
                                                      :created 1383805842761}
                                   :documents [hakija-henkilo
                                               maksaja-henkilo
                                               suunnittelija1
                                               hankkeen-kuvaus-minimum]
                                   :linkPermitData [link-permit-data-lupapistetunnus]
                                   :appsLinkingToUs [app-linking-to-us]}))

(def application-suunnittelijan-nimeaminen-muu
  (merge application-rakennuslupa {:id "LP-753-2013-00003"
                                   :organization "753-R"
                                   :state "submitted"
                                   :submitted 1426247899490
                                   :propertyId "75341600550007"
                                   :primaryOperation {:name "suunnittelijan-nimeaminen"
                                                      :id "527b3392e8dbbb95047a89de"
                                                      :created 1383805842761}
                                   :documents [hakija-henkilo
                                               maksaja-henkilo
                                               suunnittelija3
                                               hankkeen-kuvaus-minimum]
                                   :linkPermitData [link-permit-data-lupapistetunnus]
                                   :appsLinkingToUs [app-linking-to-us]}))

(ctc/validate-all-documents application-suunnittelijan-nimeaminen)

(def application-aurinkopaneeli
  (merge application-rakennuslupa {:primaryOperation (op-info aurinkopaneeli)
                                   :secondaryOperations []
                                   :documents [hankkeen-kuvaus
                                               hakija-henkilo
                                               paasuunnittelija
                                               suunnittelija1
                                               maksaja-henkilo
                                               rakennuspaikka
                                               aurinkopaneeli]}))

(ctc/validate-all-documents application-aurinkopaneeli)

(defn- validate-minimal-person [person]
  (fact person => (contains {:nimi {:etunimi "Pena" :sukunimi "Penttil\u00e4"}})))

(defn- validate-address [address]
  (let [person-katu (:teksti (:osoitenimi address))
        person-postinumero (:postinumero address)
        person-postitoimipaikannimi (:postitoimipaikannimi address)
        person-maa (:valtioSuomeksi address)
        person-country (:valtioKansainvalinen address)
        person-address (:ulkomainenLahiosoite address)
        person-post (:ulkomainenPostitoimipaikka address)]
    (fact address => truthy)
    (fact person-katu => "katu")
    (fact person-postinumero =>"33800")
    (fact person-postitoimipaikannimi => "Tuonela")
    (fact person-maa => "Kiina")
    (fact person-country => "CHN")
    (fact person-address => "katu")
    (fact person-post => "Tuonela")))

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
  (validate-address (:postiosoite company)) ; up to 2.1.4
  (validate-address (get-in company [:postiosoitetieto :postiosoite]))) ; 2.1.5+

(defn- validate-company [company]
  (validate-minimal-company company)
  (fact "puhelin" (:puhelin company) => "03-389 1380")
  (fact "sahkopostiosoite" (:sahkopostiosoite company) => "yritys@example.com"))

(facts "Canonical hakija/henkilo model is correct"
  (let [osapuoli (tools/unwrapped (:data hakija-henkilo))
        hakija-model (get-osapuoli-data osapuoli (-> hakija-henkilo :schema-info :name keyword))
        henkilo (:henkilo hakija-model)
        yritys (:yritys hakija-model)]
    (fact "model" hakija-model => truthy)
    (fact "kuntaRooliKoodi" (:kuntaRooliKoodi hakija-model) => "Rakennusvalvonta-asian hakija")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi hakija-model) => "hakija")
    (fact "turvakieltoKytkin" (:turvakieltoKytkin hakija-model) => true)
    (fact "vainsahkoinenAsiointiKytkin" (:vainsahkoinenAsiointiKytkin henkilo) => true)
    (fact "suoramarkkinointikieltoKytkin" (:suoramarkkinointikieltoKytkin hakija-model) => true)
    (validate-person henkilo)
    (fact "yritys is nil" yritys => nil)))

(facts "Canonical asiamies-henkilo model is correct"
  (let [osapuoli (tools/unwrapped (:data asiamies-henkilo))
        asiamies-model (get-osapuoli-data osapuoli (-> asiamies-henkilo :schema-info :name keyword))
        henkilo (:henkilo asiamies-model)
        yritys (:yritys asiamies-model)]
    (fact "model" asiamies-model => truthy)
    (fact "kuntaRooliKoodi" (:kuntaRooliKoodi asiamies-model) => "Hakijan asiamies")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi asiamies-model) => "muu osapuoli")
    (fact "turvakieltoKytkin" (:turvakieltoKytkin asiamies-model) => true)
    (fact "vainsahkoinenAsiointiKytkin" (:vainsahkoinenAsiointiKytkin henkilo) => true)
    (fact "suoramarkkinointikieltoKytkin" (:suoramarkkinointikieltoKytkin asiamies-model) => true)
    (validate-person henkilo)
    (fact "yritys is nil" yritys => nil)))

(facts "Canonical hakija/yritys model is correct"
  (let [osapuoli (tools/unwrapped (:data hakija-yritys))
        hakija-model (get-osapuoli-data osapuoli (-> hakija-yritys :schema-info :name keyword))
        henkilo (:henkilo hakija-model)
        yritys (:yritys hakija-model)]
    (fact "model" hakija-model => truthy)
    (fact "kuntaRooliKoodi" (:kuntaRooliKoodi hakija-model) => "Rakennusvalvonta-asian hakija")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi hakija-model) => "hakija")
    (fact "turvakieltoKytkin" (:turvakieltoKytkin hakija-model) => true)
    (fact "vainsahkoinenAsiointiKytkin" (:vainsahkoinenAsiointiKytkin yritys) => true)
    (fact "suoramarkkinointikieltoKytkin" (:suoramarkkinointikieltoKytkin hakija-model) => false)
    (validate-minimal-person henkilo)
    (validate-company yritys)))

(fact "Empty body"
  (empty? (get-parties-by-type
    {"paasuunnittelija" [{:data {}}]} :Suunnittelija ["paasuunnittelija"] get-suunnittelija-data)) => truthy)

(facts "Canonical paasuunnittelija/henkilo+yritys model is correct"
  (let [suunnittelija (tools/unwrapped (:data paasuunnittelija))
        suunnittelija-model (get-suunnittelija-data suunnittelija :paasuunnittelija)
        henkilo (:henkilo suunnittelija-model)
        yritys (:yritys suunnittelija-model)]
    (fact "model" suunnittelija-model => truthy)
    (fact "suunnittelijaRoolikoodi" (:suunnittelijaRoolikoodi suunnittelija-model) => "p\u00e4\u00e4suunnittelija")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi suunnittelija-model) => "p\u00e4\u00e4suunnittelija")
    (fact "koulutus" (:koulutus suunnittelija-model) => "arkkitehti")
    (fact "patevyysvaatimusluokka" (:patevyysvaatimusluokka suunnittelija-model) => "ei tiedossa")
    (fact "vaadittuPatevyysluokka" (:vaadittuPatevyysluokka suunnittelija-model) => "AA")
    (fact "valmistumisvuosi" (:valmistumisvuosi suunnittelija-model) => "2010")
    (fact "kokemusvuodet" (:kokemusvuodet suunnittelija-model) => "5")
    (fact "FISEpatevyyskortti" (:FISEpatevyyskortti suunnittelija-model) => "http://www.ym.fi")
    (fact "FISEkelpoisuus" (:FISEkelpoisuus suunnittelija-model) => "tavanomainen p\u00e4\u00e4suunnittelu (uudisrakentaminen)")
    (validate-person henkilo)
    (validate-minimal-company yritys)))

(facts "Canonical suunnittelija1 model is correct"
  (let [suunnittelija (tools/unwrapped (:data suunnittelija1))
        suunnittelija-model (get-suunnittelija-data suunnittelija :suunnittelija)]
    (fact "model" suunnittelija-model => truthy)
    (fact "suunnittelijaRoolikoodi" (:suunnittelijaRoolikoodi suunnittelija-model) => "rakennusfysikaalinen suunnittelija")
    (fact "muuSuunnittelijarooli" (contains? suunnittelija-model :muuSuunnittelijaRooli) => false)
    (fact "VRKrooliKoodi" (:VRKrooliKoodi suunnittelija-model) => "erityissuunnittelija")
    (fact "koulutus" (:koulutus suunnittelija-model) => "arkkitehti")
    (fact "patevyysvaatimusluokka" (:patevyysvaatimusluokka suunnittelija-model) => "B")
    (fact "vaadittuPatevyysluokka" (:vaadittuPatevyysluokka suunnittelija-model) => "C")
    (fact "valmistumisvuosi" (:valmistumisvuosi suunnittelija-model) => "2010")
    (fact "kokemusvuodet" (:kokemusvuodet suunnittelija-model) => "5")
    (fact "henkilo" (:henkilo suunnittelija-model) => truthy)
    (fact "yritys" (:yritys suunnittelija-model) => truthy)))

(facts "Canonical suunnittelija2 model is correct"
  (let [suunnittelija (tools/unwrapped (:data suunnittelija2))
        suunnittelija-model (get-suunnittelija-data suunnittelija :suunnittelija)]
    (fact "model" suunnittelija-model => truthy)
    (fact "suunnittelijaRoolikoodi" (:suunnittelijaRoolikoodi suunnittelija-model) => "GEO-suunnittelija")
    (fact "muuSuunnittelijarooli" (contains? suunnittelija-model :muuSuunnittelijaRooli) => false)
    (fact "VRKrooliKoodi" (:VRKrooliKoodi suunnittelija-model) => "erityissuunnittelija")
    (fact "koulutus" (:koulutus suunnittelija-model) => "muu")
    (fact "patevyysvaatimusluokka" (:patevyysvaatimusluokka suunnittelija-model) => "AA")
    (fact "vaadittuPatevyysluokka" (:vaadittuPatevyysluokka suunnittelija-model) => "A")
    (fact "valmistumisvuosi" (:valmistumisvuosi suunnittelija-model) => "2010")
    (fact "kokemusvuodet" (:kokemusvuodet suunnittelija-model) => "5")
    (fact "henkilo" (:henkilo suunnittelija-model) => truthy)
    (fact "yritys" (:yritys suunnittelija-model) => truthy)))

(facts "Canonical suunnittelija3 model is correct"
  (let [suunnittelija (tools/unwrapped (:data suunnittelija3))
        suunnittelija-model (get-suunnittelija-data suunnittelija :suunnittelija)]
    (fact "model" suunnittelija-model => truthy)
    (fact "suunnittelijaRoolikoodi" (:suunnittelijaRoolikoodi suunnittelija-model) => "muu")
    (fact "muuSuunnittelijarooli" (:muuSuunnittelijaRooli suunnittelija-model) => "ei listassa -rooli")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi suunnittelija-model) => "erityissuunnittelija")
    (fact "koulutus" (:koulutus suunnittelija-model) => "arkkitehti")
    (fact "patevyysvaatimusluokka" (:patevyysvaatimusluokka suunnittelija-model) => "B")
    (fact "vaadittuPatevyysluokka" (:vaadittuPatevyysluokka suunnittelija-model) => "C")
    (fact "valmistumisvuosi" (:valmistumisvuosi suunnittelija-model) => "2010")
    (fact "kokemusvuodet" (:kokemusvuodet suunnittelija-model) => "5")
    (fact "henkilo" (:henkilo suunnittelija-model) => truthy)
    (fact "yritys" (:yritys suunnittelija-model) => truthy)))

(facts "Transforming old sunnittelija schema to canonical model is correct"
  (let [suunnittelija (tools/unwrapped (:data suunnittelija-old-schema-LUPA-771))
        suunnittelija-model (get-suunnittelija-data suunnittelija :suunnittelija)]
    (fact "model" suunnittelija-model => truthy)
    (fact "suunnittelijaRoolikoodi" (:suunnittelijaRoolikoodi suunnittelija-model) => "ARK-rakennussuunnittelija")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi suunnittelija-model) => "rakennussuunnittelija")
    (fact "koulutus" (:koulutus suunnittelija-model) => "arkkitehti")
    (fact "patevyysvaatimusluokka" (:patevyysvaatimusluokka suunnittelija-model) => "B")
    (fact "valmistumisvuosi" (:valmistumisvuosi suunnittelija-model) => "2010")
    (fact "kokemusvuodet" (:kokemusvuodet suunnittelija-model) => "5")))

(facts "Canonical suunnittelija-blank-role model is correct"
  (let [suunnittelija (tools/unwrapped (:data suunnittelija-blank-role))
        suunnittelija-model (get-suunnittelija-data suunnittelija :suunnittelija)]
    (fact "model" suunnittelija-model => truthy)
    (fact "suunnittelijaRoolikoodi" (:suunnittelijaRoolikoodi suunnittelija-model) => "ei tiedossa")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi suunnittelija-model) => "ei tiedossa")))

(facts "Canonical tyonjohtaja model is correct"
  (let [tyonjohtaja-unwrapped (tools/unwrapped (:data tyonjohtaja))
        tyonjohtaja-model (get-tyonjohtaja-data {} "fi" tyonjohtaja-unwrapped :tyonjohtaja)
        henkilo (:henkilo tyonjohtaja-model)
        yritys (:yritys tyonjohtaja-model)
        sijaistus-213 (get-in tyonjohtaja-model [:sijaistustieto :Sijaistus])]
    (fact "model" tyonjohtaja-model => truthy)
    (fact "VRKrooliKoodi" (:VRKrooliKoodi tyonjohtaja-model) => "ty\u00f6njohtaja")
    (fact "tyonjohtajaRooliKoodi" (:tyonjohtajaRooliKoodi tyonjohtaja-model) => (-> tyonjohtaja :data :kuntaRoolikoodi :value))
    (fact "no suunnittelijaRoolikoodi" (:suunnittelijaRoolikoodi tyonjohtaja-model) => nil)
    (fact "no FISEpatevyyskortti" (:FISEpatevyyskortti tyonjohtaja-model) => nil)
    (fact "no FISEkelpoisuus" (:FISEkelpoisuus tyonjohtaja-model) => nil)
    (fact "alkamisPvm" (:alkamisPvm tyonjohtaja-model) => "2014-02-13")
    (fact "paattymisPvm" (:paattymisPvm tyonjohtaja-model) => "2014-02-20")
    (fact "koulutus with 'Muu' selected" (:koulutus tyonjohtaja-model) => "muu")
    (fact "valmistumisvuosi" (:valmistumisvuosi tyonjohtaja-model) => (-> tyonjohtaja :data :patevyys-tyonjohtaja :valmistumisvuosi :value))
    (fact "patevyysvaatimusluokka (backwards compatibility)" (:patevyysvaatimusluokka tyonjohtaja-model) => (-> tyonjohtaja :data :patevyys-tyonjohtaja :patevyysvaatimusluokka :value))
    (fact "vaadittuPatevyysluokka" (:vaadittuPatevyysluokka tyonjohtaja-model) => (-> tyonjohtaja :data :patevyys-tyonjohtaja :patevyysvaatimusluokka :value))
    (fact "kokemusvuodet" (:kokemusvuodet tyonjohtaja-model) => (-> tyonjohtaja :data :patevyys-tyonjohtaja :kokemusvuodet :value))
    (fact "valvottavienKohteidenMaara" (:valvottavienKohteidenMaara tyonjohtaja-model) => (-> tyonjohtaja :data :patevyys-tyonjohtaja :valvottavienKohteidenMaara :value))
    (fact "tyonjohtajaHakemusKytkin" (:tyonjohtajaHakemusKytkin tyonjohtaja-model) => true)
    (fact "vastattavatTyotehtavat" (:vastattavatTyotehtavat tyonjohtaja-model) =>
      "kiinteistonVesiJaViemarilaitteistonRakentaminen,kiinteistonilmanvaihtolaitteistonRakentaminen,maanrakennustyo,rakennelmaTaiLaitos,Muu tyotehtava")
    (fact "henkilo" (:henkilo tyonjohtaja-model) => truthy)
    (fact "yritys" (:yritys tyonjohtaja-model) => truthy)
    (fact "sijaisuus" sijaistus-213 => truthy)
    (fact "sijaistettavan nimi 2.1.4" (:sijaistettavaHlo tyonjohtaja-model) => "Jaska Jokunen")
    (fact "sijaistettavan nimi 2.1.3" (:sijaistettavaHlo sijaistus-213) => "Jaska Jokunen")
    (fact "sijaistettava rooli" (:sijaistettavaRooli sijaistus-213) => (:tyonjohtajaRooliKoodi tyonjohtaja-model))
    (fact "sijaistettavan alkamisPvm" (:alkamisPvm sijaistus-213) => "2014-02-13")
    (fact "sijaistettavan paattymisPvm" (:paattymisPvm sijaistus-213) => "2014-02-20")
    (validate-person henkilo)
    (validate-minimal-company yritys)))

(facts "Canonical tyonjohtaja v2 model is correct"
  (let [tyonjohtaja-unwrapped (tools/unwrapped (:data tyonjohtaja-v2))
        tyonjohtaja-model (get-tyonjohtaja-v2-data {:permitSubtype "tyonjohtaja-hakemus"} "fi" tyonjohtaja-unwrapped :tyonjohtaja)]
    (fact "tyonjohtajanHyvaksynta (vainTamaHankeKytkin)" (:vainTamaHankeKytkin tyonjohtaja-model) => (-> tyonjohtaja-v2 :data :tyonjohtajanHyvaksynta :tyonjohtajanHyvaksynta :value))
    (fact "koulutus" (:koulutus tyonjohtaja-model) => (-> tyonjohtaja-v2 :data :patevyys-tyonjohtaja :koulutusvalinta :value))
    (fact "valmistumisvuosi" (:valmistumisvuosi tyonjohtaja-model) => (-> tyonjohtaja-v2 :data :patevyys-tyonjohtaja :valmistumisvuosi :value))
    (fact "patevyysvaatimusluokka (backwards compatibility)" (:patevyysvaatimusluokka tyonjohtaja-model) => (-> tyonjohtaja-v2 :data :patevyysvaatimusluokka :value))
    (fact "vaadittuPatevyysluokka" (:vaadittuPatevyysluokka tyonjohtaja-model) => (-> tyonjohtaja-v2 :data :patevyysvaatimusluokka :value))
    (fact "kokemusvuodet" (:kokemusvuodet tyonjohtaja-model) => (-> tyonjohtaja-v2 :data :patevyys-tyonjohtaja :kokemusvuodet :value))
    (fact "valvottavienKohteidenMaara" (:valvottavienKohteidenMaara tyonjohtaja-model) => (-> tyonjohtaja-v2 :data :patevyys-tyonjohtaja :valvottavienKohteidenMaara :value))
    (fact "tyonjohtajaHakemusKytkin" (:tyonjohtajaHakemusKytkin tyonjohtaja-model) => true)
    (fact "vastattavatTyotehtavat"
          (:vastattavatTyotehtavat tyonjohtaja-model) => "rakennuksenPurkaminen,ivLaitoksenKorjausJaMuutostyo,uudisrakennustyoIlmanMaanrakennustoita,maanrakennustyot,sisapuolinenKvvTyo,Muu tyotehtava")
    (fact "vastattavaTyo contents"
          (map (comp :vastattavaTyo :VastattavaTyo) (:vastattavaTyotieto tyonjohtaja-model)) => (just #{"Sis\u00e4puolinen KVV-ty\u00f6"
                                                                                                        "Muu tyotehtava"}))))

(facts "Canonical tyonjohtaja-blank-role-and-blank-qualification model is correct"
  (let [tyonjohtaja-unwrapped (tools/unwrapped (:data tyonjohtaja-blank-role-and-blank-qualification))
        tyonjohtaja-model (get-tyonjohtaja-data {} "fi" tyonjohtaja-unwrapped :tyonjohtaja)]
    (fact "model" tyonjohtaja-model => truthy)
    (fact "tyonjohtajaRooliKoodi" (:tyonjohtajaRooliKoodi tyonjohtaja-model) => "ei tiedossa")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi tyonjohtaja-model) => "ei tiedossa")
    (fact "patevyysvaatimusluokka (backwards compatibility)" (:patevyysvaatimusluokka tyonjohtaja-model) => "ei tiedossa")
    (fact "vaadittuPatevyysluokka" (:vaadittuPatevyysluokka tyonjohtaja-model) => "ei tiedossa")
    (fact "tyonjohtajaHakemusKytkin" (:tyonjohtajaHakemusKytkin tyonjohtaja-model) => false)))

(facts "Canonical tyonjohtajan sijaistus model is correct"
  (let [tyonjohtaja       (tools/unwrapped (:data tyonjohtajan-sijaistus-blank-dates))
        tyonjohtaja-model (get-tyonjohtaja-data {} "fi" tyonjohtaja :tyonjohtaja)
        sijaistus-213     (-> tyonjohtaja-model :sijaistustieto :Sijaistus)]
    (facts "model 2.1.3" sijaistus-213 => truthy
      (fact "missing alkamisPvm" (:alkamisPvm sijaistus-213) => nil)
      (fact "empty paattymisPvm" (:paattymisPvm sijaistus-213) => nil)
      (fact "sijaistettavaRooli" (:sijaistettavaRooli sijaistus-213) => "KVV-ty\u00f6njohtaja")
      (fact "sijaistettavaHlo"   (:sijaistettavaHlo sijaistus-213) => "Jaska Jokunen"))))

(facts "Canonical tyonjohtajan vastattavaTyotieto is correct"
  (let [tyonjohtaja       (-> tyonjohtaja :data (dissoc :sijaistus) tools/unwrapped)
        tyonjohtaja-model (get-tyonjohtaja-data {} "fi" tyonjohtaja :tyonjohtaja)
        _     (-> tyonjohtaja-model :sijaistustieto)]
    (:sijaistustieto tyonjohtaja-model) => nil
    (fact "no dates" (-> tyonjohtaja-model :vastattavaTyotieto first :VastattavaTyo keys) => [:vastattavaTyo])
    (fact "vastattavaTyo"
      (map (comp :vastattavaTyo :VastattavaTyo) (-> tyonjohtaja-model :vastattavaTyotieto))
      =>
      (just #{"Kiinteist\u00f6n vesi- ja viem\u00e4rilaitteiston rakentaminen"
              "Kiinteist\u00f6n ilmanvaihtolaitteiston rakentaminen"
              "Maanrakennusty\u00f6"
              "Muu tyotehtava"
              "Rakennelma tai laitos"}))))

(facts "Canonical maksaja/henkilo model is correct"
  (let [osapuoli (tools/unwrapped (:data maksaja-henkilo))
        maksaja-model (get-osapuoli-data osapuoli :maksaja)
        henkilo (:henkilo maksaja-model)
        yritys (:yritys maksaja-model)]
    (fact "model" maksaja-model => truthy)
    (fact "kuntaRooliKoodi" (:kuntaRooliKoodi maksaja-model) => "Rakennusvalvonta-asian laskun maksaja")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi maksaja-model) => "maksaja")
    (fact "turvakieltoKytkin" (:turvakieltoKytkin maksaja-model) => true)
    (validate-person henkilo)
    (fact "yritys is nil" yritys => nil)))

(defn- validate-einvoice [einvoice]
  (fact "ovt-tunnus" (:ovtTunnus einvoice) => "003712345671")
  (fact "verkkolaskuTunnus" (:verkkolaskuTunnus einvoice) => "laskutunnus-1234")
  (fact "valittajaTunnus" (:valittajaTunnus einvoice) => "BAWCFI22"))

(facts "Canonical maksaja/yritys model is correct"
  (let [osapuoli (tools/unwrapped (:data maksaja-yritys))
        maksaja-model (get-osapuoli-data osapuoli :maksaja)
        henkilo (:henkilo maksaja-model)
        yritys (:yritys maksaja-model)
        verkkolaskutustieto (-> yritys :verkkolaskutustieto :Verkkolaskutus)]
    (fact "model" maksaja-model => truthy)
    (fact "kuntaRooliKoodi" (:kuntaRooliKoodi maksaja-model) => "Rakennusvalvonta-asian laskun maksaja")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi maksaja-model) => "maksaja")
    (validate-minimal-person henkilo)
    (validate-company yritys)
    (validate-einvoice verkkolaskutustieto)))

(testable-privates lupapalvelu.document.canonical-common get-handler)

(facts "Handler is sonja"
  (let [handler (get-handler (tools/unwrapped application-rakennuslupa))
        name (get-in handler [:henkilo :nimi])]
    (fact "handler" handler => truthy)
    (fact "etunimi" (:etunimi name) => "Sonja")
    (fact "sukunimi" (:sukunimi name) => "Sibbo")))

(testable-privates lupapalvelu.document.rakennuslupa-canonical get-operations)

(facts "Toimenpiteet - operation canonical is implemented for all r-operation documents"
  (against-background
    (org/pate-scope? irrelevant) => false)
  (let [operations (map (partial hash-map :id) (range 100))
        actions (->> (vals @r-operations)
                     (map (util/fn->> :schema (assoc {} :name)))
                     (map doc-schemas/get-schema)
                     (map doc-data-schema/coerce-doc)
                     (map ssg/generate)
                     (map #(assoc-in %3 [:schema-info :op] {:id %1 :name (name %2)}) (range) (keys @r-operations))
                     (assoc application-rakennuslupa :primaryOperation (first operations) :secondaryOperations (rest operations) :documents)
                     (tools/unwrapped)
                     (get-operations))]
    (fact "actions" (seq actions) => truthy)))

(testable-privates lupapalvelu.document.rakennuslupa-canonical
                   get-huoneisto-data
                   get-huoneistot-schema
                   schema-body-required-fields
                   required-fields-have-value?)

(facts "Huoneisto is correct"
  (let [huoneistot (get-huoneisto-data (-> uusi-rakennus
                                           (get-in [:data :huoneistot])
                                           tools/unwrapped)
                                       "uusiRakennus")
        h2 (last huoneistot), h1 (first huoneistot)]
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

    ;; For uusiRakennus muutostapa is always lis\u00e4ys
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
    (fact "h2 huoneistotunnus" (:huoneistotunnus h1) => truthy)
    (fact "h2 huoneistotunnus: porras" (-> h1 :huoneistotunnus :porras) => "A")
    (fact "h2 huoneistotunnus: huoneistonumero" (-> h1 :huoneistotunnus :huoneistonumero) => "001")
    (fact "h2 huoneistotunnus: jakokirjain" (-> h1 :huoneistotunnus :jakokirjain) => "a")

    (facts "Muutostapa can be missing"
      (let [first-missing (-> uusi-rakennus
                              (get-in [:data :huoneistot])
                              (util/dissoc-in [:0 :muutostapa])
                              tools/unwrapped)]
        (fact "Muutostapa missing"
          (get-in first-missing [:0 :muutostapa]) => nil
          (get-in first-missing [:1 :muutostapa]) => "muutos")

        (fact "Missing muutostapa filtered for rakennuksen-muuttaminen"
          (map :muutostapa (get-huoneisto-data first-missing "rakennuksen-muuttaminen"))
          => ["muutos"])
        (fact "Missing muutostapa is also lis\u00e4ys for uusiRakennus"
                    (map :muutostapa (get-huoneisto-data first-missing "uusiRakennus"))
          => ["lis\u00e4ys" "lis\u00e4ys"])))))

(facts "Muutostapa for old buildings"
  (let [huoneistot (get-huoneisto-data (-> uusi-rakennus
                                           (get-in [:data :huoneistot])
                                           tools/unwrapped)
                                       "rakennuksen-muuttaminen")
        h2         (last huoneistot)]
    (fact "h2 muutostapa" (:muutostapa h2) => "muutos")))

(facts "Huoneistot with created default data shouldn't create data into canonical"                       ; LPK-3844
  (let [huoneistot (get-huoneisto-data (-> (doc-schemas/get-schema 1 "uusiRakennus")
                                           (tools/create-document-data tools/default-values)
                                           :huoneistot
                                           tools/unwrapped)
                                       "uusiRakennus")]
    huoneistot => empty?))

(testable-privates lupapalvelu.document.rakennuslupa-canonical get-rakennus)

(facts "When muu-lammonlahde is empty, lammonlahde is used"
  (against-background
    (org/pate-scope? irrelevant) => false)
  (let [doc (tools/unwrapped {:schema-info {:name "uusiRakennus"}
                              :data {:lammitys {:lammitystapa {:value nil}
                                               :lammonlahde  {:value "turve"}
                                               :muu-lammonlahde {:value nil}}}})
        rakennus (get-rakennus application-rakennuslupa doc)]
    (fact (:polttoaine (:lammonlahde (:rakennuksenTiedot rakennus))) => "turve")))

(fact "LPK-427: When energiatehokkuusluku is set, energiatehokkuusluvunYksikko is included"
  (against-background
    (org/pate-scope? irrelevant) => false)
  (let [doc (tools/unwrapped {:schema-info {:name "uusiRakennus"}
                              :data {:luokitus {:energiatehokkuusluku {:value "124"}
                                                :energiatehokkuusluvunYksikko {:value "kWh/m2"}}}})
        rakennus (get-rakennus application-rakennuslupa doc)]
    (get-in rakennus [:rakennuksenTiedot :energiatehokkuusluvunYksikko]) => "kWh/m2"))

(fact "LPK-427: When energiatehokkuusluku is not set, energiatehokkuusluvunYksikko is excluded"
  (against-background
    (org/pate-scope? irrelevant) => false)
  (let [doc (tools/unwrapped {:schema-info {:name "uusiRakennus"}
                              :data {:luokitus {:energiatehokkuusluku {:value ""}
                                                :energiatehokkuusluvunYksikko {:value "kWh/m2"}}}})
        rakennus (get-rakennus application-rakennuslupa doc)]
    (get-in rakennus [:rakennuksenTiedot :energiatehokkuusluvunYksikko]) => nil))

(facts "When muu-lammonlahde is specified, it is used"
  (against-background
    (org/pate-scope? irrelevant) => false)
  (let [doc (tools/unwrapped {:schema-info {:name "uusiRakennus"}
                              :data {:lammitys {:lammitystapa {:value nil}
                                                :lammonlahde  {:value "other"}
                                                :muu-lammonlahde {:value "fuusioenergialla"}}}})
        rakennus (get-rakennus application-rakennuslupa doc)]
    (fact (:muu (:lammonlahde (:rakennuksenTiedot rakennus))) => "fuusioenergialla")))

(facts "rakennuksenTiedot"
  (against-background
    (org/pate-scope? irrelevant) => false)
  (let [doc (tools/unwrapped (assoc-in uusi-rakennus [:data :varusteet :liitettyJatevesijarjestelmaanKytkin :value] true))
        {tiedot :rakennuksenTiedot} (get-rakennus application-rakennuslupa doc)]

    (fact "liitettyJatevesijarjestelmaanKytkin"
      (:liitettyJatevesijarjestelmaanKytkin tiedot) => true)

    (fact "rakennustunnus"
      (:rakennustunnus tiedot) => {:jarjestysnumero nil,
                                   :kiinttun "21111111111111"
                                   :muuTunnustieto [{:MuuTunnus {:tunnus "kerrostalo-rivitalo-id" :sovellus "toimenpideId"}}
                                                    {:MuuTunnus {:tunnus "kerrostalo-rivitalo-id" :sovellus "Lupapiste"}}]
                                   :rakennuksenSelite "A: kerrostalo-rivitalo-kuvaus"})))

(facts "rakennuksenTiedot : varusteet"
  (against-background
    (org/pate-scope? irrelevant) => true)
  (let [doc (tools/unwrapped  {:schema-info {:name "uusiRakennus"}
                               :data {:varusteet {:viemariKytkin true
                                                  :hissiKytkin true
                                                  :sahkoKytkin true}}})
        {tiedot :rakennuksenTiedot} (get-rakennus application-rakennuslupa (tools/unwrapped  {:schema-info {:name "uusiRakennus"}}))
        {tiedot2 :rakennuksenTiedot} (get-rakennus application-rakennuslupa doc)]

    (fact "when all varusteet flags are false varusteet map should be empty"
      (:varusteet tiedot) => {})

    (fact "If one varuste is selected then all should be in map"
      (:varusteet tiedot2) => {:sahkoKytkin true
                               :kaasuKytkin false
                               :viemariKytkin true
                               :vesijohtoKytkin false
                               :lamminvesiKytkin false
                               :aurinkopaneeliKytkin false
                               :hissiKytkin true
                               :koneellinenilmastointiKytkin false
                               :saunoja nil
                               :vaestonsuoja nil})))

(facts "rakennuksenTiedot : verkostoliittymat"
  (against-background
    (org/pate-scope? irrelevant) => true)
  (let [doc (tools/unwrapped {:schema-info {:name "uusiRakennus"}
                              :data {:verkostoliittymat {:sahkoKytkin true
                                                         :viemariKytkin true}}})
        {tiedot :rakennuksenTiedot} (get-rakennus application-rakennuslupa (tools/unwrapped {:schema-info {:name "uusiRakennus"}}))
        {tiedot2 :rakennuksenTiedot} (get-rakennus application-rakennuslupa doc)]

    (fact "When theres no verkostoliittymat map should be empty"
      (:verkostoliittymat tiedot) => {})

    (fact "If one liittyma is selected then all should be in map"
      (:verkostoliittymat tiedot2) => {:sahkoKytkin true
                                       :viemariKytkin true
                                       :maakaasuKytkin false
                                       :kaapeliKytkin false
                                       :vesijohtoKytkin false})))

(testable-privates lupapalvelu.document.rakennuslupa-canonical get-rakennus-data)

(facts "Rakennustiedot - merged data with building data from WFS"
  (against-background
    (org/pate-scope? irrelevant) => true)
  (let [doc {:schema-info {:name "rakennuksen-muuttaminen"}
                           :id   "5bc9b7e1f70b0924847661c3"
                           :data {:kaytto  {:rakentajaTyyppi {:value "muu"}
                                            :kayttotarkoitus {:value "012 kahden asunnon talot"}}
                                  :mitat   {:tilavuus  "800"
                                            :kerrosala "200"
                                            :muutosala "50"}
                                  :rakenne {:rakentamistapa "paikalla"
                                            :julkisivu      "tiili"}
                                  :valtakunnallinenNumero "199887766E"}}
        {rakennus :Rakennus} (get-rakennus-data (assoc application-rakennuslupa :document-buildings [document-building]) doc)
        building-data (tools/unwrapped (:rakennuksenTiedot rakennus))]

    (fact "There is all data from document"
      (:kayttotarkoitus building-data) => "012 kahden asunnon talot"
      (:tilavuus building-data) => "800"
      (:kerrosala building-data) => "200"
      (:muutosala building-data) => nil                     ;; There's no muutosala in kuntaGML
      (:rakentamistapa building-data) => "paikalla"
      (:julkisivu building-data) => {:julkisivumateriaali "tiili"})

    (fact "And also data from WFS building data"
      (:lammitystapa building-data) => "vesikeskus"
      (:kerrosluku building-data) => "2"
      (:kokonaisala building-data) => "281"
      (:paloluokka building-data) => "P1"
      (:lammonlahde building-data) => {:polttoaine "kevyt polttoöljy"}
      (:verkostoliittymat building-data) => {:sahkoKytkin true
                                             :maakaasuKytkin false
                                             :viemariKytkin true
                                             :vesijohtoKytkin true
                                             :kaapeliKytkin false}
      (:varusteet building-data) => {:viemariKytkin true
                                     :saunoja nil
                                     :vesijohtoKytkin true
                                     :hissiKytkin false
                                     :vaestonsuoja nil
                                     :kaasuKytkin false
                                     :aurinkopaneeliKytkin false
                                     :koneellinenilmastointiKytkin true
                                     :sahkoKytkin true
                                     :lamminvesiKytkin true})

    (fact "Missing shouldnt match when missing vtj-prt"
      (let [doc {:schema-info {:name "rakennuksen-muuttaminen"}
                 :id          "5bc9b7e1f70b0924847661c3"}
            {rakennus :Rakennus} (get-rakennus-data
                                   (assoc application-rakennuslupa :document-buildings [(dissoc document-building :vtj-prt)])
                                   doc)]
        (:rakennuksenTiedot rakennus) => {:kayttotarkoitus nil
                                          :lammitystapa nil
                                          :liitettyJatevesijarjestelmaanKytkin false
                                          :rakennustunnus {:jarjestysnumero nil
                                                           :kiinttun "21111111111111"
                                                           :muuTunnustieto [{:MuuTunnus {:sovellus "toimenpideId" :tunnus nil}}
                                                                            {:MuuTunnus {:sovellus "Lupapiste" :tunnus nil}}]}
                                          :rakentamistapa nil
                                          :varusteet {}
                                          :verkostoliittymat {}}))))


(facts ":Rakennuspaikka with :kaavanaste/:kaavatilanne"
  (let [rakennuspaikka (:rakennuspaikka (documents-by-type-without-blanks (tools/unwrapped application-rakennuslupa)))]

    (fact "When kaavatilanne is set, also kaavanaste is added to canonical"
      (let [result (first (get-bulding-places rakennuspaikka application-rakennuslupa))]

        (get-in result [:Rakennuspaikka :kaavatilanne]) => truthy
        (get-in result [:Rakennuspaikka :kaavanaste]) => truthy))

    (fact "If only kaavanaste is set, kaavatilanne is not in canonical"
      (let [rakennuspaikka (assoc-in
                             (util/dissoc-in (first rakennuspaikka) [:data :kaavatilanne])
                             [:data :kaavanaste]
                             "yleis")
            result (first (get-bulding-places [rakennuspaikka] application-rakennuslupa))]

        (get-in result [:Rakennuspaikka :kaavanaste]) => truthy
        (get-in result [:Rakennuspaikka :kaavatilanne]) => falsey))

    (fact "When no mapping from kaavatilanne value to kaavanaste exists, kaavanaste should be 'ei tiedossa'"
      (let [rakennuspaikka (assoc-in (first rakennuspaikka )[:data :kaavatilanne] "maakuntakaava")
            result (first (get-bulding-places [rakennuspaikka] application-rakennuslupa))]

        (get-in result [:Rakennuspaikka :kaavanaste]) => "ei tiedossa"))

    (fact "When kaavanaste/kaavatilanne are not in rakennuspaikka, they are not in canonical either"
      (let [rakennuspaikka (util/dissoc-in rakennuspaikka [:Rakennuspaikka :kaavatilanne])
            result (first (get-bulding-places [rakennuspaikka] application-rakennuslupa))]

        (get-in result [:Rakennuspaikka]) => truthy
        (get-in result [:Rakennuspaikka :kaavanaste]) => falsey
        (get-in result [:Rakennuspaikka :kaavatilanne]) => falsey))))

(defn asiakirjat-toimitettu-checker
  "Checking depends on value of the generator above.
  Selects newest attachment version (if applicable), and checks if actual matches version's created."
  [actual]
  (if-let [version (->> (map :latestVersion (:attachments application-rakennuslupa))
                        (remove nil?)
                        (sort-by :created)
                        last)]
    (= actual (-> version (:created) (util/to-xml-date)))
    (ss/blank? actual)))

(facts "Canonical model is correct"
  (against-background
    (org/pate-scope? irrelevant) => false)
  (let [canonical (application-to-canonical application-rakennuslupa "sv")
        rakennusvalvonta (:Rakennusvalvonta canonical)
        rakennusvalvontaasiatieto (:rakennusvalvontaAsiatieto rakennusvalvonta)
        rakennusvalvontaasia (:RakennusvalvontaAsia rakennusvalvontaasiatieto)

        toimituksenTiedot (:toimituksenTiedot rakennusvalvonta)
        aineistonnimi (:aineistonnimi toimituksenTiedot )
        lausuntotieto (first (:lausuntotieto rakennusvalvontaasia))
        Lausunto (:Lausunto lausuntotieto)
        pyyntoPvm (:pyyntoPvm Lausunto)
        lausuntotieto (:lausuntotieto Lausunto)
        LL (:Lausunto lausuntotieto)  ;Lausunto oli jo kaytossa, siksi LL
        viranomainen (:viranomainen LL)
        lausunto (:lausunto LL)
        lausuntoPvm (:lausuntoPvm LL)
        Puolto (-> LL :puoltotieto :Puolto)
        puolto (:puolto Puolto)

        osapuolettieto (:osapuolettieto rakennusvalvontaasia)
        osapuolet (:Osapuolet osapuolettieto)
        osapuolitieto-hakija (first (:osapuolitieto osapuolet))
        osapuolitieto-hakijan-asiamies (first (filter #(= (get-in % [:Osapuoli :kuntaRooliKoodi]) "Hakijan asiamies") (:osapuolitieto osapuolet)))
        hakija-osapuoli1 (:Osapuoli osapuolitieto-hakija)
        hakijan-asiamies1 (:Osapuoli osapuolitieto-hakijan-asiamies)
        suunnittelijat (:suunnittelijatieto osapuolet)
        paasuunnitelija (:Suunnittelija (last suunnittelijat))
        tyonjohtajat (:tyonjohtajatieto osapuolet)
        tyonjohtajatieto (:Tyonjohtaja (last tyonjohtajat))

        sijaistus (:sijaistustieto tyonjohtajatieto)
        rakennuspaikkatiedot (:rakennuspaikkatieto rakennusvalvontaasia)
        rakennuspaikkatieto (first rakennuspaikkatiedot)
        rakennuspaikka (:Rakennuspaikka rakennuspaikkatieto)
        rakennuspaikanKiinteistotieto (:rakennuspaikanKiinteistotieto rakennuspaikka)
        RakennuspaikanKiinteistotieto (:RakennuspaikanKiinteisto rakennuspaikanKiinteistotieto)
        kiinteistotieto (:kiinteistotieto RakennuspaikanKiinteistotieto)
        Kiinteisto (:Kiinteisto kiinteistotieto)
        kaavatilanne (:kaavatilanne rakennuspaikka)
        toimenpiteet(:toimenpidetieto rakennusvalvontaasia)

        toimenpide (:Toimenpide (nth toimenpiteet 1))
        muu-muutostyo (:Toimenpide (nth toimenpiteet 0))
        laajennus-t (:Toimenpide (nth toimenpiteet 2))
        purku-t (:Toimenpide (nth toimenpiteet 4))
        kaupunkikuva-t (:Toimenpide (nth toimenpiteet 3))

        rakennustieto (:rakennustieto toimenpide)
        rakennus (:Rakennus rakennustieto)
        rakennuksen-omistajatieto (:Omistaja(first (:omistajatieto rakennus)))
        rakennuksentiedot (:rakennuksenTiedot rakennus)
        lisatiedot (:lisatiedot rakennusvalvontaasia)
        Lisatiedot (:Lisatiedot lisatiedot)
        kayttotapaus (:kayttotapaus rakennusvalvontaasia)
        asianTiedot (:asianTiedot rakennusvalvontaasia)
        Asiantiedot (:Asiantiedot asianTiedot)
        vahainen-poikkeaminen (:vahainenPoikkeaminen Asiantiedot)
        rakennusvalvontasian-kuvaus (:rakennusvalvontaasianKuvaus Asiantiedot)
        luvanTunnisteTiedot (:luvanTunnisteTiedot rakennusvalvontaasia)
        LupaTunnus (:LupaTunnus luvanTunnisteTiedot)
        muuTunnustieto (:muuTunnustieto LupaTunnus)
        MuuTunnus (:MuuTunnus muuTunnustieto)
        kasittelynTilatieto (:kasittelynTilatieto rakennusvalvontaasia)
        avainsanatieto (:avainsanaTieto rakennusvalvontaasia)]

    (fact "canonical"                       canonical => truthy)
    (fact "rakennusvalvonta"                rakennusvalvonta => truthy)
    (fact "rakennusvalvontaasiatieto"       rakennusvalvontaasiatieto => truthy)
    (fact "rakennusvalvontaasia"            rakennusvalvontaasia => truthy)
    (fact "toimituksenTiedot"               toimituksenTiedot => truthy)
    (fact "aineistonnimi"                   aineistonnimi => truthy)
    (fact "lausuntotieto"                   lausuntotieto => truthy)
    (fact "Lausunto"                        Lausunto => truthy)
    (fact "viranomainen"                    viranomainen => truthy)
    (fact "pyyntoPvm"                       pyyntoPvm => truthy)
    (fact "lausuntotieto"                   lausuntotieto => truthy)
    (fact "LL"                              LL => truthy)  ;Lausunto oli jo kaytossa, siksi LL
    (fact "viranomainen"                    viranomainen => truthy)
    (fact "lausunto"                        lausunto => truthy)
    (fact "lausuntoPvm"                     lausuntoPvm => truthy)
    (fact "Puolto"                          Puolto => truthy)
    (fact "puolto"                          puolto => truthy)
    (fact "osapuolettieto"                  osapuolettieto => truthy)
    (fact "osapuolet"                       osapuolet => truthy)
    (fact "osapuolitieto-hakija"            osapuolitieto-hakija  => truthy)
    (fact "osapuolitieto-hakijan-asiamies"  osapuolitieto-hakijan-asiamies => truthy)
    (fact "hakija-osapuoli1"                hakija-osapuoli1 => truthy)
    (fact "hakijan-asiamies1"               hakijan-asiamies1 => truthy)
    (fact "suunnittelijat"                  suunnittelijat => truthy)
    (fact "paasuunnitelija"                 paasuunnitelija => truthy)
    (fact "tyonjohtajat"                    tyonjohtajat => truthy)
    (fact "tyonjohtajatieto"                tyonjohtajatieto => truthy)
    (fact "rakennuspaikkatiedot"            rakennuspaikkatiedot => truthy)
    (fact "rakennuspaikkatieto"             rakennuspaikkatieto => truthy)
    (fact "rakennuspaikka"                  rakennuspaikka => truthy)
    (fact "rakennuspaikanKiinteistotieto"   rakennuspaikanKiinteistotieto => truthy)
    (fact "RakennuspaikanKiinteistotieto"   RakennuspaikanKiinteistotieto => truthy)
    (fact "kiinteistotieto"                 kiinteistotieto => truthy)
    (fact "Kiinteisto"                      Kiinteisto => truthy)
    (fact "Kiinteisto"                      Kiinteisto => truthy)
    (fact "toimenpiteet"                    toimenpiteet => truthy
    (fact "toimenpide"                      toimenpide => truthy)
    (fact "muu-muutostyo"                   muu-muutostyo => truthy)
    (fact "laajennus-t"                     laajennus-t => truthy)
    (fact "purku-t"                         purku-t => truthy)
    (fact "kaupunkikuva-t"                  kaupunkikuva-t  => truthy)
    (fact "rakennustieto"                   rakennustieto => truthy)
    (fact "rakennus"                        rakennus => truthy)
    (fact "rakennuksen-omistajatieto"       rakennuksen-omistajatieto => truthy)
    (fact "rakennuksentiedot"               rakennuksentiedot => truthy)
    (fact "lisatiedot"                      lisatiedot => truthy)
    (fact "Lisatiedot"                      Lisatiedot => truthy)
    (fact "kayttotapaus"                    kayttotapaus => truthy)
    (fact "asianTiedot"                     asianTiedot => truthy)
    (fact "Asiantiedot"                     Asiantiedot => truthy)
    (fact "vahainen-poikkeaminen"           vahainen-poikkeaminen => truthy)
    (fact "rakennusvalvontasian-kuvaus"     rakennusvalvontasian-kuvaus => truthy)
    (fact "luvanTunnisteTiedot"             luvanTunnisteTiedot => truthy)
    (fact "LupaTunnus"                      LupaTunnus => truthy)
    (fact "muuTunnustieto"                  muuTunnustieto => truthy)
    (fact "MuuTunnus"                       MuuTunnus => truthy)
    (fact "kasittelynTilatieto"             kasittelynTilatieto => truthy)
    (fact "avainsanatieto"                  avainsanatieto => truthy)

    (fact "contains nil" (util/contains-value? canonical nil?) => falsey)
    (fact "paasuunnitelija" paasuunnitelija => (contains {:suunnittelijaRoolikoodi "p\u00e4\u00e4suunnittelija"}))
    (fact "Osapuolien maara" (+ (count suunnittelijat) (count tyonjohtajat) (count (:osapuolitieto osapuolet))) => 10)
    (fact "rakennuspaikkojen maara" (count rakennuspaikkatiedot) => 1)
    (fact "tilanNimi" (:tilannimi Kiinteisto) => "Hiekkametsa")
    (fact "kiinteistotunnus" (:kiinteistotunnus Kiinteisto) => "21111111111111")
    (fact "maaraalaTunnus" (:maaraAlaTunnus Kiinteisto) => nil)
    (fact "kokotilakytkin" (:kokotilaKytkin RakennuspaikanKiinteistotieto) => truthy)
    (fact "hallintaperuste" (:hallintaperuste RakennuspaikanKiinteistotieto) => "oma")
    (facts "Kaavatilanne"
      (fact "is 'oikeusvaikutteinen yleiskaava'" kaavatilanne => "oikeusvaikutteinen yleiskaava")
      (fact "mapping has added correct :kaavanaste to canonical" (:kaavanaste rakennuspaikka) => "yleis"))

    (fact "Toimenpidetieto"  (count toimenpiteet) => 5)
    (fact "toimenpiteet in correct order"
      (->> (map :Toimenpide toimenpiteet) (mapcat keys) (remove #{:rakennustieto :rakennelmatieto})) => [:muuMuutosTyo :uusi :laajennus :kaupunkikuvaToimenpide :purkaminen])
    (fact "rakentajaTyyppi" (:rakentajatyyppi rakennus) => "muu")
    (fact "kayttotarkoitus" (:kayttotarkoitus rakennuksentiedot) => "012 kahden asunnon talot")
    (fact "rakentamistapa" (:rakentamistapa rakennuksentiedot) => "elementti")

    (fact "tilavuus" (:tilavuus rakennuksentiedot) => "1500")
    (fact "kokonaisala" (:kokonaisala rakennuksentiedot) => "1000")
    (fact "kellarinpinta-ala" (:kellarinpinta-ala rakennuksentiedot) => "100")
    (fact "kerrosluku" (:kerrosluku rakennuksentiedot) => "2")
    (fact "kerrosala" (:kerrosala rakennuksentiedot) => "180")
    (fact "rakennusoikeudellinenKerrosala" (:rakennusoikeudellinenKerrosala rakennuksentiedot) => "160")

    (fact "paloluokka" (:paloluokka rakennuksentiedot) => "P1")
    (fact "energialuokka" (:energialuokka rakennuksentiedot) => "C")
    (fact "energiatehokkuusluku" (:energiatehokkuusluku rakennuksentiedot) => "124")
    (fact "energiatehokkuusluvunYksikko" (:energiatehokkuusluvunYksikko rakennuksentiedot) => "kWh/m2")

    (fact "rakennuksen omistajalaji" (:omistajalaji (:omistajalaji rakennuksen-omistajatieto)) => "muu yksityinen henkil\u00f6 tai perikunta")
    (fact "KuntaRooliKoodi" (:kuntaRooliKoodi rakennuksen-omistajatieto) => "Rakennuksen omistaja")
    (fact "VRKrooliKoodi" (:VRKrooliKoodi rakennuksen-omistajatieto) => "rakennuksen omistaja")
    (fact "Lisatiedot suoramarkkinointikielto" (:suoramarkkinointikieltoKytkin Lisatiedot) => nil?)
    (fact "vakuus" (:vakuus Lisatiedot) => nil)
    (fact "Lisatiedot asiointikieli" (:asioimiskieli Lisatiedot) => "ruotsi")
    (fact "Lisatiedot asiakirjatToimitettuPvm (2.2.2)" (:asiakirjatToimitettuPvm Lisatiedot) => asiakirjat-toimitettu-checker)
    (fact "rakennusvalvontasian-kuvaus" rakennusvalvontasian-kuvaus =>"Uuden rakennuksen rakentaminen tontille.\n\nPuiden kaataminen:Puun kaataminen")
    (fact "kayttotapaus" kayttotapaus => "Uusi hakemus")

    (fact "avainsanaTieto" avainsanatieto => [{:Avainsana "avainsana"} {:Avainsana "toinen avainsana"}])

    (fact "Muu tunnus" (:tunnus MuuTunnus) => "LP-753-2013-00001")
    (fact "Sovellus" (:sovellus MuuTunnus) => "Lupapiste")
    (fact "Toimenpiteen kuvaus" (-> toimenpide :uusi :kuvaus) => "Asuinkerrostalon tai rivitalon rakentaminen")
    (fact "Toimenpiteen kuvaus" (-> muu-muutostyo :muuMuutosTyo :kuvaus) => "Muu rakennuksen muutosty\u00f6")
    (fact "Muu muutostyon perusparannuskytkin" (-> muu-muutostyo :muuMuutosTyo :perusparannusKytkin) => true)
    (fact "Muu muutostyon rakennustietojaEimuutetaKytkin" (-> muu-muutostyo :muuMuutosTyo :rakennustietojaEimuutetaKytkin) => true)
    (fact "Muutostyon laji" (-> muu-muutostyo :muuMuutosTyo :muutostyonLaji) => "muut muutosty\u00f6t")
    (fact "valtakunnallinenNumero" (-> muu-muutostyo :rakennustieto :Rakennus :rakennuksenTiedot :rakennustunnus :valtakunnallinenNumero) => "1234567892")
    (fact "muu muutostyon rakennuksen tunnus" (-> muu-muutostyo :rakennustieto :Rakennus :rakennuksenTiedot :rakennustunnus :jarjestysnumero) => 1)
    (fact "Laajennuksen kuvaus" (-> laajennus-t :laajennus :kuvaus) => "Rakennuksen laajentaminen tai korjaaminen")
    (fact "Laajennuksen rakennuksen tunnus" (-> laajennus-t :rakennustieto :Rakennus :rakennuksenTiedot :rakennustunnus :jarjestysnumero) => 3)
    (fact "Laajennuksen rakennuksen kiintun" (-> laajennus-t :rakennustieto :Rakennus :rakennuksenTiedot :rakennustunnus :kiinttun) => "21111111111111")

    (fact "Laajennuksen pinta-alat"
      (let [huoneistoalat (get-in laajennus-t [:laajennus :laajennuksentiedot :huoneistoala])]
        (fact "x 2" (count huoneistoalat) => 2)
        (fact "Laajennuksen pintaala keys" (keys (first huoneistoalat)) => (just #{:kayttotarkoitusKoodi :pintaAla}))

        (fact "positive and negative numbers"
          (-> huoneistoalat first :pintaAla) => "150"
          (-> huoneistoalat second :pintaAla) => "-10")))

    (fact "Laajennuksen kerrosala" (get-in laajennus-t [:laajennus :laajennuksentiedot :kerrosala]) => "180")
    (fact "Laajennuksen kokonaisala" (get-in laajennus-t [:laajennus :laajennuksentiedot :kokonaisala]) => "-10")
    (fact "Laajennuksen rakennusoikeudellinenKerrosala" (get-in laajennus-t [:laajennus :laajennuksentiedot :rakennusoikeudellinenKerrosala]) => "160")

    (fact "Purkamisen kuvaus" (-> purku-t :purkaminen :kuvaus) => "Rakennuksen purkaminen")
    (fact "Poistuma pvm" (-> purku-t :purkaminen :poistumaPvm) => "2013-04-17")
    (fact "Purku: syy" (-> purku-t :purkaminen :purkamisenSyy) => "tuhoutunut")
    (facts "Purku: rakennus"
      (let [rakennus (get-in purku-t [:rakennustieto :Rakennus])]
        (fact "omistaja" (-> rakennus :omistajatieto first :Omistaja :henkilo :sahkopostiosoite) => "pena@example.com")
        (fact "yksilointitieto" (-> rakennus :yksilointitieto) => "purkaminen-id")
        (fact "rakennusnro" (-> rakennus :rakennuksenTiedot :rakennustunnus :rakennusnro) => "001")
        (fact "kayttotarkoitus" (-> rakennus :rakennuksenTiedot :kayttotarkoitus) => "012 kahden asunnon talot")))


    (facts "Kaupunkikuvatoimenpide"
           (fact "Kaupunkikuvatoimenpiteen kuvaus" (-> kaupunkikuva-t :kaupunkikuvaToimenpide :kuvaus) => "Aidan rakentaminen")
           (fact "Kaupunkikuvatoimenpiteen rakennelman kuvaus" (-> kaupunkikuva-t :rakennelmatieto :Rakennelma :kuvaus :kuvaus) => "Aidan rakentaminen rajalle")
           (fact "Rakennelman yksilointitieto" (-> kaupunkikuva-t :rakennelmatieto :Rakennelma :yksilointitieto) => "kaupunkikuva-id"))

    (facts "Statement draft"
      (let [lausunnot (:lausuntotieto rakennusvalvontaasia)
            lausunto1 (:Lausunto (first lausunnot))
            lausunto2 (:Lausunto (second lausunnot))]
        (count lausunnot) => 2
        (fact "First is given statement, has lausuntotieto"
          (:lausuntotieto lausunto1) => truthy)
        (fact "Second is draft, does not have lausuntotieto"
          (:lausuntotieto lausunto2) => nil
          (keys lausunto2) => (just [:id :pyyntoPvm :viranomainen] :in-any-order))))

    (fact "menttelyTos" (:menettelyTOS rakennusvalvontaasia) => "tos menettely"))))

(facts "Canonical model ilman ilmoitusta is correct"
  (against-background
    (org/pate-scope? irrelevant) => false)
  (let [canonical (application-to-canonical application-rakennuslupa-ilman-ilmoitusta "sv")
        rakennusvalvonta (:Rakennusvalvonta canonical)
        rakennusvalvontaasiatieto (:rakennusvalvontaAsiatieto rakennusvalvonta)
        rakennusvalvontaasia (:RakennusvalvontaAsia rakennusvalvontaasiatieto)
        rakennuspaikkatiedot (:rakennuspaikkatieto rakennusvalvontaasia)
        rakennuspaikkatieto2 (first rakennuspaikkatiedot)
        rakennuspaikka2 (:Rakennuspaikka rakennuspaikkatieto2)
        rakennuspaikanKiinteistotieto2 (:rakennuspaikanKiinteistotieto rakennuspaikka2)
        RakennuspaikanKiinteistotieto2 (:RakennuspaikanKiinteisto rakennuspaikanKiinteistotieto2)
        kiinteistotieto2 (:kiinteistotieto RakennuspaikanKiinteistotieto2)
        Kiinteisto2 (:Kiinteisto kiinteistotieto2)
        kaavatilanne2 (:kaavatilanne rakennuspaikka2)]

    (fact "canonical" canonical => truthy)
    (fact "rakennusvalvonta" (:Rakennusvalvonta canonical) => truthy)
    (fact "rakennusvalvontaasiatieto" (:rakennusvalvontaAsiatieto rakennusvalvonta) => truthy)
    (fact "rakennusvalvontaasia" (:RakennusvalvontaAsia rakennusvalvontaasiatieto) => truthy)
    (fact "rakennuspaikkatiedot" (:rakennuspaikkatieto rakennusvalvontaasia) => truthy)
    (fact "rakennuspaikkatieto2" (first rakennuspaikkatiedot) => truthy)
    (fact "rakennuspaikka2" (:Rakennuspaikka rakennuspaikkatieto2) => truthy)
    (fact "rakennuspaikanKiinteistotieto2" (:rakennuspaikanKiinteistotieto rakennuspaikka2) => truthy)
    (fact "RakennuspaikanKiinteistotieto2" (:RakennuspaikanKiinteisto rakennuspaikanKiinteistotieto2) => truthy)
    (fact "kiinteistotieto2" (:kiinteistotieto RakennuspaikanKiinteistotieto2) => truthy)
    (fact "Kiinteisto2" (:Kiinteisto kiinteistotieto2) => truthy)
    (fact "kaavatilanne2" (:kaavatilanne rakennuspaikka2) => truthy)
    (fact "contains nil" (util/contains-value? canonical nil?) => falsey)
    (fact "rakennuspaikkojen maara" (count rakennuspaikkatiedot) => 1)
    (fact "tilanNimi (ilman ilmoitusta)" (:tilannimi Kiinteisto2) => "Eramaa")
    (fact "kiinteistotunnus (ilman ilmoitusta)" (:kiinteistotunnus Kiinteisto2) => "21111111111111")
    (fact "maaraalaTunnus (ilman ilmoitusta)" (:maaraAlaTunnus Kiinteisto2) => nil)
    (fact "kokotilakytkin (ilman ilmoitusta)" (:kokotilaKytkin RakennuspaikanKiinteistotieto2) => truthy)
    (fact "hallintaperuste (ilman ilmoitusta)" (:hallintaperuste RakennuspaikanKiinteistotieto2) => "vuokra")
    (facts "Kaavatilanne (ilman ilmoitusta)"
      (fact "is 'oikeusvaikutukseton yleiskaava'" kaavatilanne2 => "oikeusvaikutukseton yleiskaava")
      (fact "mapping has added correct :kaavanaste to canonical" (:kaavanaste rakennuspaikka2) => "ei tiedossa"))))


(facts "Canonical model has correct puolto"
  (against-background
    (org/pate-scope? irrelevant) => false)
  (let [application (assoc-in application-rakennuslupa [:statements 0 :status] "palautettu")
        canonical (application-to-canonical application "sv")
        rakennusvalvonta (:Rakennusvalvonta canonical)
        rakennusvalvontaasiatieto (:rakennusvalvontaAsiatieto rakennusvalvonta)
        rakennusvalvontaasia (:RakennusvalvontaAsia rakennusvalvontaasiatieto)
        puolto (-> rakennusvalvontaasia :lausuntotieto first :Lausunto :lausuntotieto :Lausunto :puoltotieto :Puolto :puolto)]

    (fact "canonical" (application-to-canonical application "sv") => truthy)
    (fact "rakennusvalvonta" (:Rakennusvalvonta canonical) => truthy)
    (fact "rakennusvalvontaasiatieto" (:rakennusvalvontaAsiatieto rakennusvalvonta) => truthy)
    (fact "rakennusvalvontaasia" (:RakennusvalvontaAsia rakennusvalvontaasiatieto) => truthy)
    (fact "puolto" (-> rakennusvalvontaasia :lausuntotieto first :Lausunto :lausuntotieto :Lausunto :puoltotieto :Puolto :puolto) => truthy)
    (fact "puolto value" puolto => "palautettu")))

(defn check-common-tyonjohtaja-canonical [case application]
  (fact {:midje/description (str case " canonical")}
    (fl/facts* "Canonical model is correct"
      (let [canonical (application-to-canonical application "fi") => truthy
            rakennusvalvonta (:Rakennusvalvonta canonical) => truthy
            rakennusvalvontaasiatieto (:rakennusvalvontaAsiatieto rakennusvalvonta) => truthy
            rakennusvalvontaasia (:RakennusvalvontaAsia rakennusvalvontaasiatieto) => truthy

            viitelupatieto (first (:viitelupatieto rakennusvalvontaasia)) => truthy
            viitelupatieto-LupaTunnus (:LupaTunnus viitelupatieto) => truthy
            _ (-> viitelupatieto-LupaTunnus :muuTunnustieto :MuuTunnus) => falsey

            luvanTunnisteTiedot-MuuTunnus (-> rakennusvalvontaasia
                                              :luvanTunnisteTiedot
                                              :LupaTunnus
                                              :muuTunnustieto
                                              :MuuTunnus) => truthy

            osapuolet-vec (-> rakennusvalvontaasia :osapuolettieto :Osapuolet :osapuolitieto) => sequential?

            ;; henkilotyyppinen maksaja
            rooliKoodi-laskun-maksaja "Rakennusvalvonta-asian laskun maksaja"
            maksaja-filter-fn #(= (-> % :Osapuoli :kuntaRooliKoodi) rooliKoodi-laskun-maksaja)
            maksaja-Osapuoli (:Osapuoli (first (filter maksaja-filter-fn osapuolet-vec)))
            maksaja-Osapuoli-henkilo (:henkilo maksaja-Osapuoli)
            maksaja-Osapuoli-yritys (:yritys maksaja-Osapuoli)

            ;; henkilotyyppinen hakija
            rooliKoodi-hakija "Rakennusvalvonta-asian hakija"
            hakija-filter-fn #(= (-> % :Osapuoli :kuntaRooliKoodi) rooliKoodi-hakija)
            hakija-Osapuoli (:Osapuoli (first (filter hakija-filter-fn osapuolet-vec)))
            hakija-Osapuoli-henkilo (:henkilo hakija-Osapuoli)
            hakija-Osapuoli-yritys (:yritys hakija-Osapuoli)

            kayttotapaus (:kayttotapaus rakennusvalvontaasia) => truthy
            Asiantiedot (-> rakennusvalvontaasia :asianTiedot :Asiantiedot) => truthy
            _ (:vahainenPoikkeaminen Asiantiedot) => falsey
            rakennusvalvontasian-kuvaus (:rakennusvalvontaasianKuvaus Asiantiedot) => truthy

            viitelupatieto-LupaTunnus_2 (:LupaTunnus (get-viitelupatieto link-permit-data-lupapistetunnus))]

        (facts "Maksaja is correct"
          (fact "kuntaRooliKoodi" (:kuntaRooliKoodi maksaja-Osapuoli) => "Rakennusvalvonta-asian laskun maksaja")
          (fact "VRKrooliKoodi" (:VRKrooliKoodi maksaja-Osapuoli) => "maksaja")
          (fact "turvakieltoKytkin" (:turvakieltoKytkin maksaja-Osapuoli) => true)
          (validate-person maksaja-Osapuoli-henkilo)
          (fact "yritys is nil" maksaja-Osapuoli-yritys => nil))

        (facts "Hakija is correct"
          (fact "kuntaRooliKoodi" (:kuntaRooliKoodi hakija-Osapuoli) => "Rakennusvalvonta-asian hakija")
          (fact "VRKrooliKoodi" (:VRKrooliKoodi hakija-Osapuoli) => "hakija")
          (fact "turvakieltoKytkin" (:turvakieltoKytkin hakija-Osapuoli) => true)
          (validate-person hakija-Osapuoli-henkilo)
          (fact "yritys is nil" hakija-Osapuoli-yritys => nil))

        (facts "\"kuntalupatunnus\" type of link permit data"
          (fact "viitelupatieto-MuuTunnus-Tunnus" (-> viitelupatieto-LupaTunnus :muuTunnustieto :MuuTunnus :tunnus) => falsey)
          (fact "viitelupatieto-MuuTunnus-Sovellus" (-> viitelupatieto-LupaTunnus :muuTunnustieto :MuuTunnus :sovellus) => falsey)
          (fact "viitelupatieto-kuntalupatunnus" (:kuntalupatunnus viitelupatieto-LupaTunnus) => (:id link-permit-data-kuntalupatunnus))
          (fact "viitelupatieto-viittaus" (:viittaus viitelupatieto-LupaTunnus) => "edellinen rakennusvalvonta-asia"))

        (facts "\"lupapistetunnus\" type of link permit data"
          (fact "viitelupatieto-2-MuuTunnus-Tunnus" (-> viitelupatieto-LupaTunnus_2 :muuTunnustieto :MuuTunnus :tunnus) => (:id link-permit-data-lupapistetunnus))
          (fact "viitelupatieto-2-MuuTunnus-Sovellus" (-> viitelupatieto-LupaTunnus_2 :muuTunnustieto :MuuTunnus :sovellus) => "Lupapiste")
          (fact "viitelupatieto-2-kuntalupatunnus" (:kuntalupatunnus viitelupatieto-LupaTunnus_2) => falsey)
          (fact "viitelupatieto-2-viittaus" (:viittaus viitelupatieto-LupaTunnus_2) => "edellinen rakennusvalvonta-asia"))


        (fact "luvanTunnisteTiedot-MuuTunnus-Tunnus" (:tunnus luvanTunnisteTiedot-MuuTunnus) => (:id application))
        (fact "luvanTunnisteTiedot-MuuTunnus-Sovellus" (:sovellus luvanTunnisteTiedot-MuuTunnus) => "Lupapiste")

        (fact "rakennusvalvontasian-kuvaus" rakennusvalvontasian-kuvaus => "Uuden rakennuksen rakentaminen tontille.")
        (fact "kayttotapaus" kayttotapaus => "Uuden ty\u00f6njohtajan nime\u00e4minen")))))

(check-common-tyonjohtaja-canonical "Tyonjohtaja V1" application-tyonjohtajan-nimeaminen)
(check-common-tyonjohtaja-canonical "Tyonjohtaja V2" application-tyonjohtajan-nimeaminen-v2)


(fl/facts* "Canonical model for suunnittelijan nimeaminen is correct"
  (let [canonical (application-to-canonical application-suunnittelijan-nimeaminen "fi") => truthy
        rakennusvalvonta (:Rakennusvalvonta canonical) => truthy
        rakennusvalvontaasiatieto (:rakennusvalvontaAsiatieto rakennusvalvonta) => truthy
        rakennusvalvontaasia (:RakennusvalvontaAsia rakennusvalvontaasiatieto) => truthy

        viitelupatieto (first (:viitelupatieto rakennusvalvontaasia)) => truthy
        viitelupatieto-LupaTunnus (:LupaTunnus viitelupatieto) => truthy
        _ (-> viitelupatieto-LupaTunnus :muuTunnustieto :MuuTunnus) => truthy

        osapuolet-vec (-> rakennusvalvontaasia :osapuolettieto :Osapuolet :osapuolitieto) => truthy

        ;; henkilotyyppinen maksaja
        rooliKoodi-laskun-maksaja "Rakennusvalvonta-asian laskun maksaja"
        maksaja-filter-fn #(= (-> % :Osapuoli :kuntaRooliKoodi) rooliKoodi-laskun-maksaja)
        maksaja-Osapuoli (:Osapuoli (first (filter maksaja-filter-fn osapuolet-vec)))
        maksaja-Osapuoli-henkilo (:henkilo maksaja-Osapuoli)
        maksaja-Osapuoli-yritys (:yritys maksaja-Osapuoli)

        luvanTunnisteTiedot-MuuTunnus (-> rakennusvalvontaasia
                                        :luvanTunnisteTiedot
                                        :LupaTunnus
                                        :muuTunnustieto
                                        :MuuTunnus) => truthy

        kayttotapaus (:kayttotapaus rakennusvalvontaasia) => truthy
        Asiantiedot (-> rakennusvalvontaasia :asianTiedot :Asiantiedot) => truthy
        _ (:vahainenPoikkeaminen Asiantiedot) => falsey
        rakennusvalvontasian-kuvaus (:rakennusvalvontaasianKuvaus Asiantiedot) => truthy]

    (facts "Maksaja is correct"
      (fact "kuntaRooliKoodi" (:kuntaRooliKoodi maksaja-Osapuoli) => "Rakennusvalvonta-asian laskun maksaja")
      (fact "VRKrooliKoodi" (:VRKrooliKoodi maksaja-Osapuoli) => "maksaja")
      (fact "turvakieltoKytkin" (:turvakieltoKytkin maksaja-Osapuoli) => true)
      (validate-person maksaja-Osapuoli-henkilo)
      (fact "yritys is nil" maksaja-Osapuoli-yritys => nil))

    (facts "\"lupapistetunnus\" type of link permit data"
      (fact "viitelupatieto-MuuTunnus-Tunnus" (-> viitelupatieto-LupaTunnus :muuTunnustieto :MuuTunnus :tunnus) => (:id link-permit-data-lupapistetunnus))
      (fact "viitelupatieto-MuuTunnus-Sovellus" (-> viitelupatieto-LupaTunnus :muuTunnustieto :MuuTunnus :sovellus) => "Lupapiste")
      (fact "viitelupatieto-kuntalupatunnus" (:kuntalupatunnus viitelupatieto-LupaTunnus) => falsey)
      (fact "viitelupatieto-viittaus" (:viittaus viitelupatieto-LupaTunnus) => "edellinen rakennusvalvonta-asia"))


    (fact "luvanTunnisteTiedot-MuuTunnus-Tunnus" (:tunnus luvanTunnisteTiedot-MuuTunnus) => "LP-753-2013-00003")
    (fact "luvanTunnisteTiedot-MuuTunnus-Sovellus" (:sovellus luvanTunnisteTiedot-MuuTunnus) => "Lupapiste")

    (fact "rakennusvalvontasian-kuvaus" rakennusvalvontasian-kuvaus => "Uuden rakennuksen rakentaminen tontille.")
    (fact "kayttotapaus" kayttotapaus => "Uuden suunnittelijan nime\u00e4minen")))

(def- authority-user-jussi {:id "777777777777777777000017"
                                     :email "jussi.viranomainen@tampere.fi"
                                     :enabled true
                                     :role "authority"
                                     :username "jussi"
                                     :organizations ["837-YA"]
                                     :firstName "Jussi"
                                     :lastName "Viranomainen"
                                     :street "Katuosoite 1 a 1"
                                     :phone "1231234567"
                                     :zip "33456"
                                     :city "Tampere"})

(def application-rakennuslupa-verdict-given
  (assoc application-rakennuslupa
    :state "verdictGiven"
    :verdicts [{:timestamp (:modified application-rakennuslupa)
                :kuntalupatunnus "2013-01"}]
    :buildings [{:propertyId   "21111111111111"
                 :nationalId   "098098098"
                 :localShortId "001"
                 :description  "Talo"
                 :operationId  "op1"}
                {:propertyId   "21111111111111"
                 :nationalId   "1234567892"
                 :localShortId "002"
                 :description  "Eri talo"}
                {:propertyId   "21111111111111"
                 :nationalId   "098098098"
                 :localShortId "003"
                 :operationId  "op3"}
                {:propertyId   "21111111111111"
                 :nationalId   "098098098"
                 :localShortId "004"
                 :operationId  "op4"}]))

(testable-privates lupapalvelu.document.rakennuslupa-canonical enrich-review-building get-review-muutunnustieto get-review-rakennustunnus get-review-katselmuksenrakennus get-review-huomautus)

(facts enrich-review-building
  (fact "empty building"
    (enrich-review-building application-rakennuslupa-verdict-given {}) => nil)

  (fact "nil building"
    (enrich-review-building application-rakennuslupa-verdict-given nil) => nil)

  (fact "with all data"
    (enrich-review-building application-rakennuslupa-verdict-given {:rakennus {:rakennusnro "001"
                                                                               :jarjestysnumero 1
                                                                               :valtakunnallinenNumero "123"
                                                                               :kiinttun "21111111111111"}})
    => {:rakennus {:rakennusnro "001"
                   :jarjestysnumero 1
                   :valtakunnallinenNumero "123"
                   :kiinttun "21111111111111"
                   :description "Talo"
                   :operationId "op1"}})

  (fact "rakennusnro missing, match by valtakunnallinenNumero"
    (enrich-review-building application-rakennuslupa-verdict-given {:rakennus {:jarjestysnumero 1
                                                                               :valtakunnallinenNumero "098098098"
                                                                               :kiinttun "21111111111111"}})
    => {:rakennus {:jarjestysnumero 1
                   :valtakunnallinenNumero "098098098"
                   :kiinttun "21111111111111"
                   :description "Talo"
                   :operationId "op1"}})

  (fact "kiinttun missing"
    (enrich-review-building application-rakennuslupa-verdict-given {:rakennus {:rakennusnro "001"
                                                                               :jarjestysnumero 1
                                                                               :valtakunnallinenNumero "123"}})
    => {:rakennus {:rakennusnro "001"
                   :jarjestysnumero 1
                   :valtakunnallinenNumero "123"
                   :kiinttun "21111111111111"
                   :description "Talo"
                   :operationId "op1"}})

  (fact "valtakunnallinenNumero missing"
    (enrich-review-building application-rakennuslupa-verdict-given {:rakennus {:rakennusnro "002"
                                                                               :jarjestysnumero 2
                                                                               :kiinttun "21111111111111"}})
    => {:rakennus {:rakennusnro "002"
                   :jarjestysnumero 2
                   :valtakunnallinenNumero "1234567892"
                   :kiinttun "21111111111111"
                   :description "Eri talo"}}))

(facts get-review-muutunnustieto
  (fact "emtpy task"
    (get-review-muutunnustieto {}) => [])

  (fact "nil task"
    (get-review-muutunnustieto nil) => [])

  (fact "with all required data"
    (get-review-muutunnustieto {:id "123" :data {:muuTunnus "456" :muuTunnusSovellus "RapApp"}})
    => [{:MuuTunnus {:tunnus "123" :sovellus "Lupapiste"}}
        {:MuuTunnus {:tunnus "456" :sovellus "RapApp"}}])

  (fact "with empty muuTunnusSovellus"
    (get-review-muutunnustieto {:id "123" :data {:muuTunnus "456" :muuTunnusSovellus ""}})
    => [{:MuuTunnus {:tunnus "123" :sovellus "Lupapiste"}}
        {:MuuTunnus {:tunnus "456" :sovellus ""}}])

  (fact "without muuTunnusSovellus"
    (get-review-muutunnustieto {:id "123" :data {:muuTunnus "456" :muuTunnusSovellus nil}})
    => [{:MuuTunnus {:tunnus "123" :sovellus "Lupapiste"}}
        {:MuuTunnus {:tunnus "456" :sovellus nil}}])

  (fact "with empty muuTunnus"
    (get-review-muutunnustieto {:id "123" :data {:muuTunnus "" :muuTunnusSovellus "RakApp"}})
    => [{:MuuTunnus {:tunnus "123" :sovellus "Lupapiste"}} ])

  (fact "without muuTunnus"
    (get-review-muutunnustieto {:id "123" :data {:muuTunnus nil :muuTunnusSovellus "RakApp"}})
    => [{:MuuTunnus {:tunnus "123" :sovellus "Lupapiste"}} ])

  (fact "with empty task id"
    (get-review-muutunnustieto {:id "" :data {:muuTunnus "456" :muuTunnusSovellus "RakApp"}})
    => [{:MuuTunnus {:tunnus "456" :sovellus "RakApp"}}])

  (fact "without task id"
    (get-review-muutunnustieto {:data {:muuTunnus "456" :muuTunnusSovellus "RakApp"}})
    => [{:MuuTunnus {:tunnus "456" :sovellus "RakApp"}}]))

(facts get-review-rakennustunnus
  (fact "emtpy buildings"
    (get-review-rakennustunnus []) => {})

  (fact "nil buildings"
    (get-review-rakennustunnus nil) => {})

  (fact "with all building data"
    (get-review-rakennustunnus [{:rakennus {:rakennusnro "002"
                                            :jarjestysnumero 2
                                            :valtakunnallinenNumero "1234567892"
                                            :kiinttun "21111111111111"
                                            :operationId "op2"
                                            :description "Eri talo"}}]) =>
    {:rakennusnro "002"
     :jarjestysnumero 2
     :valtakunnallinenNumero "1234567892"
     :kiinttun "21111111111111"})

  (fact "rakennusnro empty"
    (get-review-rakennustunnus [{:rakennus {:rakennusnro ""
                                            :jarjestysnumero 2
                                            :valtakunnallinenNumero "1234567892"
                                            :kiinttun "21111111111111"
                                            :operationId "op2"
                                            :description "Eri talo"}}]) =>
    {:jarjestysnumero 2
     :valtakunnallinenNumero "1234567892"
     :kiinttun "21111111111111"})

  (fact "valtakunnallinenNumero is nil"
    (get-review-rakennustunnus [{:rakennus {:rakennusnro "002"
                                            :jarjestysnumero 2
                                            :valtakunnallinenNumero nil
                                            :kiinttun "21111111111111"
                                            :operationId "op2"
                                            :description "Eri talo"}}]) =>
    {:rakennusnro "002"
     :jarjestysnumero 2
     :kiinttun "21111111111111"}))

(facts get-review-katselmuksenrakennus
  (fact "with all data"
    (get-review-katselmuksenrakennus {:tila     {:tila "pidetty"
                                                 :kayttoonottava true}
                                      :rakennus {:rakennusnro "001"
                                                 :jarjestysnumero 1
                                                 :valtakunnallinenNumero "123"
                                                 :kunnanSisainenPysyvaRakennusnumero "internal-123"
                                                 :kiinttun "21111111111111"
                                                 :description "Talo"
                                                 :operationId "op1"}})
    => {:KatselmuksenRakennus {:jarjestysnumero 1
                               :katselmusOsittainen "pidetty"
                               :kayttoonottoKytkin true
                               :kiinttun "21111111111111"
                               :rakennusnro "001"
                               :valtakunnallinenNumero "123"
                               :kunnanSisainenPysyvaRakennusnumero "internal-123"
                               :muuTunnustieto [{:MuuTunnus {:tunnus "op1" :sovellus "toimenpideId"}}
                                                {:MuuTunnus {:tunnus "op1" :sovellus "Lupapiste"}}]
                               :rakennuksenSelite "Talo"}})

  (fact "operation id missing"
    (get-review-katselmuksenrakennus {:tila     {:tila "pidetty"
                                                 :kayttoonottava true}
                                      :rakennus {:rakennusnro "001"
                                                 :jarjestysnumero 1
                                                 :valtakunnallinenNumero "123"
                                                 :kunnanSisainenPysyvaRakennusnumero "internal-123"
                                                 :kiinttun "21111111111111"
                                                 :description "Talo"}})
    => {:KatselmuksenRakennus {:jarjestysnumero 1
                               :katselmusOsittainen "pidetty"
                               :kayttoonottoKytkin true
                               :kiinttun "21111111111111"
                               :rakennusnro "001"
                               :valtakunnallinenNumero "123"
                               :kunnanSisainenPysyvaRakennusnumero "internal-123"
                               :rakennuksenSelite "Talo"}})

  (fact "description missing"
    (get-review-katselmuksenrakennus {:tila     {:tila "pidetty"
                                                 :kayttoonottava false}
                                      :rakennus {:rakennusnro "001"
                                                 :jarjestysnumero 1
                                                 :valtakunnallinenNumero "123"
                                                 :kunnanSisainenPysyvaRakennusnumero "internal-123"
                                                 :kiinttun "21111111111111"
                                                 :operationId "op1"}})
    => {:KatselmuksenRakennus {:jarjestysnumero 1
                               :katselmusOsittainen "pidetty"
                               :kayttoonottoKytkin false
                               :kiinttun "21111111111111"
                               :rakennusnro "001"
                               :valtakunnallinenNumero "123"
                               :kunnanSisainenPysyvaRakennusnumero "internal-123"
                               :muuTunnustieto [{:MuuTunnus {:tunnus "op1" :sovellus "toimenpideId"}}
                                                {:MuuTunnus {:tunnus "op1" :sovellus "Lupapiste"}}]}})

  (fact "minimal data"
    (get-review-katselmuksenrakennus {:tila     {:tila "osittain"}
                                      :rakennus {:jarjestysnumero 1}})
    => {:KatselmuksenRakennus {:jarjestysnumero 1
                               :katselmusOsittainen "osittain"
                               :kayttoonottoKytkin nil}}))

(facts get-review-huomautus
  (facts "empty huomautukset"
    (get-review-huomautus {}) => nil)

  (facts "huomautukset is nil"
    (get-review-huomautus nil) => nil)

  (facts "all huomautukset data"
    (get-review-huomautus {:kuvaus "korjattava"
                           :toteaja "Kaapo Katselmoinen"
                           :maaraAika "05.06.2017"
                           :toteamisHetki "07.08.2017"})
    => {:huomautus {:kuvaus "korjattava"
                    :toteaja "Kaapo Katselmoinen"
                    :maaraAika "2017-06-05"
                    :toteamisHetki "2017-08-07"}})

  (facts "kuvaus is missing"
    (get-review-huomautus {:toteaja "Kaapo Katselmoinen"
                           :maaraAika "05.06.2017"
                           :toteamisHetki "07.08.2017"})
    => nil)

  (facts "kuvaus is empty"
    (get-review-huomautus {:kuvaus ""
                           :toteaja "Kaapo Katselmoinen"
                           :maaraAika "05.06.2017"
                           :toteamisHetki "07.08.2017"})
    => {:huomautus {:kuvaus "-"
                    :toteaja "Kaapo Katselmoinen"
                    :maaraAika "2017-06-05"
                    :toteamisHetki "2017-08-07"}})

  (facts "toteaja is missing"
    (get-review-huomautus {:kuvaus "korjattava"
                           :maaraAika "05.06.2017"
                           :toteamisHetki "07.08.2017"})
    => {:huomautus {:kuvaus "korjattava"
                    :maaraAika "2017-06-05"
                    :toteamisHetki "2017-08-07"}}))

(fl/facts* "Canonical model for aloitusilmoitus is correct"
  (let [application application-rakennuslupa-verdict-given
        review {:data {:katselmus {:pitoPvm {:value 1354532324658}
                                   :pitaja {:value nil}
                                   :lasnaolijat {:value nil}
                                   :poikkeamat {:value nil}
                                   :tila {:value nil}
                                   :tiedoksianto {:value nil}
                                   :huomautukset []}
                       :katselmuksenLaji {:value "Aloitusilmoitus"}
                       :vaadittuLupaehtona {:value nil}
                       :rakennus {:0 {:tila {:tila {:value "osittainen"}}
                                      :rakennus {:rakennusnro {:value "002"}
                                                 :jarjestysnumero {:value 1}
                                                 :kiinttun {:value "21111111111111"}}}
                                  :1 {:tila {:tila {:value "lopullinen"}}
                                      :rakennus {:rakennusnro {:value "003"}
                                                 :jarjestysnumero {:value 3}
                                                 :kiinttun {:value "21111111111111"}
                                                 :valtakunnallinenNumero {:value "1234567892"}}}
                                  :2 {:tila {:tila {:value ""}}
                                      :rakennus {:rakennusnro {:value "004"}
                                                 :jarjestysnumero {:value 3}
                                                 :kiinttun {:value "21111111111111"}
                                                 :valtakunnallinenNumero {:value "1234567892"}}}}
                       :muuTunnus "review1"
                       :muuTunnusSovellus "RakApp"}
                :id "123"
                :taskname "Aloitusilmoitus 1"}
        canonical (katselmus-canonical application "sv" review authority-user-jussi)
        Rakennusvalvonta (:Rakennusvalvonta canonical) => truthy
        toimituksenTiedot (:toimituksenTiedot Rakennusvalvonta) => truthy
        _ (:kuntakoodi toimituksenTiedot) => truthy
        rakennusvalvontaAsiatieto (:rakennusvalvontaAsiatieto Rakennusvalvonta) => truthy
        RakennusvalvontaAsia (:RakennusvalvontaAsia rakennusvalvontaAsiatieto) => truthy
        kasittelynTilatieto (:kasittelynTilatieto RakennusvalvontaAsia)
        Tilamuutos (-> kasittelynTilatieto last :Tilamuutos) => truthy
        _ (:tila Tilamuutos) => "p\u00e4\u00e4t\u00f6s toimitettu"
        luvanTunnisteTiedot (:luvanTunnisteTiedot RakennusvalvontaAsia) => truthy
        LupaTunnus (:LupaTunnus luvanTunnisteTiedot) => truthy
        muuTunnustieto (:muuTunnustieto LupaTunnus) => truthy
        mt (:MuuTunnus muuTunnustieto) => truthy

        _ (:tunnus mt) => "LP-753-2013-00001"
        _ (:sovellus mt) => "Lupapiste"

        osapuolettieto (:osapuolettieto RakennusvalvontaAsia) => truthy
        Osapuolet (:Osapuolet osapuolettieto) => truthy
        osapuolitieto (:osapuolitieto Osapuolet) => truthy
        Osapuoli (:Osapuoli osapuolitieto) => truthy
        _ (:kuntaRooliKoodi Osapuoli) => "Ilmoituksen tekij\u00e4"
        henkilo (:henkilo Osapuoli) => truthy
        nimi (:nimi henkilo) => truthy
        _ (:etunimi nimi) => "Jussi"
        _ (:sukunimi nimi) => "Viranomainen"
        osoite (:osoite henkilo) => truthy
        _ (-> osoite :osoitenimi :teksti) => "Katuosoite 1 a 1"
        _ (:puhelin henkilo) => "1231234567"

        katselmustieto (:katselmustieto RakennusvalvontaAsia) => truthy
        Katselmus (:Katselmus katselmustieto) => truthy
        rakennustunnus (:rakennustunnus Katselmus) => map?]

      (:jarjestysnumero rakennustunnus) => 1
      (:valtakunnallinenNumero rakennustunnus) => "1234567892"
      (:rakennusnro rakennustunnus) => "002"
      (:kiinttun rakennustunnus) => "21111111111111"

    (:kayttotapaus RakennusvalvontaAsia) => "Aloitusilmoitus"
    (:katselmuksenLaji Katselmus)  => "ei tiedossa"
    (:pitoPvm Katselmus) => "2012-12-03"

    (fact "tarkastuksenTaiKatselmuksenNimi is trimmed (LPK-2082)"
      (:tarkastuksenTaiKatselmuksenNimi Katselmus) => "Aloitusilmoitus 1")

    (fact "KRYSP 2.1.3 data is present"
      (get-in katselmustieto [:Katselmus :muuTunnustieto]) => [{:MuuTunnus {:tunnus "123" :sovellus "Lupapiste"}}
                                                               {:MuuTunnus {:tunnus "review1" :sovellus "RakApp"}}]
      (let [rakennukset (map :KatselmuksenRakennus (get-in katselmustieto [:Katselmus :katselmuksenRakennustieto]))]
        (fact "has 2 buildings" (count rakennukset) => 2)
        (fact "jarjestysnumero" (:jarjestysnumero (last rakennukset)) => 3)
        (fact "valtakunnallinenNumero" (:valtakunnallinenNumero (last rakennukset)) => "1234567892")
        (fact "rakennusnro" (:rakennusnro (last rakennukset)) => "003")
        (fact "kiinttun" (:kiinttun (last rakennukset)) => "21111111111111")))))

(fl/facts* "Canonical model for erityissuunnitelma is correct"
  (let [application application-rakennuslupa-verdict-given
        canonical (unsent-attachments-to-canonical application "sv")

        Rakennusvalvonta (:Rakennusvalvonta canonical) => truthy
        toimituksenTiedot (:toimituksenTiedot Rakennusvalvonta) => truthy
        _ (:kuntakoodi toimituksenTiedot) => truthy
        rakennusvalvontaAsiatieto (:rakennusvalvontaAsiatieto Rakennusvalvonta) => truthy
        RakennusvalvontaAsia (:RakennusvalvontaAsia rakennusvalvontaAsiatieto) => truthy
        kasittelynTilatieto (:kasittelynTilatieto RakennusvalvontaAsia)
        Tilamuutos (-> kasittelynTilatieto last :Tilamuutos) => truthy

        luvanTunnisteTiedot (:luvanTunnisteTiedot RakennusvalvontaAsia) => truthy
        LupaTunnus (:LupaTunnus luvanTunnisteTiedot) => truthy
        muuTunnustieto (:muuTunnustieto LupaTunnus) => truthy
        mt (:MuuTunnus muuTunnustieto) => truthy]

    (fact "tila" (:tila Tilamuutos) => "p\u00e4\u00e4t\u00f6s toimitettu")
    (fact "tunnus" (:tunnus mt) => "LP-753-2013-00001")
    (fact "sovellus" (:sovellus mt) => "Lupapiste")
    (fact "kayttotapaus" (:kayttotapaus RakennusvalvontaAsia) => "Liitetiedoston lis\u00e4ys")))



;Jatkolupa

(def jatkolupa-application
  {:schema-version 1,
   :auth [{:lastName "Panaani",
           :firstName "Pena",
           :username "pena",
           :role "writer",
           :id "777777777777777777000020"}],
   :submitted 1384167310181,
   :state "submitted",
   :permitSubtype nil,
   :location [411063.82824707 6685145.8129883],
   :attachments [],
   :organization "753-R",
   :title "It\u00e4inen Hangelbyntie 163",
   :primaryOperation {:id "5280b764420622588b2f04fc",
                      :name "raktyo-aloit-loppuunsaat",
                      :created 1384167268234}
   :secondaryOperations [],
   :infoRequest false,
   :openInfoRequest false,
   :opened 1384167310181,
   :created 1384167268234,
   :propertyId "75340800010051",
   :documents [{:created 1384167268234,
                :data {:kuvaus {:modified 1384167309006,
                                :value "Pari vuotta jatko-aikaa, ett\u00e4 saadaan rakennettua loppuun."}
                       :jatkoaika-paattyy {:modified 1384167309006,
                                           :value "31.12.2018"},
                       :rakennustyo-aloitettu {:modified 1384167309006,
                                               :value "7.6.2010"}},
                :id "5280b764420622588b2f04fd",
                :schema-info {:order 1,
                              :version 1,
                              :name "jatkoaika-hankkeen-kuvaus",
                              :subtype "hankkeen-kuvaus"
                              :approvable true,
                              :op {:id "5280b764420622588b2f04fc",
                                   :name "raktyo-aloit-loppuunsaat",
                                   :created 1384167268234}}}
               hakija-henkilo],
   :_software_version "1.0.5",
   :modified 1384167309006,
   :comments [],
   :address "It\u00e4inen Hangelbyntie 163",
   :permitType "R",
   :id "LP-753-2013-00005",
   :municipality "753"
   :authority {:id "777777777777777777000023"
               :username "sonja"
               :firstName "Sonja"
               :lastName "Sibbo"
               :role "authority"}
     :linkPermitData [link-permit-data-lupapistetunnus]})

(ctc/validate-all-documents jatkolupa-application)


(fl/facts* "Canonical model for katselmus is correct"
           (let [review {:data {:katselmus {:pitoPvm {:value 1354532324658}
                                            :pitaja {:value "Sonja Silja"}
                                            :lasnaolijat {:value "Tiivi Taavi, Hipsu ja Lala"}
                                            :poikkeamat {:value "Ei poikkeamisia"}
                                            :tila {:value "pidetty"}
                                            :tiedoksianto {:value false}
                                            :huomautukset {:kuvaus {:value "Saunan ovi pit\u00e4\u00e4 vaihtaa 900mm leve\u00e4ksi.\nPiha-alue siivottava v\u00e4litt\u00f6m\u00e4sti."}
                                                           :maaraAika {:value "05.5.2014"}
                                                           :toteaja {:value "Jussi"}
                                                           :toteamisHetki {:value "4.04.2014"}}}
                                :katselmuksenLaji {:value "pohjakatselmus"}
                                :vaadittuLupaehtona {:value true}
                                :rakennus {:0 {:tila {:tila {:value "pidetty"}
                                                      :kayttoonottava {:value false}}
                                               :rakennus {:rakennusnro {:value "002"}
                                                          :jarjestysnumero {:value 1}
                                                          :kiinttun {:value "01234567891234"}}}}
                                :muuTunnus {:value "review2"}
                                :muuTunnusSovellus {:value "RakApp"}}
                         :id "123"
                         :taskname "Pohjakatselmus 1"}
                 canonical (katselmus-canonical application-rakennuslupa-verdict-given "fi" review authority-user-jussi)

                 Rakennusvalvonta (:Rakennusvalvonta canonical) => truthy
                 toimituksenTiedot (:toimituksenTiedot Rakennusvalvonta) => truthy
                 _ (:kuntakoodi toimituksenTiedot) => truthy
                 rakennusvalvontaAsiatieto (:rakennusvalvontaAsiatieto Rakennusvalvonta) => truthy
                 RakennusvalvontaAsia (:RakennusvalvontaAsia rakennusvalvontaAsiatieto) => truthy
                 kasittelynTilatieto (:kasittelynTilatieto RakennusvalvontaAsia)
                 Tilamuutos (-> kasittelynTilatieto last :Tilamuutos) => map?
                 _ (:tila Tilamuutos) => "p\u00e4\u00e4t\u00f6s toimitettu"

                 luvanTunnisteTiedot (:luvanTunnisteTiedot RakennusvalvontaAsia) => truthy
                 LupaTunnus (:LupaTunnus luvanTunnisteTiedot) => truthy
                 _ (:kuntalupatunnus LupaTunnus) => "2013-01"
                 muuTunnustieto (:muuTunnustieto LupaTunnus) => truthy
                 mt (:MuuTunnus muuTunnustieto) => truthy

                 _ (:tunnus mt) => "LP-753-2013-00001"
                 _ (:sovellus mt) => "Lupapiste"

                 osapuolettieto (:osapuolettieto RakennusvalvontaAsia) => truthy
                 Osapuolet (:Osapuolet osapuolettieto) => truthy
                 osapuolitieto (:osapuolitieto Osapuolet) => truthy
                 Osapuoli (:Osapuoli osapuolitieto) => truthy
                 _ (:kuntaRooliKoodi Osapuoli) => "Ilmoituksen tekij\u00e4"
                 henkilo (:henkilo Osapuoli) => truthy
                 nimi (:nimi henkilo) => truthy
                 _ (:etunimi nimi) => "Jussi"
                 _ (:sukunimi nimi) => "Viranomainen"
                 osoite (:osoite henkilo) => truthy
                 _ (-> osoite :osoitenimi :teksti) => "Katuosoite 1 a 1"
                 _ (:puhelin henkilo) => "1231234567"

                 katselmustieto (:katselmustieto RakennusvalvontaAsia) => truthy
                 Katselmus (:Katselmus katselmustieto) => truthy
                 rakennustunnus (:rakennustunnus Katselmus) => truthy
                 _ (:jarjestysnumero rakennustunnus) => 1
                 _ (:rakennusnro rakennustunnus) => "002"
                 _ (:kiinttun rakennustunnus) => "01234567891234"
                 _ (:pitoPvm Katselmus) => "2012-12-03"
                 _ (:osittainen Katselmus) => "pidetty"
                 _ (:pitaja Katselmus) => "Sonja Silja"
                 huomautus (-> Katselmus :huomautukset :huomautus)
                 _ (:katselmuksenLaji Katselmus) => "pohjakatselmus"
                 _ (:lasnaolijat Katselmus ) => "Tiivi Taavi, Hipsu ja Lala"
                 _ (:poikkeamat Katselmus) => "Ei poikkeamisia"
                 _ (:verottajanTvLlKytkin Katselmus) => false
                 _ (:tarkastuksenTaiKatselmuksenNimi Katselmus) => "Pohjakatselmus 1"
                 _ (:kayttotapaus RakennusvalvontaAsia) => "Uusi katselmus"

                 rakennustieto (first (:katselmuksenRakennustieto Katselmus)) => truthy
                 _ (get-in rakennustieto [:KatselmuksenRakennus :katselmusOsittainen]) => "pidetty"
                 _ (get-in rakennustieto [:KatselmuksenRakennus :kayttoonottoKytkin]) => false]

             (:kuvaus huomautus) => "Saunan ovi pit\u00e4\u00e4 vaihtaa 900mm leve\u00e4ksi.\nPiha-alue siivottava v\u00e4litt\u00f6m\u00e4sti."
             (:maaraAika huomautus) => "2014-05-05"
             (:toteamisHetki huomautus) => "2014-04-04"
             (:toteaja huomautus) => "Jussi")

           (fact "If huomautus kuvaus is empty, a dash (-) is put as kuvaus value for huomautus"
             (let [review {:data {:katselmus {:pitoPvm {:value 1354532324658}
                                              :pitaja {:value "Sonja Silja"}
                                              :lasnaolijat {:value "Tiivi Taavi, Hipsu ja Lala"}
                                              :poikkeamat {:value "Ei poikkeamisia"}
                                              :tila {:value "pidetty"}
                                              :tiedoksianto {:value false}
                                              :huomautukset {:kuvaus {:value ""}}}
                                  :katselmuksenLaji {:value "pohjakatselmus"}
                                  :vaadittuLupaehtona {:value true}
                                  :rakennus {:0 {:tila {:tila {:value "pidetty"}
                                                        :kayttoonottava {:value false}}
                                                 :rakennus {:rakennusnro {:value "002"}
                                                            :jarjestysnumero {:value 1}
                                                            :kiinttun {:value "01234567891234"}}}}
                                  :muuTunnus {:value "review2"}
                                  :muuTunnusSovellus {:value "RakApp"}}
                           :id "123"
                           :taskname "Pohjakatselmus 1"}
                   katselmus-huomautus (katselmus-canonical application-rakennuslupa-verdict-given "fi" review authority-user-jussi)]
               (get-in katselmus-huomautus [:Rakennusvalvonta :rakennusvalvontaAsiatieto :RakennusvalvontaAsia
                                            :katselmustieto :Katselmus :huomautukset :huomautus :kuvaus]) => "-")))

(fl/facts* "Katselmus with empty buildings is OK (no buildings in canonical)"
  (let [review {:data {:katselmus {:pitoPvm {:value 1354532324658}
                                   :pitaja {:value "Sonja Silja"}
                                   :lasnaolijat {:value "Tiivi Taavi, Hipsu ja Lala"}
                                   :poikkeamat {:value "Ei poikkeamisia"}
                                   :tila {:value "pidetty"}
                                   :tiedoksianto {:value false}
                                   :huomautukset {:kuvaus {:value "Saunan ovi pit\u00e4\u00e4 vaihtaa 900mm leve\u00e4ksi.\nPiha-alue siivottava v\u00e4litt\u00f6m\u00e4sti."}
                                                  :maaraAika {:value "05.5.2014"}
                                                  :toteaja {:value "Jussi"}
                                                  :toteamisHetki {:value "4.04.2014"}}}
                       :katselmuksenLaji {:value "pohjakatselmus"}
                       :vaadittuLupaehtona {:value true}
                       :rakennus {}
                       :muuTunnus {:value "review2"}
                       :muuTunnusSovellus {:value "RakApp"}}
                :id "123"
                :taskname "Pohjakatselmus 1"}
        canonical (katselmus-canonical
                   application-rakennuslupa-verdict-given
                   "fi"
                   review
                   authority-user-jussi) => truthy
        Rakennusvalvonta (:Rakennusvalvonta canonical) => truthy
        toimituksenTiedot (:toimituksenTiedot Rakennusvalvonta) => truthy
        _ (:kuntakoodi toimituksenTiedot) => truthy
        rakennusvalvontaAsiatieto (:rakennusvalvontaAsiatieto Rakennusvalvonta) => truthy
        RakennusvalvontaAsia (:RakennusvalvontaAsia rakennusvalvontaAsiatieto) => truthy
        katselmustieto (:katselmustieto RakennusvalvontaAsia) => truthy
        Katselmus (:Katselmus katselmustieto) => truthy]
    (:rakennustunnus Katselmus) => nil
    (:katselmuksenRakennustieto Katselmus) => nil))


;Aloitusoikeus (Takuu) (tyonaloitus ennen kuin valitusaika loppunut luvan myontamisesta)

(def aloitusoikeus-hakemus
  (merge
    domain/application-skeleton
    {:linkPermitData [link-permit-data-kuntalupatunnus],
     :schema-version 1,
     :auth [{:lastName "Panaani",
             :firstName "Pena",
             :username "pena",
             :role "writer",
             :id "777777777777777777000020"}],
     :submitted 1388665814105,
     :state "submitted",
     :location [406390.19848633 6681812.5],
     :organization "753-R",
     :title "Vainuddintie 92",
     :primaryOperation {:id "52c5461042065cf9f379de8b",
                        :name "aloitusoikeus",
                        :created 1388660240013}
     :secondaryOperations [],
     :infoRequest false,
     :openInfoRequest false,
     :opened 1388665814105,
     :created 1388660240013,
     :propertyId "75341900080007",
     :documents [{:id "537df18fbc454ac7ac9036c7",
                  :created 1400762767119,
                  :schema-info {:approvable true,
                                :subtype "hakija",
                                :name "hakija-r",
                                :after-update "applicant-index-update",
                                :repeating true,
                                :version 1,
                                :type "party",
                                :order 3}
                  :data {:_selected {:value "henkilo"},
                         :henkilo {:henkilotiedot {:etunimi {:modified 1400762778665, :value "Pena"},
                                                   :hetu {:modified 1400762778665, :value "010203-040A"},
                                                   :sukunimi {:modified 1400762778665, :value "Panaani"}},
                                   :osoite {:katu {:modified 1400762778665, :value "Paapankuja 12"},
                                            :postinumero {:modified 1400762778665, :value "10203"},
                                            :postitoimipaikannimi
                                            {:modified 1400762778665, :value "Piippola"}},
                                   :userId {:modified 1400762778787, :value "777777777777777777000020"},
                                   :yhteystiedot {:email {:modified 1400762778665, :value "pena@example.com"},
                                                  :puhelin {:modified 1400762778665, :value "0102030405"}}}}}
                 {:id "537df18fbc454ac7ac9036c6",
                  :created 1400762767119,
                  :schema-info {:version 1,
                                :name "aloitusoikeus",
                                :approvable true,
                                :op {:id "537df18fbc454ac7ac9036c5",
                                     :name "aloitusoikeus",
                                     :created 1400762767119}}
                  :data {:kuvaus {:modified 1400762776200, :value "Tarttis aloitta asp rakentaminen."}}}
                 {:id "537df18fbc454ac7ac9036c8",
                  :created 1400762767119,
                  :schema-info {:approvable true,
                                :name "maksaja",
                                :repeating true,
                                :version 1,
                                :type "party",
                                :order 6}
                  :data {:henkilo {:henkilotiedot {:etunimi {:modified 1400762782277, :value "Pena"},
                                                   :hetu {:modified 1400762782277, :value "010203-040A"},
                                                   :sukunimi {:modified 1400762782277, :value "Panaani"}},
                                   :osoite {:katu {:modified 1400762782277, :value "Paapankuja 12"},
                                            :postinumero {:modified 1400762782277, :value "10203"},
                                            :postitoimipaikannimi
                                            {:modified 1400762782277, :value "Piippola"}},
                                   :userId {:modified 1400762782327, :value "777777777777777777000020"},
                                   :yhteystiedot {:email {:modified 1400762782277, :value "pena@example.com"},
                                                  :puhelin {:modified 1400762782277, :value "0102030405"}}},
                         :laskuviite {:modified 1400762796099, :value "1234567890"}}}]
     :_statements-seen-by {:777777777777777777000020 1388664440961},
     :modified 1388667087403,
     :address "Vainuddintie 92",
     :permitType "R",
     :id "LP-753-2014-00001",
     :municipality "753"}))

(ctc/validate-all-documents aloitusoikeus-hakemus)

(fl/facts* "Canonical model for aloitusoikeus is correct"
  (let [canonical (application-to-canonical aloitusoikeus-hakemus "sv") => truthy
        rakennusvalvonta (:Rakennusvalvonta canonical) => truthy
        rakennusvalvontaasiatieto (:rakennusvalvontaAsiatieto rakennusvalvonta) => truthy
        rakennusvalvontaasia (:RakennusvalvontaAsia rakennusvalvontaasiatieto) => truthy
        lupa-tunnus (get-in rakennusvalvontaasia [:luvanTunnisteTiedot :LupaTunnus]) => map?
        toimituksenTiedot (:toimituksenTiedot rakennusvalvonta) => truthy
        asianTiedot (:asianTiedot rakennusvalvontaasia) => truthy
        Asiantiedot (:Asiantiedot asianTiedot)
        lisatiedot (:lisatiedot rakennusvalvontaasia) => truthy
        Lisatiedot (:Lisatiedot lisatiedot) => truthy
        _ (:vakuus Lisatiedot) => nil?]

        (:aineistonnimi toimituksenTiedot ) => "Vainuddintie 92"
        (:rakennusvalvontaasianKuvaus Asiantiedot) => "Tarttis aloitta asp rakentaminen."

        (get-in lupa-tunnus [:muuTunnustieto :MuuTunnus]) => {:tunnus (:id aloitusoikeus-hakemus), :sovellus "Lupapiste"}

        (fact "maksaja laskuviite"
          (->> rakennusvalvontaasia :osapuolettieto :Osapuolet :osapuolitieto
               (map :Osapuoli)
               (util/find-by-key :VRKrooliKoodi "maksaja")
               :laskuviite) => "1234567890")

        (fact "SaapumisPvm = submitted date"
          (:saapumisPvm lupa-tunnus) => "2014-01-02")))

(fl/facts* "Canonical model for kaupunkikuvatoimenpide/aurinkopaneeli is correct"
  (let [canonical (application-to-canonical application-aurinkopaneeli "fi") => truthy
        rakennusvalvonta (:Rakennusvalvonta canonical) => truthy
        rakennusvalvontaasiatieto (:rakennusvalvontaAsiatieto rakennusvalvonta) => truthy
        rakennusvalvontaasia (:RakennusvalvontaAsia rakennusvalvontaasiatieto) => truthy]

    (fact "kayttotarkoitus"
      (-> rakennusvalvontaasia :toimenpidetieto first :Toimenpide :rakennelmatieto :Rakennelma :kayttotarkoitus)
        => "Aurinkopaneeli")

    (fact "kokonaisala"
      (-> rakennusvalvontaasia :toimenpidetieto first :Toimenpide :rakennelmatieto :Rakennelma :kiinttun)
        => "21111111111111")

    (fact "kokonaisala"
      (-> rakennusvalvontaasia :toimenpidetieto first :Toimenpide :rakennelmatieto :Rakennelma :kokonaisala)
        => "6")

    (fact "kuvaus"
      (-> rakennusvalvontaasia :toimenpidetieto first :Toimenpide :rakennelmatieto :Rakennelma :kuvaus :kuvaus)
        => "virtaa maailmaan")

    (fact "yksilointitieto"
      (-> rakennusvalvontaasia :toimenpidetieto first :Toimenpide :rakennelmatieto :Rakennelma :yksilointitieto)
        => "muu-rakentaminen-id")

    (fact "jarjestysnumero"
      (-> rakennusvalvontaasia :toimenpidetieto first :Toimenpide :rakennelmatieto :Rakennelma :tunnus :jarjestysnumero)
      => 1)))

(def huoneistot {:0 {:muutostapa "lis\u00e4ys"
                     :porras "A"
                     :huoneistonumero "1"
                     :jakokirjain "a"
                     :huoneistoTyyppi "asuinhuoneisto"
                     :huoneistoala "56"
                     :huoneluku "66"
                     :keittionTyyppi "keittio"
                     :parvekeTaiTerassiKytkin true
                     :WCKytkin true}
                 :1 {:muutostapa "muutos"
                     :porras "A"
                     :huoneistonumero "2"
                     :jakokirjain "a"
                     :huoneistoTyyppi "toimitila"
                     :huoneistoala "03"
                     :huoneluku "12"
                     :keittionTyyppi "keittokomero"
                     :ammeTaiSuihkuKytkin true
                     :saunaKytkin true
                     :lamminvesiKytkin true}
                 :2 {:porras "A"
                     :huoneistonumero "3"
                     :jakokirjain "a"
                     :huoneistoTyyppi "asuinhuoneisto"
                     :huoneistoala "38.5"
                     :huoneluku "12"
                     :keittionTyyppi "keittokomero"
                     :ammeTaiSuihkuKytkin true
                     :saunaKytkin true
                     :lamminvesiKytkin true}})

; Huoneisto lkm and pintaala in only calculated for huoneistot with muutostapa
; lis\u00e4ys (PATE-74). For the new buildings the only allowed muutostapa is
; lis\u00e4ys and its comes by default from schema.
; For old buildings, i.e renovation projects, muutostapa is muutos and schema
; rakennuksen-muuttaminen is used and those kind of huoneistot type arent taken count of.
(facts "Huoneistot info for new building"
  (let [huoneistot-data (get-huoneisto-data huoneistot "uusiRakennus")]

    (fact "huoneistot lkm"
      (get-huoneistot-lkm huoneistot-data ) => 3)

    (fact "huoneistot pintaala"
      (get-huoneistot-pintaala huoneistot-data) => 97.5)))

(facts "Huoneistot info for old building"
  (let [huoneistot-data (get-huoneisto-data huoneistot "rakennuksen-muuttaminen")]

    (fact "huoneistot lkm"
      (get-huoneistot-lkm huoneistot-data ) => 1)

    (fact "huoneistot pintaala"
      (get-huoneistot-pintaala huoneistot-data) => 56.0)))

(facts "Huoneistot info with not valid data"
  (get-huoneistot-pintaala (get-huoneisto-data (assoc-in huoneistot [:0 :huoneistoala] nil) "uusiRakennus")) => 41.5
  (get-huoneistot-pintaala (get-huoneisto-data (assoc-in huoneistot [:0 :huoneistoala] "foo") "uusiRakennus")) => 41.5
  (get-huoneistot-pintaala (get-huoneisto-data (assoc-in huoneistot [:0 :huoneistoala] "") "uusiRakennus")) => 41.5
  (get-huoneistot-pintaala (get-huoneisto-data (assoc-in huoneistot [:0 :huoneistoala] :bar) "uusiRakennus")) => 41.5
  (get-huoneistot-pintaala (get-huoneisto-data (assoc-in huoneistot [:0 :huoneistoala] "10,0") "uusiRakennus")) => 51.5)

(facts "huoneisto - required fields filled?"
  (let [schema (get-huoneistot-schema "uusiRakennus")
        fields (schema-body-required-fields schema)
        default-doc-data (-> (doc-schemas/get-schema 1 "uusiRakennus")
                             (tools/create-document-data tools/default-values)
                             :huoneistot
                             tools/unwrapped)]
    fields => (just [:huoneistonumero :huoneluku :keittionTyyppi :huoneistoala])
    (required-fields-have-value? schema {}) => false
    (required-fields-have-value? schema {:huoneistonumero "1"}) => false
    (fact "tools/default-values doesnt fill required data"  ; LPk-3844
      (required-fields-have-value? schema default-doc-data) => false)
    (fact "all test huoneistot are valid, as they have required fileld"
      (vals huoneistot) => (has every? (partial required-fields-have-value? schema)))))
