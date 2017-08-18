(ns lupapalvelu.ui.matti.path
  (:require [clojure.string :as s]
            [lupapalvelu.matti.shared :as shared]
            [lupapalvelu.ui.common :as common]
            [rum.core :as rum]
            [sade.shared-util :as util]))

(defn state [path state]
  (rum/cursor-in state (map keyword path)))

(defn extend [path & extra]
  (->> (concat [path] extra)
       flatten
       (remove nil?)
       (map keyword)))

(defn value [path state* & extra]
  @(state (extend path extra) state*))

(defn react [path state* & extra]
  (rum/react (state (extend path extra) state*)))

(defn id [path]
  (s/replace (->> (filter identity path)
                  (map name)
                  (s/join "-"))
             "." "-"))

(defn loc
  ([path]
   (->> (filter identity path)
        (map name)
        ;; Index (number) path parts are ignored.
        (remove #(re-matches #"^\d+$" %))
        (s/join ".")
        common/loc))
  ([path {:keys [i18nkey] :as schema} & extra]
   (let [loc-prefix (or (some-> schema :body first :locPrefix)
                        (shared/parent-value schema :loc-prefix))
         i18nkey (or i18nkey (some-> schema :body first :i18nkey))
         i18nkey (and i18nkey (flatten [i18nkey]))]
     (if (> (count i18nkey) 1)
       (->> (concat i18nkey extra)
            (remove nil?)
            (reduce #(common/loc %2 (common/loc %1))))
       (-> (cond
            i18nkey    i18nkey
            loc-prefix (flatten (concat [loc-prefix] [(last path)]))
            :else      path)
           (concat extra)
           flatten
           loc)))))

(defn latest-helper
  "The latest non-nil value for the given key in the state* along the
  path. Path is shortened until the value is found. Nil if value not
  found. Key can be also be key sequence. Value-fn is either value or
  react."
  [value-fn path state* & key]
  (let [v (value-fn path state* key)]
    (if (or (some? v) (empty? path))
      v
      (latest-helper value-fn (drop-last path) state* key))))

(defn latest
  "The latest non-nil value for the given key in the state* along the
  path. Path is shortened until the value is found. Nil if value not
  found. Key can be also be key sequence."
  [path state* & key]
  (latest-helper value path state* key))

(defn meta?
  "Truthy if _meta value for the given key is found either within
  _meta options or as latest _meta from the state."
  [{:keys [state path _meta]} key]
  (or (get _meta (keyword key))
      (latest path state :_meta key)))

(defn react-meta?
  "Truthy if _meta value for the given key is found either within
  _meta options or as latest _meta from the state."
  [{:keys [state path _meta]} key]
  (or (get _meta (keyword key))
      (latest-helper react path state :_meta key)))

(defn flip-meta
  "Flips (toggles boolean) on the path _meta."
  [{state* :state path :path} key]
  (swap! (state (extend path :_meta key) state*) not))

(defn css
  "List of CSS classes based on current :css value and status
  classes (matti--edit, matti--view) from the latest _meta."
  [options & other-classes]
  (->> [(if (meta? options :editing?) "matti--edit" "matti--view")
        (:css options)
        other-classes]
       flatten
       (remove nil?)
       (map name)))

(defn meta-updated
  "Calls _meta :updated if defined."
  [{:keys [state path] :as options}]
  (when-let [fun (latest path state :_meta :updated)]
    (fun options)))

(defn key-fn
  "Rum key-fn, where key is the path id."
  [{path :path}]
  (id path))

(defn pathify
  "Splits kw-path if necessary."
  [path]
  (if (keyword? path)
    (util/split-kw-path path)
    path))

(defn- truthy [v]
  (cond
    (sequential? v) (seq v)
    (string? v) (seq v)
    :else v))

(defn- path-truthy [{state :state :as options} kw-path]
  (let [[x & [k] :as path] (pathify kw-path)]
    (truthy (if (= x :_meta )
              (react-meta? options k )
              (react path state)))))

(defn- paths-result
  "Result is calculated be calling op-fn (e.g., some) to path-truthy
  results of every path. Result is nil for empty/nil paths."
  [options op-fn paths]
  (let [paths (->> [paths] flatten (remove nil?))]
    (when (seq paths)
      (->> paths
           (map (partial path-truthy options))
           (op-fn identity )))))

(defn- flag?
  "not-any-flag: schema key for NOT-ANY (or empty/nil) condition.
   or-flag:  schema key for ANY (or empty/nil) condition.
  True if either conditions met."
  [{:keys [state schema] :as options} not-any-flag or-flag]
  (let [results (->> [(->> schema not-any-flag (paths-result options not-any?))
                      (->> schema or-flag (paths-result options some))]
                     (remove nil?))]
    (when (seq results)
      (some identity results))))

(defn disabled?
  "Component disabled status as defined in MattiEnabled schema."
  [options]
  (flag? options :enabled? :disabled?))

(defn enabled? [options] (not (disabled? options)))

(defn not-visible?
  "Component not-visible status as defined in MattiVisible schema."
  [options]
  (flag? options :show? :hide?))

(defn visible? [options] (not (not-visible? options)))
