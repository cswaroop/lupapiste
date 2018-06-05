(ns lupapalvelu.pate.verdict
  (:require [clj-time.core :as time]
            [clojure.set :as set]
            [lupapalvelu.action :as action]
            [lupapalvelu.application :as app]
            [lupapalvelu.application-meta-fields :as meta]
            [lupapalvelu.application-state :as app-state]
            [lupapalvelu.attachment :as att]
            [lupapalvelu.attachment :as att]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.company :as com]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.document.transformations :as transformations]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.inspection-summary :as inspection-summary]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.organization :as org]
            [lupapalvelu.pate.date :as date]
            [lupapalvelu.pate.legacy :as legacy]
            [lupapalvelu.pate.pdf :as pdf]
            [lupapalvelu.pate.schemas :as schemas]
            [lupapalvelu.pate.shared :as shared]
            [lupapalvelu.pate.tasks :as pate-tasks]
            [lupapalvelu.pate.verdict-template :as template]
            [lupapalvelu.state-machine :as sm]
            [lupapalvelu.states :as states]
            [lupapalvelu.tasks :as tasks]
            [lupapalvelu.tiedonohjaus :as tiedonohjaus]
            [lupapalvelu.verdict :as old-verdict]
            [lupapalvelu.xml.krysp.application-as-krysp-to-backing-system :as krysp]
            [monger.operators :refer :all]
            [ring.util.codec :as codec]
            [rum.core :as rum]
            [sade.coordinate :as coord]
            [sade.strings :as ss]
            [sade.util :as util]
            [sade.validators :as validators]
            [schema.core :as sc]
            [swiss.arrows :refer :all]
            [taoensso.timbre :refer [warnf]]))

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

(defn- general-handler [{handlers :handlers}]
  (if-let [{:keys [firstName
                   lastName]} (util/find-first :general handlers)]
    (str firstName " " lastName)
    ""))

(defn- application-deviations [application]
  (-> (domain/get-document-by-name application
                                   "hankkeen-kuvaus")
      :data :poikkeamat :value
      (or "")))

(defn- application-operation [{documents :documents}]
  (let  [{data :data} (first (domain/get-documents-by-subtype documents
                                                              :hankkeen-kuvaus))]
    (or
     (get-in data [:kuvaus :value]) ;; R
     (get-in data [:kayttotarkoitus :value]) ;; YA
     "")))

