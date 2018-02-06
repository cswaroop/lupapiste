(ns lupapalvelu.pate.verdict
  (:require [clj-time.core :as time]
            [lupapalvelu.action :as action]
            [lupapalvelu.application :as app]
            [lupapalvelu.application-state :as app-state]
            [lupapalvelu.attachment :as att]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.document.transformations :as transformations]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.inspection-summary :as inspection-summary]
            [lupapalvelu.organization :as org]
            [lupapalvelu.pate.date :as date]
            [lupapalvelu.pate.schemas :as schemas]
            [lupapalvelu.pate.pdf :as pdf]
            [lupapalvelu.pate.shared :as shared]
            [lupapalvelu.pate.verdict-template :as template]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.state-machine :as sm]
            [lupapalvelu.tiedonohjaus :as tiedonohjaus]
            [lupapalvelu.xml.krysp.application-as-krysp-to-backing-system :as krysp]
            [monger.operators :refer :all]
            [sade.strings :as ss]
            [sade.util :as util]
            [schema.core :as sc]
            [swiss.arrows :refer :all]
            [sade.validators :as validators]))

(defn neighbor-states
  "Application neighbor-states data in a format suitable for verdicts: list
  of property-id, done (timestamp) maps."
  [{neighbors :neighbors}]
  (map (fn [{:keys [propertyId status]}]
         {:property-id propertyId
          :done (:created (util/find-by-key :state
                                            "mark-done"
                                            status))})
       neighbors))

(defn data-draft
  "Kmap keys are draft targets (kw-paths). Each value is either

   1. kw-path: Path into template value to be taken as the initial value.

   2. Maps with :fn and :skip-nil? properties. :fn is the source
  (full) data handler function. If :skip-nil? is true then nil value
  entries are skipped.

   3. Anything else is used as an initial value as is.

  Nil values are substituted with empty string (unless :skip-nil? is
  true in 2).

  The second argument is the published template snapshot."
  [kmap {data :data}]
  (reduce-kv (fn [acc k v]
               (let [v-fn  (get v :fn)
                     value (cond
                             v-fn         (v-fn data)
                             (keyword? v) (get-in data (util/split-kw-path v))
                             :else v)]
                 (if (and (nil? value) (:skip-nil? v))
                   acc
                   (assoc-in acc
                             (util/split-kw-path k)
                             (if (nil? value)
                               ""
                               value)))))
             {}
             kmap))

(defn- map-unremoved-section
  "Map (for data-draft) section only it has not been removed. If
  target-key is not given, source-key is used. Result is key-key map
  or nil."
  [source-data source-key & [target-key]]
  (when-not (some-> source-data :removed-sections source-key)
    (hash-map (or target-key source-key) source-key)))

(defn- template-category [template]
  (-> template :category keyword))

(defmulti initial-draft
  "Creates a map with :data and :references"
  (fn [template & _]
    (template-category template)))

(defn- kw-format [& xs]
  (keyword (apply format (map ss/->plain-string xs))))

(defmethod initial-draft :r
  [{snapshot :published} application]
  {:data       (data-draft
                (merge {:verdict-code          :verdict-code
                        :verdict-text          :paatosteksti
                        :bulletinOpDescription :bulletinOpDescription}
                       (reduce (fn [acc kw]
                                 (assoc acc
                                        kw kw
                                        (kw-format "%s-included" kw)
                                        {:fn (util/fn-> (get-in [:removed-sections kw]) not)}))
                               {}
                               [:foremen :plans :reviews])
                       (reduce (fn [acc kw]
                                 (assoc acc
                                        kw  {:fn        #(when (util/includes-as-kw? (:verdict-dates %) kw)
                                                           "")
                                             :skip-nil? true}))
                               {}
                               shared/verdict-dates)
                       (reduce (fn [acc k]
                                 (merge acc (map-unremoved-section (:data snapshot) k)))
                               {}
                               [:neighbors :appeal :statements :collateral
                                :complexity :rights :purpose :extra-info
                                :attachments])
                       ;; List of conditions to conditions map where keys are ids.
                       (when (map-unremoved-section (:data snapshot) :conditions)
                         {:conditions {:fn        (fn [{:keys [conditions]}]
                                                    (reduce (fn [acc condition]
                                                              (assoc-in acc
                                                                        [(keyword (mongo/create-id)) :condition]
                                                                        condition))
                                                            {}
                                                            conditions))
                                       :skip-nil? true}})
                       (when (map-unremoved-section (:data snapshot) :deviations)
                         {:deviations (-> (domain/get-document-by-name application
                                                                       "hankkeen-kuvaus")
                                          :data :poikkeamat :value
                                          (or ""))})
                       (when (map-unremoved-section (:data snapshot) :attachments)
                         {:attachments []}))
                snapshot)
   :references (:settings snapshot)})

