(ns lupapalvelu.ui.matti.docgen
  "Rudimentary support for docgen subset in the Matti context."
  (:require [lupapalvelu.matti.shared :as shared]
            [lupapalvelu.ui.common :as common]
            [lupapalvelu.ui.components :as components]
            [lupapalvelu.ui.matti.path :as path]
            [rum.core :as rum]
            [sade.shared_util :as util]))

(defn docgen-loc [options & extra]
  (path/loc options extra))

(defn docgen-type [{schema :schema}]
  (-> schema :body first :type keyword))

(defmulti docgen-label-wrap (fn [options & _]
                              (docgen-type options)))

(defmethod docgen-label-wrap :default
  [{:keys [schema path] :as options} component]
  [:div.col--vertical
   (if (-> schema :body first :label false?)
     (common/empty-label)
     [:label.matti-label {:for (path/id path)}
      (docgen-loc options)])
   component])

(defn warning? [{:keys [path state]}]
  (path/react (cons :_errors path) state ))

(defn docgen-attr [{:keys [path state] :as options} & kv]
  (let [id (path/id path)]
    (merge {:key id
            :id  id
            :disabled (path/disabled? options)
            :class (common/css-flags :warning (warning? options))}
           (apply hash-map kv))))

(defn- state-change [{:keys [state path] :as options}]
  (let [handler (common/event->state (path/state path state))]
    (fn [event]
      (when (handler event)
        (path/meta-updated options)))))

;; ---------------------------------------
;; Components
;; ---------------------------------------

(rum/defc docgen-select < rum/reactive
  [{:keys [schema state path] :as options}]
  (let [local-state (path/state path state)
        sort-fn (if (some->> schema :body first
                             :sortBy (util/=as-kw :displayName))
                  (partial sort-by :text)
                  identity)]
    [:select.dropdown
     (docgen-attr options
                  :value     (rum/react local-state)
                  :on-change (state-change options))
     (->> schema :body first
          :body
          (map (fn [{n :name}]
                 {:value n
                  :text  (if-let [item-loc (:item-loc-prefix schema)]
                           (path/loc [item-loc n])
                           (docgen-loc options n))}))
          sort-fn
          (cons {:value ""
                 :text  (common/loc "selectone")})
          (map (fn [{:keys [value text]}]
                 [:option {:key value :value value} text])))]))

(rum/defc docgen-checkbox < rum/reactive
  [{:keys [schema state path] :as options}]
  (let [state*    (path/state path state)
        input-id  (path/unique-id "checkbox-input")]
    [:div.matti-checkbox-wrapper (docgen-attr options)
     [:input (docgen-attr options
                          :type    "checkbox"
                          :checked (rum/react state*)
                          :id      input-id)]
     [:label.matti-checkbox-label
      {:for      input-id
       :on-click (fn [_]
                   (swap! state* not)
                   (path/meta-updated options))}
      (when-not (false? (:label schema))
        (docgen-loc options))]]))

(rum/defc docgen-radio-group < rum/reactive
  [{:keys [schema state path] :as options}]
  (let [state (path/state path state)
        checked (rum/react state)]
    [:div
     (->> schema
          :body first :body
          (map (fn [{n :name}]
                 (let [radio-path (path/extend path n)
                       radio-id   (str (path/id radio-path) "radio")]
                   [:div.matti-radio-wrapper
                    (docgen-attr {:path radio-path})
                    [:input {:type    "radio"
                             :checked  (= n checked)
                             :value    n
                             :name     (path/id path)
                             :id       radio-id}]
                   [:label.matti-radio-label
                    {:for      radio-id
                     :on-click (fn [_]
                                 (when (common/reset-if-needed! state n)
                                   (path/meta-updated options)))}
                    (docgen-loc options n)]]))))]))


(rum/defcs text-edit < (rum/local "" ::text)
  rum/reactive
  {:key-fn (fn [_ {path :path} _ & _] (path/id path))}
  "Update the options model state only on blur. Immediate update does
  not work reliably."
  [local-state {:keys [schema state path] :as options} tag & [attr]]
  (let [text* (::text local-state)
        state (path/state path state)]
    (common/reset-if-needed! text* @state)
    [tag
     (merge (docgen-attr options
                         :disabled  (-> schema :body first :readonly)
                         :value     @text*
                         :on-change identity ;; A function is needed
                         :on-blur   (state-change options))
            attr)]))

(rum/defc docgen-date < rum/reactive
  [{:keys [schema path state] :as options}]
  (components/date-edit (path/state path state)
                        (docgen-attr options
                                     :callback #(path/meta-updated options))))

;; ---------------------------------------
;; Component dispatch
;; ---------------------------------------

(defn docgen-component [options]
  (case (docgen-type options)
    :select     (docgen-select options)
    :checkbox   (docgen-checkbox options)
    :radioGroup (docgen-radio-group options)
    :string     (text-edit options :input.grid-style-input {:type "text"})
    :text       (text-edit options :textarea.grid-style-input)
    :date       (docgen-date options)))

;; ---------------------------------------
;; Docgen view components
;; ---------------------------------------

(defmulti docgen-view docgen-type)

(defmethod docgen-view :default
  [{:keys [schema state path] :as options}]
  [:span.formatted (docgen-attr options)
   (rum/react (path/state path state))])

(defmethod docgen-view :select
  [{:keys [schema state path] :as options}]
  [:span (docgen-attr options)
   (when-let [v (not-empty (rum/react (path/state path state)))]
     (docgen-loc options v))])

(defmethod docgen-view :checkbox
  [{:keys [schema state path] :as options}]
  (when (rum/react (path/state path state))
    [:span.matti-checkbox (docgen-attr options)
     (docgen-loc options)]))
