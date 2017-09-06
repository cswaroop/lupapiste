(ns lupapalvelu.verdict-test
  (:require [midje.sweet :refer :all]
           [midje.util :refer [testable-privates]]
           [lupapalvelu.itest-util :refer [expected-failure? ->xml]]
           [lupapalvelu.action :as action]
           [lupapalvelu.application :as application]
           [lupapalvelu.application-meta-fields :as meta-fields]
           [lupapalvelu.domain :as domain]
           [lupapalvelu.xml.krysp.application-from-krysp :as krysp-fetch]
           [lupapalvelu.verdict :refer :all]
           [lupapalvelu.permit :as permit]
           [lupapalvelu.organization :as organization]
           [sade.common-reader :as cr]
           [sade.core :refer [now]]
           [sade.xml :as xml]
           [sade.util :as util])
  (:import [java.nio.charset StandardCharsets]))

(testable-privates lupapalvelu.verdict get-verdicts-with-attachments content-disposition-filename verdict-in-application-without-attachment?)

(facts "Verdicts parsing"
  (let [xml (sade.xml/parse (slurp "dev-resources/krysp/verdict-r-no-verdicts.xml"))]
    (fact "No verdicts found in the attachment parsing phase"
      (count (get-verdicts-with-attachments {:permitType "R"} {} (now) xml permit/read-verdict-xml {})) => 0
      )))

(def tj-doc {:schema-info {:name "tyonjohtaja-v2"}
             :data {:sijaistus {:paattymisPvm {:value nil},
                                :alkamisPvm {:value nil},
                                :sijaistettavaHloSukunimi {:value ""},
                                :sijaistettavaHloEtunimi {:value ""}}
                    :kuntaRoolikoodi {:value "vastaava ty\u00f6njohtaja"},
                    :yhteystiedot {:email {:value "jukka.testaaja@example.com"}
                                   :puhelin {:value ""}}}})

(def tj-app {:id "2"
             :municipality "753"
             :permitType "R"
             :primaryOperation {:name "tyonjohtajan-nimeaminen-v2"}
             :linkPermitData [{:id "1", :type "lupapistetunnus", :operation "kerrostalo-rivitalo"}]
             :documents [tj-doc]})

(def link-app {:id "1"
               :municipality "753"
               :permitType "R"
               :primaryOperation {:name "kerrostalo-rivitalo"}})

(def cmd {:application tj-app :user {:username "sonja"} :created (now)})

(facts "Tyonjohtaja and suunnittelijan nimeaminen tests"
  (let [xml (xml/parse (slurp "dev-resources/krysp/verdict-r-2.1.8-foremen.xml"))]
    (facts
      (fact "Success when TJ data is ok, compared to XML. Email is same, kuntaRoolikoodi is same"
        (count (:verdicts (fetch-tj-suunnittelija-verdict cmd))) => 1)

      (fact "KRYSP version needs to be 2.1.8 or higher"
        (fetch-tj-suunnittelija-verdict cmd) => nil
        (provided
         (organization/resolve-organization "753" "R") => {:krysp {:R {:version "2.1.7"}}}))

      (fact "Operation name must be correct"
        (fetch-tj-suunnittelija-verdict (assoc-in cmd [:application :primaryOperation :name] "something-else")) => nil)

      (fact "kuntaRoolikoodi must not be nil"
        (fetch-tj-suunnittelija-verdict (util/dissoc-in cmd [:application :documents 0 :data :kuntaRoolikoodi])) => nil
        (provided
          (meta-fields/enrich-with-link-permit-data irrelevant) => (util/dissoc-in tj-app [:documents 0 :data :kuntaRoolikoodi])))

      (fact "Validator doesn't accept unknown kuntaRoolikoodi"
        (fetch-tj-suunnittelija-verdict
          (assoc-in cmd [:application :documents 0 :data :kuntaRoolikoodi :value] "KVV-ty\u00f6njohtaja")) => (partial expected-failure? "info.no-verdicts-found-from-backend")
        (provided
          (meta-fields/enrich-with-link-permit-data irrelevant) => (assoc-in tj-app [:documents 0 :data :kuntaRoolikoodi :value] "KVV-ty\u00f6njohtaja")))

      (fact "Validator error if document's email doesn't match with XML osapuoli email"
        (fetch-tj-suunnittelija-verdict
          (assoc-in cmd [:application :documents 0 :data :yhteystiedot :email :value] "teppo@example.com")) => (partial expected-failure? "info.no-verdicts-found-from-backend")
        (provided
          (meta-fields/enrich-with-link-permit-data irrelevant) => (assoc-in tj-app [:documents 0 :data :yhteystiedot :email :value] "teppo@example.com")))

      (against-background ; some fixed values so we can test verdict fetching process
        (krysp-fetch/get-application-xml-by-application-id anything) => nil
        (krysp-fetch/get-application-xml-by-backend-id anything anything) => nil
        (krysp-fetch/get-application-xml-by-application-id link-app) => xml
        (organization/resolve-organization "753" "R") => {:krysp {:R {:version "2.1.8"}}}
        (meta-fields/enrich-with-link-permit-data irrelevant) => tj-app
        (application/get-link-permit-apps irrelevant) => [link-app]
        (action/update-application irrelevant irrelevant) => nil
        (lupapalvelu.attachment/upload-and-attach! irrelevant irrelevant irrelevant) => nil
        (organization/get-organization nil) => "753-R"
        (organization/krysp-integration? "753-R" "R") => false))))

