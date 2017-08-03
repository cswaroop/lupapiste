(ns lupapalvelu.organization
  (:import [org.geotools.data FileDataStoreFinder DataUtilities]
           [org.geotools.geojson.feature FeatureJSON]
           [org.geotools.feature.simple SimpleFeatureBuilder SimpleFeatureTypeBuilder]
           [org.geotools.geojson.geom GeometryJSON]
           [org.geotools.geometry.jts JTS]
           [org.geotools.referencing CRS]
           [org.geotools.referencing.crs DefaultGeographicCRS]
           [org.opengis.feature.simple SimpleFeature]
           [java.util ArrayList])

  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn error errorf fatal]]
            [clojure.string :as s]
            [clojure.walk :as walk]
            [monger.operators :refer :all]
            [cheshire.core :as json]
            [schema.core :as sc]
            [sade.core :refer [ok fail fail!]]
            [sade.env :as env]
            [sade.strings :as ss]
            [sade.util :as util]
            [sade.crypt :as crypt]
            [sade.http :as http]
            [sade.xml :as sxml]
            [sade.schemas :as ssc]
            [lupapalvelu.geojson :as geo]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.wfs :as wfs]
            [me.raynes.fs :as fs]
            [lupapalvelu.attachment.stamp-schema :as stmp]
            [clojure.walk :refer [keywordize-keys]]
            [lupapalvelu.matti.schemas :refer [MattiSavedVerdictTemplates]]))

(def scope-skeleton
  {:permitType nil
   :municipality nil
   :inforequest-enabled false
   :new-application-enabled false
   :open-inforequest false
   :open-inforequest-email ""
   :opening nil})

(sc/defschema Tag
  {:id ssc/ObjectIdStr
   :label sc/Str})

(sc/defschema Layer
  {:id sc/Str
   :base sc/Bool
   :name sc/Str})

(sc/defschema Link
  {:url  (zipmap i18n/all-languages (repeat ssc/OptionalHttpUrl))
   :name (zipmap i18n/all-languages (repeat sc/Str))
   (sc/optional-key :modified) ssc/Timestamp})

(sc/defschema Server
  {(sc/optional-key :url)       ssc/OptionalHttpUrl
   (sc/optional-key :username)  (sc/maybe sc/Str)
   (sc/optional-key :password)  (sc/maybe sc/Str)
   (sc/optional-key :crypto-iv) sc/Str})

(sc/defschema InspectionSummaryTemplate
  {:id ssc/ObjectIdStr
   :name sc/Str
   :modified ssc/Timestamp
   :items [sc/Str]})

(sc/defschema HandlerRole
  {:id                              ssc/ObjectIdStr
   :name                            (zipmap i18n/all-languages (repeat ssc/NonBlankStr))
   (sc/optional-key :general)       sc/Bool
   (sc/optional-key :disabled)      sc/Bool})

(sc/defschema AssignmentTrigger
  {:id ssc/ObjectIdStr
   :targets [sc/Str]
   (sc/optional-key :handlerRole) HandlerRole
   :description sc/Str})

(sc/defschema OrgId
  (sc/pred string?))

(sc/defschema DocStoreInfo
  {:docStoreInUse           sc/Bool
   :documentPrice           sc/Num
   :organizationDescription (i18n/localization-schema sc/Str)})

(def default-docstore-info
  {:docStoreInUse           false
   :documentPrice           0.0
   :organizationDescription (i18n/supported-langs-map (constantly ""))})

