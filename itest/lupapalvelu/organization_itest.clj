(ns lupapalvelu.organization-itest
  (:require [midje.sweet :refer :all]
            [clojure.java.io :as io]
            [clojure.set :refer [difference]]
            [lupapalvelu.organization :as local-org-api]
            [lupapalvelu.waste-ads :as waste-ads]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.proxy-services :as proxy]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.factlet :refer [fact* facts*]]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.fixture.core :as fixture]
            [lupapalvelu.i18n :as i18n]
            [monger.operators :refer :all]
            [sade.core :as sade]
            [sade.strings :as ss]
            [sade.schemas :as ssc]
            [sade.schema-generators :as ssg]))

(apply-remote-minimal)

(defn- language-map [langs suffix]
  (into {} (map (juxt identity
                      #(str (name %) " " suffix))
                langs)))

(facts "set-krysp-endpoint"
  (let [uri "http://127.0.0.1:8000/dev/krysp"]
    (fact "pena can't set krysp-url"
      (command pena :set-krysp-endpoint :url uri :username "" :password "" :permitType "R" :version "1") => unauthorized?)

    (fact "sipoo can set working krysp-url"
      (command sipoo :set-krysp-endpoint :url uri :username "" :password "" :permitType "YA" :version "2") => ok?)

    (fact "sipoo can set working krysp-url containing extra spaces"
      (command sipoo :set-krysp-endpoint :url (str " " uri " ") :username "" :password "" :permitType "YA" :version "2") => ok?)

   (fact "sipoo cant set incorrect krysp-url"
      (command sipoo :set-krysp-endpoint :url "BROKEN_URL" :username "" :password "" :permitType "R"  :version "1") => fail?)))

(facts "set-krysp-endpoint private url"
  (let [uri "http://127.0.0.1:8000/dev/private-krysp"
        non-private "http://127.0.0.1:8000/dev/krysp"]

    (fact "sipoo can not set working krysp-url without credentials"
      (command sipoo :set-krysp-endpoint :url uri :username "" :password "" :permitType "R" :version "2") => fail?)

    (fact "sipoo can not set working krysp-url with incorrect credentials"
      (command sipoo :set-krysp-endpoint :url uri :username "foo" :password "bar" :permitType "R" :version "2") => fail?)

    (fact "sipoo can set working krysp-url with correct credentials"
      (command sipoo :set-krysp-endpoint :url uri :username "pena" :password "pena" :permitType "R" :version "2") => ok?)

    (fact "sipoo can not set working krysp-url with incorrect username and saved password"
      (command sipoo :set-krysp-endpoint :url uri :username "foo" :password "" :permitType "R" :version "2") => fail?)

    (fact "sipoo can set working krysp-url with only username set"
      (command sipoo :set-krysp-endpoint :url uri :username "pena" :password "" :permitType "R" :version "2") => ok?)

    (fact "query krysp config - no credentials set for P endpoint"
          (-> (query sipoo :krysp-config) :krysp :P (select-keys [:url :username :password])) => (just {:url anything}))

    (fact "query krysp config - credentials not set for YA endpoint"
      (-> (query sipoo :krysp-config) :krysp :YA (select-keys [:url :username :password])) => (just {:url anything}))

    (fact "query krysp config - credentials set for R endpoint - password is not returned"
      (-> (query sipoo :krysp-config) :krysp :R (select-keys [:url :username :password])) => (just {:url uri :username "pena"}))

    (fact "changing to uri without username is possible"    ; LPK-3719
      (command sipoo :set-krysp-endpoint :url non-private :username "" :password "" :permitType "R" :version "2") => ok?
      (fact "username has been $unset"
        (-> (query sipoo :krysp-config) :krysp :R (select-keys [:url :username :password])) => (just {:url non-private})))
    (fact "and returning back again works"
      (command sipoo :set-krysp-endpoint :url uri :username "pena" :password "" :permitType "R" :version "2") => fail?
      (command sipoo :set-krysp-endpoint :url uri :username "pena" :password "pena" :permitType "R" :version "2") => ok?
      (-> (query sipoo :krysp-config) :krysp :R (select-keys [:url :username :password])) => (just {:url uri :username "pena"}))))


(facts* "users-in-same-organizations"
  (let [naantali (apikey-for "rakennustarkastaja@naantali.fi")
        jarvenpaa (apikey-for "rakennustarkastaja@jarvenpaa.fi")
        oulu (apikey-for "olli")

        naantali-user (query naantali :user) => ok?
        jarvenpaa-user (query jarvenpaa :user) => ok?
        oulu-user (query oulu :user) => ok?]

    ; Meta
    (fact "naantali user in naantali & jarvenpaa orgs"
      (->> naantali-user :user :orgAuthz keys) => (just [:529-R :186-R] :in-any-order))
    (fact "jarvenpaa just jarvenpaa"
      (->> jarvenpaa-user :user :orgAuthz keys) => [:186-R])
    (fact "oulu user in oulu & naantali orgs"
      (->> oulu-user :user :orgAuthz keys) => (just [:564-R :529-R :564-YMP] :in-any-order))


    (let [naantali-sees (:users (query naantali :users-in-same-organizations))
          jarvenpaa-sees (:users (query jarvenpaa :users-in-same-organizations))
          oulu-sees (:users (query oulu :users-in-same-organizations))]

      (fact "naantali user sees other users in naantali & jarvenpaa (but not admin)"
        (map :username naantali-sees) =>
        (contains ["rakennustarkastaja@naantali.fi" "lupasihteeri@naantali.fi" "rakennustarkastaja@jarvenpaa.fi" "lupasihteeri@jarvenpaa.fi" "olli" "digitoija@jarvenpaa.fi"] :in-any-order))

      (fact "jarvenpaa just jarvenpaa users (incl. Mr. Naantali but not admin)"
        (map :username jarvenpaa-sees) =>
        (contains ["rakennustarkastaja@jarvenpaa.fi" "lupasihteeri@jarvenpaa.fi" "rakennustarkastaja@naantali.fi"] :in-any-order))

      (fact "oulu user sees other users in oulu & naantali"
        (map :username oulu-sees) =>
        (contains ["olli" "olli-ya" "rakennustarkastaja@naantali.fi" "lupasihteeri@naantali.fi"] :in-any-order)))))

(fact* "Organization details query works"
 (let [resp  (query pena "organization-details" :municipality "753" :operation "kerrostalo-rivitalo" :lang "fi") => ok?]
   (count (:attachmentsForOp resp )) => pos?
   (count (:links resp)) => pos?))

(fact* "The query /organizations"
  (let [resp (query admin :organizations) => ok?]
    (count (:organizations resp)) => pos?))

(fact "Update organization"
  (let [organization         (first (:organizations (query admin :organizations)))
        orig-scope           (first (:scope organization))
        organization-id      (:id organization)
        resp                 (command admin :update-organization
                               :permitType (:permitType orig-scope)
                               :municipality (:municipality orig-scope)
                               :inforequestEnabled (not (:inforequest-enabled orig-scope))
                               :applicationEnabled (not (:new-application-enabled orig-scope))
                               :openInforequestEnabled (not (:open-inforequest orig-scope))
                               :openInforequestEmail "someone@localhost.localdomain"
                               :opening nil)
        updated-organization (:data (query admin :organization-by-id :organizationId organization-id))
        updated-scope        (local-org-api/resolve-organization-scope (:municipality orig-scope) (:permitType orig-scope) updated-organization)]

    resp => ok?

    (fact "inforequest-enabled" (:inforequest-enabled updated-scope) => (not (:inforequest-enabled orig-scope)))
    (fact "new-application-enabled" (:new-application-enabled updated-scope) => (not (:new-application-enabled orig-scope)))
    (fact "open-inforequest" (:open-inforequest updated-scope) => (not (:open-inforequest orig-scope)))
    (fact "open-inforequest-email" (:open-inforequest-email updated-scope) => "someone@localhost.localdomain")))

(fact "Admin - Add scope"
  (let [organization   (first (:organizations (query admin :organizations)))
        org-id         (:id organization)
        scopes         (:scope organization)
        first-scope    (first scopes)
        new-permitType (first
                         (difference
                           (set (keys (permit/permit-types)))
                           (set (map :permitType scopes))))]
    (fact "Duplicate scope can't be added"
      (command admin :add-scope
               :organization org-id
               :permitType "R" ; Sipoo in minimal
               :municipality "753" ; Sipoo in minimal
               :inforequestEnabled true
               :applicationEnabled true
               :openInforequestEnabled false
               :openInforequestEmail ""
               :opening nil) => (partial expected-failure? :error.organization.duplicate-scope))
    (fact "invalid muni can't be added"
      (command admin :add-scope
               :organization org-id
               :permitType "R"
               :municipality "foobar"
               :inforequestEnabled true
               :applicationEnabled true
               :openInforequestEnabled false
               :openInforequestEmail ""
               :opening nil) => (partial expected-failure? :error.invalid-municipality))
    (fact "Admin can add new scope to organization"
      (command admin :add-scope
               :organization org-id
               :permitType new-permitType
               :municipality (:municipality first-scope) ; Sipoo in minimal
               :inforequestEnabled true
               :applicationEnabled true
               :openInforequestEnabled false
               :openInforequestEmail ""
               :opening nil) => ok?)))

(fact* "Tampere-ya sees (only) YA operations and attachments (LUPA-917, LUPA-1006)"
       (let [resp (query tampere-ya :organization-by-user) => ok?
        tre  (:organization resp)]
         (fact "operations attachments"
           (keys (:operationsAttachments tre)) => [:YA]
           (-> tre :operationsAttachments :YA) => truthy)
         (fact "attachment types"
           (keys (:attachmentTypes resp)) => [:YA]
           (-> resp :attachmentTypes :YA) => truthy)))

(facts assignment-triggers
  (fact "must have targets"
    (command sipoo :upsert-assignment-trigger
             :description "no targets"
             :handler nil) => (partial expected-failure? :error.missing-parameters))
  (fact "must have description"
    (command sipoo :upsert-assignment-trigger
             :targets ["ennakkoluvat_ja_lausunnot.elyn_tai_kunnan_poikkeamapaatos"]
             :description ""
             :handler nil) => (partial expected-failure? :error.missing-parameters))
  (fact "handler data must be valid"
    (command sipoo :upsert-assignment-trigger
             :targets ["ennakkoluvat_ja_lausunnot.elyn_tai_kunnan_poikkeamapaatos"]
             :description "Description"
             :handler {:id "handler-id"
                       :name {:fi " " ; names must be non-blank
                              :sv " "
                              :en ""}}) => (partial expected-failure? :error.validator))
  (fact "correct data is inserted"
    (let [trigger {:targets ["ennakkoluvat_ja_lausunnot.elyn_tai_kunnan_poikkeamapaatos"]
                   :description "ely"}
          trigger-resp (:trigger (apply command sipoo :upsert-assignment-trigger
                                        (mapcat seq trigger)))]
      (:id trigger-resp) => string?
      (dissoc trigger-resp :id) => trigger))

  (facts updates
    (let [trigger (:trigger (command sipoo :upsert-assignment-trigger
                                     :targets ["paapiirustus.leikkauspiirustus"]
                                     :description "ely"))
          updated-trigger (:trigger (command sipoo :upsert-assignment-trigger
                                             :triggerId (:id trigger)
                                             :targets ["paapiirustus.muu_paapiirustus"]
                                             :description "ely v2"))]
      (fact "correct data is updated"
        (:targets updated-trigger) => ["paapiirustus.muu_paapiirustus"]
        (:description updated-trigger) => "ely v2")

      (let [{application-id :id} (create-and-submit-application pena :propertyId sipoo-property-id)
            resp1 (upload-file pena "dev-resources/test-attachment.txt") => ok?
            file-id-1 (get-in resp1 [:files 0 :fileId])
            _ (command sonja :create-attachments
                       :id application-id
                       :attachmenttypes [{:type-group :paapiirustus :type-id :muu_paapiirustus}]
                       :group nil)
            {job :job :as job-resp} (command pena :bind-attachments
                                             :id application-id
                                             :filedatas [{:fileId file-id-1
                                                          :type {:type-group "paapiirustus"
                                                                 :type-id "muu_paapiirustus"}
                                                          :contents "eka"}]) => ok?]
        (poll-job pena :bind-attachments-job (:id job) (:version job) 25) => ok?
        (Thread/sleep 1000)
        (fact "trigger assignment is created"
          (let [assignments-for-trigger (->> (query sonja :assignments-for-application :id application-id)
                                            :assignments
                                            (filter #(= (:trigger %) (:id trigger))))]
            (count assignments-for-trigger) => 1
            (-> assignments-for-trigger first :trigger) => (:id trigger)
            (-> assignments-for-trigger first :description) => "ely v2"))

        (fact "trigger assignment descriptions are synchronized with trigger description"
          (command sipoo :upsert-assignment-trigger
                   :triggerId (:id trigger)
                   :targets ["paapiirustus.muu_paapiirustus"]
                   :description "ely v3"))
          (->> (query sonja :assignments-for-application :id application-id)
               :assignments
               (filter #(= (:trigger %) (:id trigger)))
               first
               :description) => "ely v3"))))

(facts "Organization attachment types"
  (facts "753-R: R P YM YI YL MAL VVVL KT MM"
    (let [types (:attachmentTypes (query sipoo :organization-attachment-types))]
      (fact "R P attachment: ote_kiinteistorekisteristerista"
        types => (contains {:type-group "rakennuspaikka"
                            :type-id "ote_kiinteistorekisteristerista"}))
      (fact "YM attachment: selvitys_ymparistonsuojelutoimista"
        types => (contains {:type-group "koeluontoinen_toiminta"
                            :type-id "selvitys_ymparistonsuojelutoimista"}))
      (fact "YI VVVL attachment: kartta-melun-ja-tarinan-leviamisesta"
        types => (contains {:type-group "kartat"
                            :type-id "kartta-melun-ja-tarinan-leviamisesta"}))
      (fact "YL attachment: paastot_ilmaan"
        types => (contains {:type-group "ymparistokuormitus"
                            :type-id "paastot_ilmaan"}))
      (fact "MAL attachment: ottamisalueen_omistus_hallintaoikeus"
        types => (contains {:type-group "hakija"
                            :type-id "ottamisalueen_omistus_hallintaoikeus"}))
      (fact "KT MM attachment: tilusvaihtosopimus"
        types => (contains {:type-group "kiinteiston_hallinta"
                            :type-id "tilusvaihtosopimus"}))
      (fact "No YA attachment: valokuva"
        types =not=> (contains {:type-group "yleiset-alueet"
                                :type-id "valokuva"}))))
  (facts "753-YA: YA"
    (let [types (:attachmentTypes (query sipoo-ya :organization-attachment-types))]
      (fact "No R P attachment: ote_kiinteistorekisteristerista"
        types =not=> (contains {:type-group "rakennuspaikka"
                                :type-id "ote_kiinteistorekisteristerista"}))
      (fact "No YM attachment: selvitys_ymparistonsuojelutoimista"
        types =not=> (contains {:type-group "koeluontoinen_toiminta"
                                :type-id "selvitys_ymparistonsuojelutoimista"}))
      (fact "No YI VVVL attachment: kartta-melun-ja-tarinan-leviamisesta"
        types =not=> (contains {:type-group "kartat"
                                :type-id "kartta-melun-ja-tarinan-leviamisesta"}))
      (fact "No YL attachment: paastot_ilmaan"
        types =not=> (contains {:type-group "ymparistokuormitus"
                                :type-id "paastot_ilmaan"}))
      (fact "No MAL attachment: ottamisalueen_omistus_hallintaoikeus"
        types =not=> (contains {:type-group "hakija"
                                :type-id "ottamisalueen_omistus_hallintaoikeus"}))
      (fact "No KT MM attachment: tilusvaihtosopimus"
        types =not> (contains {:type-group "kiinteiston_hallinta"
                               :type-id "tilusvaihtosopimus"}))
      (fact "YA attachment: valokuva"
        types => (contains {:type-group "yleiset-alueet"
                            :type-id "valokuva"})))))

(facts "Selected operations"

  (fact "For an organization which has no selected operations, all operations are returned"
    (let [resp (query sipoo "all-operations-for-organization" :organizationId "753-YA")
          operations (:operations resp)]
      ;; All the YA operations (and only those) are received here.
      (count operations) => 1
      (-> operations first first) => "yleisten-alueiden-luvat"))

  (fact "Set selected operations"
    (command pena "set-organization-selected-operations" :operations ["pientalo" "aita"]) => unauthorized?
    (command sipoo "set-organization-selected-operations" :operations ["pientalo" "aita"]) => ok?)

  (fact* "Query selected operations"
    (query pena "selected-operations-for-municipality" :municipality "753") => ok?
    (let [resp (query sipoo "selected-operations-for-municipality" :municipality "753")]
      resp => ok?

      ;; Received the two selected R operations plus 4 YA operations.
      (:operations resp) =>  [["Rakentaminen ja purkaminen"
                               [["Uuden rakennuksen rakentaminen"
                                 [["pientalo" "pientalo"]]]
                                ["Rakennelman rakentaminen"
                                 [["Aita" "aita"]]]]]
                              ["yleisten-alueiden-luvat"
                               [["sijoituslupa"
                                 [["pysyvien-maanalaisten-rakenteiden-sijoittaminen"
                                   [["vesi-ja-viemarijohtojen-sijoittaminen" "ya-sijoituslupa-vesi-ja-viemarijohtojen-sijoittaminen"]]]]]
                                ["katulupa" [["kaivaminen-yleisilla-alueilla"
                                              [["vesi-ja-viemarityot" "ya-katulupa-vesi-ja-viemarityot"]]]
                                             ["liikennealueen-rajaaminen-tyokayttoon"
                                              [["nostotyot" "ya-kayttolupa-nostotyot"]
                                               ["vaihtolavat" "ya-kayttolupa-vaihtolavat"]]]]]
                                ["kayttolupa" [["mainokset" "ya-kayttolupa-mainostus-ja-viitoitus"]
                                               ["terassit" "ya-kayttolupa-terassit"]]]]]]))

  (fact* "Query selected operations"
    (let [id   (create-app-id pena :operation "kerrostalo-rivitalo" :propertyId sipoo-property-id)
          resp (query pena "addable-operations" :id id) => ok?]
      (:operations resp) => [["Rakentaminen ja purkaminen" [["Uuden rakennuksen rakentaminen" [["pientalo" "pientalo"]]] ["Rakennelman rakentaminen" [["Aita" "aita"]]]]]]))

  (fact* "The query 'organization-by-user' correctly returns the selected operations of the organization"
    (let [resp (query pena "organization-by-user") => unauthorized?
          resp (query sipoo "organization-by-user") => ok?]
      (get-in resp [:organization :selectedOperations]) => {:R ["aita" "pientalo"]}))

  (fact "An application query correctly returns the 'required fields filling obligatory' and 'kopiolaitos-email' info in the organization meta data"
    (let [app-id (create-app-id pena :operation "kerrostalo-rivitalo" :propertyId sipoo-property-id)
          app    (query-application pena app-id)
          org    (:data (query admin "organization-by-id" :organizationId  (:organization app)))
          kopiolaitos-email "kopiolaitos@example.com"
          kopiolaitos-orderer-address "Testikatu 1"
          kopiolaitos-orderer-phone "123"
          kopiolaitos-orderer-email "orderer@example.com"]

      (fact "the 'app-required-fields-filling-obligatory' and 'kopiolaitos-email' flags have not yet been set for organization in db"
        (:app-required-fields-filling-obligatory org) => falsey
        (-> app :organizationMeta :requiredFieldsFillingObligatory) => falsey)

      (command sipoo "set-organization-app-required-fields-filling-obligatory" :enabled false) => ok?

      (let [app    (query-application pena app-id)
            org    (:data (query admin "organization-by-id" :organizationId  (:organization app)))
            organizationMeta (:organizationMeta app)]
        (fact "the 'app-required-fields-filling-obligatory' is set to False"
          (:app-required-fields-filling-obligatory org) => false
          (:requiredFieldsFillingObligatory organizationMeta) => false)
        (fact "the 'kopiolaitos-email' is set (from minimal)"
          (:kopiolaitos-email org) => "sipoo@example.com"
          (get-in organizationMeta [:kopiolaitos :kopiolaitosEmail]) => "sipoo@example.com")
        (fact "the 'kopiolaitos-orderer-address' is set (from minimal)"
          (:kopiolaitos-orderer-address org) => "Testikatu 2, 12345 Sipoo"
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererAddress]) => "Testikatu 2, 12345 Sipoo")
        (fact "the 'kopiolaitos-orderer-email' is set (from minimal)"
          (:kopiolaitos-orderer-email org) => "tilaaja@example.com"
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererEmail]) => "tilaaja@example.com")
        (fact "the 'kopiolaitos-orderer-phone' is set (from minimal)"
          (:kopiolaitos-orderer-phone org) => "0501231234"
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererPhone]) => "0501231234"))

      (command sipoo "set-organization-app-required-fields-filling-obligatory" :enabled true) => ok?
      (command sipoo "set-kopiolaitos-info"
        :kopiolaitosEmail kopiolaitos-email
        :kopiolaitosOrdererAddress kopiolaitos-orderer-address
        :kopiolaitosOrdererPhone kopiolaitos-orderer-phone
        :kopiolaitosOrdererEmail kopiolaitos-orderer-email) => ok?

      (let [app    (query-application pena app-id)
            org    (:data (query admin "organization-by-id" :organizationId  (:organization app)))
            organizationMeta (:organizationMeta app)]
        (fact "the 'app-required-fields-filling-obligatory' flag is set to true value"
          (:app-required-fields-filling-obligatory org) => true
          (:requiredFieldsFillingObligatory organizationMeta) => true)
        (fact "the 'kopiolaitos-email' flag is set to given email address"
          (:kopiolaitos-email org) => kopiolaitos-email
          (get-in organizationMeta [:kopiolaitos :kopiolaitosEmail]) => kopiolaitos-email)
        (fact "the 'kopiolaitos-orderer-address' flag is set to given address"
          (:kopiolaitos-orderer-address org) => kopiolaitos-orderer-address
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererAddress]) => kopiolaitos-orderer-address)
        (fact "the 'kopiolaitos-orderer-phone' flag is set to given phone address"
          (:kopiolaitos-orderer-phone org) => kopiolaitos-orderer-phone
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererPhone]) => kopiolaitos-orderer-phone)
        (fact "the 'kopiolaitos-orderer-email' flag is set to given email address"
          (:kopiolaitos-orderer-email org) => kopiolaitos-orderer-email
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererEmail]) => kopiolaitos-orderer-email)))))