(declare enrich-verdict)

(defmulti template-info
  "Contents of the verdict's template property. The actual contents
  depend on the category. Typical keys:

  :giver      Verdict giver type (lautakunta vs. viranhaltija).
  :exclusions A map where each key matches a verdict dictionary
  key. Value is either true (dict is excluded) or another exclusions
  map (for repeating)."
  template-category)

(defmethod template-info :default [_] nil)

(defmethod template-info :r
  [{snapshot :published}]
  (let [data             (:data snapshot)
        removed?         #(boolean (get-in data [:removed-sections %]))
        building-details (->> [(when-not (:autopaikat data)
                                 [:rakennetut-autopaikat
                                  :kiinteiston-autopaikat
                                  :autopaikat-yhteensa])
                               (map  (fn [kw]
                                       (when-not (kw data) kw))
                                     [:vss-luokka :paloluokka])]
                              flatten
                              (remove nil?))
        exclusions       (-<>> [(filter removed? [:appeal :statements
                                                  :collateral :rights :purpose
                                                  :extra-info :deviations])
                                (when (removed? :conditions)
                                  [:conditions :add-condition])
                                (when (removed? :collateral)
                                  [:collateral :collateral-date :collateral-type])
                                (when (removed? :neighbors)
                                  [:neighbors :neighbor-states])
                                (when (removed? :complexity)
                                  [:complexity :complexity-text])
                                (let [no-attachments? (removed? :attachments)]
                                  [(when no-attachments?
                                     :attachments)
                                   (when (or no-attachments? (not (:upload data)))
                                     :upload)])
                                (util/difference-as-kw shared/verdict-dates
                                                       (:verdict-dates data))
                                (if (-> snapshot :settings :boardname)
                                  :contact
                                  :verdict-section)]
                               flatten
                               (remove nil?)
                               (zipmap <> (repeat true))
                               (util/assoc-when <> :buildings (or (removed? :buildings)
                                                                  (zipmap building-details
                                                                          (repeat true))))
                               not-empty)]
    {:giver      (:giver data)
     :exclusions exclusions}))

(defn new-verdict-draft [template-id {:keys [application organization created]
                                      :as   command}]
  (let [template (template/verdict-template @organization template-id)
        draft    (assoc (initial-draft template application)
                        :template (template-info template)
                        :id       (mongo/create-id)
                        :modified created)]
    (action/update-application command
                               {$push {:pate-verdicts
                                       (sc/validate
                                        schemas/PateVerdict
                                        (util/assoc-when draft
                                                         :category (:category template)))}})
    {:verdict  (enrich-verdict command draft)
     :references (:references draft)}))

(defn verdict-summary [verdict]
  (select-keys verdict [:id :published :modified]))

(defn mask-verdict-data [{:keys [user application]} verdict]
  (cond
    (not (auth/application-authority? application user))
    (util/dissoc-in verdict [:data :bulletinOpDescription])
    :default verdict))

(defn command->verdict
  "Gets verdict based on command data. If refresh? is true then the
  application is read from mongo and not taken from command."
  ([{:keys [data application] :as command} refresh?]
   (update (->> (if refresh?
                  (domain/get-application-no-access-checking (:id application)
                                                             {:pate-verdicts 1})
                  application)
                :pate-verdicts
                (util/find-by-id (:verdict-id data))
                (mask-verdict-data command))
           :category keyword))
  ([command]
   (command->verdict command false)))

(defn verdict-template-for-verdict [verdict organization]
  (let [{:keys [id version-id]} (:template verdict)]
    (->>  id
          (template/verdict-template organization)
          :versions
          (util/find-by-id version-id))))

(defn delete-verdict [verdict-id command]
  (action/update-application command
                             {$pull {:pate-verdicts {:id verdict-id}
                                     :attachments   {:target.id verdict-id}}}))

(defn- listify
  "Transforms argument into list if it is not sequential. Nil results
  in empty list."
  [a]
  (cond
    (sequential? a) a
    (nil? a)        '()
    :default        (list a)))