(sc/defschema Organization
  {:id OrgId
   :name (zipmap i18n/all-languages (repeat sc/Str))
   :scope [{:permitType sc/Str
            :municipality sc/Str
            :new-application-enabled sc/Bool
            :inforequest-enabled sc/Bool
            (sc/optional-key :opening) (sc/maybe ssc/Timestamp)
            (sc/optional-key :open-inforequest) sc/Bool
            (sc/optional-key :open-inforequest-email) ssc/OptionalEmail
            (sc/optional-key :caseManagement) {:enabled sc/Bool
                                               :version sc/Str
                                               (sc/optional-key :ftpUser) sc/Str}}]

   (sc/optional-key :allowedAutologinIPs) sc/Any
   (sc/optional-key :app-required-fields-filling-obligatory) sc/Bool
   (sc/optional-key :assignments-enabled) sc/Bool
   (sc/optional-key :extended-construction-waste-report-enabled) sc/Bool
   (sc/optional-key :automatic-ok-for-attachments-enabled) sc/Bool
   (sc/optional-key :areas) sc/Any
   (sc/optional-key :areas-wgs84) sc/Any
   (sc/optional-key :calendars-enabled) sc/Bool
   (sc/optional-key :guestAuthorities) sc/Any
   (sc/optional-key :hadOpenInforequest) sc/Bool ;; TODO legacy flag, migrate away
   (sc/optional-key :kopiolaitos-email) (sc/maybe sc/Str) ;; TODO split emails into an array
   (sc/optional-key :kopiolaitos-orderer-address) (sc/maybe sc/Str)
   (sc/optional-key :kopiolaitos-orderer-email) (sc/maybe sc/Str)
   (sc/optional-key :kopiolaitos-orderer-phone) (sc/maybe sc/Str)
   (sc/optional-key :krysp) sc/Any
   (sc/optional-key :links) [Link]
   (sc/optional-key :map-layers) sc/Any
   (sc/optional-key :notifications) {(sc/optional-key :inforequest-notification-emails) [ssc/Email]
                                     (sc/optional-key :neighbor-order-emails)      [ssc/Email]
                                     (sc/optional-key :submit-notification-emails) [ssc/Email]}
   (sc/optional-key :operations-attachments) sc/Any
   (sc/optional-key :operations-tos-functions) sc/Any
   (sc/optional-key :permanent-archive-enabled) sc/Bool
   (sc/optional-key :permanent-archive-in-use-since) sc/Any
   (sc/optional-key :reservations) sc/Any
   (sc/optional-key :selected-operations) sc/Any
   (sc/optional-key :statementGivers) sc/Any
   (sc/optional-key :handler-roles) [HandlerRole]
   (sc/optional-key :suti) {(sc/optional-key :www) ssc/OptionalHttpUrl
                            (sc/optional-key :enabled) sc/Bool
                            (sc/optional-key :server) Server
                            (sc/optional-key :operations) [sc/Str]}
   (sc/optional-key :tags) [Tag]
   (sc/optional-key :validate-verdict-given-date) sc/Bool
   (sc/optional-key :vendor-backend-redirect) {(sc/optional-key :vendor-backend-url-for-backend-id) ssc/OptionalHttpUrl
                                               (sc/optional-key :vendor-backend-url-for-lp-id)      ssc/OptionalHttpUrl}
   (sc/optional-key :use-attachment-links-integration) sc/Bool
   (sc/optional-key :section) {(sc/optional-key :enabled)    sc/Bool
                               (sc/optional-key :operations) [sc/Str]}
   (sc/optional-key :3d-map) {(sc/optional-key :enabled) sc/Bool
                              (sc/optional-key :server)  Server}
   (sc/optional-key :inspection-summaries-enabled) sc/Bool
   (sc/optional-key :inspection-summary) {(sc/optional-key :templates) [InspectionSummaryTemplate]
                                          (sc/optional-key :operations-templates) sc/Any}
   (sc/optional-key :assignment-triggers) [AssignmentTrigger]
   (sc/optional-key :stamps) [stmp/StampTemplate]
   (sc/optional-key :verdict-templates) MattiSavedVerdictTemplates
   (sc/optional-key :docstore-info) DocStoreInfo})

(sc/defschema SimpleOrg
  (select-keys Organization [:id :name :scope]))

(def permanent-archive-authority-roles [:tos-editor :tos-publisher :archivist])
(def authority-roles
  "Reader role has access to every application within org."
  (concat [:authority :approver :commenter :reader] permanent-archive-authority-roles))