(defn dicts->kw-paths
  [dictionary]
  (->> dictionary
       (map (fn [[k v]]
              (if-let [repeating (:repeating v)]
                (map #(util/kw-path k %) (dicts->kw-paths repeating))
                k)))
       flatten))

(defn published-template-removed-sections
  "Set of removed section ids from a published template."
  [template]
  (or (some->> template
               :data
               :removed-sections
               (map (fn [[k v]]
                      (when v
                        (keyword k))))
               (remove nil?)
               set)
      #{}))

(defn inclusions
  "List if kw-paths that denote the dicts included in the verdict. By
  default, every dict is included, but a dict is excluded if any
  the following are true:

  - Dict schema :template-section refers to a removed template section.

  - Every section that dict belongs to has a :template-section that
    refers to a removed template-section.

  - Dict schema :template-dict refers to template dict that is not in
    the template inclusions. In other words, the template dict is part
    of a removed template section.

  Note: if a :repeating is included, its every dict is included."
  [category published-template]
  (let [removed-tem-secs   (published-template-removed-sections published-template)
        t-inclusions       (->> (:inclusions published-template)
                                (map keyword)
                                set)
        {:keys [dictionary
                sections]} (shared/verdict-schema category)
        dic-sec            (schemas/dict-sections sections)
        removed-ver-secs   (->> sections
                                (filter #(contains? removed-tem-secs
                                                    (:template-section %)))
                                (map :id)
                                set)]
    (->> (keys dictionary)
         (remove (fn [dict]
                   (let [{tem-sec :template-section
                          tem-dic :template-dict} (dict dictionary)
                         in-sections              (dict dic-sec)]
                     (or
                      (contains? removed-tem-secs tem-sec)
                      (and (seq in-sections)
                           (empty? (set/difference in-sections removed-ver-secs)))
                      (and tem-dic (not (contains? t-inclusions tem-dic)))))))
         (select-keys dictionary)
         dicts->kw-paths)))

(defn default-verdict-draft
  "Prepares the default draft (for initmap)
  with :category, :schema-version, :template, :data and :references
  keys. Resolves inclusions and initial values from the template."
  [{:keys [category published] :as template}]
  (let [{dic     :dictionary
         version :version} (shared/verdict-schema category)
        {:keys [data
                settings]} published
        incs               (inclusions category published)
        included?          #(contains? (set incs) %)]
    {:category       category
     :schema-version version
     :template       {:inclusions incs}
     :data           (reduce-kv (fn [acc dict value]
                                  (let [{:keys [template-dict
                                                repeating]} value
                                        initial-value       (and template-dict
                                                                 (template-dict data))]
                                    (cond
                                      ;; So far only one level repeating can be initialized
                                      (and initial-value repeating)
                                      (reduce (fn [m v]
                                                (reduce-kv (fn [a inner-k inner-v]
                                                             (cond-> a
                                                               (included? (util/kw-path dict inner-k))
                                                               (assoc-in [dict (mongo/create-id) inner-k] inner-v)))
                                                           m
                                                           v))
                                              acc
                                              initial-value)

                                      (and initial-value (included? dict))
                                      (assoc acc dict initial-value)

                                      :else
                                      acc)))
                                {}
                                dic)
     ;; Foremen, plans and reviews are initialized in separate init functions.
     :references (dissoc settings :foremen :plans :reviews)}))


;; Argument map:
;; template:    Published template for the verdict
;; application: Application
;; draft:       Default draft:
;;
;;              template:   Template part of the verdict data. The map
;;                          should contain at least inclusions.
;;
;;              data: Initialized verdict data (from
;;                    default-verdict-drat).
;;
;;              references: References for the data (default is
;;                          published settings)
;;
;; The return value is the modified (if needed) draft.
(defmulti initialize-verdict-draft (util/fn-> :template :category keyword))

;; Initialization helper functions
(defn section-removed? [template section]
  (contains? (published-template-removed-sections (:published template))
             section))

(defn dict-included? [{:keys [draft]} dict]
  (contains? (-> draft :template :inclusions set) dict))

(defn- resolve-requirement-inclusion
  "Dict is either :foremen, :plans or :reviews. Only empty requirement
  is excluded."
  [{:keys [template] :as initmap} dict]
  (let [included (keyword (str (name dict) "-included"))]
    (if (some-> initmap :draft :references dict seq)
      (assoc-in initmap
                [:draft :data included]
                (not (section-removed? template dict)))
      (update-in initmap
                 [:draft :template :inclusions]
                 util/difference-as-kw [dict included]))))

(defn init--foremen
  "Build a multi-select reference-list value according to template
  data. The included toggle is initialized in `init--included-checks`
  above."
  [{:keys [template] :as initmap}]
  (let [t-data  (-> template :published :data)
        updated (reduce (fn [acc code]
                          (let [codename (name code)
                                inc-dict  (keyword (str codename "-included"))
                                included? (inc-dict t-data)
                                selected? (when included?
                                            (code t-data))]
                            (cond-> acc
                              included? (update-in [:draft :references :foremen] conj codename)
                              selected? (update-in [:draft :data :foremen] conj codename))))
                        initmap
                        shared/foreman-codes)]
    (resolve-requirement-inclusion updated :foremen)))

(defn init--requirements-references
  "Plans and reviews are defined in the published template settings."
  [{:keys [template] :as initmap} dict]
  (-<> initmap
       (reduce (fn [acc {:keys [selected] :as item}]
                 (let [id (mongo/create-id)]
                   (cond-> (update-in acc
                                      [:draft :references dict]
                                      conj (-> item
                                               (dissoc :selected)
                                               (assoc :id id)))
                    selected (update-in [:draft :data dict]
                                        conj id))))
               <>
              (get-in template [:published :settings dict]))
      (resolve-requirement-inclusion dict)))

(defn init--verdict-dates
  "Include in verdict only those verdict dates that have been checked in
  the template. Also, if no verdict dates, the automatic-verdict-dates
  toggle is excluded."
  [{:keys [template] :as initmap}]
  (update-in initmap
             [:draft :template :inclusions]
             (fn [incs]
               (let [{:keys [verdict-dates]} (-> template :published :data)]
                 (cond-> (util/difference-as-kw incs
                                                [:automatic-verdict-dates]
                                                shared/verdict-dates)
                   (seq verdict-dates)
                   (util/union-as-kw verdict-dates
                                     [:automatic-verdict-dates]))))))

(defn init--upload
  "Upload is included if the attachments section is not removed and
  upload is checked in the template. Note that the attachments section
  dependence has been resolved earlier."
    [{:keys [template] :as initmap}]
  (update-in initmap
             [:draft :template :inclusions]
             (fn [incs]
               (util/difference-as-kw incs
                                      (when-not (some-> template
                                                        :published
                                                        :data
                                                        :upload)
                                        [:upload])))))

(defn init--verdict-giver-type
  "Adds giver key into template. The value is either lautakunta or
  viranhaltija. Verdict-section and boardname dicts not included for
  viranhaltija verdicts."
  [{:keys [template] :as initmap}]
  (let [giver-type (some-> template :published :data :giver)]
    (cond-> (assoc-in initmap
                      [:draft :template :giver]
                      giver-type)
      (util/=as-kw giver-type :viranhaltija)
      (update-in [:draft :template :inclusions]
                 util/difference-as-kw  [:verdict-section
                                         :boardname]))))

(defn init--dict-by-application
  "Inits given dict (if included) with the app-fn result (if the result
  is non-nil). App-fn takes application as an argument"
  [{:keys [application] :as initmap} dict app-fn]
  (let [value (when (dict-included? initmap dict)
                (app-fn application))]
    (if (nil? value)
      initmap
      (assoc-in initmap [:draft :data dict] value))))

(def buildings-inclusion {:autopaikat [:buildings.rakennetut-autopaikat
                                       :buildings.kiinteiston-autopaikat
                                       :buildings.autopaikat-yhteensa]
                          :vss-luokka [:buildings.vss-luokka]
                          :paloluokka [:buildings.paloluokka]})
(def buildings-inclusion-keys (-> buildings-inclusion vals flatten))

(defn init--buildings
  "Update inclusions according to selected building details."
  [{:keys [template] :as initmap}]
  (cond-> initmap
    (not (section-removed? template :buildings))
    (update-in [:draft :template :inclusions]
               (fn [incs]
                 (let [details (some->  template :published :data)]
                   (reduce-kv (fn [acc k v]
                                (cond-> acc
                                  (k details) (util/union-as-kw v)))
                              (util/difference-as-kw incs
                                                     buildings-inclusion-keys)
                              buildings-inclusion))))))

(defn- integer-or-nil [value]
  (when (integer? value)
    value))

(defn init--permit-period
  "YA verdict start and end dates are copied from tyoaika document"
  [{:keys [application] :as initmap}]
  (let [doc-data (:data (domain/get-document-by-name application
                                                     "tyoaika"))
        starts (and (dict-included? initmap :start-date)
                    (integer-or-nil (get-in doc-data
                                            [:tyoaika-alkaa-ms :value])))
        ends (and (dict-included? initmap :end-date)
                  (integer-or-nil (get-in doc-data
                                          [:tyoaika-paattyy-ms :value])))]
    (cond-> initmap
      starts (assoc-in [:draft :data :start-date] starts)
      ends (assoc-in [:draft :data :end-date] ends))))



(defmethod initialize-verdict-draft :r
  [initmap]
  (-> initmap
      (init--dict-by-application :handler general-handler)
      (init--dict-by-application :deviations application-deviations)
      init--foremen
      (init--requirements-references :plans)
      (init--requirements-references :reviews)
      init--verdict-dates
      init--upload
      init--verdict-giver-type
      init--buildings
      (init--dict-by-application :operation application-operation)
      (init--dict-by-application :address :address)))

(defmethod initialize-verdict-draft :p
  [initmap]
  (-> initmap
      (init--dict-by-application :handler general-handler)
      (init--dict-by-application :deviations application-deviations)
      init--verdict-dates
      init--upload
      init--verdict-giver-type
      (init--dict-by-application :operation application-operation)
      (init--dict-by-application :address :address)))

(defmethod initialize-verdict-draft :ya
  [initmap]
  (-> initmap
      (init--dict-by-application :handler general-handler)
      init--verdict-dates
      (init--dict-by-application :verdict-type shared/ya-verdict-type)
      (init--requirements-references :plans)
      (init--requirements-references :reviews)
      init--upload
      init--verdict-giver-type
      (init--dict-by-application :operation application-operation)
      (init--dict-by-application :address :address)
      init--permit-period))

(defmethod initialize-verdict-draft :tj
  [initmap]
  (-> initmap
      (init--dict-by-application :handler general-handler)
      (init--dict-by-application :operation application-operation)
      (init--dict-by-application :address :address)))

(declare enrich-verdict)

(defn user-ref [{user :user}]
  (select-keys user [:id :username]))

(defn new-verdict-draft
  ([template-id command]
    (new-verdict-draft template-id command nil))
  ([template-id {:keys [application organization created] :as command} replacement-id]
   (let [template       (template/verdict-template @organization template-id)
         {draft :draft} (-> {:template    template
                             :draft       (default-verdict-draft template)
                             :application application}
                            initialize-verdict-draft
                            (update-in [:draft]
                                       (fn [draft]
                                         (cond-> (assoc draft
                                                  :id (mongo/create-id)
                                                  :modified created
                                                  :user (user-ref command))

                                           replacement-id
                                           (assoc-in [:replacement :replaces]
                                                     replacement-id)))))]
     (action/update-application command
                                {$push {:pate-verdicts
                                        (sc/validate schemas/PateVerdict draft)}})
     (:id draft))))

(defn new-legacy-verdict-draft
  "Legacy verdicts do not have templates or references. Inclusions
  contain every schema dict."
  [{:keys [application organization created]
    :as   command}]
  (let [category   (shared/application->category application)
        verdict-id (mongo/create-id)]
    (action/update-application command
                               {$push {:pate-verdicts
                                       (sc/validate schemas/PateVerdict
                                                    {:id       verdict-id
                                                     :modified created
                                                     :user     (user-ref command)
                                                     :category (name category)
                                                     :data     {:handler (general-handler application)}
                                                     :template {:inclusions (-> category
                                                                                legacy/legacy-verdict-schema
                                                                                :dictionary
                                                                                dicts->kw-paths)}
                                                     :legacy?  true})}})
    verdict-id))

(declare verdict-schema)

(defn- title-fn [s fun]
  (util/pcond-> (-> s ss/->plain-string ss/trim)
                ss/not-blank? fun))

(defn- verdict-string [lang {:keys [data] :as verdict} dict]
  (title-fn (dict data)
            (fn [value]
              (when-let [{:keys [reference-list
                                 select
                                 text]} (some-> (verdict-schema verdict)
                                                :dictionary
                                                dict)]
                (if text
                  value
                  (i18n/localize lang
                                 (:loc-prefix (or reference-list
                                                  select))
                                 value))))))

(defn- verdict-section-string [{data :data}]
  (title-fn (:verdict-section data) #(str "\u00a7" %)))

(defn- verdict-summary [lang section-strings
                        {:keys [id data template replacement
                                references category
                                published legacy? schema-version]
                         :as   verdict}]
  (let [replaces (:replaces replacement)
        rep-string (title-fn replaces (fn [vid]
                                        (let [section (get section-strings vid)]
                                          (if (ss/blank? section)
                                            (i18n/localize lang :pate.replacement-verdict)
                                            (i18n/localize-and-fill lang
                                                                    :pate.replaces-verdict
                                                                    section)))))]
    (assoc (select-keys verdict [:id :published :modified :legacy?])
          :giver (if (util/=as-kw (:giver template) :lautakunta)
                   (:boardname references)
                   (:handler data))
          :replaces replaces
          :verdict-date (:verdict-date data)
          :title (->> (cond
                        (util/=as-kw category :contract)
                        [(i18n/localize lang :pate.verdict-table.contract)]

                        published
                        [(get section-strings id)
                         (util/pcond-> (verdict-string lang verdict :verdict-type)
                          ss/not-blank? (str " -"))
                         (verdict-string lang verdict :verdict-code)
                         rep-string]

                        :else
                        [(i18n/localize lang :pate-verdict-draft)
                         rep-string])
                      (remove ss/blank?)
                      (ss/join " ")))))

(defn verdict-list
  [{:keys [lang application]}]
  (let [category (shared/application->category application)
        ;; There could be both contracts and verdicts.
        verdicts        (filter #(util/=as-kw category (:category %))
                                (:pate-verdicts application))
        section-strings (reduce (fn [acc v]
                                  (assoc acc (:id v) (verdict-section-string v)))
                                {}
                                verdicts)
        summaries       (reduce (fn [acc v]
                                  (assoc acc
                                         (:id v)
                                         (verdict-summary lang
                                                          section-strings
                                                          v)))
                                {}
                                verdicts)
        replaced        (->> (vals summaries)
                             (map :replaces)
                             (remove nil?)
                             set)]
    (loop [[x & xs] (->> (vals summaries)
                         (sort-by :modified)
                         reverse)
           result   []]
      (cond
        (nil? x) result
        (contains? replaced (:id x)) (recur xs result)
        :else (recur xs (concat result
                                (loop [{:keys [replaces] :as v} x
                                       sub []]
                                  (if replaces
                                    (recur (assoc (get summaries replaces)
                                                  :replaced? true)
                                           (conj sub (dissoc v :replaces)))
                                    (conj sub (dissoc v :replaces))))))))))

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

(defn- verdict-attachment-ids
  "Ids of attachments, whose either target or source is the given
  verdict."
  [{:keys [attachments]} verdict-id]
  (->> attachments
       (filter (fn [{:keys [target source]}]
                 (or (and (util/=as-kw (:type target) :verdict)
                          (= (:id target) verdict-id))
                     (and (util/=as-kw (:type source) :verdicts)
                          (= (:id source) verdict-id)))))
       (map :id)))

(defn delete-verdict [verdict-id {application :application :as command}]
  (action/update-application command
                             {$pull {:pate-verdicts {:id verdict-id}}})
  (att/delete-attachments! application
                           (verdict-attachment-ids application verdict-id)))

(defn delete-verdict-tasks-helper
  "Identifies verdict tasks and their attachments. Separate function
  since called also upon replacing a verdict. Returns a map
  with :task-ids and :task-attachment-ids keys."
  [application verdict-id]
  (let [task-ids (old-verdict/deletable-verdict-task-ids application verdict-id)]
    {:task-ids            task-ids
     :task-attachment-ids (map :id (old-verdict/task-ids->attachments application
                                                                      task-ids))}))

(defn delete-legacy-verdict [{:keys [application user created] :as command}]
  ;; Mostly copied from the old verdict_api.clj.
  (let [{verdict-id :id
         published  :published
         :as        verdict}          (command->verdict command)
        target                        {:type "verdict" :id verdict-id} ; key order seems to be significant!
        {:keys [sent state
                pate-verdicts]}       application
        ;; Deleting the only given verdict? Return sent or submitted state.
        step-back?                    (and published
                                           (= 1 (count (filter :published pate-verdicts)))
                                           (states/verdict-given-states (keyword state)))
        {:keys [task-ids
                task-attachment-ids]} (delete-verdict-tasks-helper application verdict-id)
        updates                       (merge {$pull {:pate-verdicts {:id verdict-id}
                                                     :comments      {:target target}
                                                     :tasks         {:id {$in task-ids}}}}
                                             (when step-back?
                                               (app-state/state-transition-update
                                                (if (and sent
                                                         (sm/valid-state? application
                                                                          :sent))
                                                  :sent
                                                  :submitted)
                                                created
                                                application
                                                user)))]
      (action/update-application command updates)
      ;;(bulletins/process-delete-verdict id verdict-id)
      (att/delete-attachments! application
                               (->> task-attachment-ids
                                    (concat (verdict-attachment-ids application verdict-id))
                                    (remove nil?)))

      ;;(appeal-common/delete-by-verdict command verdict-id)
      (when step-back?
        (notifications/notify! :application-state-change command))))

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

(defn update-automatic-verdict-dates
  "Returns map of dates (timestamps). While the calculation takes every date into
  account, the result only includes the dates included in the
  template."
  [{:keys [template references verdict-data] :as args}]
  (let [timestamp   (:verdict-date verdict-data)
        date-deltas (:date-deltas references)
        automatic?  (:automatic-verdict-dates verdict-data)]
    (when (and automatic? (integer? timestamp))
      (loop [dates      {}
             [kw & kws] shared/verdict-dates
             latest     timestamp]
        (if (nil? kw)
          (select-keys dates (util/intersection-as-kw shared/verdict-dates
                                                      (:inclusions template)))
          (let [{:keys [delta unit]} (kw date-deltas)
                result (date/parse-and-forward latest
                                               (util/->long delta)
                                               (keyword unit))]
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
                    ;; Dispatcher result: :last-path-part
                    (keyword (last path))))

(defmethod changes :default [_])

(defmethod changes :verdict-date
  [options]
  (update-automatic-verdict-dates options))

(defmethod changes :automatic-verdict-dates
  [options]
  (update-automatic-verdict-dates options))

(defn select-inclusions [dictionary inclusions]
  (->> (map util/split-kw-path inclusions)
       (reduce (fn [acc [x & xs]]
                 (update acc
                         x
                         (fn [v]
                           (if (seq xs)
                             (conj v (util/kw-path xs))
                             []))))
               {})
       (reduce-kv (fn [acc k v]
                    (let [dic-value (k dictionary)]
                      (assoc acc
                             k
                             (if (seq v)
                               (assoc dic-value
                                      :repeating (select-inclusions
                                                  (-> dictionary k :repeating)
                                                  v))
                               dic-value))))
                  {})))

(defn- verdict-schema [{:keys [category schema-version legacy? template]}]
  (update (if legacy?
            (legacy/legacy-verdict-schema category)
            (shared/verdict-schema category schema-version))
          :dictionary
          #(select-inclusions % (map keyword (:inclusions template)))))

(defn verdict-filled?
  "Have all the required fields been filled. Refresh? argument can force
  the read from mongo (see command->verdict)."
  ([command refresh?]
   (let [{:keys [data] :as verdict} (command->verdict command refresh?)
         schema (verdict-schema verdict)]
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

(defn- op-description [{primary     :primaryOperation
                        secondaries :secondaryOperations} op]
  (->> (cons primary secondaries)
       (util/find-by-id (:id op))
       :description))

(defn buildings
  "Map of building infos: operation id is key and value map contains
  operation (loc-key), building-id (either national or manual id),
  tag (tunnus), description and order (primary operation is the first)."
  [{primary-op :primaryOperation :as application}]
  (->> application
       app-documents-having-buildings
       (reduce (fn [acc {:keys [schema-info data]}]
                 (let [{:keys [id name] :as op} (:op schema-info)]
                   (assoc acc
                          (keyword id)
                          (util/convert-values
                           {:operation   name
                            :description (op-description application op)
                            :building-id (->> [:valtakunnallinenNumero
                                               :manuaalinen_rakennusnro]
                                              (select-keys data)
                                              vals
                                              (util/find-first ss/not-blank?))
                            :tag         (:tunnus data)
                            :order (if (= id (:id primary-op)) 0 1)}
                           ss/->plain-string))))
               {})))

(defn- building-update-map [{:keys [building-updates]}]
  (reduce (fn [acc building-update]
            (assoc acc (:operationId building-update)
                   (update building-update :nationalBuildingId not-empty)))
          {}
          building-updates))

(defn ->buildings-array
  "Construction of the application-buildings array. This should be
  equivalent to ->buildings-summary function in
  lupapalvelu.xml.krysp.building-reader the namespace, but instead of
  the message from backing system, here all the input data is
  originating from PATE verdict."
  [application]
  (let [building-updates (building-update-map application)]
    (->> application
         app-documents-having-buildings
         (util/indexed 1)
         (map (fn [[n {toimenpide :data {op :op} :schema-info}]]
                (let [{:keys [rakennusnro valtakunnallinenNumero mitat kaytto tunnus]} toimenpide
                      description-parts (remove ss/blank? [tunnus (op-description application op)])
                      building-update (get building-updates (:id op))
                      location (when-let [loc (:location building-update)] [(:x loc) (:y loc)])
                      location-wgs84 (when location (coord/convert "EPSG:3067" "WGS84" 5 location))]
                  {:localShortId (or rakennusnro (when (validators/rakennusnumero? tunnus) tunnus))
                   :nationalId (or valtakunnallinenNumero (:nationalBuildingId building-update))
                   :buildingId (or valtakunnallinenNumero (:nationalBuildingId building-update) rakennusnro)
                   :location-wgs84 location-wgs84
                   :location location
                   :area (:kokonaisala mitat)
                   :index (str n)
                   :description (ss/join ": " description-parts)
                   :operationId (:id op)
                   :usage (or (:kayttotarkoitus kaytto) "")}))))))

(defn edit-verdict
  "Updates the verdict data. Validation takes the template inclusions
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
                references]
         :as verdict} (command->verdict command)
        {:keys [data value path op]
         :as     processed}    (schemas/validate-and-process-value
                                (verdict-schema verdict)
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


(defn- merge-buildings [app-buildings verdict-buildings]
  (reduce (fn [acc [op-id v]]
            (assoc acc op-id (merge {:show-building true}
                                    (op-id verdict-buildings) v)))
          {}
          app-buildings))

(defn command->category [{app :application}]
  (shared/application->category app))

(defn statements
  "List of maps with given (timestamp), text (string) and
  status (loc-key part) keys."
  [{statements :statements} given-only?]
  (map (fn [{:keys [given person status]}]
         {:text   (:text person)
          :given  given
          :status status})
       (if given-only?
         (filter :given statements)
         statements)))

(defn enrich-verdict
  "Augments verdict data, but MUST NOT update mongo (this is called from
  query actions, too).  If final? is truthy then the enrichment is
  part of publishing."
  ([{:keys [application]} {:keys [data template category published]
                           :as   verdict} final?]
   (let [inc-set (->> template
                      :inclusions
                      (map keyword)
                      set)
         addons  (merge
                  (when (seq (util/intersection-as-kw inc-set
                                                      buildings-inclusion-keys))
                    {:buildings (merge-buildings (buildings application)
                                                 (:buildings data))})
                  (when (:neighbors inc-set)
                    {:neighbor-states (neighbor-states application)})
                  (when (:statements inc-set)
                    {:statements (statements application final?)})
                  (when (and (util/=as-kw :contract category)
                             final?
                             (not published))
                    ;; Verdict giver (handler) is the initial, implicit signer
                    {:signatures
                     {(keyword (mongo/create-id))
                      {:name (:handler data)
                       :date (:verdict-date data)}}}))]
     (assoc verdict :data (merge data addons))))
  ([command verdict]
   (enrich-verdict command verdict false)))

(defn open-verdict [{:keys [application] :as command}]
  (let [{:keys [data published template]
         :as   verdict} (command->verdict command)]
    {:verdict    (assoc (select-keys verdict [:id :modified :published
                                              :category :schema-version
                                              :legacy?])
                        :data (if published
                                data
                                (:data (enrich-verdict command
                                                       verdict)))
                        :inclusions (:inclusions template))
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
  "Section is generated only for non-board (lautakunta) non-legacy
  verdicts."
  [org-id created {:keys [data template legacy?] :as verdict}]
  (let [{section :verdict-section} data
        {giver :giver}             template]
    (cond-> verdict
      (and (ss/blank? section)
           (util/not=as-kw giver :lautakunta)
           (not legacy?))
      (->
       (assoc-in [:data :verdict-section]
                 (next-section org-id
                               created
                               giver))
       (update-in [:template :inclusions]
                  #(distinct (conj % :verdict-section)))))))

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

(defn attachment-items
  "Returns a map with the following properties:

    items: Attachment items (`verdict-attachment-items` result)

    update-fn: Function that takes verdict data as argument and
               updates it."
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

(defn log-task-errors [tasks]
  (when-let [errs (seq (mapv #(tasks/task-doc-validation (-> % :schema-info :name) %) tasks))]
    (doseq [err errs
            :when (seq err)
            sub-error err]
      (warnf "PATE task (%s) validation warning - elem locKey: %s, results: %s"
             (get-in sub-error [:document :id])
             (get-in sub-error [:element :locKey])
             (get-in sub-error [:result])))))

(defn- archive-info
  "Convenience info map is stored into verdict for archiving purposes."
  [{:keys [data template references] :as verdict}]
  (merge {:verdict-date  (:verdict-date data)
          :verdict-giver (if (util/=as-kw :lautakunta (:giver template))
                           (:boardname references)
                           (pdf/join-non-blanks " "
                                                (:handler-title data)
                                                (:handler data)))}
         (when-let [lainvoimainen (:lainvoimainen data)]
           (when (integer? lainvoimainen)
             {:lainvoimainen lainvoimainen}))))

(defn accepted-verdict? [verdict]
  (some #{(keyword (get-in verdict [:data :verdict-code]))} [:hyvaksytty :myonnetty])) ; TODO Which verdict codes are accepted??


(defn link-permit-application [application]
  (->> application
       (meta/enrich-with-link-permit-data)
       (app/get-link-permit-apps)
       (first)))

(defn can-verdict-be-replaced?
  "Modern verdict can be replaced if its published and not already
  replaced (or being replaced). Contracts cannot be replaced."
  [{:keys [pate-verdicts]} verdict-id]
  (when-let [{:keys [published legacy?
                     replacement category]} (util/find-by-id verdict-id
                                                             pate-verdicts)]
    (and published
         (not legacy?)
         (util/not=as-kw category :contract)
         (not (some-> replacement :replaced-by))
         (not (util/find-first #(= (get-in % [:replacement :replaces])
                                   verdict-id)
                               pate-verdicts)))))

(defn replace-verdict [{:keys [application] :as command} old-verdict-id verdict-id]
  (let [{:keys [task-ids
                task-attachment-ids]} (delete-verdict-tasks-helper application
                                                                   old-verdict-id)]
    (action/update-application command
                               {:pate-verdicts {$elemMatch {:id old-verdict-id}}}
                               {$set  {:pate-verdicts.$.replacement {:user        (user-ref command)
                                                                     :replaced-by verdict-id}}
                                $pull {:tasks {:id {$in task-ids}}}})
    (att/delete-attachments! application task-attachment-ids)))

(defn publish-verdict
  "Publishing verdict does the following:
   1. Finalize and publish verdict
   2. Update application state
   3. Inspection summaries
   4. Other document updates (e.g., waste plan -> waste report)
   5. Construct buildings array
   6. Freeze (locked and read-only) verdict attachments and update TOS details
   7. Create tasks (old ones will be overwritten)
   8. Generate section (for non-board verdicts)
   9. Create PDF/A for the verdict
  10. Update date for continuation applications
  11. Generate KuntaGML
  12. TODO: Assignments?

  If the verdict replaces an old verdict, then
  13. Update old verdict's replacement property
  14. Delete old verdict tasks."
  [{:keys [created application user organization] :as command}]
  (let [verdict                (-<>> (command->verdict command)
                                     (enrich-verdict command <> true)
                                     (insert-section (:organization application)
                                                     created))
        next-state             (sm/verdict-given-state application)
        buildings              (->buildings-array application)
        tasks                  (pate-tasks/pate-verdict->tasks verdict
                                                               created
                                                               buildings)
        {att-items :items
         update-fn :update-fn} (attachment-items command verdict)
        verdict                (update verdict :data update-fn)]
    (log-task-errors tasks) ; TODO cancel publishing if validation errors?
    (verdict-update command
                    (util/deep-merge
                     {$set (merge
                            {:pate-verdicts.$.data                (:data verdict)
                             :pate-verdicts.$.template.inclusions (-> verdict
                                                                      :template
                                                                      :inclusions)
                             :pate-verdicts.$.published           created
                             :pate-verdicts.$.archive             (archive-info verdict)
                             :pate-verdicts.$.user                (user-ref command)}
                            {:buildings buildings}
                            (att/attachment-array-updates (:id application)
                                                          #(util/includes-as-kw? (map :id att-items)
                                                                                 (:id %))
                                                          :readOnly true
                                                          :locked   true
                                                          :target {:type "verdict"
                                                                   :id   (:id verdict)}))}
                     (when (seq tasks)
                       {$push {:tasks {$each tasks}}})
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

    (when (and (app/jatkoaika-application? application)
               (accepted-verdict? verdict))
      (app/add-continuation-period
        (link-permit-application application)
        (:id application)
        (get-in verdict [:data :handler])
        (get-in verdict [:data :voimassa])))

    (when-let [replace-verdict-id (get-in verdict [:replacement :replaces])]
      (replace-verdict command replace-verdict-id (:id verdict)))

    (let [application (domain/get-application-no-access-checking (:id application))
          verdict-attachment-id (pdf/create-verdict-attachment (assoc command
                                                                      :application application)
                                                               (assoc verdict :published created))]
      ;; KuntaGML (only for non-legacy verdicts)
      (when (and (not (:legacy? verdict))
                 (org/krysp-integration? @organization (:permitType application)))
        (let [application (domain/get-application-no-access-checking (:id application))]
          (krysp/verdict-as-kuntagml (assoc command :application application)
                                     (assoc verdict :verdict-attachment
                                            (util/find-by-id verdict-attachment-id
                                                             (:attachments application)))))
        nil))))

(defn preview-verdict
  "Preview version of the verdict.
  1. Finalize verdict but do not store the changes.
  2. Generate PDF and return it."
  [{:keys [lang application created] :as command}]
  (let [{:keys [error
                pdf-file-stream
                filename]} (-<>> (command->verdict command)
                                 (enrich-verdict command <> true)
                                 (pdf/create-verdict-preview command))]
    (if error
      {:status 503 ;; Service Unavailable
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (let [msg (i18n/localize lang error)]
               (rum/render-static-markup
                [:html
                 [:head [:title msg]]
                 [:body
                  [:div
                   {:style {:margin "2em 2em"
                            :border "2px solid red"
                            :padding "1em 1em"}}
                   [:h3 {:style {:margin-top 0}} msg]
                   [:a {:href (str "/api/raw/preview-pate-verdict?"
                                   (codec/form-encode (:data command)))}
                    (i18n/localize lang :pate.try-again)]]]]))}
      {:status  200
       :headers {"Content-Type"        "application/pdf"
                 "Content-Disposition" (format "filename=\"%s\"" filename)}
       :body    pdf-file-stream})))

(defn latest-published-pate-verdict
  [{:keys [application] :as command}]
  (->> (:pate-verdicts application)
       (filter #(some? (:published %)))
       (sort-by :published)
       (last)))

(defn user-can-sign? [{:keys [application user] :as command}]
  (let [sigs  (some-> (command->verdict command)
                      :data :signatures vals)
        {com-id :id} (auth/auth-via-company application user)]
    (not (util/find-first (fn [{:keys [user-id company-id]}]
                            (or (= user-id (:id user))
                                (and com-id
                                     (= com-id company-id))))
                          sigs))))

(defn- add-signature
  "Returns verdict with the user's signature added."
  [{:keys [application user created]} verdict]
  (let [person            (ss/trim (format "%s %s"
                                           (:firstName user)
                                           (:lastName user)))
        {company-id :id} (auth/auth-via-company application
                                                 user)]
    (assoc-in verdict [:data :signatures (keyword (mongo/create-id))]
              (cond-> {:user-id (:id user)
                       :date created
                       :name person}
                company-id (assoc
                            :company-id company-id
                            ;; Get the up-to-date company name just in case
                            :name (->> (com/find-company-by-id company-id)
                                       :name
                                       (format "%s, %s" person)))))))

(defn sign-contract
  "Sign the contract
   - Update verdict data
   - Generate new contract attachment version."
  [{:keys [user created application] :as command}]
  (let [verdict (add-signature command
                               (command->verdict command))]

    (verdict-update command
                    (util/deep-merge
                     {$set {:pate-verdicts.$.data (:data verdict)}}
                     (when (util/not=as-kw (:state application)
                                           :agreementSigned)
                       (app-state/state-transition-update :agreementSigned
                                                          created
                                                          application
                                                          user))))

    (pdf/create-verdict-attachment-version
     (assoc command
            :application
            (domain/get-application-no-access-checking (:id application)))
     verdict)))
