(ns lupapalvelu.pate.verdict-canonical
  (:require [sade.util :as util]
            [sade.strings :as ss]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.pate.shared :as pate-shared]))

(defn- vaadittu-katselmus-canonical [lang {{reviews :reviews} :references :as verdict} review-id]
  (let [review (util/find-by-id review-id reviews)]
    {:Katselmus {:katselmuksenLaji (pate-shared/review-type-map (or (keyword (:type review)) :ei-tiedossa))
                 :tarkastuksenTaiKatselmuksenNimi (get-in review [:name (keyword lang)])
                 :muuTunnustieto [#_{:MuuTunnus "yht:MuuTunnusType"}]}})) ; TODO: initialize review tasks and pass ids here

(defn- maarays-seq-canonical [{data :data :as verdict}]
  (some->> data :conditions vals
           (map :condition)
           (remove ss/blank?)
           (map #(assoc-in {} [:Maarays :sisalto] %))
           not-empty))

(defn- vaadittu-erityissuunnitelma-canonical [lang {{plans :plans} :references :as verdict} plan-id]
  (let [plan (util/find-by-id plan-id plans)]
    {:VaadittuErityissuunnitelma {:vaadittuErityissuunnitelma (get-in plan [:name (keyword lang)])
                                  :toteutumisPvm nil}}))

(def ^:private foreman-role-mapping {:vv-tj "KVV-ty\u00f6njohtaja"
                                     :iv-tj "IV-ty\u00f6njohtaja"
                                     :erityis-tj "erityisalojen ty\u00f6njohtaja"
                                     :vastaava-tj "vastaava ty\u00f6njohtaja"
                                     :tj "ty\u00f6njohtaja"
                                     nil "ei tiedossa"})

(defn- vaadittu-tyonjohtaja-canonical [foreman]
  {:VaadittuTyonjohtaja {:tyonjohtajaRooliKoodi (get foreman-role-mapping (keyword foreman) "ei tiedossa")}})

(defn- lupamaaraykset-type-canonical [lang {{buildings :buildings :as data} :data :as verdict}]
  {:autopaikkojaEnintaan nil
   :autopaikkojaVahintaan nil
   :autopaikkojaRakennettava (->> (map :autopaikat-yhteensa (vals buildings)) (map util/->int) (apply +))
   :autopaikkojaRakennettu (->> (map :rakennetut-autopaikat (vals buildings)) (map util/->int) (apply +))
   :autopaikkojaKiinteistolla (->> (map :kiinteiston-autopaikat (vals buildings)) (map util/->int) (apply +))
   :autopaikkojaUlkopuolella nil
   :kerrosala nil
   :kokonaisala nil
   :rakennusoikeudellinenKerrosala nil
   :vaaditutKatselmukset (map (partial vaadittu-katselmus-canonical lang verdict) (:reviews data))
   :maaraystieto (maarays-seq-canonical verdict)
   :vaadittuErityissuunnitelmatieto (map (partial vaadittu-erityissuunnitelma-canonical lang verdict) (:plans data))
   :vaadittuTyonjohtajatieto (map vaadittu-tyonjohtaja-canonical (:foremen data))})

(defn- paivamaarat-type-canonical [lang {data :data :as verdict}]
  {:aloitettavaPvm (util/to-xml-date-from-string (:aloitettava data))
   :lainvoimainenPvm (util/to-xml-date-from-string (:lainvoimainen data))
   :voimassaHetkiPvm (util/to-xml-date-from-string (:voimassa data))
   :raukeamisPvm nil
   :antoPvm (util/to-xml-date-from-string (:anto data))
   :viimeinenValitusPvm (util/to-xml-date-from-string (:valitus data))
   :julkipanoPvm (util/to-xml-date-from-string (:julkipano data))})

(defn- paatoksentekija [lang {{:keys [contact giver]} :data :as verdict}]
  (cond
    (ss/blank? giver) contact
    (ss/blank? contact) (i18n/localize lang "pate-verdict.giver" giver)
    :else (format "%s (%s)" contact (i18n/localize lang "pate-verdict.giver" giver))))

(defn- paatospoytakirja-type-canonical [lang {data :data :as verdict}]
  {:paatos (:verdict-text data)
   :paatoskoodi (pate-shared/verdict-code-map (or (keyword (:verdict-code data)) :ei-tiedossa))
   :paatoksentekija (paatoksentekija lang verdict)
   :paatospvm (util/to-xml-date-from-string (:verdict-date data))
   :pykala (:verdict-section data)
   :liite nil #_"yht:RakennusvalvontaLiiteType"}) ; TODO: add generated pdf attachment

(defn verdict-canonical [application lang verdict]
  {:Paatos {:lupamaaraykset (lupamaaraykset-type-canonical lang verdict)
            :paivamaarat (paivamaarat-type-canonical lang verdict)
            :poytakirja [(paatospoytakirja-type-canonical lang verdict)]}})
