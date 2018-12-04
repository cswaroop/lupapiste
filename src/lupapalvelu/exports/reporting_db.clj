(ns lupapalvelu.exports.reporting-db
  (:require [lupapalvelu.data-skeleton :as ds]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.document.rakennuslupa-canonical :refer [application-to-canonical]]))

;; Ei suomeksi, noudattaa omaa tietomallia

;;

(def reporting-app-skeleton
  {;; Hakemus
   :id (ds/access :id)
   ;; Factassa asian pääavain, tulee itse asiassa päätöksien tietoihin
   ;; :lupanumero (ds/access :test) ;; TODO: Mistä? Kuntalupatunnus? Selite: Löytyy koko numero samasata kentästä: Luvan numero - Luvan vuosi - kaupunginosa - lupatyyppi
   ;; :luvanNumero (ds/access :test) ;; Oletus: lupanumerosta
   ;; :luvanVuosi (ds/access :test) ;; Oletus: lupanumerosta
   :araFunding (ds/access :araFunding) ;; TODO: Mistä? Ara-käsittelijä flägi boolean
   ;; :eiJulkistaLainaa (ds/access :test) ;; TODO: Mistä? Selite: Hankkeeseen liittyvä
   ;; :kaupunginosa (ds/access :test) ;; TODO: Mistä?
   ;; :korkotukivuokra-asunnotPitkaaik (ds/access :test) ;; TODO: Mistä? Selite: Johdettuna, ei tarpeellista jatkossa
   :state (ds/access :state) ;; Selite: Tilakone. Vierillä, käyttöönotettu... OMAT TERMIT esim submitted
   :permitType (ds/access :permitType) ;; Selite: Voidaan päätellä toimenpidetiedosta permitType

   :location-etrs-tm35fin (ds/access :location)
   :location-wgs84 (ds/access :location-wgs84)
   :address (ds/access :address)
   :tilanteenPvm (ds/access :test) ;; Selite: Koska hakemuksen viimeisin tila on tullut voimaan Historysta etsitään
   :toimenpideteksti (ds/access :test) ;; Selite: Hankkeen kuvaus korvaamaan tätä. Konversiossa huomioitava, rakennusvalvonta-asian kuvaus
   :uuttaHuoneistoalaa (ds/access :test) ;; Selite: Mahdollisuus myös negatiiviseen arvoon. Oleellinen tieto. Tulee rakennuksen tietona ja summataan hakemukselle
   :uuttaKerrosalaa (ds/access :test) ;; Selite: Mahdollisuus myös negatiiviseen arvoon. Oleellinen tieto. Kerrosalassa mukana yleiset tilat. Tulee rakennuksen tietona ja summataan hakemukselle

   ;; Huoneisto
   :huoneidenLkm (ds/access :test) ;; Selite: Lupapisteessä huoneisto-käsite on huoneistomuutos
   :huoneistonLisaysMuutosPoistokoodi (ds/access :test)

   ;; Katselmus
   :katselmuksenLaji (ds/access :test) ;; Selite: Liittyy toimenpiteeseen
   :katselmuksenPitopvm (ds/access :test)
   :katselmuksenPitaja (ds/access :test)
   :verottajanTiedoksiantovelvollisuudenLaiminlyonti (ds/access :test)

   ;; Kiinteistö
   :autopaikkavaatimusEnintaan (ds/access :test)
   :autopaikkavaatimusRakennettava (ds/access :test)
   :autopaikkavaatimusRakennettavaVahintaan (ds/access :test)
   :autopaikkojaKiinteistolla (ds/access :test)
   :autopaikkojaKiinteistonUlkopuolella (ds/access :test)
   :autopaikkojaRakennettavaYhteensa (ds/access :test)
   :autopaikkojaRakennettu (ds/access :test)
   :autopaikkojaRakennettuYhteensa (ds/access :test)
   :autopaikkojaVahintaan (ds/access :test)
   :kiinteistotunnus (ds/access :test) ;; Mukana mahdollinen hallintayksikkötunnus

   ;; Lasku -> taulukko?
   :laskurivinTaksankohdanSelite (ds/access :test) ;; Selite: Kehityksen alla oleva toiminnallisuus
   :laskunPvm (ds/access :test)
   :laskunSummaYhteensa (ds/access :test)
   :laskunViiteasiatunnus (ds/access :test)
   :laskutusvuosi (ds/access :test)
   :rivisumma (ds/access :test)

   ;; Lausunto -> taulukko?
   ;; TODO: Lausunnon sisältö?
   :lausunnonAntajanNimi (ds/access :test)
   :lausunnonAntopvm (ds/access :test)

   ;; Osapuoli
   :ammatinNimi (ds/access :test)
   :hakijanLahiosoite (ds/access :test)
   :hakijanNimi (ds/access :test)
   :hakijanPostiosoite (ds/access :test)
   :suunnittelijanEmail (ds/access :test)
   :suunnittelijanLaji (ds/access :test)
   :suunnittelijanLahiJaPostiosoite (ds/access :test)
   :suunnittelijanNimi (ds/access :test)
   :suunnittelijanPuhelinnumero (ds/access :test)
   :suunnittelijarooli (ds/access :test)

   ;; Päätös
   :lupaehdonSisaltoteksti (ds/access :test)
   :lisaselvitys (ds/access :test) ;; Selite: Tulee uusille päätöksille rakenteellisena.
   :lupaVoimassaSaakkaPvm (ds/access :test)
   :lupahakemuksenSaapumispvm (ds/access :test)
   :lupatunnus (ds/access :test)
   :luvanAntopvm (ds/access :test)
   :luvanKerrosala (ds/access :test)
   :luvanLainvoimaisuuspvm (ds/access :test)
   :luvanPaatospaiva (ds/access :test)
   :luvanUusiKerrosala (ds/access :test)
   :luvanUusiKokonaisala (ds/access :test)
   :luvanUusiTilavuus (ds/access :test)
   :poikkeukset (ds/access :test) ;; Selite: Kirjattava tieto. Tulee rakenteellisena.
   :paatoksenTekija (ds/access :test)
   :paatoksenTulos (ds/access :test)
   :paatospvm (ds/access :test)
   :valmistelijanNimi (ds/access :test)
   :viimeisinPaatospvm (ds/access :test) ;; Selite: Luvalla voi olla monta päätöstä, esim. oikaisuvaatimuspäätös

   ;; Rakennus
   ;; Raportoidaan se mitä sattuu löytymään
   :hankkeenRakennuksenHuoneistonAla (ds/access :test)
   :hankkeenRakennuksenKerrosala (ds/access :test)
   :hankkeenRakennuksenKokonaisala (ds/access :test)
   :hankkeenRakennuksenKayttotarkoitus (ds/access :test)
   :hankkeenRakennuksenLaajennuksenKerrosala (ds/access :test)
   :hankkeenRakennuksenLammitystapa (ds/access :test)
   :hankkeenRakennuksenPolttoaine (ds/access :test)
   :hankkeenRakennuksenRaukeamispvm (ds/access :test)
   :hankkeenRakennuksenTilavuus (ds/access :test)
   :hankkeenRakennuksenToimenpidenro (ds/access :test)
   :hankkeenRakennuksenToidenAloituspvm (ds/access :test)
   :hankkeenRakennuksenValmistumispvm (ds/access :test)

   :rakennuksenKerrosala (ds/access :test)
   :rakennuksenKoordinaatit (ds/access :test) ;; (KKJ ja GK) Selite: Löytyy joko WGS84 tai ETRS-formaatissa
   :rakennuksenKayttotarkoitus (ds/access :test)
   :rakennuksenPaaasiallinenHallinta (ds/access :test)
   :rakennuksenRakennelmanSelite (ds/access :test)
   :rakennusnumero (ds/access :test) ;; rakennusnumero(n viimeinen osa): Factassa kc_raknro eli vanhanmallinen
   :rakennustunnus (ds/access :test) ;; VTJ-PRT
   :uusiaAsuntoja (ds/access :test)
   :uusienAsuntojenHuoneistoalaLuvalla (ds/access :test)
   :uusienAsuntojenLkm (ds/access :test) ;; Selite: Per toimenpide
   :uusienAsuntojenLkmLuvalla (ds/access :test) ;; Selite: Summattuna rakennusten lukumäärä per hakemus

   ;; Rakennuspaikka
   :rakennettu (ds/access :test) ; rakennuspaikalla valmiiden rakennusten kerrosala yhteensä. Selite: Ennen Lupapisteen tuloa olevat mahdottomia, tulevaisuudessa Matti?
   :sallittu (ds/access :test) ; rakennuspaikan sallittu kerrosala kaavan mukaan. Selite: Tulossa rajapintaan, otetaan jatkossa mukaan. Ainakin uusille luville.

   ;; Rakentamistoimenpide
   :hankkeenRakennuksenMuutostyonLaji (ds/access :test)

   ;; Taksa
   :taksanSelite (ds/access :test)
   :taksanVoimaantulopvm (ds/access :test) ;; Selite: Liittyy laskuun
   :taksankohta (ds/access :test)

   ;; Toimenpide
   :rakentamistoimenpiteenLaji (ds/access :test) ;; (rakennuksella) Selite: Yhdellä hakemuksella voi olla useampi toimenpide

   ;; Työnjohtajahakemus
   :työnjohtajanLaji (ds/access :test) ;; Työnjohtajasta oma hakemus viitelupana
   :työnjohtajanEmail (ds/access :test)

   ;; Muuta
   :aluejaonNimi (ds/access :test) ;; aluejaon (suuralueen) nimi
   ;; TODO Kaikkia kenttiä ei vielä listattu
   })

(def reporting-app-accessors
  {:test (constantly "foo")
   :id (ds/from-context [:application :id])
   :address (ds/from-context [:application :address])
   :araFunding (ds/from-context [:application #(domain/get-document-by-name % "hankkeen-kuvaus")
                                 :data tools/unwrapped :rahoitus]
                                false)
   :location (ds/from-context [:application :location])
   :location-wgs84 (ds/from-context [:application :location-wgs84])
   :state (ds/from-context [:application :state])
   :permitType (ds/from-context [:application :permitType])})

(defn ->reporting-result [application lang]
  ;; TODO check permit type, R or P (or others as well?)
  (let [application-canonical (application-to-canonical application lang)]
    (ds/build-with-skeleton reporting-app-skeleton
                            {:application application
                             :canonical application-canonical}
                            reporting-app-accessors)))
