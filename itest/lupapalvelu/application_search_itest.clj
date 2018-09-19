(ns lupapalvelu.application-search-itest
  (:require [clojure.string :as s]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.factlet :refer :all]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.pate-itest-util :refer :all]
            [lupapalvelu.pate-legacy-itest-util :refer :all]
            [midje.sweet :refer :all]
            [sade.property :as p]
            [sade.strings :as ss]
            [sade.util :as util]))

(defn- num-of-results? [n response]
  (and
    (ok? response)
    (= n (count (get-in response [:data :applications])))))

(defn- total-count-is-n? [n response]
  (and
    (ok? response)
    (= n (get-in response [:data :totalCount]))))

(def no-results? (partial num-of-results? 0))
(def one-result? (partial num-of-results? 1))
(def zero-total? (partial total-count-is-n? 0))
(def one-total? (partial total-count-is-n? 1))

(defn- search [query-s]
  (datatables mikko :applications-search :searchText query-s))

(defn- search-total [query-s]
  (datatables mikko :applications-search-total-count :searchText query-s))

(facts* "Search"
  (apply-remote-minimal)

  (let [property-id (str sonja-muni "-123-0000-1234")
        application (create-and-submit-application mikko
                      :propertyId sipoo-property-id
                      :address "Hakukuja 123"
                      :propertyId (p/to-property-id property-id)
                      :operation "muu-uusi-rakentaminen") => truthy
        application-id (:id application)
        id-matches? (fn [response]
                      (and
                        (one-result? response)
                        (= (get-in response [:data :applications 0 :id]) application-id)))

        hakija-doc (first (domain/get-documents-by-subtype (:documents application) "hakija"))]

    (command mikko :set-current-user-to-document :id application-id :documentId (:id hakija-doc) :path "henkilo") => ok?
    (command mikko :add-operation :id application-id :operation "pientalo") => ok?
    (:secondaryOperations (query-application mikko application-id)) => seq

    (fact "Required fields for external LupapisteApi exists"
      (keys (get-in (search "") [:data :applications 0])) => (contains [:id :location
                                                                        :address :municipality
                                                                        :applicant :permitType] :gaps-ok :in-any-order))

    (facts "by applicant"
      (fact "no matches" (search "Pena") => no-results?)
      (fact "total count shows 0" (search-total "Pena") => zero-total?)
      (fact "one match" (search "Mikko") => id-matches?)
      (fact "total count shows 1" (search-total "Mikko") => one-total?))

    (facts "by address"
      (fact "no matches" (search "hakukatu") => no-results?)
      (fact "one match" (search "hakukuja") => id-matches?)
      (fact "one fuzzy match" (search "ku 2"))
      (fact "one fuzzy case-insensitive match " (search "JA 3")))

    (facts "by ID"
      (fact "no matches" (search (str "LP-" sonja-muni "-2010-00001")) => no-results?)
      (fact "one match" (search application-id) => id-matches?)
      (fact "one match - lower case query" (search (s/lower-case application-id)) => id-matches?)
      (fact "one fuzzy match" (search (subs application-id 3 7) ) => id-matches?))

    (facts "by property ID"
      (fact "no matches" (search (str sonja-muni "-123-0000-1230")) => no-results?)
      (fact "one match" (search property-id) => id-matches?))

    (facts "by verdict ID"
      (fact "no verdict, matches" (search "Hakup\u00e4\u00e4t\u00f6s-2014") => no-results?)
      (give-legacy-verdict sonja application-id :kuntalupatunnus "Hakup\u00e4\u00e4t\u00f6s-2014-1") => truthy
      (fact "no matches" (search "Hakup\u00e4\u00e4t\u00f6s-2014-2") => no-results?)
      (fact "one match" (search "Hakup\u00e4\u00e4t\u00f6s-2014") => id-matches?)
      (fact "Legacy draft"
        (let [{vid :verdict-id} (command sonja :new-legacy-verdict-draft :id application-id)]
          (fill-verdict sonja application-id vid :kuntalupatunnus "Hakup\u00e4\u00e4t\u00f6s-2014-3")
          (fact "no matches" (search "Hakup\u00e4\u00e4t\u00f6s-2014-2") => no-results?)
          (fact "one match" (search "Hakup\u00e4\u00e4t\u00f6s-2014") => id-matches?)
          (fact "also one match" (search "Hakup\u00e4\u00e4t\u00f6s-2014-3") => id-matches?))))

    (facts "by operation name"
      (fact "no matches" (search "vaihtolavan sijoittaminen") => no-results?)
      (fact "total count shows 0" (search-total "vaihtolavan sijoittaminen") => zero-total?)
      (fact "one match" (search "Muun kuin edell\u00e4 mainitun rakennuksen rakentaminen") => id-matches?)
      (fact "total count shows 1" (search-total "Muun kuin edell\u00e4 mainitun rakennuksen rakentaminen") => one-total?))

    (fact "by a very very long search term"
      (search (ss/join (repeat 50 "1234567890"))) => ok?
      (search (ss/join (repeat 51 "1234567890"))) => fail?)

    (facts "With municipality"
      (fact "Wrong municipality" (search "hakukuja, Vantaa") => no-results?)
      (fact "Correct municipality" (search "hakukuja, Sip") => id-matches?)
      (fact "Bad municipality" (search "hakukuja, Sipoot") => no-results?)
      (fact "Municipality must be the last part" (search "hakukuja, Sipoo 123") => no-results?)
      (fact "Municipality only" (search "Sipoo") => id-matches?)
      (fact "Internal punctuation does not matter" (search "  Hakukuja ___123,,,Sip  ") => id-matches?))

    (fact "Submitted application is returned by latest-applications"
      (let [resp (query pena :latest-applications)
            applications (:applications resp)
            application (first applications)]
        resp => ok?
        (count applications) => 1
        (fact "Contains only public information: municipality, operation and timestamp"
          (:municipality application) => sonja-muni
          (get-in application [:operationName :fi]) => "Muun kuin edell\u00e4 mainitun rakennuksen rakentaminen (navetta, liike-, toimisto-, opetus-, p\u00e4iv\u00e4koti-, palvelu-, hoitolaitos- tai muu rakennus)"
          (get-in application [:operationName :sv]) => seq
          (:timestamp application) => pos?
          (count (keys application)) => 4)))

    (fact "applications integration endpoint returns localized data"
      (let [resp (datatables mikko :applications)
            application (util/find-by-id application-id (:applications resp))
            {primary :primaryOperation secondaries :secondaryOperations} application
            {state-fi :stateNameFi state-sv :stateNameSv} application]

        (fact "Primary operation is localized"
          (:displayNameFi primary) => "Muun kuin edell\u00e4 mainitun rakennuksen rakentaminen (navetta, liike-, toimisto-, opetus-, p\u00e4iv\u00e4koti-, palvelu-, hoitolaitos- tai muu rakennus)"
          (:displayNameSv primary) => seq)

        (fact "Result contains all - and only - the documented keys"
          (keys application) => (just [:id :address :applicant :authority
                                       :drawings :infoRequest :location
                                       :modified :municipality
                                       :primaryOperation :secondaryOperations
                                       :permitType
                                       :state :stateNameFi :stateNameSv
                                       :submitted] :in-any-order)
          (keys (:location application)) => (just [:x :y] :in-any-order))

        (fact "Secondary operation is localized"
          (count secondaries) => 1
          (-> secondaries first :name) => "pientalo"
          (-> secondaries first :displayNameFi) =>
            "Asuinpientalon rakentaminen (enint\u00e4\u00e4n kaksiasuntoinen erillispientalo)"
          (-> secondaries first :displayNameSv) =>
            "Byggande av sm\u00e5hus (h\u00f6gst ett frist\u00e5ende sm\u00e5hus f\u00f6r tv\u00e5 bost\u00e4der)")

        (fact "Sonja gave verdict"
          state-fi => "P\u00e4\u00e4t\u00f6s annettu"
          state-sv => "Beslut givet"))))

  (let [property-id (str sonja-muni "-123-0000-1234")
        application (create-and-submit-application sonja
                      :address "Latokuja"
                      :propertyId "75341600550007"
                      :x 404369.304 :y 6693806.957
                      :operation "muu-uusi-rakentaminen") => truthy
        application-id (:id application)

        foreman-app (command sonja :create-foreman-application :id application-id :taskId "" :foremanRole "ei tiedossa" :foremanEmail "")

        application2 (create-and-submit-application sonja
                       :address "Hakukuja 10"
                       :propertyId (p/to-property-id property-id)
                       :operation "purkaminen")
        application-id2 (:id application2)
        modified (:modified application2)]

    (fact "No applications modified after the last one was created"
      (let [resp (datatables sonja :applications :modifiedAfter modified)]
        resp => ok?
        (-> resp :applications count) => 0))

    (fact "One applications modified since just before the last one was created"
      (let [resp (datatables sonja :applications :modifiedAfter (dec modified))]
        resp => ok?
        (-> resp :applications count) => 1))

    (count (get-in (datatables sonja :applications-search :handlers [sonja-id]) [:data :applications])) => 2

    (let [{handler-id :id :as resp} (command sonja :upsert-application-handler :id application-id :roleId sipoo-general-handler-id :userId ronja-id)]

      (fact "Set handler"
        resp => ok?)

      (fact "Handler ID filter"
        (let [resp (datatables sonja :applications-search :handlers [ronja-id])]
          (count (get-in resp [:data :applications])) => 1
          (get-in (datatables sonja :applications-search :handlers [ronja-id]) [:data :applications 0 :id]) => application-id))

      (fact "Handler email filter"
        (let [resp (datatables sonja :applications-search :handlers [(email-for "ronja")])]
          (count (get-in resp [:data :applications])) => 1
          (get-in resp [:data :applications 0 :id]) => application-id))

      (command sonja :add-application-tags :id application-id :tags ["222222222222222222222222"]) => ok?
      (fact "$and query returns 1"
        (count (get-in (datatables sonja :applications-search :handlers [ronja-id] :tags ["222222222222222222222222"]) [:data :applications])) => 1)

      (fact "Remove handler"
        (command sonja :remove-application-handler :id application-id :handlerId handler-id) => ok?)

      (fact "$and query returns 0 when handler is returning 0 matches"
        (count (get-in (datatables sonja :applications-search :handlers [ronja-id] :tags ["222222222222222222222222"]) [:data :applications])) => 0))

    (fact "Tags filter"
      (get-in (datatables sonja :applications-search :tags ["222222222222222222222222"]) [:data :applications 0 :id]) => application-id)
    (facts "Organization areas"
      (let [body (:body (decode-response (upload-area sipoo)))
            features (get-in body [:areas :features])
            keskusta (first (filter (fn [feature]
                                      (= "Nikkil\u00e4" (get-in feature [:properties :nimi]))) features))]
        (fact "Area filter"
          (let [res (datatables sonja :applications-search :areas [(:id keskusta)])]
            (count (get-in res [:data :applications])) => 1
            (get-in res [:data :applications 0 :id]) => application-id))

        (fact "Combined"
          (let [res (datatables sonja :applications-search
                                :areas [(:id keskusta)]
                                :handlers [sonja-id]
                                :tags ["222222222222222222222222" "111111111111111111111111"])]
            (count (get-in res [:data :applications])) => 1
            (get-in res [:data :applications 0 :id]) => application-id))

        (fact "Area filter works with same id after shapefile is uploaded again"
          (upload-area sipoo)
          (let [res (datatables sonja :applications-search :areas [(:id keskusta)])]
            (count (get-in res [:data :applications])) => 1
            (get-in res [:data :applications 0 :id]) => application-id))))

    (fact "canceled application and foreman application"

      (command sonja :cancel-application :id application-id :text "test" :lang "fi") => ok?

      (let [{default-res :applications}   (datatables sonja :applications)
            {unlimited-res :applications} (datatables sonja :applications :applicationType "unlimited")
            default-ids (map :id default-res)
            all-ids (map :id unlimited-res)]
        (fact "are NOT returned with default search"
          (count default-res) => 2
          default-ids =not=> (contains application-id)
          default-ids =not=> (contains (:id foreman-app)))

        (fact "are returned with unlimited search"
          (count unlimited-res) => 4
          all-ids => (contains application-id)
          all-ids => (contains (:id foreman-app)))))

    (facts "limit, skip, sort"
      (let [limit 2
            {results :applications} (datatables sonja :applications :applicationType "unlimited"
                                      ; latest first, skip the absolute latest and return 2
                                       :sort {:field :id, :asc false} :skip 1 :limit limit)]

        (count results) => limit
        (fact "Application 2 was the last to be creaded, not in results" (map :id results) =not=> (contains application-id2))
        (fact "Foreman app was the second to last to be creaded" (-> results first :id) => (:id foreman-app))
        (fact "Application 1 was the third to last to be creaded" (-> results second :id) => application-id)))))