(facts "organization-operations-attachments"
  (fact "Invalid operation is rejected"
    (command sipoo :organization-operations-attachments :operation "foo" :attachments []) => (partial expected-failure? "error.unknown-operation"))

  (fact "Empty attachments array is ok"
    (command sipoo :organization-operations-attachments :operation "pientalo" :attachments []) => ok?)

  (fact "scalar value as attachments parameter is not ok"
    (command sipoo :organization-operations-attachments :operation "pientalo" :attachments "") => (partial expected-failure? "error.non-vector-parameters"))

  (fact "Invalid attachment is rejected"
    (command sipoo :organization-operations-attachments :operation "pientalo" :attachments [["foo" "muu"]]) => (partial expected-failure? "error.unknown-attachment-type"))

  (fact "Valid attachment is ok"
    (command sipoo :organization-operations-attachments :operation "pientalo" :attachments [["muut" "muu"]]) => ok?))

(facts "Archiving features can be set"
  (let [organization  (first (:organizations (query admin :organizations)))
        id (:id organization)]

    (fact "Permanent archive can be enabled"
      (command admin "set-organization-boolean-attribute" :enabled true :organizationId id :attribute "permanent-archive-enabled") => ok?
      (let [updated-org (:data (query admin "organization-by-id" :organizationId id))]
        (:permanent-archive-enabled updated-org) => true))

    (fact "Permanent archive can be disabled"
      (command admin "set-organization-boolean-attribute" :enabled false :organizationId id :attribute "permanent-archive-enabled") => ok?
      (let [updated-org (:data (query admin "organization-by-id" :organizationId id))]
        (:permanent-archive-enabled updated-org) => false))

    (fact "Digitizer tools can be enabled"
      (command admin "set-organization-boolean-attribute" :enabled true :organizationId id :attribute "digitizer-tools-enabled") => ok?
      (let [updated-org (:data (query admin "organization-by-id" :organizationId id))]
        (:digitizer-tools-enabled updated-org) => true))

    (fact "Digitizer tools can be disabled"
      (command admin "set-organization-boolean-attribute" :enabled false :organizationId id :attribute "digitizer-tools-enabled") => ok?
      (let [updated-org (:data (query admin "organization-by-id" :organizationId id))]
        (:digitizer-tools-enabled updated-org) => false))))