(def example-meaningful-tj-krysp
  {:tag :Rakennusvalvonta,
   :content [{:tag :rakennusvalvontaAsiatieto,
              :attrs nil,
              :content [{:tag :RakennusvalvontaAsia,
                         :content [{:tag :lisatiedot,
                                    :attrs nil,
                                    :content [{:tag :Lisatiedot,
                                               :attrs nil,
                                               :content [{:tag :salassapitotietoKytkin, :attrs nil, :content ["false"]}
                                                         {:tag :asioimiskieli, :attrs nil, :content ["suomi"]}
                                                         {:tag :suoramarkkinointikieltoKytkin,
                                                          :attrs nil,
                                                          :content ["false"]}]}]}
                                   {:tag :asianTiedot,
                                    :attrs nil,
                                    :content [{:tag :Asiantiedot,
                                               :attrs nil,
                                               :content [{:tag :vahainenPoikkeaminen, :attrs nil, :content nil}
                                                         {:tag :rakennusvalvontaasianKuvaus, :attrs nil, :content nil}]}]}]}]}]})

(def example-application
  {:primaryOperation {:name "tyonjohtajan-nimeaminen-v2"}})

(facts "special foreman/designer verdict"
  (let [xml (verdict-xml-with-foreman-designer-verdicts example-application example-meaningful-tj-krysp)]
    (fact "paatostieto is injected before lisatiedot"
          (keys (cr/all-of xml [:RakennusvalvontaAsia])) => (just [:paatostieto :lisatiedot :asianTiedot]))))

(facts verdict-attachment-type
  (fact "R"
    (verdict-attachment-type {:permitType "R"}) => {:type-group "paatoksenteko" :type-id "paatosote"})
  (fact "P"
    (verdict-attachment-type {:permitType "P"}) => {:type-group "paatoksenteko" :type-id "paatosote"})
  (fact "YA"
    (verdict-attachment-type {:permitType "YA"}) => {:type-group "muut" :type-id "paatosote"})
  (fact "YI"
    (verdict-attachment-type {:permitType "YI"}) => {:type-group "muut" :type-id "paatosote"})
  (fact "VVVL"
    (verdict-attachment-type {:permitType "VVVL"}) => {:type-group "muut" :type-id "paatosote"})
  (fact "R - with type"
    (verdict-attachment-type {:permitType "R"} anything) => {:type-group "paatoksenteko" :type-id anything})
  (fact "YA - with type"
    (verdict-attachment-type {:permitType "YA"} anything) => {:type-group "muut" :type-id anything}))

