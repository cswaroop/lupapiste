(ns lupapalvelu.application-search
  (:require [taoensso.timbre :as timbre :refer [debug info warn error errorf]]
            [clojure.string :as s]
            [clojure.set :refer [rename-keys]]
            [monger.operators :refer :all]
            [monger.query :as query]
            [sade.core :refer :all]
            [sade.env :as env]
            [sade.property :as p]
            [sade.strings :as ss]
            [sade.util :as util]
            [sade.validators :as v]
            [lupapalvelu.application-meta-fields :as meta-fields]
            [lupapalvelu.application-utils :as app-utils]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.user :as user]
            [lupapalvelu.states :as states]
            [lupapalvelu.geojson :as geo]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.permit :as permit]))

;;
;; Query construction
;;

(def max-date 253402214400000)

(defn- make-free-text-query [filter-search]
  (let [search-keys [:address :verdicts.kuntalupatunnus :_applicantIndex :foreman :_id :documents.data.yritys.yritysnimi.value
                     :documents.data.henkilo.henkilotiedot.sukunimi.value]
        fuzzy       (ss/fuzzy-re filter-search)
        or-query    {$or (map #(hash-map % {$regex fuzzy $options "i"}) search-keys)}
        ops         (operations/operation-names filter-search)]
    (if (seq ops)
      (update-in or-query [$or] concat [{:primaryOperation.name {$in ops}}
                                        {:secondaryOperations.name {$in ops}}])
      or-query)))

(defn make-text-query [filter-search]
  {:pre [filter-search]}
  (cond
    (re-matches #"^([Ll][Pp])-\d{3}-\d{4}-\d{5}$" filter-search) {:_id (ss/upper-case filter-search)}
    (re-matches p/property-id-pattern filter-search) {:propertyId (p/to-property-id filter-search)}
    (re-matches v/rakennustunnus-pattern filter-search) {:buildings.nationalId filter-search}
    :else (make-free-text-query filter-search)))

(def applicant-application-states
  {:state {$in ["open" "submitted" "sent" "complementNeeded" "draft"]}})

(def authority-application-states
  {:state {$in ["submitted" "sent" "complementNeeded"]}})

(def no-handler "no-authority")

(defn- handler-email-to-id [handler]
  (if (ss/contains? handler "@")
    (:id (user/get-user-by-email handler))
    handler))

(defn- archival-query [user]
  (let [from-ts (->> (user/organization-ids-by-roles user #{:archivist})
                     (organization/earliest-archive-enabled-ts))
        base-query {$or [{$and [{:state {$in ["verdictGiven" "constructionStarted" "appealed" "inUse" "foremanVerdictGiven" "acknowledged"]}} {:archived.application nil} {:permitType {$ne "YA"}}]}
                         {$and [{:state {$in ["closed" "extinct" "foremanVerdictGiven" "acknowledged"]}} {:archived.completed nil} {:permitType {$ne "YA"}}]}
                         {$and [{:state {$in ["closed" "extinct"]}} {:archived.completed nil} {:permitType "YA"}]}]}]
    (if from-ts
      {$and [base-query
             {:submitted {$gte from-ts}}]}
      base-query)))

(defn- event-search [event]
  (and (not (empty? event))
       (not (empty? (:eventType event)))))

(defn make-query [query {:keys [searchText applicationType handlers tags companyTags organizations operations areas modifiedAfter event]} user]
  {$and
   (filter seq
     [query
      (when-not (ss/blank? searchText) (make-text-query (ss/trim searchText)))
      (when-let [modified-after (util/->long modifiedAfter)]
        {:modified {$gt modified-after}})
      (if (user/applicant? user)
        (case applicationType
          "unlimited"          {}
          "inforequest"        {:state {$in ["answered" "info"]}}
          "application"        applicant-application-states
          "construction"       {:state {$in ["verdictGiven" "constructionStarted"]}}
          "canceled"           {:state "canceled"}
          "verdict"            {:state {$in states/post-verdict-states}}
          "foremanApplication" (assoc applicant-application-states :permitSubtype "tyonjohtaja-hakemus")
          "foremanNotice"      (assoc applicant-application-states :permitSubtype "tyonjohtaja-ilmoitus")
          {:state {$ne "canceled"}})
        (case applicationType
          "unlimited"          {}
          "inforequest"        {:state {$in ["open" "answered" "info"]} :permitType {$ne permit/ARK}}
          "application"        authority-application-states
          "construction"       {:state {$in ["verdictGiven" "constructionStarted"]}}
          "verdict"            {:state {$in states/post-verdict-states}}
          "canceled"           {:state "canceled"}
          "foremanApplication" (assoc authority-application-states :permitSubtype "tyonjohtaja-hakemus")
          "foremanNotice"      (assoc authority-application-states :permitSubtype "tyonjohtaja-ilmoitus")
          "readyForArchival"   (archival-query user)
          "archivingProjects"  {:permitType permit/ARK :state {$nin [:archived :canceled]}}
          {$and [{:state {$ne "canceled"}
                  :permitType {$ne permit/ARK}}
                 {$or [{:state {$ne "draft"}}
                       {:organization {$nin (->> user :orgAuthz keys (map name))}}]}]}))
      (when-not (empty? handlers)
        (if ((set handlers) no-handler)
          {:handlers {$size 0}}
          (when-let [handler-ids (seq (remove nil? (map handler-email-to-id handlers)))]
            {$or [{:auth.id {$in handler-ids}}
                  {:handlers.userId {$in handler-ids}}]})))
      (when-not (empty? tags)
        {:tags {$in tags}})
      (when-not (empty? companyTags)
        {:company-notes {$elemMatch {:companyId (get-in user [:company :id]) :tags {$in companyTags}}}})
      (when-not (empty? organizations)
        {:organization {$in organizations}})
      (when (event-search event)
        (case (first (:eventType event))
          "warranty-period-end"                 {$and [{:warrantyEnd {"$gte" (or (:start event) 0)
                                                                     "$lt" (or (:end event) max-date)}}]}
          "license-period-start"                {$and [{:documents.data.tyoaika-alkaa-ms.value {"$gte" (or (:start event) 0)
                                                                                              "$lt" (or (:end event) max-date)}}]}
          "license-period-end"                  {$and [{:documents.data.tyoaika-paattyy-ms.value {"$gte" (or (:start event) 0)
                                                                                                "$lt" (or (:end event) max-date)}}]}
          "license-started-not-ready"           {$and [{:documents.data.tyoaika-alkaa-ms.value {"$lt" (now)}},
                                                       {:state {$ne "closed"}}]}
          "license-ended-not-ready"             {$and [{:documents.data.tyoaika-paattyy-ms.value {"$lt" (now)}},
                                                       {:state {$ne "closed"}}]}
          "announced-to-ready-state-not-ready"  {$and [{:closed {"$lt" (now)}},
                                                       {:state {$ne "closed"}},
                                                       {:permitType "YA"}]}))
      (cond
        (seq operations) {:primaryOperation.name {$in operations}}
        (and (user/authority? user) (not= applicationType "unlimited"))
        ; Hide foreman applications in default search, see LPK-923
        {:primaryOperation.name {$nin (cond-> ["tyonjohtajan-nimeaminen-v2"]
                                              (= applicationType "readyForArchival") (conj "aiemmalla-luvalla-hakeminen"))}})
      (when-not (empty? areas)
        (app-utils/make-area-query areas user))])})

;;
;; Fields
;;

(def- db-fields ; projection
  [:_comments-seen-by :_statements-seen-by :_verdicts-seen-by
   :_attachment_indicator_reset :address :applicant :attachments
   :auth :handlers.firstName :handlers.lastName :authorityNotice :comments :created :documents
   :foreman :foremanRole :infoRequest :location :modified :municipality
   :neighbors :permitType :permitSubtype :primaryOperation :state :statements
   :organization ; required for authorization checks
   :submitted :tasks :urgency :verdicts :archived])

(def- indicator-fields
  (map :field meta-fields/indicator-meta-fields))

(def- frontend-fields
  [:id :address :applicant :handlers :authorityNotice
   :infoRequest :kind :location :modified :municipality
   :primaryOperation :state :submitted :urgency :verdicts
   :foreman :foremanRole :permitType])

(defn- select-fields [application]
  (select-keys
    application
    (concat frontend-fields indicator-fields)))


(defn- enrich-row [app]
  (-> app
      (assoc :handlers (distinct (:handlers app))) ;; Each handler only once.
      app-utils/with-application-kind
      app-utils/location->object))

(def- sort-field-mapping {"applicant" :applicant
                          "handler" [:handlers.0.lastName :handlers.0.firstName]
                          "location" :address
                          "modified" :modified
                          "submitted" :submitted
                          "foreman" :foreman
                          "foremanRole" :foremanRole
                          "state" :state
                          "id" :_id})

(defn dir [asc] (if asc 1 -1))

(defn make-sort [{{:keys [field asc]} :sort}]
  (let [sort-field (sort-field-mapping field)]
    (cond
      (= "type" field) (array-map :permitSubtype (dir asc) :infoRequest (dir (not asc)))
      (nil? sort-field) {}
      (sequential? sort-field) (apply array-map (interleave sort-field (repeat (dir asc))))
      :else (array-map sort-field (dir asc)))))

(defn search [query db-fields sort skip limit]
  (try
    (mongo/with-collection "applications"
      (query/find query)
      (query/fields db-fields)
      (query/sort sort)
      (query/skip skip)
      (query/limit limit))
    (catch com.mongodb.MongoException e
      (errorf "Application search query=%s, sort=%s failed: %s" query sort e)
      (fail! :error.unknown))))


(defn applications-for-user [user {:keys [searchText] :as params}]
  (when (> (count searchText) (env/value :search-text-max-length))
    (fail! :error.search-text-is-too-long))
  (let [user-query  (domain/basic-application-query-for user)
        user-total  (mongo/count :applications user-query)
        query       (make-query user-query params user)
        query-total (mongo/count :applications query)
        skip        (or (util/->long (:skip params)) 0)
        limit       (or (util/->long (:limit params)) 10)
        apps        (search query db-fields (make-sort params) skip limit)
        rows        (map
                      (comp
                        select-fields
                        enrich-row
                        (partial meta-fields/with-indicators user)
                        #(domain/filter-application-content-for % user)
                        mongo/with-id)
                      apps)]
    {:userTotalCount user-total
     :totalCount query-total
     :applications rows}))

;;
;; Public API
;;

(defn public-fields [{:keys [municipality submitted] :as application}]
  {:municipality municipality
   :timestamp submitted
   :operation (app-utils/operation-description application :fi)
   :operationName (i18n/supported-langs-map (partial app-utils/operation-description application))})