(facts "Organization names"
  (let [{names :names :as resp} (query pena :get-organization-names)]
    resp => ok?
    (count names) => pos?
    (-> names :753-R :fi) => "Sipoon rakennusvalvonta"))

(facts "Organization tags"
  (fact "only auth admin can add new tags"
    (command sipoo :save-organization-tags :tags []) => ok?
    (command sipoo :save-organization-tags :tags [{:id nil :label "makeja"} {:id nil :label "nigireja"}]) => ok?
    (command sonja :save-organization-tags :tags [{:id nil :label "illegal"}] =not=> ok?)
    (command pena :save-organization-tags :tags [{:id nil :label "makeja"}] =not=> ok?))
  (fact "tags get ids when saved"
    (:tags (query sipoo :get-organization-tags)) => (just {:753-R (just {:name (just (i18n/localization-schema string?))
                                                                         :tags (just [(just {:id string? :label "makeja"})
                                                                                      (just {:id string? :label "nigireja"})])})}))

  (fact "only authority can fetch available tags"
    (query pena :get-organization-tags) =not=> ok?
    (map :label (:tags (:753-R (:tags (query sonja :get-organization-tags))))) => ["makeja" "nigireja"])

  (fact "invalid data is rejected"
    (command sipoo :save-organization-tags :tagz []) => fail?
    (command sipoo :save-organization-tags :tags {}) => fail?
    (command sipoo :save-organization-tags :tags nil) => fail?
    (command sipoo :save-organization-tags :tags "tag") => fail?
    (command sipoo :save-organization-tags :tags [{}]) => fail?
    (command sipoo :save-organization-tags :tags [{:id "id"}]) => fail?
    (command sipoo :save-organization-tags :tags [{:label false}]) => fail?
    (command sipoo :save-organization-tags :tags [{:id nil :label {:injection true}}]) => fail?)

  (fact "Check tag deletion query"
    (let [id (create-app-id sonja)
          tag-id (-> (query sonja :get-organization-tags)
                   :tags :753-R :tags first :id)]
      (command sonja :add-application-tags :id id :tags [tag-id]) => ok?

      (fact "when tag is used, application id is returned"
        (let [res (query sipoo :remove-tag-ok :tagId tag-id)]
          res =not=> ok?
          (-> res :applications first :id) => id))

      (fact "when tag is not used in applications, ok is returned"
        (command sonja :add-application-tags :id id :tags []) => ok?
        (query sipoo :remove-tag-ok :tagId tag-id) => ok?))))

