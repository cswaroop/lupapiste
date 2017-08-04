(ns lupapalvelu.exports-api
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn error fatal]]
            [monger.operators :refer :all]
            [schema.core :as sc]
            [sade.excel-reader :as xls]
            [sade.strings :as ss]
            [sade.util :as util]
            [sade.core :refer [ok]]
            [sade.schemas :as ssc]
            [lupapalvelu.action :refer [defexport] :as action]
            [lupapalvelu.application :as application]
            [lupapalvelu.archiving :as archiving]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.permit :as permit]
            [lupapiste-commons.states :as common-states]))

(def kayttotarkoitus-hinnasto (delay (xls/read-map "kayttotarkoitus-hinnasto.xlsx")))

(defn- uuden-rakentaminen [application operation]
  {:post [%]}
  (let [doc (domain/get-document-by-operation application operation)
        [_ kayttotarkoitus] (first (tools/deep-find doc [:kayttotarkoitus :value]))]
    (if (ss/blank? kayttotarkoitus)
      {:price-class "C" :kayttotarkoitus nil}
      {:price-class (get @kayttotarkoitus-hinnasto kayttotarkoitus) :kayttotarkoitus kayttotarkoitus})))

(def permit-type-price-codes
  {"R" 901
   "P" 901
   "YA" 902
   "KT" 904
   "MM" 904
   "MAL" 903
   "YI"  903
   "YL" 903
   "YM" 903
   "VVVL" 903
   "ARK" 901})

(def usage-price-codes
  {"A" 905
   "B" 906
   "C" 907
   "D" 908
   "E" 909})