(facts "Section requirement for verdicts"
       (let [org        {:section {:operations ["pool" "house"]
                                   :enabled    true}}
             pool       {:primaryOperation {:name "pool"}}
             no-xml1    (->xml {:root {:foo {:bar "nope"}}})
             no-xml2    (->xml {:root {:foo {:bar "nope"}
                                       :paatostieto {:hii "hoo"}}})
             blank-xml1 (->xml {:root {:foo {:bar "nope"}
                                       :paatostieto {:pykala ""}}})
             blank-xml2 (->xml {:root {:foo         {:bar "nope"}
                                       :paatostieto {:doh {:pykala ""}}}})
             wrong-path (->xml {:root {:pykala 22}})
             good1      (->xml {:root {:paatostieto {:pykala 22}}})
             good2      (->xml {:root {:another {:paatostieto {:pykala "33"}}}})
             good3      (->xml {:root {:paatostieto {:between {:pykala "33"}}}})
             fail-check (partial expected-failure? :info.section-required-in-verdict)]
         (fact "No paatostieto element"
               (validate-section-requirement pool no-xml1 org) => fail-check)
         (fact "No pykala element"
               (validate-section-requirement pool no-xml2 org) => fail-check)
         (fact "Muutoslupa"
               (validate-section-requirement (assoc pool :permitSubtype "muutoslupa") no-xml2 org) => nil)
         (fact "Blank section 1"
               (validate-section-requirement pool blank-xml1 org) => fail-check)
         (fact "Blank section 2"
               (validate-section-requirement pool blank-xml2 org) => fail-check)
         (fact "Pykala outside of paatostieto element"
               (validate-section-requirement pool wrong-path org) => fail-check)
         (fact "Good sections 1"
               (validate-section-requirement pool good1 org) => nil)
         (fact "Good sections 2"
               (validate-section-requirement pool good2 org) => nil)
         (fact "Good section 3"
               (validate-section-requirement pool good3 org) => nil)
         (fact "Organization does not require section"
               (validate-section-requirement pool no-xml1 {:section {:operations ["pool" "house"]
                                                                     :enabled    false}}) => nil)
         (fact "Section not required for the operation"
               (validate-section-requirement pool no-xml1 {:section {:operations ["sauna" "house"]
                                                                     :enabled    true}}) => nil)))

(facts "Content-Disposition and string encoding"
       (fact "No header"
             (content-disposition-filename {:headers {}}) => nil?)
       (fact "No decoding"
             (content-disposition-filename {:headers {"content-disposition" "attachment; filename=P\u00e4\u00e4t\u00f6sote.txt"}})
             => "P\u00e4\u00e4t\u00f6sote.txt")
       (fact "Encoding: Microsoft-IIS/7.5"
             (content-disposition-filename {:headers {"content-disposition" (String. (.getBytes  "attachment; filename=\"P\u00e4\u00e4t\u00f6sote.txt\""
                                                                                                 StandardCharsets/UTF_8)
                                                                                     StandardCharsets/ISO_8859_1)
                                                      "server"              "Microsoft-IIS/7.5"}})
             => "P\u00e4\u00e4t\u00f6sote.txt"))

(facts "updating verdict paatosote attachments"

  (fact "verdict-in-application-without-attachment?"
    (let [app {:verdicts [{:kuntalupatunnus "KL-1"
                           :paatokset [{:poytakirjat ["not-empty"]}]}
                          {:kuntalupatunnus "KL-2"
                           :paatokset [{:poytakirjat []}
                                       {:poytakirjat ["something"]}]}]}]
      ;; KL-1 has an element in :poytakirjat array
      (verdict-in-application-without-attachment? app {:kuntalupatunnus "KL-1"}) => false
      ;; KL-3 is not present in the :verdicts array
      (verdict-in-application-without-attachment? app {:kuntalupatunnus "KL-3"}) => false
      ;; KL-2 is present and :poytakirjat is empty
      (verdict-in-application-without-attachment? app {:kuntalupatunnus "KL-2"}) => true)))