(facts "Organization areas zip file upload"
  (fact "only authorityAdmin can upload"
    (:body (upload-area pena)) => "unauthorized"
    (:body (upload-area sonja)) => "unauthorized")

  (fact "text file is not ok (zip required)"
        (-> (decode-response (upload-area sipoo "dev-resources/test-attachment.txt"))
            :body
            :text) => "error.illegal-shapefile")

  (let [resp (upload-area sipoo)
        body (:body (decode-response resp))]

    (fact "zip file with correct shape file can be uploaded by auth admin"
      resp => http200?
      body => ok?)))

(def local-db-name (str "test_organization_itest_" (sade/now)))

(mongo/connect!)
(mongo/with-db local-db-name (fixture/apply-fixture "minimal"))

(facts "Municipality (753) maps"
       (mongo/with-db local-db-name
         (let [url "http://mapserver"]
           (local-org-api/update-organization
            "753-R"
            {$set {:map-layers {:server {:url url}
                                :layers [{:name "asemakaava"
                                          :id "asemakaava-id"
                                          :base true}
                                         {:name "kantakartta"
                                          :id "kantakartta-id"
                                          :base true}
                                         {:name "foo"
                                          :id "foo-id"
                                          :base false}
                                         {:name "bar"
                                          :id "bar-id"
                                          :base false}]}}})
           (local-org-api/update-organization
            "753-YA"
            {$set {:map-layers {:server {:url url}
                                :layers [{:name "asemakaava"
                                          :id "other-asemakaava-id"
                                          :base true}
                                         {:name "kantakartta"
                                          :id "other-kantakartta-id"
                                          :base true}
                                         {:name "Other foo"
                                          :id "foo-id"
                                          :base false}
                                         {:name "kantakartta" ;; not base layer
                                          :id "hii-id"
                                          :base false}]}}})
           (let [layers (proxy/municipality-layers "753")
                 objects (proxy/municipality-layer-objects "753")]
             (fact "Five layers"
                   (count layers) => 5)
             (fact "Only one asemakaava"
                   (->> layers (filter #(= (:name %) "asemakaava")) count) => 1)
             (fact "Only one with foo-id"
                   (->> layers (filter #(= (:id %) "foo-id")) count) => 1)
             (facts "Two layers named kantakartta"
                    (let [kantas (filter #(= "kantakartta" (:name %)) layers)
                          [k1 k2] kantas]
                      (fact "Two layers" (count kantas) => 2)
                      (fact "One of which is not a base layer"
                            (not= (:base k1) (:base k2)) => true)))
             (facts "Layer objects"
                    (defn loc-data [s] (zipmap [:fi :sv :en] (repeat s)))
                    (fact "Every layer object has an unique id"
                          (->> objects (map :id) set count) => 5)
                    (fact "Ids are correctly formatted"
                          (every? #(let [{:keys [id base]} %]
                                     (or (and base (= (name {"asemakaava"  101
                                                             "kantakartta" 102})
                                                      id))
                                         (and (not base) (not (number? id))))) objects))
                    (fact "Bar layer is correct "
                          (let [subtitles (loc-data "")
                                bar-index (->> layers (map-indexed #(assoc %2 :index %1)) (some #(if (= (:id %) "bar-id") (:index %))))]

                            (nth objects bar-index) => {:name        (loc-data "bar")
                                                        :subtitle    subtitles
                                                        :id          (str "Lupapiste-" bar-index)
                                                        :baseLayerId (str "Lupapiste-" bar-index)
                                                        :isBaseLayer false
                                                        :minScale    400000
                                                        :wmsName     "Lupapiste-753-R:bar-id"
                                                        :wmsUrl      "/proxy/kuntawms"})))
             (facts "New map data with different server to 753-YA"
                    (local-org-api/update-organization
                     "753-YA"
                     {$set {:map-layers {:server {:url "http://different"}
                                         :layers [{:name "asemakaava"
                                                   :id   "other-asemakaava-id"
                                                   :base true}
                                                  {:name "kantakartta"
                                                   :id   "other-kantakartta-id"
                                                   :base true}
                                                  {:name "Other foo"
                                                   :id   "foo-id"
                                                   :base false}]}}})
                    (let [layers (proxy/municipality-layers "753")]
                      (fact "Two layers with same ids are allowed if the servers differ"
                            (->> layers (filter #(= (:id %) "foo-id")) count) => 2)
                      (fact "Still only two base layers"
                            (->> layers (filter :base) count) => 2)))
             (facts "Storing passwords"
                    (local-org-api/update-organization-map-server "753-YA"
                                                                  "http://doesnotmatter"
                                                                  "username"
                                                                  "plaintext")
                    (let [org (local-org-api/get-organization "753-YA")
                          {:keys [username password crypto-iv]} (-> org :map-layers :server)]
                      (fact "Password is encrypted"
                            (not= password "plaintext") => true)
                      (fact "Password decryption"
                            (local-org-api/decode-credentials password crypto-iv) => "plaintext")
                      (fact "No extra credentials are stored (nil)"
                            (local-org-api/update-organization-map-server "753-YA"
                                                                          "http://stilldoesnotmatter"
                                                                          nil
                                                                          nil)
                            (-> "753-YA" local-org-api/get-organization :map-layers :server
                                (select-keys [:password :crypto-iv])) => {:password nil})
                      (fact "No extra credentials are stored ('')"
                            (local-org-api/update-organization-map-server "753-YA"
                                                                          "http://stilldoesnotmatter"
                                                                          ""
                                                                          "")
                            (-> "753-YA" local-org-api/get-organization :map-layers :server
                                (select-keys [:password :crypto-iv])) => {:password nil})))))))

(doseq [[command-name config-key] [[:set-organization-neighbor-order-email :neighbor-order-emails]
                                   [:set-organization-submit-notification-email :submit-notification-emails
                                    :set-organization-inforequest-notification-email :inforequest-notification-emails]]]
  (facts {:midje/description (name command-name)}
    (fact "Emails are not set in fixture"
      (let [resp (query sipoo :organization-by-user)]
        resp => ok?
        (:organization resp) => seq
        (get-in resp [:organization :notifications config-key]) => empty?))

    (fact "One email is set"
      (command sipoo command-name :emails "kirjaamo@sipoo.example.com") => ok?
      (-> (query sipoo :organization-by-user)
        (get-in [:organization :notifications config-key])) => ["kirjaamo@sipoo.example.com"])

    (fact "Three emails are set"
      (command sipoo command-name :emails "KIRJAAMO@sipoo.example.com,  sijainen1@sipoo.example.com;sijainen2@sipoo.example.com") => ok?
      (-> (query sipoo :organization-by-user)
        (get-in [:organization :notifications config-key])) => ["kirjaamo@sipoo.example.com", "sijainen1@sipoo.example.com", "sijainen2@sipoo.example.com"])

    (fact "Reset email addresses"
      (command sipoo command-name :emails "") => ok?
      (-> (query sipoo :organization-by-user)
        (get-in [:organization :notifications config-key])) => empty?)))

(facts "Suti server datails"
  (fact "initially empty"
    (let [initial-org-resp (query sipoo :organization-by-user)]
      initial-org-resp => ok?
      (get-in initial-org-resp [:organization :suti :server]) => empty?))

  (fact "updated succeeds"
    (command sipoo :update-suti-server-details :url "http://localhost:8000/dev/suti" :username "sipoo" :password "xx") => ok?)

  (fact "is set"
    (let [{:keys [organization] :as org-resp} (query sipoo :organization-by-user)]
     org-resp => ok?
     (get-in organization [:suti :server]) => (contains {:url "http://localhost:8000/dev/suti" :username "sipoo"})

     (fact "password not echoed"
       (get-in organization [:suti :server :password]) => nil))))

(facts "Construction waste feeds"
  (mongo/with-db local-db-name
    (mongo/insert :applications
                  {:_id          "LP-1"
                   :organization "753-R"
                   :municipality "753"
                   :documents    [
                                  {:schema-info {:name "rakennusjateselvitys"}
                                   :data        {:contact            {:name  {:value    "Bob"
                                                                              :modified 1}
                                                                      :phone {:value    "12345"
                                                                              :modified 2}
                                                                      :email {:value    "bob@reboot.tv"
                                                                              :modified 2222}}
                                                 :availableMaterials {:0 {:aines      {:value    "Sora"
                                                                                       :modified 100}
                                                                          :maara      {:value    "2"
                                                                                       :modified 110}
                                                                          :yksikko    {:value    "kg"
                                                                                       :modified 200}
                                                                          :saatavilla {:value    "17.12.2015"
                                                                                       :modified 170}
                                                                          :kuvaus     {:value    "Rouheaa"
                                                                                       :modified 100}}}
                                                 }}]})
    (fact "Waste ads: one row"
      (waste-ads/waste-ads "753-R") => '({:modified  2222,
                                          :materials ({:kuvaus     "Rouheaa",
                                                       :saatavilla "17.12.2015",
                                                       :yksikko    "kg", :maara "2", :aines "Sora"}),
                                          :contact   {:email "bob@reboot.tv", :phone "12345", :name "Bob"},
                                          :municipality "753"}))
    (fact "No ads for 753-YA"
      (waste-ads/waste-ads "753-YA") => '())
    (mongo/insert :applications
                  {:_id          "LP-NO-NAME"
                   :organization "753-R"
                   :municipality "753"
                   :documents    [
                                  {:schema-info {:name "rakennusjateselvitys"}
                                   :data        {:contact            {:name  {:value ""}
                                                                      :phone {:value    "12345"
                                                                              :modified 2}
                                                                      :email {:value    "bob@reboot.tv"
                                                                              :modified 2222}}
                                                 :availableMaterials {:0 {:aines      {:value    "Sora"
                                                                                       :modified 100}
                                                                          :maara      {:value    "2"
                                                                                       :modified 110}
                                                                          :yksikko    {:value    "kg"
                                                                                       :modified 200}
                                                                          :saatavilla {:value    "17.12.2015"
                                                                                       :modified 170}
                                                                          :kuvaus     {:value    "Rouheaa"
                                                                                       :modified 100}}}
                                                 }}]})
    (mongo/insert :applications
                  {:_id          "LP-NO-PHONE-EMAIL"
                   :organization "753-R"
                   :municipality "753"
                   :documents    [
                                  {:schema-info {:name "rakennusjateselvitys"}
                                   :data        {:contact            {:name  {:value    "Bob"
                                                                              :modified 21}
                                                                      :phone {:value ""}
                                                                      :email {:value ""}}
                                                 :availableMaterials {:0 {:aines      {:value    "Sora"
                                                                                       :modified 100}
                                                                          :maara      {:value    "2"
                                                                                       :modified 110}
                                                                          :yksikko    {:value    "kg"
                                                                                       :modified 200}
                                                                          :saatavilla {:value    "17.12.2015"
                                                                                       :modified 170}
                                                                          :kuvaus     {:value    "Rouheaa"
                                                                                       :modified 100}}}
                                                 }}]})
    (mongo/insert :applications
                  {:_id          "LP-NO-AINES"
                   :organization "753-R"
                   :municipality "753"
                   :documents    [
                                  {:schema-info {:name "rakennusjateselvitys"}
                                   :data        {:contact            {:name  {:value    "Bob"
                                                                              :modified 1}
                                                                      :phone {:value    "12345"
                                                                              :modified 2}
                                                                      :email {:value    "bob@reboot.tv"
                                                                              :modified 2222}}
                                                 :availableMaterials {:0 {:aines      {:value ""}
                                                                          :maara      {:value    "2"
                                                                                       :modified 110}
                                                                          :yksikko    {:value    "kg"
                                                                                       :modified 200}
                                                                          :saatavilla {:value    "17.12.2015"
                                                                                       :modified 170}
                                                                          :kuvaus     {:value    "Rouheaa"
                                                                                       :modified 100}}}
                                                 }}]})
    (mongo/insert :applications
                  {:_id          "LP-NO-MAARA"
                   :organization "753-R"
                   :municipality "753"
                   :documents    [
                                  {:schema-info {:name "rakennusjateselvitys"}
                                   :data        {:contact            {:name  {:value    "Bob"
                                                                              :modified 1}
                                                                      :phone {:value    "12345"
                                                                              :modified 2}
                                                                      :email {:value    "bob@reboot.tv"
                                                                              :modified 2222}}
                                                 :availableMaterials {:0 {:aines      {:value    "Sora"
                                                                                       :modified 100}
                                                                          :maara      {:value ""}
                                                                          :yksikko    {:value    "kg"
                                                                                       :modified 200}
                                                                          :saatavilla {:value    "17.12.2015"
                                                                                       :modified 170}
                                                                          :kuvaus     {:value    "Rouheaa"
                                                                                       :modified 100}}}
                                                 }}]})
    (fact "Ads only include proper information"
      (count (waste-ads/waste-ads "753-R")) => 1)
    (mongo/insert :applications
                  {:_id          "LP-2"
                   :organization "753-R"
                   :municipality "753"
                   :documents    [
                                  {:schema-info {:name "laajennettuRakennusjateselvitys"}
                                   :data        {:contact            {:name  {:value    "Dot"
                                                                              :modified 1}
                                                                      :phone {:value    "12345"
                                                                              :modified 2}
                                                                      :email {:value    "dot@reboot.tv"
                                                                              :modified 2221}}
                                                 :availableMaterials {:0 {:aines      {:value    "Kivi"
                                                                                       :modified 12345}
                                                                          :maara      {:value    "100"
                                                                                       :modified 110}
                                                                          :yksikko    {:value    "tonni"
                                                                                       :modified 200}
                                                                          :saatavilla {:value    "17.12.2015"
                                                                                       :modified 170}
                                                                          :kuvaus     {:value    "Rouheaa"
                                                                                       :modified 100}}
                                                                      :1 {:aines      {:value ""}
                                                                          :maara      {:value    "100"
                                                                                       :modified 999999}
                                                                          :yksikko    {:value    "tonni"
                                                                                       :modified 200}
                                                                          :saatavilla {:value    "17.12.2015"
                                                                                       :modified 170}
                                                                          :kuvaus     {:value    "Ignored"
                                                                                       :modified 100}}
                                                                      :2 {:aines      {:value "Puu"}
                                                                          :maara      {:value    "8"
                                                                                       :modified 88}
                                                                          :yksikko    {:value    "m3"
                                                                                       :modified 200}
                                                                          :saatavilla {:value    "20.12.2015"
                                                                                       :modified 170}
                                                                          :kuvaus     {:value ""}}}
                                                 }}]})
    (fact "Two ads"
      (waste-ads/waste-ads "753-R")
      => '({:modified 999999,
            :materials
                      ({:kuvaus "Rouheaa", :saatavilla "17.12.2015", :yksikko "tonni", :maara "100", :aines "Kivi"}
                        {:kuvaus "", :saatavilla "20.12.2015", :yksikko "m3", :maara "8", :aines "Puu"}),
            :contact  {:email "dot@reboot.tv", :phone "12345", :name "Dot"},
            :municipality "753"}
            {:modified  2222,
             :materials ({:kuvaus "Rouheaa", :saatavilla "17.12.2015", :yksikko "kg", :maara "2", :aines "Sora"}),
             :contact   {:email "bob@reboot.tv", :phone "12345", :name "Bob"},
             :municipality "753"}))
    (fact "Ad list size limit"
      (doseq [id (range 110)]
        (mongo/insert :applications
                      {:_id          (str "LP-FILL-" id)
                       :organization "753-R"
                       :municipality "753"
                       :documents    [
                                      {:schema-info {:name "rakennusjateselvitys"}
                                       :data        {:contact            {:name  {:value    "Bob"
                                                                                  :modified 1}
                                                                          :phone {:value    "12345"
                                                                                  :modified 2}
                                                                          :email {:value    "bob@reboot.tv"
                                                                                  :modified 2222}}
                                                     :availableMaterials {:0 {:aines      {:value    "Sora"
                                                                                           :modified 100}
                                                                              :maara      {:value    "2"
                                                                                           :modified 110}
                                                                              :yksikko    {:value    "kg"
                                                                                           :modified 200}
                                                                              :saatavilla {:value    "17.12.2015"
                                                                                           :modified 170}
                                                                              :kuvaus     {:value    "Rouheaa"
                                                                                           :modified 100}}}
                                                     }}]}))
      (count (waste-ads/waste-ads "753-R")) => 100
      (count (waste-ads/waste-ads nil)) => 100
      (count (waste-ads/waste-ads "")) => 100)

    (facts "Validators"
      (fact "Bad format: nil" (local-org-api/valid-feed-format {:data {:fmt nil}})
        => {:ok false, :text "error.invalid-feed-format"})
      (fact "Bad format: ''" (local-org-api/valid-feed-format {:data {:fmt ""}})
        => {:ok false, :text "error.invalid-feed-format"})
      (fact "Bad format: foo" (local-org-api/valid-feed-format {:data {:fmt "foo"}})
        => {:ok false, :text "error.invalid-feed-format"})

      (fact "Good format: rSs" (local-org-api/valid-feed-format {:data {:fmt "rSs"}})
        => nil)
      (fact "Good format: jsON" (local-org-api/valid-feed-format {:data {:fmt "jsON"}})
        => nil)
      (fact "Valid organization 753-R" (local-org-api/valid-org {:data {:org "753-R"}})
        => nil)
      (fact "Valid organization 753-r" (local-org-api/valid-org {:data {:org "753-r"}})
        => nil)
      (fact "Empty organization is valid " (local-org-api/valid-org {:data {}})
        => nil)
      (fact "Invalid organization 888-X" (local-org-api/valid-org {:data {:org "888-X"}})
        => {:ok false, :text "error.organization-not-found"})
      )))



(facts allowed-autologin-ips-for-organization

  (fact "applicant is not authorized"
    (query pena :allowed-autologin-ips-for-organization :org-id "753-R") => unauthorized?)
  (fact "authority is not authorized"
    (query sonja :allowed-autologin-ips-for-organization :org-id "753-R") => unauthorized?)
  (fact "authorityadmin is not authorized"
    (query sipoo :allowed-autologin-ips-for-organization :org-id "753-R") => unauthorized?)
  (fact "admin is authorized"
    (query admin :allowed-autologin-ips-for-organization :org-id "753-R") => ok?)

  (fact "no allowed autologin ips for sipoo is empty"
    (-> (query admin :allowed-autologin-ips-for-organization :org-id "753-R") :ips) => empty?)

  (fact "four allowed autologin ips for porvoo"
    (-> (query admin :allowed-autologin-ips-for-organization :org-id "638-R") :ips count) => 4))

(facts update-allowed-autologin-ips

  (fact "applicant is not authorized"
    (command pena :update-allowed-autologin-ips :org-id "753-R" :ips []) => unauthorized?)
  (fact "authority is not authorized"
    (command sonja :update-allowed-autologin-ips :org-id "753-R" :ips []) => unauthorized?)
  (fact "authorityadmin is not authorized"
    (command sipoo :update-allowed-autologin-ips :org-id "753-R" :ips []) => unauthorized?)
  (fact "admin is authorized"
    (command admin :update-allowed-autologin-ips :org-id "753-R" :ips []) => ok?)

  (fact "autologin ips are updated for sipoo"
    (let [ips (repeatedly 5 #(ssg/generate ssc/IpAddress))]
      (command admin :update-allowed-autologin-ips :org-id "753-R" :ips ips) => ok?
      (-> (query admin :allowed-autologin-ips-for-organization :org-id "753-R") :ips) => ips))

  (fact "there are still four allowed autologin ips for porvoo"
    (-> (query admin :allowed-autologin-ips-for-organization :org-id "638-R") :ips count) => 4)

  (fact "trying to update with invalid ip address"
    (command admin :update-allowed-autologin-ips :org-id "753-R" :ips ["inv.val.id.ip"]) => (partial expected-failure? :error.invalid-ip)))


(facts update-organization-name

  (fact "admin is authorized"
    (command admin :update-organization-name :org-id "753-R" :name {:fi "modified"}) => ok?)

  (fact "authorityAdmin of the same organization is authorized"
    (command sipoo :update-organization-name :org-id "753-R" :name {:fi "modified"}) => ok?)

  (fact "authorityAdmin of different organization is not authorized"
    (command sipoo :update-organization-name :org-id "186-R" :name {:fi "modified"}) => unauthorized?)

  (fact "authority is not authorized"
    (command sonja :update-organization-name :org-id "753-R" :name {:fi "modified"}) => unauthorized?)

  (fact "applicant is not authorized"
    (command pena :update-organization-name :org-id "753-R" :name {:fi "modified"}) => unauthorized?)

  (fact "illegal name map"
    (command admin :update-organization-name :org-id "753-R" :name "modified") => (partial expected-failure? :error.illegal-localization-value))

  (fact "illegal name key"
    (command admin :update-organization-name :org-id "753-R" :name {:suomi "modified"}) => (partial expected-failure? :error.illegal-localization-value))

  (fact "illegal name value type"
    (command admin :update-organization-name :org-id "753-R" :name {:fi {}}) => (partial expected-failure? :error.illegal-localization-value))

  (fact "empty name value"
    (command admin :update-organization-name :org-id "753-R" :name {:fi ""}) => (partial expected-failure? :error.empty-organization-name))

  (facts "organization name is changed only for languages that are specified in command data"
    (let [change-all-map (language-map i18n/supported-langs "modified")]
      (fact "all languages"
        (command admin :update-organization-name :org-id "753-R" :name change-all-map) => ok?
        (get-in (query sipoo :organization-by-user) [:organization :name]) => change-all-map)

      (fact "all but one language"
        (let [unchanged (first i18n/supported-langs)
              changed (rest i18n/supported-langs)
              change-map (language-map changed "modified again")
              expected-response (merge (select-keys change-all-map [unchanged])
                                       change-map)]
          (command admin :update-organization-name :org-id "753-R" :name change-map) => ok?
          (get-in (query sipoo :organization-by-user) [:organization :name]) => expected-response)))))


(facts organization-names-by-user
  (let [organization-name-map (select-keys {:fi "Sipoo" :sv "Sibbo" :en "Sipoo"}
                                           i18n/supported-langs)]
    (command admin :update-organization-name :org-id "753-R"
             :name organization-name-map) => ok?

    (fact "query returns organization names for all languages"
      (query sipoo :organization-name-by-user) => {:ok true :id "753-R"
                                                   :name organization-name-map})))

(def sipoo-handler-roles (->> (query sipoo :organization-by-user) :organization :handler-roles))

(facts upsert-handler-role
  (fact "cannot update with unexisting id"
    (command sipoo :upsert-handler-role
             :roleId "abba1111111111111666acdc"
             :name {:fi "Not found id kasittelija"
                    :sv "Not found id handlaggare"
                    :en "Not found id handler"}) => (partial expected-failure? :error.unknown-handler))

  (fact "cannot update with non-string name"
    (command sipoo :upsert-handler-role
             :roleId (get-in sipoo-handler-roles [1 :id])
             :name {:fi {:handler "non-string kasittelija"}
                    :sv "Updated Swedish handlaggare"
                    :en "Updated English handler"}) => (partial expected-failure? :error.illegal-localization-value))

  (fact "cannot update with missing handler name localization"
    (command sipoo :upsert-handler-role
             :roleId (get-in sipoo-handler-roles [1 :id])
             :name {:fi "Updated Finnish kasittelija"
                    :sv "Updated Swedish handlaggare"
                    :en ""}) => (partial expected-failure? :error.missing-parameters))

  (facts "update existing handler role"
    (command sipoo :upsert-handler-role
             :roleId (get-in sipoo-handler-roles [0 :id])
             :name {:fi "Updated Finnish kasittelija"
                    :sv "Updated Swedish handlaggare"
                    :en "Updated English handler"}) => ok?

    (let [handler-roles (get-in (query sipoo :organization-by-user) [:organization :handler-roles])]
      (fact "handler role is updated"
        (first handler-roles) => {:id   (get-in sipoo-handler-roles [0 :id])
                                  :name {:fi "Updated Finnish kasittelija"
                                         :sv "Updated Swedish handlaggare"
                                         :en "Updated English handler"}
                                  :general true})
      (fact "no new handlers added"
        (count handler-roles) => (count sipoo-handler-roles))

      (fact "other handler roles not changed"
        (second handler-roles) => (second sipoo-handler-roles))))

  (facts "insert new handler role"
    (command sipoo :upsert-handler-role
             :name {:fi "New Finnish kasittelija"
                    :sv "New Swedish handlaggare"
                    :en "New English handler"}) => ok?

    (let [handler-roles (get-in (query sipoo :organization-by-user) [:organization :handler-roles])]
      (fact "one handler is added"
        (count handler-roles) => (inc (count sipoo-handler-roles)))

      (fact "handler name is set"
        (:name (last handler-roles)) => {:fi "New Finnish kasittelija"
                                         :sv "New Swedish handlaggare"
                                         :en "New English handler"})

      (fact "id is set"
        (:id (last handler-roles)) => ss/not-blank?)

      (fact "other handler-roles not updated"
        (take 2 handler-roles) => [{:id   (get-in sipoo-handler-roles [0 :id])
                                    :name {:fi "Updated Finnish kasittelija"
                                           :sv "Updated Swedish handlaggare"
                                           :en "Updated English handler"}
                                    :general true}
                                   (second sipoo-handler-roles)]))))

(def sipoo-handler-roles (->> (query sipoo :organization-by-user) :organization :handler-roles))

(facts toggle-handler-role
  (fact "cannot disable with unexisting id"
    (command sipoo :toggle-handler-role
             :roleId "abba1111111111111666acdc"
             :enabled false) => (partial expected-failure? :error.unknown-handler))

  (fact "cannot disable with blank id"
    (command sipoo :toggle-handler-role
             :roleId ""
             :enabled false) => (partial expected-failure? :error.missing-parameters))

  (fact "cannot disable general handler role"
        (command sipoo :toggle-handler-role
                 :enabled false
                 :roleId (-> sipoo-handler-roles first :id))
        => (partial expected-failure? :error.illegal-handler-role))

  (fact "disable handler role"
        (command sipoo :toggle-handler-role
                 :enabled false
                 :roleId (-> sipoo-handler-roles second :id))=> ok?

        (let [handler-roles (get-in (query sipoo :organization-by-user) [:organization :handler-roles])]
          (fact "no handler roles added or deleted"
                (count handler-roles) => (count sipoo-handler-roles))

          (fact "second handler is disabled"
                (-> handler-roles second :disabled) => true)

          (fact "second handler is not name and id is not edited"
                (-> handler-roles second (select-keys [:id :name]))
                => (-> sipoo-handler-roles second (select-keys [:id :name])))

          (fact "other handler-roles not updated"
                (first handler-roles) => (first sipoo-handler-roles))))

  (fact "enable handler role"
        (command sipoo :toggle-handler-role
                 :enabled true
                 :roleId (-> sipoo-handler-roles second :id)) => ok?)
  (fact "second handler is now enabled"
        (-> (get-in (query sipoo :organization-by-user) [:organization :handler-roles])
            second :disabled) => false))

(facts create-organization
  (fact "organization already exists"
    (command admin :create-organization :org-id "753-R" :municipality "666" :name "Manalan rakennusvalvonta" :permit-types ["R" "P"]) => (partial expected-failure? :error.organization-already-exists))

  (fact "duplicate scope"
    (command admin :create-organization :org-id "666-R" :municipality "753" :name "Manalan rakennusvalvonta" :permit-types ["R" "P"]) => (partial expected-failure? :error.organization.duplicate-scope))

  (fact "invalid permit type"
    (command admin :create-organization :org-id "666-R" :municipality "666" :name "Manalan rakennusvalvonta" :permit-types ["G"]) => (partial expected-failure? :error.invalid-permit-type))

  (fact "success"
    (command admin :create-organization :org-id "666-R" :municipality "666" :name "Manalan rakennusvalvonta" :permit-types ["R" "P"]) => ok?)

  (fact "organization created succesfully"
    (let [result (query admin :organization-by-id :organizationId "666-R")
          org    (:data result)]
      (:id org) => "666-R"
      (:name org) => {:en "Manalan rakennusvalvonta" :fi "Manalan rakennusvalvonta" :sv "Manalan rakennusvalvonta"}
      (:scope org) => [{:inforequest-enabled false,
                        :municipality "666",
                        :new-application-enabled false,
                        :open-inforequest false,
                        :open-inforequest-email "",
                        :opening nil, :permitType "R"}
                       {:inforequest-enabled false,
                        :municipality "666",
                        :new-application-enabled false,
                        :open-inforequest false,
                        :open-inforequest-email "",
                        :opening nil,
                        :permitType "P"}])))

(defn update-docstore-info [org-id docStoreInUse docTerminalInUse documentPrice organizationDescription]
  (command admin :update-docstore-info
           :org-id org-id
           :docStoreInUse docStoreInUse
           :docTerminalInUse docTerminalInUse
           :documentPrice documentPrice
           :organizationDescription organizationDescription))

(defn get-docstore-info [org-id]
  (let [result (query admin :organization-by-id :organizationId org-id)]
    (if (ok? result)
      (-> result :data :docstore-info)
      result)))

(facts update-docstore-info

  (fact "calling does not change other organization data"
    (let [org (:data (query admin :organization-by-id :organizationId "753-R"))]
      (update-docstore-info "753-R" true false 100 (i18n/supported-langs-map (constantly "Description"))) => ok?
      (dissoc org :docstore-info)
      => (-> (query admin :organization-by-id :organizationId "753-R")
             :data
             (dissoc :docstore-info))))

  (fact "calling updates organization's docstore info"
    (update-docstore-info "753-R" true false 100 {:fi "Kuvaus" :sv "Beskrivning" :en "Description"}) => ok?
    (get-docstore-info "753-R")
    => {:docStoreInUse true
        :docTerminalInUse false
        :allowedTerminalAttachmentTypes []
        :documentPrice 100
        :organizationDescription {:fi "Kuvaus" :sv "Beskrivning" :en "Description"}
        :documentRequest {:enabled false
                          :email ""
                          :instructions {:en "" :fi "" :sv ""}}})

  (fact "can't set negative document price"
    (update-docstore-info "753-R" true false -100 "Description")
    => (partial expected-failure? :error.illegal-number))

  (fact "can't set decimal document price"
    (update-docstore-info "753-R" true true 1.0 "Description")
    => (partial expected-failure? :error.illegal-number)))

(facts set-docterminal-attachment-type
  (fact "only authority admin can call"
    (command sonja :set-docterminal-attachment-type :attachmentType "osapuolet.cv" :enabled false)
    => (partial expected-failure? :error.unauthorized))

  (fact "cannot set nonsense types"
    (command sipoo :set-docterminal-attachment-type :attachmentType "foo" :enabled true)
    => (partial expected-failure? :error.illegal-value:schema-validation))

  (fact "Docterminal not enabled"
    (query sipoo :docterminal-enabled)
    => (partial expected-failure? :error.docterminal-not-enabled))

  (fact "cannot set unless docterminal is enabled"
    (command sipoo :set-docterminal-attachment-type :attachmentType "osapuolet.cv" :enabled true)
    => (partial expected-failure? :error.docterminal-not-enabled))

  (fact "calling with proper type adds type to organization's docstore-info"
    (update-docstore-info "753-R" true true 100 {:fi "Kuvaus" :sv "Beskrivning" :en "Description"}) => ok?

    (command sipoo :set-docterminal-attachment-type :attachmentType "osapuolet.cv" :enabled true) => ok?
    (-> "753-R" get-docstore-info :allowedTerminalAttachmentTypes) => ["osapuolet.cv"]

    (command sipoo :set-docterminal-attachment-type :attachmentType "all" :enabled true) => ok?
    (-> "753-R" get-docstore-info :allowedTerminalAttachmentTypes) => local-org-api/allowed-attachments

    (command sipoo :set-docterminal-attachment-type :attachmentType "all" :enabled false) => ok?
    (-> "753-R" get-docstore-info :allowedTerminalAttachmentTypes) => [])

  (fact "Docterminal enabled"
    (query sipoo :docterminal-enabled)
    => ok?))

(fact "Document request info"
  (fact "only authority admin can call"
    (command sonja :set-document-request-info :enabled false :email "a@b.c" :instructions "Instructions")
    => (partial expected-failure? :error.unauthorized))

  (fact "email must be valid"
    (command sipoo :set-document-request-info :enabled false :email "not-valid-email" :instructions "Instructions")
    => (partial expected-failure? :error.email))

  (fact "setting works"
    (command sipoo :set-document-request-info :enabled true :email "a@b.c"
             :instructions {:en "Instructions" :fi "Ohjeet" :sv "Anvisningar"}) => ok?
    (-> (query sipoo :document-request-info)
        :documentRequest)
    => {:enabled true
        :email "a@b.c"
        :instructions {:en "Instructions" :fi "Ohjeet" :sv "Anvisningar"}}))

(fact "organizations bulletin settings"
  (facts "query setting"
    (fact "sipoo"
      (let [result (query sipoo :user-organization-bulletin-settings)]
        result => ok?
        (:bulletin-scopes result)  => [{:permitType "R"
                                            :municipality "753"
                                            :bulletins {:enabled true
                                                        :url "http://localhost:8000/dev/julkipano"
                                                        :notification-email "sonja.sibbo@sipoo.fi"
                                                        :descriptions-from-backend-system false}}]))

    (facts "oulu"
      (query oulu :user-organization-bulletin-settings)
      => (partial expected-failure? :error.bulletins-not-enebled-for-scope))

    (facts "authority"
      (query sonja :user-organization-bulletin-settings) => unauthorized?))

  (facts "update-organization-bulletin-scope"
    (fact "sipoo R"
      (command sipoo :update-organization-bulletin-scope
               :permitType "R"
               :municipality "753"
               :notificationEmail "pena@example.com") => ok?

      (command sipoo :update-organization-bulletin-scope
               :permitType "R"
               :municipality "753"
               :descriptionsFromBackendSystem "foobar") => (partial expected-failure? :error.invalid-value)

      (command sipoo :update-organization-bulletin-scope
               :permitType "R"
               :municipality "753"
               :descriptionsFromBackendSystem true) => ok?

      (map :permitType (:bulletin-scopes (query sipoo :user-organization-bulletin-settings))) => ["R"])

    (fact "sipoo P"
      (command sipoo :update-organization-bulletin-scope
               :permitType "P"
               :municipality "753"
               :notificationEmail "pena@example.com")
      => (partial expected-failure? :error.bulletins-not-enebled-for-scope)

      (map :permitType (:bulletin-scopes (query sipoo :user-organization-bulletin-settings))) => ["R"]))

  (facts "enable organization bulletins"

    (fact "sipoo P - enable"
      (command admin :update-organization
               :permitType "P"
               :municipality "753"
               :inforequestEnabled true
               :applicationEnabled true
               :openInforequestEnabled false
               :openInforequestEmail false
               :opening nil
               :bulletinsEnabled true
               :bulletinsUrl nil) => ok?

      (fact "is enabled"

        (:bulletin-scopes (query sipoo :user-organization-bulletin-settings))
        => [{:permitType "R"
             :municipality "753"
             :bulletins {:enabled true
                         :url "http://localhost:8000/dev/julkipano"
                         :notification-email "pena@example.com"
                         :descriptions-from-backend-system true}}
            {:permitType "P"
             :municipality "753"
             :bulletins {:enabled true
                         :url ""}}]

        (command sipoo :update-organization-bulletin-scope
                 :permitType "P"
                 :municipality "753"
                 :notificationEmail "rane@example.com") => ok?))

    (fact "sipoo P - update url"
      (command admin :update-organization
               :permitType "P"
               :municipality "753"
               :inforequestEnabled true
               :applicationEnabled true
               :openInforequestEnabled false
               :openInforequestEmail false
               :opening nil
               :bulletinsEnabled true
               :bulletinsUrl "http://foo.my.url") => ok?

      (:bulletin-scopes (query sipoo :user-organization-bulletin-settings))
      => [{:permitType "R"
           :municipality "753"
           :bulletins {:enabled true
                       :url "http://localhost:8000/dev/julkipano"
                       :notification-email "pena@example.com"
                       :descriptions-from-backend-system true}}
          {:permitType "P"
           :municipality "753"
           :bulletins {:enabled true
                       :url "http://foo.my.url"
                       :notification-email "rane@example.com"}}])

    (fact "sipoo - update texts"
      (command sipoo :upsert-organization-local-bulletins-text
               :lang "fi" :key "heading1" :value "Sipoo") => {:ok true :valid true}
      (command sipoo :upsert-organization-local-bulletins-text
               :lang "fi" :key "heading2" :value "Rak.valv.julkipanolistat") => {:ok true :valid true}
      (command sipoo :upsert-organization-local-bulletins-text
               :lang "fi" :key "caption" :value "Sipoo") => {:ok true :valid false}
      (command sipoo :upsert-organization-local-bulletins-text
               :lang "fi" :key "caption" :index 0 :value "Sipoo123") => {:ok true :valid true}
      (command sipoo :remove-organization-local-bulletins-caption :lang "fi" :index 1) => ok?
      (command sipoo :remove-organization-local-bulletins-caption :lang "fi" :index 1) => ok?
      (-> (query sipoo :user-organization-bulletin-settings) :local-bulletins-page-texts :fi)
      => {:heading1 "Sipoo"
          :heading2 "Rak.valv.julkipanolistat"
          :caption ["Sipoo123"]})

    (fact "sipoo P - disable"
      (command admin :update-organization
               :permitType "P"
               :municipality "753"
               :inforequestEnabled true
               :applicationEnabled true
               :openInforequestEnabled false
               :openInforequestEmail false
               :opening nil
               :bulletinsEnabled false
               :bulletinsUrl "http://foo.my.url") => ok?

      (fact "is disabled"
        (command sipoo :update-organization-bulletin-scope
                 :permitType "P"
                 :municipality "753"
                 :notificationEmail "pena@example.com")
        => (partial expected-failure? :error.bulletins-not-enebled-for-scope)

        (map :permitType (:bulletin-scopes (query sipoo :user-organization-bulletin-settings))) => ["R"]))))

(facts "update-organization-backend-systems"
  (fact "Empty backend systems parameter is not allowed"
    (command admin :update-organization-backend-systems
             :org-id "564-YMP"
             :backend-systems {})
    => (partial expected-failure? :error.empty-map-parameters)))