(defn- verdict-update
  "Updates application, using $elemMatch query for given verdict."
  [{:keys [data created] :as command} update]
  (let [{verdict-id :verdict-id} data]
    (action/update-application command
                               {:pate-verdicts {$elemMatch {:id verdict-id}}}
                               (assoc-in update
                                         [$set :pate-verdicts.$.modified]
                                         created))))

(defn- verdict-changes-update
  "Write the auxiliary changes into mongo."
  [command changes]
  (when (seq changes)
    (verdict-update command {$set (reduce (fn [acc [k v]]
                                            (assoc acc
                                                   (util/kw-path :pate-verdicts.$.data k)
                                                   v))
                                          {}
                                          changes)})))

(defn update-automatic-verdict-dates [{:keys [category references verdict-data]}]
  (let [datestring  (:verdict-date verdict-data)
        dictionary  (-> shared/settings-schemas category :dictionary)
        date-deltas (:date-deltas references)
        automatic?  (:automatic-verdict-dates verdict-data)]
    (when (and automatic? (ss/not-blank? datestring))
      (loop [dates      {}
             [kw & kws] shared/verdict-dates
             latest     datestring]
        (if (nil? kw)
          dates
          (let [unit   (-> dictionary  kw :date-delta :unit)
                result (date/parse-and-forward latest
                                               ;; Delta could be empty.
                                               (util/->long (kw date-deltas))
                                               unit)]
            (recur (assoc dates
                          kw
                          result)
                   kws
                   result)))))))

;; Additional changes to the verdict data.
;; Methods options include category, template, verdict-data, path and value.
;; Changes is called after value has already been updated into mongo.
;; The method result is a changes for verdict data.
(defmulti changes (fn [{:keys [category path]}]
                    ;; Dispatcher result: [:category :last-path-part]
                    [category (keyword (last path))]))

(defmethod changes :default [_])

(defmethod changes [:r :verdict-date]
  [options]
  (update-automatic-verdict-dates options))

(defmethod changes [:r :automatic-verdict-dates]
  [options]
  (update-automatic-verdict-dates options))


(defn- strip-exclusions
  "Removes excluded target keys from dictionary schema."
  [exclusions dictionary]
  (cond
    (nil? exclusions)  dictionary
    (true? exclusions) nil

    :else
    (reduce (fn [acc [k v]]
              (let [excluded (k exclusions)]
                (if (-> v keys first (= :repeating))
                  (if-let [dic (strip-exclusions excluded
                                                 (-> v vals first))]
                    (assoc acc k {:repeating dic})
                    acc)
                  (util/assoc-when acc k (when-not excluded
                                           v)))))
            {}
            dictionary)))

(defn- verdict-schema [category template]
  (update (category shared/verdict-schemas)
          :dictionary
          (partial strip-exclusions (:exclusions template))))

(defn verdict-filled?
  "Have all the required fields been filled. Refresh? argument can force
  the read from mongo (see command->verdict)."
  ([command refresh?]
   (let [{:keys [data category template]} (command->verdict command refresh?)
         schema (verdict-schema category template)]
     (schemas/required-filled? schema data)))
  ([command]
   (verdict-filled? command false)))

(defn- app-documents-having-buildings
  [{:keys [documents] :as application}]
  (->> application
       app/get-sorted-operation-documents
       tools/unwrapped
       (filter (util/fn-> :data (contains? :valtakunnallinenNumero)))
       (map (partial app/populate-operation-info
                     (app/get-operations application)))))

(defn buildings
  "Map of building infos: operation id is key and value map contains
  operation (loc-key), building-id (either national or manual id),
  tag (tunnus) and description."
  [application]
  (->> application
       app-documents-having-buildings
       (reduce (fn [acc {:keys [schema-info data]}]
                 (let [{:keys [id name
                               description]} (:op schema-info)]
                   (assoc acc
                          (keyword id)
                          (util/convert-values
                           {:operation   name
                            :description description
                            :building-id (->> [:valtakunnallinenNumero
                                               :manuaalinen_rakennusnro]
                                              (select-keys data)
                                              vals
                                              (util/find-first ss/not-blank?))
                            :tag         (:tunnus data)}
                           ss/->plain-string))))
               {})))

