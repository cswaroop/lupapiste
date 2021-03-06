(ns lupapalvelu.price-catalogues
  "A common interface for accessing price catalogues and related data"
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [lupapalvelu.time-util :refer [tomorrow day-before ->date
                                           ->date-str tomorrow-or-later?
                                           timestamp-after?]]
            [lupapalvelu.invoices :as invoices]
            [lupapalvelu.invoices.schemas :as invsc]
            [lupapalvelu.mongo :as mongo]
            [monger.operators :refer [$in $and $lt $gt]]
            [schema.core :as sc]
            [sade.core :refer [ok fail] :as sade]
            [sade.schemas :as ssc]
            [sade.util :refer [to-millis-from-local-date-string
                               to-finnish-date
                               find-first]]
            [taoensso.timbre :refer [trace tracef debug debugf info infof
                                     warn warnf error errorf fatal fatalf]]))

(defn fetch-price-catalogues [organization-id]
  (mongo/select :price-catalogues {:organization-id organization-id}))

(defn fetch-previous-published-price-catalogue
  [{:keys [valid-from organization-id] :as price-catalogue}]
  (debug ">> fetch-previous-published-price-catalogue catalogue: " price-catalogue)
  (if (and valid-from organization-id)
    (when-let [prev-catalogues (seq (mongo/select :price-catalogues {$and [{:organization-id organization-id}
                                                                           {:valid-from {$lt valid-from}}
                                                                           {:state "published"}]}))]
      (apply max-key :valid-from prev-catalogues))))

(defn fetch-next-published-price-catalogue
  [{:keys [valid-from organization-id] :as price-catalogue}]
  (debug ">> fetch-next-price-catalogue catalogue: " price-catalogue)
  (if (and valid-from organization-id)
    (when-let [next-catalogues (seq (mongo/select :price-catalogues {$and [{:organization-id organization-id}
                                                                           {:valid-from {$gt valid-from}}
                                                                           {:state "published"}]}))]
      (apply min-key :valid-from next-catalogues))))

(defn fetch-same-day-published-price-catalogues
  [{:keys [valid-from organization-id] :as price-catalogue}]
  (debug ">> fetch-same-day-price-catalogue catalogue: " price-catalogue)
  (when (and valid-from organization-id)
    (mongo/select :price-catalogues {$and [{:organization-id organization-id}
                                           {:valid-from valid-from}
                                           {:state "published"}]})))

(defn validate-price-catalogues [price-catalogues]
  (debug ">> validate-price-catalogues price-catalogues: " price-catalogues)
  (when (seq price-catalogues)
    (sc/validate [invsc/PriceCatalogue] price-catalogues)))

(def time-format (tf/formatter "dd.MM.YYYY"))

(defn validate-insert-price-catalogue-request [{{catalogue-request :price-catalogue} :data :as command}]
  (try
    (sc/validate invsc/PriceCatalogueInsertRequest catalogue-request)
    (if (not (tomorrow-or-later? (:valid-from-str catalogue-request)))
      (fail :error.price-catalogue.incorrect-date))

    (catch Exception e
      (warn "Invalid price catalogue request " (.getMessage e))
      (fail :error.invalid-price-catalogue))))

(defn ->price-catalogue-db
  [price-catalogue-req user organization-id]
  (debug ">> price-catalogue-db price-catalogue-request: " price-catalogue-req " organization-id: " organization-id " user: " user)
  {:rows (:rows price-catalogue-req)
   :valid-from (to-millis-from-local-date-string (:valid-from-str price-catalogue-req))
   :meta {:created (sade/now)
          :created-by (invsc/->invoice-user user)}
   :organization-id organization-id})

(defn with-id [price-catalogue]
  (assoc price-catalogue :id (mongo/create-id)))

(defn validate-price-catalogue [price-catalogue]
  (sc/validate invsc/PriceCatalogue price-catalogue))

(defn catalogue-with-valid-until-one-day-before-timestamp [timestamp catalogue]
  (debug ">> catalogue-with-valid-until-one-day-before-timestamp timestamp" timestamp " catalogue " (:id catalogue))
  (let [date (tc/from-long timestamp)
        timestamp-day-before (tc/to-long (day-before date))]
    (assoc catalogue :valid-until timestamp-day-before)))

(defn update-catalogue! [{:keys [id] :as catalogue}]
  (validate-price-catalogue catalogue)
  (mongo/update-by-id :price-catalogues id catalogue)
  id)

(defn update-previous-catalogue! [previous-catalogue {new-catalogue-start :valid-from :as new-catalogue}]
  (debug ">> update-previous-catalogue!")
  (if (and previous-catalogue
           (or (timestamp-after? (:valid-until previous-catalogue) new-catalogue-start)
               (not (:valid-until previous-catalogue))))
    (let [prev-catalogue-with-valid-until (catalogue-with-valid-until-one-day-before-timestamp new-catalogue-start previous-catalogue)]
      (update-catalogue! prev-catalogue-with-valid-until))))

(defn create-price-catalogue!
  [price-catalogue & [defaults]]
  (debug ">> create-price-catalogue! catalogue: " price-catalogue " defaults: " defaults)
  (let [catalogue-doc (->> price-catalogue
                           with-id
                           (merge defaults))]
    (->> catalogue-doc
         validate-price-catalogue
         (mongo/insert :price-catalogues))
    (:id catalogue-doc)))

(defn delete-catalogues! [catalogues]
  (doseq [{:keys [id]} catalogues]
    (when id
      (mongo/remove :price-catalogues id))))


(defn get-valid-price-catalogue-on-day [catalogues timestamp]
  (let [between-valid-from-and-valid-until (fn [{:keys [valid-from valid-until]}]
                                             (and (<= valid-from timestamp)
                                                  (or (not valid-until)
                                                      (<= timestamp valid-until))))
        [valid-catalogue :as valid-catalogues] (filter between-valid-from-and-valid-until catalogues)]
    (if (> (count valid-catalogues) 1)
      (error (format "Multiple valid catalogues [ids %s] found for the timestamp %s"
                     (pr-str (map :id valid-catalogues)) timestamp))
      valid-catalogue)))

(defn fetch-valid-catalogue [org-id timestamp]
  (debug ">> fetch-valid-catalogue org-id " org-id " timestamp: " timestamp " findate: " (to-finnish-date timestamp))
  (let [filter-published (fn [catalogues] (filter (fn [{:keys [state]}] (= state "published")) catalogues))]

    (-> (fetch-price-catalogues org-id)
        filter-published
        (get-valid-price-catalogue-on-day timestamp))))

(defn submitted-timestamp [application]
  (:ts (find-first #(= "submitted" (:state %)) (:history application))))