(def price-classes-for-operation
  ; See lupapiste-chef/cookbooks/lupapiste-dw/files/default/etl/setupdata/price_class_csv.csv

  {:asuinrakennus               uuden-rakentaminen ; old operation tree
   :vapaa-ajan-asuinrakennus    uuden-rakentaminen
   :varasto-tms                 uuden-rakentaminen
   :julkinen-rakennus           uuden-rakentaminen ; old operation tree
   :muu-uusi-rakentaminen       uuden-rakentaminen
   :laajentaminen               "D"
   :perus-tai-kant-rak-muutos   "D"
   :kayttotark-muutos           "D"
   :julkisivu-muutos            "D"
   :jakaminen-tai-yhdistaminen  "D"
   :markatilan-laajentaminen    "D"
   :takka-tai-hormi             "D"
   :parveke-tai-terassi         "D"
   :muu-laajentaminen           "D"
   :auto-katos                  "D"
   :masto-tms                   "D"
   :mainoslaite                 "D"
   :aita                        "D"
   :maalampo                    "D"
   :jatevesi                    "D"
   :muu-rakentaminen            "D"
   :purkaminen                  "D"
   :kaivuu                      "D"
   :puun-kaataminen             "D"
   :muu-maisema-toimenpide      "D"
   :tontin-ajoliittyman-muutos  "D"
   :paikoutysjarjestus-muutos   "D"
   :kortteli-yht-alue-muutos    "D"
   :muu-tontti-tai-kort-muutos  "D"
   :poikkeamis                  "D"
   :meluilmoitus                "D"
   :pima                        "D"
   :maa-aineslupa               "D"
   :aiemmalla-luvalla-hakeminen "D"
   :tyonjohtajan-nimeaminen     "E" ; old operation tree
   :tyonjohtajan-nimeaminen-v2  "E"
   :suunnittelijan-nimeaminen   "D"
   :jatkoaika                   "D"
   :aloitusoikeus               "D"
   :vvvl-vesijohdosta               "D"
   :vvvl-viemarista                 "D"
   :vvvl-vesijohdosta-ja-viemarista "D"
   :vvvl-hulevesiviemarista         "D"
   :ya-kayttolupa-tapahtumat                                          "D"
   :ya-kayttolupa-harrastustoiminnan-jarjestaminen                    "D"
   :ya-kayttolupa-metsastys                                           "D"
   :ya-kayttolupa-vesistoluvat                                        "D"
   :ya-kayttolupa-terassit                                            "F"
   :ya-kayttolupa-kioskit                                             "D"
   :ya-kayttolupa-muu-kayttolupa                                      "D"
   :ya-kayttolupa-mainostus-ja-viitoitus                              "D"
   :ya-kayttolupa-nostotyot                                           "D"
   :ya-kayttolupa-vaihtolavat                                         "F"
   :ya-kayttolupa-kattolumien-pudotustyot                             "D"
   :ya-katulupa-muu-liikennealuetyo                                   "D"
   :ya-kayttolupa-talon-julkisivutyot                                 "D"
   :ya-kayttolupa-talon-rakennustyot                                  "D"
   :ya-kayttolupa-muu-tyomaakaytto                                    "D"
   :ya-katulupa-vesi-ja-viemarityot                                   "D"
   :ya-katulupa-maalampotyot                                          "D"
   :ya-katulupa-kaukolampotyot                                        "D"
   :ya-katulupa-kaapelityot                                           "D"
   :ya-katulupa-kiinteiston-johto-kaapeli-ja-putkiliitynnat           "D"
   :ya-sijoituslupa-vesi-ja-viemarijohtojen-sijoittaminen             "F"
   :ya-sijoituslupa-maalampoputkien-sijoittaminen                     "F"
   :ya-sijoituslupa-kaukolampoputkien-sijoittaminen                   "F"
   :ya-sijoituslupa-sahko-data-ja-muiden-kaapelien-sijoittaminen      "F"
   :ya-sijoituslupa-rakennuksen-tai-sen-osan-sijoittaminen            "F"
   :ya-sijoituslupa-ilmajohtojen-sijoittaminen                        "F"
   :ya-sijoituslupa-muuntamoiden-sijoittaminen                        "F"
   :ya-sijoituslupa-jatekatoksien-sijoittaminen                       "F"
   :ya-sijoituslupa-leikkipaikan-tai-koiratarhan-sijoittaminen        "F"
   :ya-sijoituslupa-rakennuksen-pelastuspaikan-sijoittaminen          "F"
   :ya-sijoituslupa-muu-sijoituslupa                                  "F"
   :ya-jatkoaika                                                      "D"
   :yl-uusi-toiminta                                                  "B"
   :yl-olemassa-oleva-toiminta                                        "D"
   :yl-toiminnan-muutos                                               "D"
   :yl-puiden-kaataminen                                              "D"
   :koeluontoinen-toiminta                                            "D"
   :tonttijako                                                        "D"
   :rasitetoimitus                                                    "D"
   :rajankaynti                                                       "D"
   :asemakaava                                                        "D"
   :ranta-asemakaava                                                  "D"
   :yleiskaava                                                        "D"
   :kiinteistonmuodostus                                              "D"
   :kerrostalo-rivitalo                                               uuden-rakentaminen
   :pientalo                                                          uuden-rakentaminen
   :teollisuusrakennus                                                uuden-rakentaminen
   :linjasaneeraus                                                    "D"
   :rak-valm-tyo                                                      "D"
   :raktyo-aloit-loppuunsaat                                          "D"
   :sisatila-muutos                                                   "D"
   :kerrostalo-rt-laaj                                                "D"
   :pientalo-laaj                                                     "D"
   :talousrakennus-laaj                                               "D"
   :teollisuusrakennus-laaj                                           "D"
   :vapaa-ajan-rakennus-laaj                                          "D"
   :muu-rakennus-laaj                                                 "D"
   :tontin-jarjestelymuutos                                           "D"
   :jatteen-keraystoiminta                                            "D"
   :muistomerkin-rauhoittaminen                                       "D"
   :lannan-varastointi                                                "D"
   :kaytostapoistetun-oljy-tai-kemikaalisailion-jattaminen-maaperaan  "D"
   :ilmoitus-poikkeuksellisesta-tilanteesta                           "D"
   :maa-ainesten-kotitarveotto                                        "D"
   :maastoliikennelaki-kilpailut-ja-harjoitukset                      "D"
   :archiving-project                                                 "D"
   })