(defn ->buildings-array
  "Construction of the application-buildings array. This should be
  equivalent to ->buildings-summary function in
  lupapalvelu.xml.krysp.building-reader the namespace, but instead of
  the message from backing system, here all the input data is
  originating from PATE verdict."
  [application]
  (->> application
       app-documents-having-buildings
       (util/indexed 1)
       (map (fn [[n {toimenpide :data {op :op} :schema-info}]]
              (let [{:keys [rakennusnro valtakunnallinenNumero mitat kaytto tunnus]} toimenpide
                    description-parts (remove ss/blank? [tunnus (:description op)])]
                 {:localShortId (or rakennusnro (when (validators/rakennusnumero? tunnus) tunnus))
                  :nationalId valtakunnallinenNumero
                  :buildingId (or valtakunnallinenNumero rakennusnro)
                  :location-wgs84 nil
                  :location nil
                  :area (:kokonaisala mitat)
                  :index (str n)
                  :description (ss/join ": " description-parts)
                  :operationId (:id op)
                  :usage (or (:kayttotarkoitus kaytto) "")})))))

(defn edit-verdict
  "Updates the verdict data. Validation takes the template exclusions
  into account. Some updates (e.g., automatic dates) can propagate other
  changes as well. Returns processing result or modified and (possible
  additional) changes."
  [{{:keys [verdict-id path value]} :data
    organization                    :organization
    application                     :application
    created                         :created
    :as                             command}]
  (let [{:keys [data category
                template
                references]} (command->verdict command)
        {:keys [data value path op]
         :as     processed}    (schemas/validate-and-process-value
                                (verdict-schema category template)
                                path value
                                ;; Make sure that building related
                                ;; paths can be resolved.
                                (update data
                                        :buildings
                                        (fn [houses]
                                          (merge (zipmap (keys (buildings application))
                                                         (repeat {}))
                                                 houses)))
                                references)]
    (if-not data
      processed
      (let [mongo-path (util/kw-path :pate-verdicts.$.data path)]
        (verdict-update command
                        (if (= op :remove)
                          {$unset {mongo-path 1}}
                          {$set {mongo-path value}}))
        (template/changes-response {:modified created
                                    :changes  (let [options {:path         path
                                                             :value        value
                                                             :verdict-data data
                                                             :template     template
                                                             :references   references
                                                             :category     category}
                                                    changed (changes options)]
                                                (verdict-changes-update command changed)
                                                (map (fn [[k v]]
                                                       [(util/split-kw-path k) v])
                                                     changed))}
                                   processed)))))


(defn- merge-buildings [app-buildings verdict-buildings defaults]
  (reduce (fn [acc [op-id v]]
            (assoc acc op-id (merge defaults (op-id verdict-buildings) v)))
          {}
          app-buildings))

(defn- building-defaults
  "Verdict building defaults (keys with empty values). Nil if building
  or every detail is not to be included in the verdict."
  [exclusions]
  (when-not (true? (:buildings exclusions))
    (let [subkeys (remove #(get-in exclusions [:buildings %])
                          [:rakennetut-autopaikat
                           :kiinteiston-autopaikat
                           :autopaikat-yhteensa
                           :vss-luokka
                           :paloluokka])]
      (when (seq subkeys)
        (assoc (zipmap subkeys (repeat ""))
               :show-building true)))))

(defn command->category [{app :application}]
  (shared/permit-type->category (:permitType app)))

(defmulti enrich-verdict
  "Augments verdict data, but MUST NOT update mongo (this is called from
  query actions, too)."
  (fn [command & _]
    (command->category command)))

(defmethod enrich-verdict :default [_ verdict _]
  verdict)

(defmethod enrich-verdict :r
  [{:keys [application]} {:keys [data template]:as verdict}]
  (let [{:keys [exclusions]} template
        addons (merge
                ;; Buildings added only if the buildings section is in
                ;; the template AND at least one of the checkboxes has
                ;; been selected.
                (when-let [defaults (building-defaults exclusions)]
                  {:buildings (merge-buildings (buildings application)
                                               (:buildings data)
                                               defaults)})
                ;; Neighbors added if in the template
                (when-not (:neighbors exclusions)
                  {:neighbor-states (neighbor-states application)}))]
    (assoc-in verdict [:data] (merge data addons))))

(defn open-verdict [{:keys [application] :as command}]
  (let [{:keys [data published] :as verdict} (command->verdict command)]
    {:verdict  (assoc (select-keys verdict [:id :modified :published])
                      :data (if published
                              data
                              (:data (enrich-verdict command
                                                     verdict))))
     :references (:references verdict)}))

