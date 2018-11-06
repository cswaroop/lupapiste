(ns lupapalvelu.pate.verdict-interface
  "Accessor interface for verdict clien code. This interface should be
  used (and extended) instead of directly accessing application or
  mongo."
  (:require [lupapalvelu.pate.metadata :as metadata]
            [lupapalvelu.pate.verdict-common :as vc]
            [sade.strings :as ss]
            [sade.util :as util]))

(defn- bs-verdict-date
  "Backing system verdict date (from :paivamaarat map). Note that
  backing system verdicts are always published."
  [verdicts key]
  (->> verdicts
       (map (fn [{:keys [paatokset]}]
              (->> (map #(get-in % [:paivamaarat (keyword key)]) paatokset)
                   (remove nil?)
                   (first))))
       (flatten)
       (remove nil?)
       (sort)
       (last)))

(defn- bs-verdict-data
  "Backing system verdict accessor. If there are multiple verdicts,
  returns the first non-nil value for the given key."
  [verdicts key]
  (->> verdicts
       (map (fn [{:keys [paatokset]}]
              (map (fn [pt] (map key (:poytakirjat pt))) paatokset)))
       (flatten)
       (remove nil?)
       (first)))

(defn all-verdicts
  "All verdicts regardless of state or origin. Unwraps all metadata."
  [{:keys [verdicts pate-verdicts]}]
  (concat verdicts (metadata/unwrap-all pate-verdicts)))

(defn find-verdict
  "Find a verdict by id."
  [application verdict-id]
  (util/find-by-id verdict-id (all-verdicts application)))

(defn verdicts-by-backend-id
  "All verdicts filtered by backend Id."
  [{:keys [verdicts pate-verdicts]} backendId]
  (or
    (some->> verdicts
             (filter #(= (:kuntalupatunnus %) backendId)))
    (some->> pate-verdicts
             metadata/unwrap-all
             (filter #(= (get-in % [:data :kuntalupatunnus]) backendId)))))

(defn published-kuntalupatunnus
  "Search kuntalupatunnus from backing-system and published legacy
  verdicts. Returns the first one found."
  [{:keys [verdicts pate-verdicts]}]
  ;; Backing system verdict has always kuntalupatunnus
  (or (some-> verdicts first :kuntalupatunnus)
      (some->> pate-verdicts
               (filter vc/published?)
               (filter vc/legacy?)
               (map (util/fn-> :data :kuntalupatunnus metadata/unwrap))
               (remove ss/blank?)
               first)))

(defn kuntalupatunnukset
  "Search all backendIds from legacy verdicts and pate verdicts."
  [{:keys [verdicts pate-verdicts]}]
  (or (some->> verdicts
               (map :kuntalupatunnus)
               (remove nil?))
      (some->> pate-verdicts
               (map (util/fn-> :data :kuntalupatunnus metadata/unwrap))
               (remove ss/blank?))))

(defn latest-published-verdict-date
  "The latest verdict date (timestamp) of the published application
  verdicts. The first argument is either an application or a list
  of (any kind of) verdicts."
  ([application-or-verdicts]
   (latest-published-verdict-date application-or-verdicts identity))
  ([application-or-verdicts post-process]
   (let [verdicts (if (map? application-or-verdicts)
                    (all-verdicts application-or-verdicts)
                    (metadata/unwrap-all application-or-verdicts))]
     (some->> verdicts
              (filter vc/published?)
              (map vc/verdict-date)
              (filter integer?)
              seq
              (apply max)
              post-process))))

(defn latest-published-pate-verdict
  "Returns unwrapped published Pate verdict (or nil). If there are
  multiple Pate verdicts, the one with the latest published timestamp
  is returned."
  [{:keys [application]}]
  (some->> (:pate-verdicts application)
           (filter :published)
           (sort-by (comp :published :published))
           last
           metadata/unwrap-all))

(defn handler
  "Get verdict handler."
  [{:keys [verdicts pate-verdicts] :as application}]
  (if (some? pate-verdicts)
    (get-in (latest-published-pate-verdict {:application application}) [:data :handler])
    (bs-verdict-data verdicts :paatoksentekija)))

(defn lainvoimainen
  "Get lainvoimainen date. Takes optional date formatter as parameter."
  ([application]
    (lainvoimainen application nil))
  ([{:keys [verdicts pate-verdicts] :as application} post-process]
   (let [ts (if (some? pate-verdicts)
              (get-in (latest-published-pate-verdict {:application application})
                      [:data :lainvoimainen])
              (bs-verdict-date verdicts :lainvoimainen))]
     (if post-process
       (post-process ts)
       ts))))
