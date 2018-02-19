(ns lupapalvelu.copy-application-test
  (:require [clojure.data :refer [diff]]
            [clojure.walk :as walk]
            [lupapalvelu.application :as app]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.copy-application :refer :all]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.document.waste-schemas :as waste-schemas]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.organization :as org]
            [lupapalvelu.test-util :refer [walk-dissoc-keys]]
            [lupapalvelu.user :as usr]
            [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [sade.coordinate :as coord]
            [sade.env :as env]
            [sade.schema-generators :as ssg]
            [sade.util :as util]))

(testable-privates lupapalvelu.copy-application
                   handle-copy-action
                   handle-special-cases
                   clear-personal-information
                   check-valid-source-application)

(defn dissoc-ids-and-timestamps [application]
  (walk-dissoc-keys application :id :created :modified :ts))

(def users
  {"source-user"  {:id        "source-user"
                   :username  "source-user"
                   :firstName "Source"
                   :lastName  "User"
                   :role      :applicant}
   "copying-user" {:id        "copying-user"
                   :username  "copying-user"
                   :firstName "New"
                   :lastName  "User"
                   :role      :applicant}
   "iida"         {:id        "iida"
                   :firstName "Iida"
                   :lastName  "Invitee"
                   :username  "iida@example.com"
                   :email     "iida@example.com"}})

(defn add-invite [{:keys [auth id]}]
  {:auth (conj auth (auth/create-invite-auth (get users "source-user")
                                             {:id "iida"
                                              :firstName "Iida"
                                              :lastName "Invitee"
                                              :email "iida@example.com"}
                                             id
                                             "writer"
                                             1504879553883))})

(defn pointing-to-operations-of [application]
  (let [operations (conj (:secondaryOperations application) (:primaryOperation application))]
    (fn [op-infos]
      (every? (fn [op-info]
                (= (:name (util/find-by-id (:id op-info) operations))
                   (:name op-info)))
              op-infos))))