(defn- with-scope-defaults [org]
  (if (:scope org)
    (update-in org [:scope] #(map (fn [s] (util/deep-merge scope-skeleton s)) %))
    org))

(defn- remove-sensitive-data [organization]
  (let [org (dissoc organization :allowedAutologinIPs)]
    (if (:krysp org)
      (update org :krysp #(into {} (map (fn [[permit-type config]] [permit-type (dissoc config :password :crypto-iv)]) %)))
      org)))

(defn get-organizations
  ([]
    (get-organizations {}))
  ([query]
   (->> (mongo/select :organizations query)
        (map remove-sensitive-data)
        (map with-scope-defaults)))
  ([query projection]
   (->> (mongo/select :organizations query projection)
        (map remove-sensitive-data)
        (map with-scope-defaults))))

(defn get-autologin-ips-for-organization [org-id]
  (-> (mongo/by-id :organizations org-id [:allowedAutologinIPs])
      :allowedAutologinIPs))

(defn autogin-ip-mongo-changes [ips]
  (when (nil? (sc/check [ssc/IpAddress] ips))
    {$set {:allowedAutologinIPs ips}}))

(defn get-organization
  ([id] (get-organization id {}))
  ([id projection]
   {:pre [(not (s/blank? id))]}
   (->> (mongo/by-id :organizations id projection)
        remove-sensitive-data
        with-scope-defaults)))

(defn update-organization [id changes]
  {:pre [(not (s/blank? id))]}
  (mongo/update-by-id :organizations id changes))

(defn get-organization-attachments-for-operation [organization {operation-name :name}]
  (get-in organization [:operations-attachments (keyword operation-name)]))

(defn allowed-ip? [ip organization-id]
  (pos? (mongo/count :organizations {:_id organization-id, $and [{:allowedAutologinIPs {$exists true}} {:allowedAutologinIPs ip}]})))

(defn encode-credentials
  [username password]
  (when-not (s/blank? username)
    (let [crypto-iv        (crypt/make-iv-128)
          crypted-password (crypt/encrypt-aes-string password (env/value :backing-system :crypto-key) crypto-iv)
          crypto-iv-s      (-> crypto-iv crypt/base64-encode crypt/bytes->str)]
      {:username username :password crypted-password :crypto-iv crypto-iv-s})))

(defn decode-credentials
  "Decode password that was originally generated (together with the init-vector)
   by encode-credentials. Arguments are base64 encoded."
  [password crypto-iv]
  (crypt/decrypt-aes-string password (env/value :backing-system :crypto-key) crypto-iv))

(defn resolve-krysp-wfs
  "Returns a map containing :url and :version information for municipality's KRYSP WFS"
  ([organization permit-type]
   (let [krysp-config (get-in organization [:krysp (keyword permit-type)])
         crypto-iv    (:crypto-iv krysp-config)
         password     (when-let [password (and crypto-iv (:password krysp-config))]
                        (decode-credentials password crypto-iv))
         username     (:username krysp-config)]
     (when-not (s/blank? (:url krysp-config))
       (->> (when username {:credentials [username password]})
            (merge (select-keys krysp-config [:url :version])))))))

(defn get-krysp-wfs
  "Returns a map containing :url and :version information for municipality's KRYSP WFS"
  ([{:keys [organization permitType] :as application}]
    (get-krysp-wfs {:_id organization} permitType))
  ([query permit-type]
   (-> (mongo/select-one :organizations query [:krysp])
       (resolve-krysp-wfs permit-type))))

(defn municipality-address-endpoint [^String municipality]
  {:pre [(or (string? municipality) (nil? municipality))]}
  (when (and (ss/not-blank? municipality) (re-matches #"\d{3}" municipality) )
    (let [no-bbox-srs (env/value :municipality-wfs (keyword municipality) :no-bbox-srs)]
      (some-> (get-krysp-wfs {:scope.municipality municipality, :krysp.osoitteet.url {"$regex" "\\S+"}} :osoitteet)
              (util/assoc-when :no-bbox-srs (boolean no-bbox-srs))))))

(defn set-krysp-endpoint
  [id url username password endpoint-type version]
  {:pre [(mongo/valid-key? endpoint-type)]}
  (let [url (ss/trim url)
        updates (->> (encode-credentials username password)
                  (merge {:url url :version version})
                  (map (fn [[k v]] [(str "krysp." endpoint-type "." (name k)) v]))
                  (into {})
                  (hash-map $set))]
    (if (and (ss/not-blank? url) (= "osoitteet" endpoint-type))
      (let [capabilities-xml (wfs/get-capabilities-xml url username password)
            osoite-feature-type (some->> (wfs/feature-types capabilities-xml)
                                         (map (comp :FeatureType sxml/xml->edn))
                                         (filter #(re-matches #"[a-z]*:?Osoite$" (:Name %))) first)
            address-updates (assoc-in updates [$set (str "krysp." endpoint-type "." "defaultSRS")] (:DefaultSRS osoite-feature-type))]
        (if-not osoite-feature-type
          (fail! :error.no-address-feature-type)
          (update-organization id address-updates)))
      (update-organization id updates))))

(defn get-organization-name
  ([organization]
  (let [default (get-in organization [:name :fi] (str "???ORG:" (:id organization) "???"))]
    (get-in organization [:name i18n/*lang*] default)))
  ([organization-id lang]
   (let [organization (get-organization organization-id)
         default (get-in organization [:name :fi] (str "???ORG:" (:id organization) "???"))]
     (get-in organization [:name lang] default))))

(defn get-organization-auto-ok [organization-id]
  (:automatic-ok-for-attachments-enabled (get-organization organization-id)))

(defn resolve-organizations
  ([municipality]
    (resolve-organizations municipality nil))
  ([municipality permit-type]
    (get-organizations {:scope {$elemMatch (merge {:municipality municipality} (when permit-type {:permitType permit-type}))}})))

(defn resolve-organization [municipality permit-type]
  {:pre  [municipality (permit/valid-permit-type? permit-type)]}
  (when-let [organizations (resolve-organizations municipality permit-type)]
    (when (> (count organizations) 1)
      (errorf "*** multiple organizations in scope of - municipality=%s, permit-type=%s -> %s" municipality permit-type (count organizations)))
    (first organizations)))

(defn resolve-organization-scope
  ([municipality permit-type]
    {:pre  [municipality (permit/valid-permit-type? permit-type)]}
    (let [organization (resolve-organization municipality permit-type)]
      (resolve-organization-scope municipality permit-type organization)))
  ([municipality permit-type organization]
    {:pre  [municipality organization (permit/valid-permit-type? permit-type)]}
   (first (filter #(and (= municipality (:municipality %)) (= permit-type (:permitType %))) (:scope organization)))))

(defn permit-types [{scope :scope :as organization}]
  (map (comp keyword :permitType) scope))

(defn with-organization [id function]
  (if-let [organization (get-organization id)]
    (function organization)
    (do
      (debugf "organization '%s' not found with id." id)
      (fail :error.organization-not-found))))

(defn krysp-integration? [organization permit-type]
  (let [mandatory-keys [:url :version :ftpUser]]
    (when-let [krysp (select-keys (get-in organization [:krysp (keyword permit-type)]) mandatory-keys)]
     (and (= (count krysp) (count mandatory-keys)) (not-any? ss/blank? (vals krysp))))))

(defn allowed-roles-in-organization [organization]
  {:pre [(map? organization)]}
  (if-not (:permanent-archive-enabled organization)
    (remove #(% (set permanent-archive-authority-roles)) authority-roles)
    authority-roles))

(defn filter-valid-user-roles-in-organization [organization roles]
  (let [organization  (if (map? organization) organization (get-organization organization))
        allowed-roles (set (allowed-roles-in-organization organization))]
    (filter (comp allowed-roles keyword) roles)))

(defn create-tag-ids
  "Creates mongo id for tag if id is not present"
  [tags]
  (map
    #(if (:id %)
       %
       (assoc % :id (mongo/create-id)))
    tags))

(defn some-organization-has-archive-enabled? [organization-ids]
  (pos? (mongo/count :organizations {:_id {$in organization-ids} :permanent-archive-enabled true})))

(defn earliest-archive-enabled-ts [organization-ids]
  (->> (mongo/select :organizations {:_id {$in organization-ids} :permanent-archive-enabled true} {:permanent-archive-in-use-since 1} {:permanent-archive-in-use-since 1})
       (first)
       (:permanent-archive-in-use-since)))

(defn some-organization-has-calendars-enabled? [organization-ids]
  (pos? (mongo/count :organizations {:_id {$in organization-ids} :calendars-enabled true})))

(defn organizations-with-calendars-enabled []
  (map :id (mongo/select :organizations {:calendars-enabled true} {:id 1})))

;;
;; Backend server addresses
;;

(defn- update-organization-server [mongo-path org-id url username password]
  {:pre [mongo-path (ss/not-blank? (name mongo-path))
         (string? org-id)
         (ss/optional-string? url)
         (ss/optional-string? username)
         (ss/optional-string? password)]}
  (let [server (cond
                 (ss/blank? username) {:url url, :username nil, :password nil} ; this should replace the server map (removes password)
                 (ss/blank? password) {:url url, :username username} ; update these keys only, password not changed
                 :else (assoc (encode-credentials username password) :url url)) ; this should replace the server map
        updates (if-not (contains? server :password)
                  (into {} (map (fn [[k v]] [(str (name mongo-path) \. (name k)) v]) server) )
                  {mongo-path server})]
   (update-organization org-id {$set updates})))

;;
;; Organization/municipality provided map support.
;;

(defn query-organization-map-server
  [org-id params headers]
  (when-let [m (-> org-id get-organization :map-layers :server)]
    (let [{:keys [url username password crypto-iv]} m
          base-request {:query-params params
                        :throw-exceptions false
                        :quiet true
                        :headers (select-keys headers [:accept :accept-encoding])
                        :as :stream}
          request (if-not (ss/blank? crypto-iv)
                    (assoc base-request :basic-auth [username (decode-credentials password crypto-iv)])
                    base-request)
          response (http/get url request)]
      (if (= 200 (:status response))
        response
        (do
          (error "error.integration - organization" org-id "wms server" url "returned" (:status response))
          response)))))

(defn organization-map-layers-data [org-id]
  (when-let [{:keys [server layers]} (-> org-id get-organization :map-layers)]
    (let [{:keys [url username password crypto-iv]} server]
      {:server {:url url
                :username username
                :password (if (ss/blank? crypto-iv)
                            password
                            (decode-credentials password crypto-iv))}
       :layers layers})))

(def update-organization-map-server (partial update-organization-server :map-layers.server))

;;
;; Suti
;;

(def update-organization-suti-server (partial update-organization-server :suti.server))

;; 3D Map. See also 3d-map and 3d-map-api namespaces.

(def update-organization-3d-map-server (partial update-organization-server :3d-map.server))

;; Waste feed enpoint parameter validators

(defn valid-org
  "Empty organization is valid"
  [{{:keys [org]} :data}]
  (when-not (or (ss/blank? org) (-> org ss/upper-case get-organization))
    (fail :error.organization-not-found)))

(defn valid-feed-format [cmd]
  (when-not (->> cmd :data :fmt ss/lower-case keyword (contains? #{:rss :json}) )
    (fail :error.invalid-feed-format)))

(defn valid-ip-addresses [ips]
  (when-let [error (sc/check [ssc/IpAddress] ips)]
    (fail :error.invalid-ip :desc (str error))))

(defn permit-type-validator
  "Returns validator for user-organizations permit types"
  [& valid-permit-types]
  (fn [{org :user-organizations}]
    (when (->>  (mapcat permit-types org)
                (not-any? (set (map keyword valid-permit-types))))
      (fail :error.invalid-permit-type))))

(defn-
  ^org.geotools.data.simple.SimpleFeatureCollection
  transform-crs-to-wgs84
  "Convert feature crs in collection to WGS84"
  [org-id existing-areas ^org.geotools.feature.FeatureCollection collection]
  (let [existing-areas (if-not existing-areas
                         (mongo/by-id :organizations org-id {:areas.features.id 1 :areas.features.properties.nimi 1 :areas.features.properties.NIMI 1})
                         {:areas existing-areas})
        map-of-existing-areas (into {} (map (fn [a]
                                              (let [properties (:properties a)
                                                    nimi (if (contains? properties :NIMI)
                                                           (:NIMI properties)
                                                           (:nimi properties))]
                                                {nimi (:id a)})) (get-in existing-areas [:areas :features])))
        iterator (.features collection)
        list (ArrayList.)
        _ (loop [feature (when (.hasNext iterator)
                           (.next iterator))]
            (when feature
              ; Set CRS to WGS84 to bypass problems when converting to GeoJSON (CRS detection is skipped with WGS84).
              ; Atm we assume only CRS EPSG:3067 is used.
              ; Always give feature the same id if names match, so that search filters continue to work after reloading shp file
              ; with same feature names
              ;
              ; Cheatsheet to understand naming conventions in Geotools (from http://docs.geotools.org/latest/userguide/tutorial/feature/csv2shp.html):
              ; Java  | GeoSpatial
              ; --------------
              ; Object  Feature
              ; Class   FeatureType
              ; Field   Attribute

              (let [feature-type        (DataUtilities/createSubType (.getFeatureType feature) nil DefaultGeographicCRS/WGS84)
                    name-property       (or ; try to get name of feature from these properties
                                          (.getProperty feature "nimi")
                                          (.getProperty feature "NIMI")
                                          (.getProperty feature "Nimi")
                                          (.getProperty feature "name")
                                          (.getProperty feature "NAME")
                                          (.getProperty feature "Name")
                                          (.getProperty feature "id")
                                          (.getProperty feature "ID")
                                          (.getProperty feature "Id"))

                    feature-name        (when name-property
                                          (.getValue name-property))
                    id                  (if (contains? map-of-existing-areas feature-name)
                                          (get map-of-existing-areas feature-name)
                                          (mongo/create-id))

                    type-builder        (doto (SimpleFeatureTypeBuilder.) ; FeatureType builder, 'nimi' property
                                          (.init  feature-type) ; Init with existing subtyped feature (correct CRS, no attributes)
                                          (.add "nimi" (.getClass String))) ; Add the attribute we are interested in
                    new-feature-type    (.buildFeatureType type-builder)

                    builder             (SimpleFeatureBuilder. new-feature-type) ; new FeatureBuilder with changed crs and new attribute
                    builder             (doto builder
                                          (.init feature) ; init builder with original feature
                                          (.set "nimi" feature-name)) ; ensure 'nimi' property exists
                    transformed-feature (.buildFeature builder id)]
                (.add list transformed-feature)))
            (when (.hasNext iterator)
              (recur (.next iterator))))]
    (.close iterator)
    (DataUtilities/collection list)))

(defn- transform-coordinates-to-wgs84
  "Convert feature coordinates in collection to WGS84 which is supported by mongo 2dsphere index"
 [collection]
  (let [schema (.getSchema collection)
        crs (.getCoordinateReferenceSystem schema)
        transform (CRS/findMathTransform crs DefaultGeographicCRS/WGS84 true)
        iterator (.features collection)
        feature (when (.hasNext iterator)
                  (.next iterator))
        list (ArrayList.)
        _ (loop [feature (cast SimpleFeature feature)]
            (when feature
              (let [geometry (.getDefaultGeometry feature)
                    transformed-geometry (JTS/transform geometry transform)]
                (.setDefaultGeometry feature transformed-geometry)
                (.add list feature)))
            (when (.hasNext iterator)
              (recur (.next iterator))))]
    (.close iterator)
    (DataUtilities/collection list)))

(defn parse-shapefile-to-organization-areas [org-id tempfile tmpdir]
  (let [target-dir (util/unzip (.getPath tempfile) tmpdir)
        shape-file (first (util/get-files-by-regex (.getPath target-dir) #"^.+\.shp$"))
        data-store (FileDataStoreFinder/getDataStore shape-file)
        new-collection (some-> data-store
                               .getFeatureSource
                               .getFeatures
                               ((partial transform-crs-to-wgs84 org-id nil)))
        precision      13 ; FeatureJSON shows only 4 decimals by default
        areas (keywordize-keys (json/parse-string (.toString (FeatureJSON. (GeometryJSON. precision)) new-collection)))
        ensured-areas (geo/ensure-features areas)

        new-collection-wgs84 (some-> data-store
                                     .getFeatureSource
                                     .getFeatures
                                     transform-coordinates-to-wgs84
                                     ((partial transform-crs-to-wgs84 org-id ensured-areas)))
        areas-wgs84 (keywordize-keys (json/parse-string (.toString (FeatureJSON. (GeometryJSON. precision)) new-collection-wgs84)))
        ensured-areas-wgs84 (geo/ensure-features areas-wgs84)]
    (when (geo/validate-features (:features ensured-areas))
      (fail! :error.coordinates-not-epsg3067))
    (update-organization org-id {$set {:areas ensured-areas
                                       :areas-wgs84 ensured-areas-wgs84}})
    (.dispose data-store)
    ensured-areas))

;; Group denotes organization property that has enabled and operations keys.
;; Suti and section are groups.



(defn toggle-group-enabled
  "Toggles enabled flag of a group (e.g., suti, section)."
  [organization-id group flag]
  (update-organization organization-id
                       {$set {(util/kw-path group :enabled) flag}}))

(defn toggle-group-operation
  "Toggles (either adds or removes) an operation of a group (e.g., suti, section)."
  [organization group operation-id flag]
  (let [already (contains? (-> organization group :operations set) operation-id)]
    (when (not= (boolean already) (boolean flag))
      (update-organization (:id organization)
                           {(if flag $push $pull) {(util/kw-path group :operations) operation-id}}))))

(defn add-organization-link [organization name url created]
  (update-organization organization
                       {$push {:links {:name     name
                                       :url      url
                                       :modified created}}}))

(defn update-organization-link [organization index name url created]
  (update-organization organization
                       {$set {(str "links." index) {:name     name
                                                    :url      url
                                                    :modified created}}}))

(defn- combine-keys [prefix [k v]]
  [(keyword (str (name prefix) "." (name k))) v])

(defn- mongofy
  "Transform eg. {:outer {:inner :value}} into {:outer.inner :value}"
  [m]
  (into {}
        (mapcat (fn [[k v]]
                  (if (and (keyword? k)
                           (map? v))
                    (map (partial combine-keys k) (mongofy v))
                    [[k v]]))
                m)))

(defn remove-organization-link [organization name url]
  (update-organization organization
                       {$pull {:links (mongofy {:name name
                                                :url  url})}}))

(defn general-handler-id-for-organization [{roles :handler-roles :as organization}]
  (:id (util/find-first :general roles)))

(defn create-handler-role
  ([]
   (create-handler-role nil {:fi "K\u00e4sittelij\u00e4"
                             :sv "Handl\u00e4ggare"
                             :en "Handler"}))
  ([role-id name]
   {:id (or role-id (mongo/create-id))
    :name name}))

(defn upsert-handler-role! [{handler-roles :handler-roles org-id :id} handler-role]
  (let [ind (or (util/position-by-id (:id handler-role) handler-roles)
                (count handler-roles))]
    (update-organization org-id {$set {(util/kw-path :handler-roles ind :id)   (:id handler-role)
                                       (util/kw-path :handler-roles ind :name) (:name handler-role)}})))

(defn disable-handler-role! [org-id role-id]
  (mongo/update :organizations {:_id org-id :handler-roles.id role-id} {$set {:handler-roles.$.disabled true}}))

(defn create-trigger [triggerId target handler description]
  (cond->
    {:id (or triggerId (mongo/create-id))
     :targets target
     :description description}
    (:name handler) (conj {:handlerRole (create-handler-role (:id handler) (:name handler))})))

(defn add-assignment-trigger [{org-id :id} trigger]
  (update-organization org-id {$push {:assignment-triggers trigger}}))

(defn- user-created? [trigger-id]
  (= trigger-id "user-created"))

(defn- update-assignment-descriptions [trigger-id description]
  (when (not (user-created? trigger-id))
    (mongo/update-by-query :assignments
                           {:trigger trigger-id}
                           {$set {:description description}})))

(defn update-assignment-trigger [{org-id :id} trigger triggerId]
  (let [query (assoc {:assignment-triggers {$elemMatch {:id triggerId}}} :_id org-id)
        changes {$set {:assignment-triggers.$.targets (:targets trigger)
                       :assignment-triggers.$.handlerRole (:handlerRole trigger)
                       :assignment-triggers.$.description (:description trigger)}}
        num-updated (mongo/update-by-query :organizations query changes)]
    ; it is assumed that triggers are not updated very often, so this
    ; description synchronization is done to avoid unnecessary
    ; organization queries elsewhere
    (update-assignment-descriptions triggerId (:description trigger))
    num-updated))

(defn remove-assignment-trigger [{org-id :id} trigger-id]
  (update-organization org-id {$pull {:assignment-triggers {:id trigger-id}}}))

(defn toggle-handler-role! [org-id role-id enabled?]
  (mongo/update :organizations
                {:_id org-id :handler-roles.id role-id}
                {$set {:handler-roles.$.disabled (not enabled?)}}))

(defn get-duplicate-scopes [municipality permit-types]
  (not-empty (mongo/select :organizations {:scope {$elemMatch {:permitType {$in permit-types} :municipality municipality}}} [:scope])))

(defn new-scope [municipality permit-type & {:keys [inforequest-enabled new-application-enabled open-inforequest open-inforequest-email opening]}]
  (util/assoc-when scope-skeleton
                   :municipality            municipality
                   :permitType              permit-type
                   :inforequest-enabled     inforequest-enabled
                   :new-application-enabled new-application-enabled
                   :open-inforequest        open-inforequest
                   :open-inforequest-email  open-inforequest-email
                   :opening                 (when (number? opening) opening)))