(defn- export [collection query fields]
  (ok collection (mongo/select collection query fields)))

(defn- resolve-price-class
  "priceClass = legacy price class, which is mapped in .csv in dw.
   priceCode = new price codes for 'puitesopimus'
   use = usage code for some price classes (kayttotarkoitus)
   useFi = usage code in Finnish
   useSv = usage code in Svedish
   usagePriceCode = mapping from legacy priceClass to new price code (called 'kayttotarkoitushinnasto')"
  [application op]
  (let [op-name  (keyword (:name op))
        price-class (get price-classes-for-operation op-name)]

    (cond
      ; special case 1: paperilupa
      (= :aiemmalla-luvalla-hakeminen op-name )
      (assoc op
        :priceClass price-class
        :priceCode nil
        :use nil
        :useFi nil
        :useSv nil
        :usagePriceCode (get usage-price-codes price-class))

      ; new buildings: fixed price code, but usage price code is determined from the building data
      (= uuden-rakentaminen price-class)
      (let [{:keys [price-class kayttotarkoitus]} (uuden-rakentaminen application op)]
        (assoc op
          :priceClass price-class
          :priceCode 900
          :use kayttotarkoitus
          :useFi (when-not (ss/blank? kayttotarkoitus) (i18n/localize :fi :kayttotarkoitus kayttotarkoitus))
          :useSv (when-not (ss/blank? kayttotarkoitus) (i18n/localize :sv :kayttotarkoitus kayttotarkoitus))
          :usagePriceCode (get usage-price-codes price-class)))

       ; In other cases the price code is determined from permit type
       :else
       (assoc op
         :priceClass price-class
         :priceCode (get permit-type-price-codes (:permitType application))
         :use nil
         :useFi nil
         :useSv nil
         :usagePriceCode (get usage-price-codes price-class)))))

(defn- operation-mapper [application op]
  (util/assoc-when-pred (resolve-price-class application op) util/not-empty-or-nil?
    :displayNameFi (i18n/localize "fi" "operations" (:name op))
    :displayNameSv (i18n/localize "sv" "operations" (:name op))
    :submitted     (when (= "aiemmalla-luvalla-hakeminen" (:name op)) (:created application))))

(defn- verdict-mapper [verdict]
  (-> verdict
    (select-keys [:id :kuntalupatunnus :timestamp :paatokset])
    (update :paatokset #(map (fn [paatos]
                               (merge
                                 (select-keys (:paivamaarat paatos) [:anto :lainvoimainen])
                                 (select-keys (-> paatos :poytakirjat first) [:paatos :paatoksentekija :paatospvm]))) %))))