(when (env/feature? :copy-applications)
 (with-redefs [coord/convert (fn [_ _ _ coords] (str "converted " coords))
               usr/get-user-by-id (fn [id] (get users id))
               lupapalvelu.copy-application/empty-document-copy
               (fn [document _ & _]
                 document)]

   (let [source-user (get users "source-user")
         user        (get users "copying-user")
         invitee     (get users "iida")
         invitation (auth/create-invite-auth (get users "source-user")
                                             invitee
                                             "LP-123"
                                             :writer
                                             1504879553883)
         source-created 12345
         created 23456
         municipality "753"
         organization {:id "753-R"
                       :operations-attachments
                       {:kerrostalo-rivitalo
                        [["paapiirustus"
                          "asemapiirros"]
                         ["paapiirustus"
                          "pohjapiirustus"]]}}
         raw-new-app (app/make-application "LP-123" "kerrostalo-rivitalo" 0 0
                                           "address" "01234567891234" nil municipality
                                           organization false false ["message1" "message2"] source-user
                                           source-created nil)
         source-app (-> raw-new-app
                        (assoc :attachments (attachment/make-attachments 999 :draft
                                                                         [{:type (ssg/generate attachment/Type)}
                                                                          {:type (ssg/generate attachment/Type)}]
                                                                         nil false true true))
                        (update :documents conj {:id "extra-document"
                                                 :schema-info {:name "lisatiedot"}})
                        (update :auth conj invitation))]
     (facts new-application-copy
       (facts "No options specified"
         (let [new-app (new-application-copy source-app user organization created {})
               [new old _] (diff new-app source-app)]

           (fact "the application is copied almost verbatim"
                 (let [[only-new only-old _] (diff (dissoc-ids-and-timestamps new-app)
                                                   (dissoc-ids-and-timestamps source-app))]
                   (keys only-new) ; gives the same result as (keys only-old)
                   => (just [:auth :attachments :comments :history] :in-any-order))
                 (keys new)        ; gives the same result as (keys old)
                 => (just [:auth :attachments :comments :created :documents :history :id :modified :primaryOperation]
                          :in-any-order))

           (fact "the operation info of attachments in copied application points to the new copied operations"
                 (->> new-app :attachments (map :op)) => (pointing-to-operations-of new-app))

           (fact "application is created and modified now"
                 (:created new-app) => created
                 (:modified new-app) => created)

           (fact "application has new history"
                 (map :ts (:history new-app)) =>  (has every? #(= created %)))

           (fact "id references are updated"
                 (->> new :documents (map (comp :id :op :schema-info)) (remove nil?))
                 => (has every? #(= % (-> new :primaryOperation :id))))

           (fact "document creation time is the same as the application's"
                 (->> new-app :documents (map :created))
                 => (has every? #(= created %)))

           (fact "comments are not copied by default"
                 (:comments new-app) => empty?)

           (fact "attachments are overridden with those of a normal new application"
                 (= (dissoc-ids-and-timestamps (select-keys new-app [:attachments]))
                    (dissoc-ids-and-timestamps (select-keys raw-new-app [:attachments])))  => true?)

           (fact "user has writer role in application, previous writers are invited with writer role"
             (:auth source-app) => [(assoc source-user :role :writer :unsubscribed false)
                                    invitation]
             (:auth new-app) => [(assoc user :role :writer :unsubscribed false)
                                 (assoc source-user
                                        :role :reader
                                        :invite {:created created
                                                 :email nil
                                                 :inviter user
                                                 :role :writer
                                                 :user source-user})
                                 (let [invitee (dissoc invitee :email)]
                                   (assoc invitee
                                          :role :reader
                                          :invite {:created created
                                                   :inviter user
                                                   :email "iida@example.com"
                                                   :role :writer
                                                   :user invitee}))]
             )))

            (fact "If documents are not copied or overridden, those of normal new application are created"
                  (let [new-app (new-application-copy source-app user organization created
                                                      (update default-copy-options :whitelist
                                                              (partial remove #{:documents})))]
                    (dissoc-ids-and-timestamps (:documents new-app)) => (dissoc-ids-and-timestamps (:documents raw-new-app))))

            (against-background
             (app/make-application-id anything) => "application-id-753"
             (org/get-organization (:id organization)) => organization))))

 (facts "document intricacies"

   (fact "if waste plan does not match organization settings, it is replaced by correct one"
     (let [application {:created 12345
                        :schema-version 1
                        :primaryOperation {:name "kerrostalo-rivitalo"}}
           basic-waste-plan-doc {:schema-info
                                 {:name waste-schemas/basic-construction-waste-plan-name}}
           extended-waste-report-doc {:schema-info
                                      {:name waste-schemas/extended-construction-waste-report-name}}
           organization-with-basic-waste-plan {:extended-construction-waste-report-enabled false}
           organization-with-extended-waste-report {:extended-construction-waste-report-enabled true}]
       (-> (handle-special-cases basic-waste-plan-doc
                                 application
                                 organization-with-basic-waste-plan
                                 nil)
           :schema-info :name)
       => waste-schemas/basic-construction-waste-plan-name

       (-> (handle-special-cases basic-waste-plan-doc
                                 application
                                 organization-with-extended-waste-report
                                 nil)
           :schema-info :name)
       => waste-schemas/extended-construction-waste-report-name

       (-> (handle-special-cases extended-waste-report-doc
                                 application
                                 organization-with-extended-waste-report
                                 nil)
           :schema-info :name)
       => waste-schemas/extended-construction-waste-report-name

       (-> (handle-special-cases extended-waste-report-doc
                                 application
                                 organization-with-basic-waste-plan
                                 nil)
           :schema-info :name)
       => waste-schemas/basic-construction-waste-plan-name))

   (facts "handle-copy-action"
     (with-redefs [lupapalvelu.copy-application/empty-document-copy
                   (fn [document _ & _]
                     {:cleared? true})]
       (let [application {:created 12345
                          :schema-version 1
                          :primaryOperation {:name "kerrostalo-rivitalo"}}
             hakija-document {:schema-info {:name "hakija-r"}
                              :data (tools/create-document-data (schemas/get-schema 1 "hakija-r"))}
             pt-document {:schema-info {:name "paatoksen-toimitus-rakval"}
                          :data (tools/create-document-data (schemas/get-schema 1 "hakija-r"))}]
         (fact "documents with :copy-action defined as :clear in their schema are cleared"
           (-> (handle-copy-action pt-document application {} {}) :cleared?)
           => true)

         (fact "documents with no specified :copy-action are copied as such"
           (handle-copy-action hakija-document application {} {}) => hakija-document))))

   (facts "clear-personal-information"
     (let [application {:created 12345
                        :schema-version 1
                        :primaryOperation {:name "kerrostalo-rivitalo"}}
           empty-hakija-document {:schema-info {:name "hakija-r"}
                                  :data (tools/create-document-data (schemas/get-schema 1 "hakija-r"))}
           hakija-document (-> empty-hakija-document
                               (assoc-in [:_selected :value] "henkilo")
                               (assoc-in [:henkilo :userId :value] "ab12cd34")
                               (assoc-in [:henkilo :henkilotiedot :etunimi :value] "Erkki")
                               (assoc-in [:henkilo :henkilotiedot :sukunimi :value] "Esimerkki"))]

       (fact "user information with unauthorized user is cleared"
         (:henkilo (clear-personal-information hakija-document application)) => (:henkilo empty-hakija-document))

       (fact "for invited users that have not accepted invitation, the userId field is cleared to make the document valid"
             (let [cleared (clear-personal-information hakija-document
                                                       (assoc application :auth [{:id "ab12cd34"
                                                                                  :role "reader"
                                                                                  :invite {:id "ab12cd34"
                                                                                           :role "writer"}}]))]
               (-> cleared :henkilo :henkilotiedot :etunimi :value) => "Erkki"
               (-> cleared :henkilo :henkilotiedot :sukunimi :value) => "Esimerkki"
               (-> cleared :henkilo :userId :value) => ""))

       ))
   (facts "checks"

     (fact "change permits cannot be copied"
       (check-valid-source-application {:permitSubtype "muutoslupa"})
       => (contains {:text "error.application-invalid-permit-subtype"}))

     (fact "foreman and jatkoaika applications cannot be copied"
       (check-valid-source-application {:primaryOperation {:name "tyonjohtajan-nimeaminen"}})
       => (contains {:text "error.operations.copying-not-allowed"})
       (check-valid-source-application {:primaryOperation {:name "tyonjohtajan-nimeaminen-v2"}})
       => (contains {:text "error.operations.copying-not-allowed"})
       (check-valid-source-application {:primaryOperation {:name "jatkoaika"}})
       => (contains {:text "error.operations.copying-not-allowed"})))))
