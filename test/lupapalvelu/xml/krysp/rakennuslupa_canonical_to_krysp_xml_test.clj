(ns lupapalvelu.xml.krysp.rakennuslupa-canonical-to-krysp-xml-test
  (:require [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [clojure.data.xml :refer :all]
            [clojure.java.io :refer :all]
            [net.cgrand.enlive-html :as enlive]
            [sade.xml :as xml]
            [sade.strings :as ss]
            [sade.common-reader :as cr]
            [lupapalvelu.xml.krysp.application-as-krysp-to-backing-system :refer :all :as mapping-to-krysp]
            [lupapalvelu.document.rakennuslupa-canonical :refer [application-to-canonical katselmus-canonical]]
            [lupapalvelu.document.rakennuslupa-canonical-test :refer [application-rakennuslupa
                                                                      application-aurinkopaneeli
                                                                      application-tyonjohtajan-nimeaminen
                                                                      application-tyonjohtajan-nimeaminen-v2
                                                                      application-suunnittelijan-nimeaminen
                                                                      application-suunnittelijan-nimeaminen-muu
                                                                      jatkolupa-application
                                                                      aloitusoikeus-hakemus]]
            [lupapalvelu.xml.krysp.rakennuslupa-mapping :refer [rakennuslupa_to_krysp_212
                                                                rakennuslupa_to_krysp_213
                                                                rakennuslupa_to_krysp_214
                                                                rakennuslupa_to_krysp_215
                                                                rakennuslupa_to_krysp_216
                                                                rakennuslupa_to_krysp_218
                                                                rakennuslupa_to_krysp_220
                                                                rakennuslupa_to_krysp_222
                                                                rakennuslupa-element-to-xml]]
            [lupapalvelu.xml.validator :refer [validate]]
            [lupapalvelu.xml.krysp.canonical-to-krysp-xml-test-common :refer [has-tag]]
            [lupapalvelu.xml.validator :refer :all :as validator]
            [lupapalvelu.xml.emit :refer :all]))

(fact "2.1.2: :tag is set" (has-tag rakennuslupa_to_krysp_212) => true)
(fact "2.1.3: :tag is set" (has-tag rakennuslupa_to_krysp_213) => true)
(fact "2.1.4: :tag is set" (has-tag rakennuslupa_to_krysp_214) => true)
(fact "2.1.5: :tag is set" (has-tag rakennuslupa_to_krysp_215) => true)
(fact "2.1.6: :tag is set" (has-tag rakennuslupa_to_krysp_216) => true)
(fact "2.1.8: :tag is set" (has-tag rakennuslupa_to_krysp_218) => true)
(fact "2.2.0: :tag is set" (has-tag rakennuslupa_to_krysp_220) => true)
(fact "2.2.2: :tag is set" (has-tag rakennuslupa_to_krysp_222) => true)

(defn- do-test [application & {:keys [validate-tyonjohtaja-type validate-pysyva-tunnus? finnish? validate-operations?]
                               :or {validate-tyonjohtaja-type nil validate-pysyva-tunnus? false finnish? false validate-operations? false}}]
  (facts "Rakennusvalvonta KRYSP checks"
    (let [canonical (application-to-canonical application "fi")
          xml_212 (rakennuslupa-element-to-xml canonical "2.1.2")
          xml_213 (rakennuslupa-element-to-xml canonical "2.1.3")
          xml_214 (rakennuslupa-element-to-xml canonical "2.1.4")
          xml_215 (rakennuslupa-element-to-xml canonical "2.1.5")
          xml_216 (rakennuslupa-element-to-xml canonical "2.1.6")
          xml_218 (rakennuslupa-element-to-xml canonical "2.1.8")
          xml_220 (rakennuslupa-element-to-xml canonical "2.2.0")
          xml_222 (rakennuslupa-element-to-xml canonical "2.2.2")
          xml_212_s (indent-str xml_212)
          xml_213_s (indent-str xml_213)
          xml_214_s (indent-str xml_214)
          xml_215_s (indent-str xml_215)
          xml_216_s (indent-str xml_216)
          xml_218_s (indent-str xml_218)
          xml_220_s (indent-str xml_220)
          xml_222_s (indent-str xml_222)]

      (fact "2.1.2: xml exist" xml_212 => truthy)
      (fact "2.1.3: xml exist" xml_213 => truthy)
      (fact "2.1.4: xml exist" xml_214 => truthy)
      (fact "2.1.5: xml exist" xml_215 => truthy)
      (fact "2.1.6: xml exist" xml_216 => truthy)
      (fact "2.1.8: xml exist" xml_218 => truthy)
      (fact "2.2.0: xml exist" xml_220 => truthy)
      (fact "2.2.2: xml exist" xml_222 => truthy)

      (let [lp-xml_212 (cr/strip-xml-namespaces (xml/parse xml_212_s))
            lp-xml_213 (cr/strip-xml-namespaces (xml/parse xml_213_s))
            lp-xml_216 (cr/strip-xml-namespaces (xml/parse xml_216_s))
            lp-xml_218 (cr/strip-xml-namespaces (xml/parse xml_218_s))
            lp-xml_220 (cr/strip-xml-namespaces (xml/parse xml_220_s))
            lp-xml_222 (cr/strip-xml-namespaces (xml/parse xml_222_s))
            tyonjohtaja_212 (xml/select1 lp-xml_212 [:osapuolettieto :Tyonjohtaja])
            tyonjohtaja_213 (xml/select1 lp-xml_213 [:osapuolettieto :Tyonjohtaja])
            tyonjohtaja_216 (xml/select1 lp-xml_216 [:osapuolettieto :Tyonjohtaja])]

        (when validate-operations?
          (fact "Toimenpiteet"

            (let [app-ops (cons (:primaryOperation application) (:secondaryOperations application))
                  ; maisematyot sisaltyvat hankkeen kuvaukseen
                  operations (remove #(= "puun-kaataminen" (:name %)) app-ops)
                  expected (count operations)
                  xml-operations (xml/select lp-xml_220 [:toimenpidetieto :Toimenpide])]

              (fact "Count" (count xml-operations) => expected)

              (fact "Got buildings"
                (count (xml/select lp-xml_220 [:toimenpidetieto :Toimenpide :Rakennus])) => pos?)

              (doseq [op xml-operations
                      :let [op-tag (-> op :content first :tag)
                            rakennus (xml/select1 op [:Rakennus])]
                      :when rakennus]

                (when (= :uusi op-tag)
                  (fact "Operation description for a new building"
                    (xml/get-text rakennus [:rakennustunnus :rakennuksenSelite]) => "A: kerrostalo-rivitalo-kuvaus"))

                (fact {:midje/description (str "Building ID includes operation ID of " (name op-tag))}
                  (let [ids (xml/select rakennus [:rakennustunnus :MuuTunnus])
                        toimenpide-id (xml/select ids (enlive/has [:sovellus (enlive/text-pred (partial = "toimenpideId"))]))
                        lupapiste-id  (xml/select ids (enlive/has [:sovellus (enlive/text-pred (partial = "Lupapiste"))]))]
                    (fact "with sovellus=toimenpideId"
                      (xml/get-text toimenpide-id [:MuuTunnus :tunnus]) =not=> ss/blank?)

                    (fact "with sovellus=Lupapiste"
                      (xml/get-text lupapiste-id [:MuuTunnus :tunnus]) =not=> ss/blank?)))))))

        (fact "hakija, maksaja and hakijan asiamies parties exist"
          (let [osapuoli-codes (->> (xml/select lp-xml_220 [:osapuolettieto :Osapuoli])
                                 (map (comp :kuntaRooliKoodi cr/all-of)))]
            (->> osapuoli-codes (filter #(= % "Rakennusvalvonta-asian hakija")) count) => pos?
            (if (= (:id application) (:id application-rakennuslupa))
              (->> osapuoli-codes (filter #(= % "Hakijan asiamies")) count) => 1)
            (->> osapuoli-codes (filter #(= % "Rakennusvalvonta-asian laskun maksaja")) count) => pos?))

        (fact "saapumisPvm"
          (let [expected (sade.util/to-xml-date (:submitted application))]
            (xml/get-text lp-xml_212 [:luvanTunnisteTiedot :LupaTunnus :saapumisPvm]) => expected
            (xml/get-text lp-xml_213 [:luvanTunnisteTiedot :LupaTunnus :saapumisPvm]) => expected))

        (if validate-tyonjohtaja-type
          (do
            (fact "In KRYSP 2.1.2, patevyysvaatimusluokka/vaadittuPatevyysluokka A are mapped to 'ei tiedossa'"
              (xml/get-text tyonjohtaja_212 :vaadittuPatevyysluokka) => "ei tiedossa"
              (xml/get-text tyonjohtaja_212 :patevyysvaatimusluokka) => "ei tiedossa")

            (fact "In KRYSP 2.1.3, patevyysvaatimusluokka/vaadittuPatevyysluokka A is not mapped"
              (xml/get-text tyonjohtaja_213 :patevyysvaatimusluokka) => "A"
              (xml/get-text tyonjohtaja_213 :vaadittuPatevyysluokka) => "A")

            (when (= :v1 validate-tyonjohtaja-type)

              (fact "FISEpatevyyskortti" (->> (xml/select lp-xml_220 [:osapuolettieto :Suunnittelija])
                                           (map cr/all-of)
                                           (every? #(and (-> % :FISEpatevyyskortti string?) (-> % :FISEkelpoisuus string?)))) => true))

            (when (= :v2 validate-tyonjohtaja-type)
              (fact "In KRYSP 2.1.6, :vainTamaHankeKytkin was added (Yhteiset schema was updated to 2.1.5 and tyonjohtaja along with it)"
                (xml/get-text tyonjohtaja_216 :vainTamaHankeKytkin) => "true")))
          (do
            tyonjohtaja_212 => nil
            tyonjohtaja_213 => nil))

        (if validate-pysyva-tunnus?
          (fact "pysyva rakennusnumero" (xml/get-text lp-xml_212 [:rakennustunnus :valtakunnallinenNumero]) => "1234567892")))

      (when (= :v1 validate-tyonjohtaja-type)
        (fact "Rakennusvalvonta KRYSP 2.1.5"
          (let [lp-xml_215 (cr/strip-xml-namespaces (xml/parse xml_215_s))]
            ; Address format has changed in 2.1.5
            (xml/get-text lp-xml_215 [:omistajatieto :Omistaja :yritys :postiosoitetieto :postiosoite :osoitenimi :teksti]) => "katu"
            (xml/get-text lp-xml_215 [:omistajatieto :Omistaja :yritys :postiosoitetieto :postiosoite :postitoimipaikannimi]) => "Tuonela"

            ; E-Invoicing fields added in 2.1.5
            (xml/get-text lp-xml_215 [:osapuolitieto :Osapuoli :yritys :verkkolaskutustieto :Verkkolaskutus :ovtTunnus]) => "003712345671"
            (xml/get-text lp-xml_215 [:osapuolitieto :Osapuoli :yritys :verkkolaskutustieto :Verkkolaskutus :verkkolaskuTunnus]) => "laskutunnus-1234"
            (xml/get-text lp-xml_215 [:osapuolitieto :Osapuoli :yritys :verkkolaskutustieto :Verkkolaskutus :valittajaTunnus]) => "BAWCFI22")))

      (fact "Country information"
        (let [lp-xml_220 (cr/strip-xml-namespaces (xml/parse xml_220_s))
              parties (xml/select lp-xml_220 [:osapuolitieto :Osapuoli])
              applicant (some #(and (= "hakija" (xml/get-text % [:VRKrooliKoodi])) %) parties)]
          ;; Foreign addresses were already in 2.1.5 mapping, but implemented in 2.2.0
          (when (= :v1 validate-tyonjohtaja-type)
            (xml/get-text lp-xml_220 [:omistajatieto :Omistaja  :yritys :postiosoitetieto :postiosoite :valtioSuomeksi]) => "Kiina"
            (xml/get-text lp-xml_220 [:omistajatieto :Omistaja  :yritys :postiosoitetieto :postiosoite :valtioKansainvalinen]) => "CHN")

          (if finnish?
            (do (xml/get-text applicant [:valtioSuomeksi]) => "Suomi"
              (xml/get-text applicant [:valtioKansainvalinen]) => "FIN")
            (do (xml/get-text applicant [:valtioSuomeksi]) => "Kiina"
              (xml/get-text applicant [:valtioKansainvalinen]) => "CHN"
              (xml/get-text applicant [:ulkomainenPostitoimipaikka]) => "Tuonela"
              (xml/get-text applicant [:ulkomainenLahiosoite]) => "katu"))))

      (validator/validate xml_212_s (:permitType application) "2.1.2")
      (validator/validate xml_213_s (:permitType application) "2.1.3")
      (validator/validate xml_214_s (:permitType application) "2.1.4")
      (validator/validate xml_215_s (:permitType application) "2.1.5")
      (validator/validate xml_216_s (:permitType application) "2.1.6")
      (validator/validate xml_218_s (:permitType application) "2.1.8")
      (validator/validate xml_220_s (:permitType application) "2.2.0")
      (validator/validate xml_222_s (:permitType application) "2.2.2")
      )))

(facts "Rakennusvalvonta type of permits to canonical and then to xml with schema validation"
  (fact "Rakennuslupa application -> canonical -> xml"
    (do-test application-rakennuslupa :validate-tyonjohtaja-type :v1 :validate-pysyva-tunnus? true :validate-operations? true))

  (fact "Ty\u00f6njohtaja application -> canonical -> xml"
    (do-test application-tyonjohtajan-nimeaminen-v2 :validate-tyonjohtaja-type :v2))

  (fact "Suunnittelija application -> canonical -> xml"
    (do-test application-suunnittelijan-nimeaminen))

  (fact "Aloitusoikeus -> canonical -> xml"
    (do-test aloitusoikeus-hakemus :finnish? true)))


(facts "Rakennusvalvonta tests"
  (let [canonical (application-to-canonical application-rakennuslupa "fi")
        xml_220 (rakennuslupa-element-to-xml canonical "2.2.0")
        xml_222 (rakennuslupa-element-to-xml canonical "2.2.2")
        xml_220_s (indent-str xml_220)
        xml_222_s (indent-str xml_222)
        lp-xml_220 (cr/strip-xml-namespaces (xml/parse xml_220_s))
        lp-xml_222 (cr/strip-xml-namespaces (xml/parse xml_222_s))]

    (facts "2.2.0"
      (fact "avainsanatieto"
        (xml/select lp-xml_220 [:avainsanaTieto :Avainsana]) => [])

      (fact "menettelyTOS"
        (xml/get-text lp-xml_220 [:menettelyTOS]) => nil))

    (facts "2.2.2"
      (fact "avainsanatieto"
        (->> (xml/select lp-xml_222 [:avainsanaTieto :Avainsana])
             (map :content)) => [["avainsana"]
                                 ["toinen avainsana"]])
      (fact "menettelyTOS"
        (xml/get-text lp-xml_222 [:menettelyTOS]) => "tos menettely"))))

(facts "Rakennelma"
  (let [canonical (application-to-canonical application-aurinkopaneeli "fi")
        xml_218 (rakennuslupa-element-to-xml canonical "2.1.8")
        xml_220 (rakennuslupa-element-to-xml canonical "2.2.0")
        xml_222 (rakennuslupa-element-to-xml canonical "2.2.2")
        xml_218_s (indent-str xml_218)
        xml_220_s (indent-str xml_220)
        xml_222_s (indent-str xml_222)]

    (validator/validate xml_218_s :R "2.1.8")
    (validator/validate xml_220_s :R "2.2.0")
    (validator/validate xml_222_s :R "2.2.2")

    (facts "2.1.8"
      (let [lp-xml (cr/strip-xml-namespaces (xml/parse xml_218_s))
            toimenpide (xml/select1 lp-xml [:Toimenpide])
            rakennelma (xml/select1 toimenpide [:Rakennelma])]

        (fact "yksilointitieto" (xml/get-text toimenpide [:yksilointitieto]) => "muu-rakentaminen-id")
        (fact "kuvaus"          (xml/get-text rakennelma [:kuvaus :kuvaus])  => "virtaa maailmaan")
        (fact "kayttotarkoitus" (xml/get-text rakennelma [:kayttotarkoitus]) => nil)))

    (facts "2.2.0"
      (let [lp-xml (cr/strip-xml-namespaces (xml/parse xml_220_s))
            toimenpide (xml/select1 lp-xml [:Toimenpide])
            rakennelma (xml/select1 toimenpide [:Rakennelma])]

        (fact "yksilointitieto" (xml/get-text toimenpide [:yksilointitieto]) => "muu-rakentaminen-id")
        (fact "kuvaus"          (xml/get-text rakennelma [:kuvaus :kuvaus])  => "virtaa maailmaan")
        (fact "kayttotarkoitus" (xml/get-text rakennelma [:kayttotarkoitus]) => "Muu rakennelma")))

    (facts "2.2.2"
      (let [lp-xml (cr/strip-xml-namespaces (xml/parse xml_222_s))
            toimenpide (xml/select1 lp-xml [:Toimenpide])
            rakennelma (xml/select1 toimenpide [:Rakennelma])]

        (fact "yksilointitieto" (xml/get-text toimenpide [:yksilointitieto]) => "muu-rakentaminen-id")
        (fact "kuvaus"          (xml/get-text rakennelma [:kuvaus :kuvaus])  => "virtaa maailmaan")
        (fact "kayttotarkoitus" (xml/get-text rakennelma [:kayttotarkoitus]) => "Aurinkopaneeli")))))

(facts "Tyonjohtajan sijaistus"
  (let [canonical (application-to-canonical application-tyonjohtajan-nimeaminen-v2 "fi")
        xml_213 (rakennuslupa-element-to-xml canonical "2.1.3")
        xml_215 (rakennuslupa-element-to-xml canonical "2.1.5")
        xml_213_s (indent-str xml_213)
        xml_215_s (indent-str xml_215)]

    (validator/validate xml_213_s (:permitType application-tyonjohtajan-nimeaminen-v2) "2.1.3")
    (validator/validate xml_215_s (:permitType application-tyonjohtajan-nimeaminen-v2) "2.1.5")

    (facts "2.1.3"
      (let [lp-xml (cr/strip-xml-namespaces (xml/parse xml_213_s))
            tyonjohtaja (xml/select1 lp-xml [:Tyonjohtaja])
            sijaistus (xml/select1 lp-xml [:Sijaistus])]
        (fact "sijaistettavan nimi" (xml/get-text sijaistus [:sijaistettavaHlo]) => "Jaska Jokunen")
        (fact "sijaistettava rooli" (xml/get-text sijaistus [:sijaistettavaRooli]) => (xml/get-text lp-xml [:tyonjohtajaRooliKoodi]))
        (fact "sijaistettavan alkamisPvm" (xml/get-text sijaistus [:alkamisPvm]) => "2014-02-13")
        (fact "sijaistettavan paattymisPvm" (xml/get-text sijaistus [:paattymisPvm]) => "2014-02-20")
        (fact "postiosoite"
          (xml/get-text tyonjohtaja [:yritys :postiosoite :osoitenimi :teksti]) => "katu")))

    (facts "2.1.5"
      (let [lp-xml (cr/strip-xml-namespaces (xml/parse xml_215_s))
            tyonjohtaja (xml/select1 lp-xml [:Tyonjohtaja])
            vastuu (xml/select tyonjohtaja [:vastattavaTyotieto])]
        (fact "rooli" (xml/get-text tyonjohtaja [:tyonjohtajaRooliKoodi]) => "KVV-ty\u00f6njohtaja")
        (fact "sijaistettavan nimi" (xml/get-text tyonjohtaja [:sijaistettavaHlo]) => "Jaska Jokunen")
        (fact "2 vastuuta" (count vastuu) => 2)
        (fact "vastuun alkamisPvm" (xml/get-text tyonjohtaja [:alkamisPvm]) => "2014-02-13")
        (fact "vastuun paattymisPvm" (xml/get-text tyonjohtaja [:paattymisPvm]) => "2014-02-20")
        (fact "vastattavaTyo"
          (map #(xml/get-text % [:vastattavaTyo]) vastuu) => (just #{"Sis\u00e4puolinen KVV-ty\u00f6"
                                                                     "Muu tyotehtava"}))
        (fact "postiosoite"
          (xml/get-text tyonjohtaja [:yritys :postiosoitetieto :postiosoite :osoitenimi :teksti]) => "katu")))))


(def suunnittelijan-nimaminen-canonical (application-to-canonical application-suunnittelijan-nimeaminen "fi"))

(facts "Suunnittelija"

  (facts "2.1.2"
    (let [xml_s (-> suunnittelijan-nimaminen-canonical (rakennuslupa-element-to-xml "2.1.2") indent-str)
          suunnittelija (-> xml_s xml/parse cr/strip-xml-namespaces (xml/select1 [:Suunnittelija]))]

      (validator/validate xml_s (:permitType application-tyonjohtajan-nimeaminen) "2.1.2")

      (fact "suunnittelijaRoolikoodi" (xml/get-text suunnittelija [:suunnittelijaRoolikoodi]) => "ei tiedossa")
      (fact "VRKrooliKoodi"           (xml/get-text suunnittelija [:VRKrooliKoodi])           => "erityissuunnittelija")
      (fact "postitetaanKytkin"       (xml/get-text suunnittelija [:postitetaanKytkin])       => nil)
      (fact "patevyysvaatimusluokka"  (xml/get-text suunnittelija [:patevyysvaatimusluokka])  => "B")
      (fact "koulutus"                (xml/get-text suunnittelija [:koulutus])                => "arkkitehti")
      (fact "valmistumisvuosi"        (xml/get-text suunnittelija [:valmistumisvuosi])        => "2010")
      (fact "vaadittuPatevyysluokka"  (xml/get-text suunnittelija [:vaadittuPatevyysluokka])  => "C")
      (fact "yritys - nimi"           (xml/get-text suunnittelija [:yritys :nimi])            => "Solita Oy")))

  (facts "2.2.0"
    (let [xml_s (-> suunnittelijan-nimaminen-canonical (rakennuslupa-element-to-xml "2.2.0") indent-str)
          suunnittelija (-> xml_s xml/parse cr/strip-xml-namespaces (xml/select1 [:Suunnittelija]))]

      (validator/validate xml_s (:permitType application-tyonjohtajan-nimeaminen) "2.2.0")

      (fact "suunnittelijaRoolikoodi" (xml/get-text suunnittelija [:suunnittelijaRoolikoodi]) => "rakennusfysikaalinen suunnittelija")
      (fact "VRKrooliKoodi"           (xml/get-text suunnittelija [:VRKrooliKoodi])           => "erityissuunnittelija")
      (fact "postitetaanKytkin"       (xml/get-text suunnittelija [:postitetaanKytkin])       => nil)
      (fact "patevyysvaatimusluokka"  (xml/get-text suunnittelija [:patevyysvaatimusluokka])  => "B")
      (fact "koulutus"                (xml/get-text suunnittelija [:koulutus])                => "arkkitehti")
      (fact "valmistumisvuosi"        (xml/get-text suunnittelija [:valmistumisvuosi])        => "2010")
      (fact "vaadittuPatevyysluokka"  (xml/get-text suunnittelija [:vaadittuPatevyysluokka])  => "C")
      (fact "kokemusvuodet"           (xml/get-text suunnittelija [:kokemusvuodet])           => "5")
      (fact "FISEpatevyyskortti"      (xml/get-text suunnittelija [:FISEpatevyyskortti])      => "http://www.ym.fi")
      (fact "FISEkelpoisuus"          (xml/get-text suunnittelija [:FISEkelpoisuus])          => "tavanomainen p\u00e4\u00e4suunnittelu (uudisrakentaminen)")
      (fact "yritys - nimi"           (xml/get-text suunnittelija [:yritys :nimi])            => "Solita Oy"))))

(def suunnittelijan-nimaminen-muu-canonical (application-to-canonical application-suunnittelijan-nimeaminen-muu "fi"))

(facts "Muu suunnittelija"

  (facts "2.1.2"
    (let [xml_s (-> suunnittelijan-nimaminen-muu-canonical (rakennuslupa-element-to-xml "2.1.2") indent-str)
          suunnittelija (-> xml_s xml/parse cr/strip-xml-namespaces (xml/select1 [:Suunnittelija]))]

      (validator/validate xml_s (:permitType application-tyonjohtajan-nimeaminen) "2.1.2")

      (fact "suunnittelijaRoolikoodi" (xml/get-text suunnittelija [:suunnittelijaRoolikoodi]) => "ei tiedossa")
      (fact "muuSuunnittelijaRooli"   (xml/get-text suunnittelija [:muuSuunnittelijaRooli])   => nil)
      (fact "VRKrooliKoodi"           (xml/get-text suunnittelija [:VRKrooliKoodi])           => "erityissuunnittelija")))

  (facts "2.2.0"
    (let [xml_s (-> suunnittelijan-nimaminen-muu-canonical (rakennuslupa-element-to-xml "2.2.0") indent-str)
          suunnittelija (-> xml_s xml/parse cr/strip-xml-namespaces (xml/select1 [:Suunnittelija]))]

      (validator/validate xml_s (:permitType application-tyonjohtajan-nimeaminen) "2.2.0")

      (fact "suunnittelijaRoolikoodi" (xml/get-text suunnittelija [:suunnittelijaRoolikoodi]) => "ei tiedossa")
      (fact "muuSuunnittelijaRooli"   (xml/get-text suunnittelija [:muuSuunnittelijaRooli])   => nil)
      (fact "VRKrooliKoodi"           (xml/get-text suunnittelija [:VRKrooliKoodi])           => "erityissuunnittelija")))

  (facts "2.2.2"
    (let [xml_s (-> suunnittelijan-nimaminen-muu-canonical (rakennuslupa-element-to-xml "2.2.2") indent-str)
          suunnittelija (-> xml_s xml/parse cr/strip-xml-namespaces (xml/select1 [:Suunnittelija]))]

      (validator/validate xml_s (:permitType application-tyonjohtajan-nimeaminen) "2.2.2")

      (fact "suunnittelijaRoolikoodi" (xml/get-text suunnittelija [:suunnittelijaRoolikoodi]) => "muu")
      (fact "muuSuunnittelijaRooli"   (xml/get-text suunnittelija [:muuSuunnittelijaRooli])   => "ei listassa -rooli")
      (fact "VRKrooliKoodi"           (xml/get-text suunnittelija [:VRKrooliKoodi])           => "erityissuunnittelija"))))