(defn- exported-application [application]
  (-> application
    (update :operations #(map (partial operation-mapper application) %))
    (update :verdicts #(map verdict-mapper %))
    ; documents not needed in DW
    (dissoc :documents)))

(defexport export-applications
  {:user-roles #{:trusted-etl}}
  [{{ts :modifiedAfterTimestampMillis} :data user :user}]
  (let [query (merge
                (domain/application-query-for user)
                {:primaryOperation.id {$exists true}}
                (when (ss/numeric? ts)
                  {:modified {$gte (Long/parseLong ts 10)}}))
        fields {:address 1 :applicant 1 :authority 1 :closed 1 :created 1 :convertedToApplication 1
                :infoRequest 1 :modified 1 :municipality 1 :opened 1 :openInfoRequest 1
                :primaryOperation 1 :secondaryOperations 1 :organization 1 :propertyId 1
                :permitSubtype 1 :permitType 1 :sent 1 :started 1 :state 1 :submitted 1
                :verdicts 1
                :documents.data.kaytto.kayttotarkoitus.value 1
                :documents.schema-info.op.id 1}
        raw-applications (mongo/select :applications query fields)
        applications-with-operations (map
                                       (fn [a] (assoc a :operations (application/get-operations a)))
                                       raw-applications)]
    (ok :applications (map exported-application applications-with-operations))))

(defn application-to-salesforce [application]
  (letfn [(truncate-op-description [op] (update op :description #(ss/limit % 252 "...")))
          (map-operations [app] (->> (application/get-operations app)
                                     (map truncate-op-description)
                                     (map (partial operation-mapper app))))]
    (-> application
        (assoc  :operations          (map-operations application))
        (update :primaryOperation    truncate-op-description)
        (update :secondaryOperations (fn [ops] (map truncate-op-description ops)))
        (dissoc :documents))))

(def operation-schema
  {:id          ssc/ObjectIdStr
   :created     ssc/Timestamp
   :description (sc/maybe (ssc/max-length-string 255))
   :name        sc/Str})

(def export-operation-schema
  (merge operation-schema
         {(sc/optional-key :displayNameFi) sc/Str
          (sc/optional-key :displayNameSv) sc/Str
          :priceClass                      (sc/enum "A" "B" "C" "D" "E" "F")
          :priceCode                       (sc/maybe (apply sc/enum 900 (vals permit-type-price-codes)))
          :usagePriceCode                  (sc/maybe (apply sc/enum (vals usage-price-codes)))
          :use                             (sc/maybe sc/Str)
          :useSv                           (sc/maybe sc/Str)
          :useFi                           (sc/maybe sc/Str)
          (sc/optional-key :submitted)     ssc/Timestamp}))

(sc/defschema SalesforceExportApplication
  "Application schema for export to Salesforce"
  (merge {:id                                ssc/ApplicationId
          :address                           sc/Str
          :archived                          archiving/archived-ts-keys-schema
          :infoRequest                       sc/Bool
          :municipality                      sc/Str
          :state                             (apply sc/enum
                                                    "info"
                                                    "answered"
                                                    (map name (keys common-states/all-transitions-graph)))
          (sc/optional-key :openInfoRequest) (sc/maybe sc/Bool)
          :organization                      sc/Str
          (sc/optional-key :permitSubtype)   (sc/maybe sc/Str)
          :permitType                        (apply sc/enum (keys (permit/permit-types)))
          :propertyId                        sc/Str
          :primaryOperation                  operation-schema
          :secondaryOperations               [operation-schema]}
         {:operations [export-operation-schema]}
         (zipmap [:created :modified] (repeat ssc/Timestamp))
         (zipmap [(sc/optional-key :started)
                  (sc/optional-key :closed)
                  (sc/optional-key :opened)
                  (sc/optional-key :sent) :submitted] (repeat (sc/maybe ssc/Timestamp)))))

(defn- validate-export-data
  "Validate output data against schema."
  [_ {:keys [applications]}]
  (doseq [exported-application applications]
    (sc/validate SalesforceExportApplication exported-application)))

(defexport salesforce-export
  {:user-roles #{:trusted-salesforce}
   :on-success validate-export-data}
  [{{after  :modifiedAfterTimestampMillis
     before :modifiedBeforeTimestampMillis} :data user :user}]
  (let [query (merge
                (domain/application-query-for user)
                {:primaryOperation.id {$exists true}}
                (when (or (ss/numeric? after) (ss/numeric? before))
                  {:modified (util/assoc-when {}
                                              $gte (when after (Long/parseLong after 10))
                                              $lt  (when before (Long/parseLong before 10)))}))
        fields [:address :archived :closed :created
                :infoRequest :modified :municipality :opened :openInfoRequest :organization
                :primaryOperation :propertyId :permitSubtype :permitType
                :secondaryOperations :sent :started :state :submitted
                :documents.data.kaytto.kayttotarkoitus.value
                :documents.schema-info.op.id]
        raw-applications (mongo/select :applications query fields)]
    (ok :applications (map application-to-salesforce raw-applications))))

(defexport export-organizations
  {:user-roles #{:trusted-etl}}
  [_]
  (export :organizations {:scope.0 {$exists true}} [:name :scope]))