(defn- next-section [org-id created verdict-giver]
  (when (and org-id created verdict-giver
             (ss/not-blank? (name org-id))
             (ss/not-blank? (name verdict-giver)))
    (->> (util/to-datetime-with-timezone created)
         (time/year)
         (vector "verdict" (name verdict-giver) (name org-id))
         (ss/join "_")
         (mongo/get-next-sequence-value)
         (str))))

(defn- insert-section
  "Section is generated only for non-board verdicts (lautakunta)."
  [org-id created {:keys [data template] :as verdict}]
  (let [{section :verdict-section} data
        {giver :giver}             template]
    (cond-> verdict
      (and (ss/blank? section)
           (util/not=as-kw giver
                           :lautakunta)) (assoc-in [:data :verdict-section]
                                                   (next-section org-id
                                                                 created
                                                                 giver)))))

(defn- verdict-attachment-items
  "Type-groups, type-ids and ids of the verdict attachments. These
  include the new, added verdict attachments and the attachments
  corresponding to the given attachments dict.
  Note: Empty attachments are ignored."
  [{:keys [application]} {verdict-id :id data :data} attachments-dict]
  (let [ids (set (attachments-dict data))]
    (->> (:attachments application)
         (filter #(some-> % :latestVersion :fileId ss/not-blank?))
         (filter (fn [{:keys [id target]}]
                   (or (= verdict-id (:id target))
                       (contains? ids id))))
         (map (fn [{:keys [type id]}]
                {:type-group (keyword (:type-group type))
                 :type-id    (keyword (:type-id type))
                 :id         id})))))

;; Each method returns a map with the following properties
;;  items: Attachment items (verdict-attachment-items result)
;;  update-fn: Function that takes verdict data as argument and updates it.
(defmulti attachment-items (fn [command _]
                             (command->category command)))

(defmethod attachment-items :r
  [command verdict]
  (let [items (verdict-attachment-items command
                                        verdict
                                        :attachments)]
    {:items     items
     :update-fn (fn [data]
                  (assoc data
                         :attachments
                         (->> (cons {:type-group :paatoksenteko
                                     :type-id    :paatos}
                                    items)
                              (group-by #(select-keys % [:type-group
                                                         :type-id]))
                              (map (fn [[k v]]
                                     (assoc k :amount (count v)))))))}))

(defn publish-verdict
  "Publishing verdict does the following:
   1. Finalize and publish verdict
   2. Update application state
   3. Inspection summaries
   4. Other document updates (e.g., waste plan -> waste report)
   5. Construct buildings array
   6. Freeze (locked and read-only) verdict attachments and update TOS details
   7. TODO: Create tasks
   8. Generate section
   9. Create PDF/A for the verdict
  10. Generate KuntaGML
  11. TODO: Assignments?"
  [{:keys [created application user organization] :as command}]
  (let [verdict                (->> (command->verdict command)
                                    (enrich-verdict command)
                                    (insert-section (:organization application)
                                                    created))
        next-state             (sm/verdict-given-state application)
        {att-items :items
         update-fn :update-fn} (attachment-items command verdict)
        buildings-updates      {:buildings (->buildings-array application)}
        verdict                (update verdict :data update-fn)]
    (verdict-update command
                    (util/deep-merge
                     {$set (merge
                            {:pate-verdicts.$.data      (:data verdict)
                             :pate-verdicts.$.published created}
                            buildings-updates
                            (att/attachment-array-updates (:id application)
                                                          #(util/includes-as-kw? (map :id att-items)
                                                                                 (:id %))
                                                          :readOnly true
                                                          :locked   true
                                                          :target {:type "verdict"
                                                                   :id   (:id verdict)}))}
                     (app-state/state-transition-update next-state
                                                        created
                                                        application
                                                        user)))
    (inspection-summary/process-verdict-given application)
    (when-let [doc-updates (not-empty (transformations/get-state-transition-updates command next-state))]
      (action/update-application command
                                 (:mongo-query doc-updates)
                                 (:mongo-updates doc-updates)))
    (tiedonohjaus/mark-app-and-attachments-final! (:id application)
                                                  created)
    (pdf/create-verdict-attachment command
                                   (assoc verdict :published created))
    ;; KuntaGML
    (when (org/krysp-integration? @organization (:permitType application))
      (-> (assoc command :application (domain/get-application-no-access-checking (:id application)))
          (krysp/verdict-as-kuntagml verdict))
      nil)))
