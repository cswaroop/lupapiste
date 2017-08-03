(ns lupapalvelu.application-utils
  (:require [sade.strings :as ss]
            [lupapalvelu.organization :as org]
            [lupapalvelu.i18n :as i18n]
            [sade.strings :as s]
            [lupapalvelu.user :as usr]
            [lupapalvelu.mongo :as mongo]
            [monger.operators :refer :all]
            [lupapalvelu.permit :as permit]))


(defn operation-description
  "obtain the name of application's primary operation, taking into
  account that some legacy applications may not have a primary
  operation"
  [application lang]
  (let [primary-operation (-> application :primaryOperation :name)]
    (if primary-operation
      (i18n/localize lang "operations" primary-operation)
      "")))

(defn with-application-kind [{:keys [permitSubtype infoRequest permitType] :as app}]
  (assoc app :kind (cond
                     infoRequest "applications.inforequest"
                     (not (ss/blank? permitSubtype))
                                 (str "permitSubtype." permitSubtype)
                     (= permitType permit/ARK) "digitizedPermit"
                     :else       "applications.application")))

(defn enrich-applications-with-organization-name [applications]
  (let [application-org-ids    (map (comp :organization first) (partition-by :organization applications))
        organization-name-map  (zipmap application-org-ids
                                       (map #(org/with-organization % org/get-organization-name)
                                            application-org-ids))]
    (map (fn [app] (assoc app :organizationName (get organization-name-map (:organization app)))) applications)))

(defn with-organization-name [{:keys [organization] :as app}]
  (assoc app :organizationName (org/with-organization organization org/get-organization-name)))

(defn location->object [application]
  (let [[x y] (:location application)]
    (assoc application :location {:x x :y y})))

(defn make-area-query
  ([areas user]
   (make-area-query areas user nil))
  ([areas user key-prefix]
   {:pre [(sequential? areas)]}
   (let [orgs (usr/organization-ids-by-roles user #{:authority :commenter :reader})
         orgs-with-areas (mongo/select :organizations {:_id {$in orgs} :areas-wgs84.features.id {$in areas}} [:areas-wgs84])
         features (flatten (map (comp :features :areas-wgs84) orgs-with-areas))
         selected-areas (set areas)
         filtered-features (filter (comp selected-areas :id) features)
         target-kw (if (keyword? key-prefix)
                     (keyword (str (name key-prefix) ".location-wgs84"))
                     :location-wgs84)]
     (when (seq filtered-features)
       {$or (map (fn [feature] {target-kw {$geoWithin {"$geometry" (:geometry feature)}}}) filtered-features)}))))
