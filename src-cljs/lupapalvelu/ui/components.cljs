(ns lupapalvelu.ui.components
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [>! <!] :as async]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as s]
            [clojure.walk :as walk]
            [lupapalvelu.pate.markup :as markup]
            [lupapalvelu.ui.common :as common]
            [lupapalvelu.ui.components.datepicker :as datepicker]
            [lupapalvelu.ui.hub :as hub]
            [rum.core :as rum]
            [sade.shared-util :as util])
  (:import [goog.async Delay]))

(defn atom-on-change
  "Common 'on-change' event option: value atom is reset with element value."
  [value*]
  {:on-change #(reset! value* (.. % -target -value))})

(rum/defc select [change-fn data-test-id value options & [classes]]
  [:select
   {:class (or classes "form-entry is-middle")
    :on-change    #(change-fn (.. % -target -value))
    :data-test-id data-test-id
    :value        value}
   (map (fn [[k v]] [:option {:key k :value k} v]) options)])

(rum/defc autofocus-input-field < rum/reactive
                                  {:did-mount #(-> % rum/dom-node .focus)}
  [value data-test-id commit-fn]
  (let [val (atom value)]
    [:input {:type          "text"
             :value         @val
             :on-change     #(reset! val (-> % .-target .-value))
             :on-blur       #(commit-fn @val)
             :on-key-press  #(when (= "Enter" (.-key %))
                               (do
                                 (.preventDefault %)
                                 (.stopPropagation %)
                                 (commit-fn @val)))
             :data-test-id  data-test-id}]))

(defn confirm-dialog [titleKey messageKey callback]
  (hub/send "show-dialog"
           {:ltitle titleKey
            :size   "medium"
            :component "yes-no-dialog"
            :componentParams #js {:ltext     (name messageKey)
                                  :yesFn     callback
                                  :lyesTitle "ok"
                                  :lnoTitle  "cancel"}}))

(defn initial-value-mixin
  "Assocs to component's local state local-key with atom that is
  initialized to the first component argument. If the argument is an
  atom or cursor it is used as is (aka poor man's two-way binding)"
  [local-key]
  (letfn [(update-state [state]
            (let [value (-> state :rum/args first)]
              (assoc state local-key (common/atomize value))))]
    {:will-mount  update-state
     :did-remount #(update-state %2)}))


(defn- text-options [text* {:keys [callback required?
                                   test-id immediate?
                                   class] :as options}]
  ;; Textarea/input can lose focus without blur when the typing starts if the
  ;; contents are nil.
  (when (nil? @text*)
    (reset! text* ""))
  (merge {:value     @text*
          :class     (concat (or class [])
                             (common/css-flags :required (and required?
                                                              (-> text* rum/react s/blank?))))
          :on-change (fn [event]
                       (let [value (.. event -target -value)]
                         (common/reset-if-needed! text* value)
                         (when (and callback immediate?)
                           (callback value))))}
         (when (and callback (not immediate?))
           {:on-blur #(callback (.. % -target -value))})
         (common/add-test-id {} test-id)
         (dissoc options :callback :required? :test-id :immediate? :class)))

;; Arguments Initial value, options
;; Initial value can be atom for two-way binding (see the mixin).
;; Options [all optional]
;;  callback   change callback. Called depending on the immediate? option.
;;  required?  truthy if required.
;;  test-id    data-test-id attribute value
;;  immediate? If true, callback is called on change (default on blur).
;; In addition all the Rum options can be passed as well.
(rum/defcs text-edit < (initial-value-mixin ::text)
  rum/reactive
  [local-state _ & [options]]
  (let [text* (::text local-state)]
    [:input.grid-style-input
     (merge {:type (get options :type :text)}
            (text-options text* options))]))

;; The same arguments as for text-edit.
(rum/defcs textarea-edit < (initial-value-mixin ::text)
  rum/reactive
  [local-state _ & [options]]
  (let [text* (::text local-state)]
    [:textarea.grid-style-input
     (text-options text* options)]))

(rum/defcs text-and-button < (initial-value-mixin ::text)
  rum/reactive
  "Text input with button.
   Initial value can be atom for two-way binding (see the mixin).
   Options [optional]:
     [icon:] Button icon (default :lupicon-save)
     [button-class:] Default :primary
     callback: Callback function
     [disabled?:] Is component disabled
     [input-type:] Default text."
  [{text* ::text} _ {:keys [icon button-class callback
                            disabled? input-type]
                     :as   options}]
  [:span.text-and-button
   (text-edit text* (merge {:disabled  disabled?
                            :type      input-type
                            :on-key-up #(when (and (not (s/blank? @text*))
                                                   (= (.-keyCode %) 13))
                                          (callback @text*))}
                           (dissoc options
                                   :icon :button-class :callback
                                   :disabled? :input-type)))
   [:button
    {:class    (common/css (or button-class :primary))
     :disabled (or disabled? (s/blank? (rum/react text*)))
     :on-click #(callback @text*)}
    [:i {:class (common/css (or icon :lupicon-save))}]]])

(defn default-items-fn [items]
  (fn [term]
    (let [fuzzy (common/fuzzy-re term)]
      (filter #(re-find fuzzy (:text %)) items))))

(defn- scroll-element-if-needed [container elem]
  (when container
    (let [scroll-top      (.-scrollTop container)
          container-top    (.-offsetTop container)
          container-height (.-offsetHeight container)
          scroll-bottom    (+ scroll-top container-height)
          elem-top         (- (.-offsetTop elem) container-top)
          elem-height      (.-offsetHeight elem)
          elem-bottom      (+ elem-top elem-height)
          scroll           (cond
                             (< elem-top scroll-top)
                             elem-top
                             (> elem-bottom scroll-bottom)
                             (+ (- elem-top container-height)
                                elem-height))]
      (when (integer? scroll)
        (aset container "scrollTop" scroll)))))

(defn- set-selected  [selected* value callback]
  (when (common/reset-if-needed! selected* value)
    (when callback
      (callback value))))

(defn- complete-parts
  "Very (too?) straigh-forward DRY solution for sharing code between
  autocomplete and combobox. Returns map with :items-fn :text-edit
  and :menu-items."
  [local-state
   {:keys [items callback combobox?]}
   text-edit-options]
  (let [{selected* ::selected
         term*     ::term
         current*  ::current
         open?*    ::open?} local-state
        items-fn            (if (fn? items)
                              items
                              (default-items-fn items))
        items               (vec (cond->> (items-fn (rum/react term*))
                                   combobox? (mapv #(assoc % :value (:text %)))))]
    (letfn [(close []
              (reset! open?* false)
              (reset! current* 0))
            (select [value]
              (when value
                (set-selected (or selected* term*) value callback))
              (when-not combobox?
                (close)))
            (inc-current [n]
              (when (pos? (count items))
                (do (swap! current* #(rem (+ (or % 0) n) (count items)))
                    (scroll-element-if-needed (rum/ref local-state "autocomplete-items")
                                              (rum/ref local-state (str "item-" @current*))))))]
      {:items-fn   items-fn
       :text-edit  (text-edit (if combobox?
                                term*
                                @term*)
                              (merge {:callback    (fn [text]
                                                     (reset! term* text)
                                                     (common/reset-if-needed! current* 0))
                                      :immediate?  true
                                      :auto-focus  (not combobox?)
                                      :on-blur     (fn []
                                                     ;; Delay is needed to make sure
                                                     ;; that possible selection will
                                                     ;; be processed and the menu will
                                                     ;; not accidentally reopen.
                                                     (.start (Delay. close 200))
                                                     (when combobox?
                                                       (callback @term*)))
                                      :on-key-down #(case (.-keyCode %)
                                                      ;; Enter
                                                      13 (do
                                                           (select (some->> @current*
                                                                            (get items)
                                                                            :value))
                                                           (.preventDefault %))
                                                      ;; Esc
                                                      27 (when-not combobox?
                                                           (close))
                                                      ;; Up
                                                      38 (inc-current (- (count items) 1))
                                                      ;; Down
                                                      40 (inc-current 1)
                                                      :default)}
                                     text-edit-options))
       :menu-items [:ul.ac__items
                    {:ref "autocomplete-items"}
                    (loop [[x & xs]   items
                           index      0
                           last-group ""
                           li-items   []]
                      (if x
                        (let [{:keys [text value group]} x
                              group                      (-> (or group "") s/trim)
                              li-items                   (if (or (s/blank? group)
                                                                 (= group last-group))
                                                           li-items
                                                           (conj li-items [:li.ac--group group]))]
                          (recur xs
                                 (inc index)
                                 group
                                 (conj li-items
                                       [:li
                                        {:on-click       #(select value)
                                         :ref            (str "item-" index)
                                         :on-mouse-enter #(reset! current* index)
                                         :class          (common/css-flags :ac--current (= (rum/react current*)
                                                                                           index)
                                                                           :ac--grouped (not (s/blank? group)))}
                                        text])))
                        li-items))]})))


(rum/defcs combobox < (initial-value-mixin ::term)
  rum/reactive
  (rum/local 0 ::current) ;; Current term
  (rum/local false ::open?)
  (rum/local "" ::latest)
  "Parameters: initial-value options
   Initial value can be atom for two-way binding (see the mixin).
   Options [optional]:
     items  either list or function. The function argument is the
            filtering term. An item is a map with a mandatory :text
            and optional :group key.
     [callback] change callback that is called when after list selection or blur.
     [disabled?] Is component disabled? (false)
     [required?] Is component required? (false)"
  [{open?*   ::open?
    term*    ::term
    latest*  ::latest
    :as      local-state} _ {:keys [callback required? disabled?] :as options}]
  (let [{:keys [text-edit
                menu-items
                items-fn]} (complete-parts local-state
                                           (assoc options
                                                  :callback #(when (not= % @latest*)
                                                               (do
                                                                 (reset! latest* %)
                                                                 (callback %)))
                                                  :combobox? true)
                                           {:on-focus  #(common/reset-if-needed! open?* true)
                                            :required? required?
                                            :disabled  disabled?})]
    [:div.pate-autocomplete
     [:div.ac--combobox text-edit]
     (let [items (items-fn @term*)]
       (when (and (rum/react open?*)
                  (seq (filter (fn [{:keys [text group]}]
                                 (and (s/blank? group)
                                      (not= (s/trim @term*) text)))
                               items)))
         [:div.ac__menu menu-items]))]))

(rum/defcs autocomplete < (initial-value-mixin ::selected)
  rum/reactive
  (rum/local "" ::term)    ;; Filtering term
  (rum/local 0 ::current)  ;; Currently highlighted item
  (rum/local false ::open?)
  "Parameters: initial-value options
   Initial value can be atom for two-way binding (see the mixin).
   Options [optional]:
     items  either list or function. The function argument is the
            filtering term. An item is a map with mandatory :text
            and :value keys and optional :group key.
     [callback] change callback
     [clear?] if truthy, clear button is shown when proper (default
              false).
     [disabled?] Is component disabled? (false)
     [required?] Is component required? (false)
     [placeholder] Placeholder text, visible if initial value is empty string. Note
                   that you probably have to make sure value empty string as value
                   will work on calling code
     [test-id] data-test-id attribute for the top-level div."
  [{selected* ::selected
    term*     ::term
    open?*    ::open?
    :as       local-state} _ {:keys [clear? callback disabled? required? placeholder test-id] :as options}]
  (let [{:keys [text-edit
                menu-items
                items-fn]} (complete-parts local-state
                                           options
                                           {})

        open? (rum/react open?*)
        selected (rum/react selected*)]
    (when-not open?
      (common/reset-if-needed! term* ""))
    [:div.pate-autocomplete
     (common/add-test-id {} test-id)
     [:div.like-btn.ac--selected
      {:on-click (when-not disabled?
                   #(swap! open?* not))
       :class    (common/css-flags :disabled disabled?
                                   :required (and required?
                                                  (s/blank? selected)))}
      [:span (if (and (s/blank? selected) placeholder)
               placeholder
               (:text (util/find-first #(util/=as-kw (:value %)
                                                     selected)
                                       (items-fn ""))))]
      [:i.primary.ac--chevron
       {:class (common/css-flags :lupicon-chevron-small-down (not open?)
                                 :lupicon-chevron-small-up   open?)}]
      (when (and clear?
                 (not (s/blank? selected))
                 (not disabled?))
        [:i.secondary.ac--clear.lupicon-remove
         {:on-click (fn [event]
                      (set-selected selected* "" callback)
                      (.stopPropagation event))}])]
     (when open?
       [:div.ac__menu
        [:div.ac__term text-edit]
        menu-items])]))

(rum/defcs dropdown < rum/reactive
  (initial-value-mixin ::selected)
  "Dropwdown is a styled select.
   Parameters: initial-value options
   Options [optional]:

     items: list of :value, :text maps. Items are rendered ordered by
            text.

     [sort-by] Either :text or :value.

     [choose?] If true, the select caption (empty selection) is
     included. (default true)

     [callback] Selection callback
     [disabled?] See common/resolve-disabled
     [enabled?]  See common/resolve-disabled
     [required?] If true, the select is highlighted if empty.
     [test-id]   Data-test-id attribute value

   The rest of the options are passed to the underlying select."
  [{selected* ::selected} _ {:keys    [items choose? callback required?
                                       test-id]
                             sort-key :sort-by
                             :as      options}]
  (let [value   (rum/react selected*)
        choose? (-> choose? false? not)]
    [:select.dropdown
     (merge {:value     value
             :on-change #(set-selected selected* (.. % -target -value) callback)
             :disabled  (common/resolve-disabled options)}
            (common/update-css (dissoc options
                                       :items :choose? :callback
                                       :disabled? :enabled? :required?
                                       :sort-by :test-id)
                               :required (and required? (s/blank? value)))
            (common/add-test-id {} test-id))
     (cond->> items
       sort-key (sort-by sort-key
                         (if (= sort-key :text)
                           js/util.localeComparator
                           identity))
       choose?  (cons {:text  (common/loc :selectone)
                       :value ""})
       true     (map (fn [{:keys [text value]}]
                       [:option {:key   value
                                 :value value} text])))]))

(def log (.-log js/console))

;; Prettyprints the contents of the given atom.
(rum/defc debug-atom < rum/reactive
  ([atom* title]
   [:div.pprint
    [:div.title [:h4 title]]
    [:a {:on-click #(log @atom*)} "console"]
    [:div.code (with-out-str (pprint (rum/react atom*)))]])
  ([atom*] (debug-atom atom* "debug")))

;; Special options (all optional):
;;   callback: on-blur callback
;; The rest of the options are passed to the underlying input.
;; Note: date format is always the Finnish format (21.8.2017).
(rum/defcs date-edit < rum/reactive
  (initial-value-mixin ::date)
  (datepicker/date-state-mixin ::date {:format "D.M.YYYY"})
  [{date* ::date} _ {:keys [callback] :as options}]
  [:input.dateinput.dateinput--safe
   (merge {:type      "text"
           :value     @date*
           :on-blur #(set-selected date*
                                   (.. % -target -value)
                                   callback)}
          (dissoc options :callback))])

(rum/defc text-and-link < rum/reactive
"Renders text with included link
 Options (text and text-loc are mutually exclusive) [optional]:
   text Text where link is in brackets: 'Press [me] for details'
   text-loc Localization key for text.
   click Function to be called when the link is clicked
   [test-id] Test-id for the link element

   [disabled?] See `common/resolve-disabled`
   [enabled?]  See `common/resolve-disabled`"
  [{:keys [click test-id] :as options}]
  (let [regex          #"\[(.*)\]"
        text           (common/resolve-text options)
        link           (last (re-find regex text))
        ;; Split results include the link
        [before after] (remove #(= link %) (s/split text regex))
        disabled?      (common/resolve-disabled options)]
    [:span {:class (common/css-flags :disabled disabled?)}
     before
     (common/add-test-id (if disabled?
                           link
                           [:a {:on-click click} link])
                         test-id)
     after]))

(rum/defc icon-button < rum/reactive
  "Button with optional icon and waiting support
   Options (text and text-loc are mutually exclusive) [optional]

     [text or text-loc] See `common/resolve-text`.

     [icon] Icon class for the button (e.g., :lupicon-save)

     [wait?] If true the button is disabled and the wait icon is
     shown (if the icon has been given). Can be either value or
     atom. Atom makes the most sense for the typical use cases.

     [disabled?] See `common/resolve-disabled`
     [enabled?]  See `common/resolve-disabled`

     [class] Class definitions that are processed with `common/css`.

     [test-id] Test id for the button.

   Any other options are passed to the :button
   tag (e.g, :on-click). The only exception is :disabled,
   since it is overridden with :disabled?"
  [{:keys [icon wait? class test-id] :as options}]
  (let [waiting? (rum/react (common/atomize wait?))
        text     (common/resolve-text options)]
    [:button
     (-> (dissoc options
                 :text :text-loc :icon :wait?
                 :disabled? :enabled? :class :test-id)
         (assoc :disabled (or (common/resolve-disabled options)
                              waiting?)
                :class (common/css class))
         (common/add-test-id test-id))
     (when icon
       [:i {:class (common/css (if waiting?
                                 [:icon-spin :lupicon-refresh]
                                 icon))}])
     (when text [:span text])]))

(rum/defc link-button < rum/reactive
  "Link that is rendered as button.
   Options:
     url: Link url

   In addition, text, text-loc, enabled? and disabled? options are
   supported."
  [{:keys [url] :as options}]
  (let [disabled? (common/resolve-disabled options)
        text      (common/resolve-text options)]
    (if disabled?
      [:span.btn.primary.outline.disabled text]
      [:a.btn.primary.outline
       {:href   url
        :target :_blank}
       text])))

(rum/defcs toggle < rum/reactive
  (initial-value-mixin ::value)
  "Toggle component (using checkbox wrapper mechanism and intial-value-mixin).
   Parameters: initial-value options
   Options [optional]:
   text or text-loc See common/resolve-text
   [disabled?]      See common/resolve-disabled
   [enabled?]       See common/resolve-disabled
   [callback]       Toggle callback function. Optional because sometimes
                    the two-way binding via the mixin is enough.
   [negate?]        If true, the value is negated (default false)
   [prefix]         Wrapper class prefix (default :pate-checkbox)
   [test-id]        Test id prefix for input and label."
  [{value* ::value} _ {:keys [callback negate? prefix test-id] :as options}]
  (let [input-id (common/unique-id "toggle")
        value-fn (if negate? not identity)
        value    (-> value* rum/react value-fn)
        prefix   (name (or prefix :pate-checkbox))]
    [:div
     {:class (str prefix "-wrapper")
      :key   input-id}
     [:input (common/add-test-id {:type      "checkbox"
                                  :disabled  (common/resolve-disabled options)
                                  :checked   value
                                  :id        input-id
                                  :on-change (fn [_]
                                               (swap! value* not)
                                               (when callback
                                                 (callback (value-fn @value*))))}
                                 test-id :input)]
     [:label
      (common/add-test-id {:class (str prefix "-label")
                           :for   input-id}
                          test-id :label)
      (common/resolve-text options "")]]))

(rum/defcs pen-input < (rum/local "" ::name)
  (rum/local false ::editing?)
  "Editable text via pen button. Options [optional]:

   value:       Initial input value
   callback:    Callback to be called with new value.
   [disabled?]: Is the editing disabled.

   [test-id]: Prefix for test-ids. If given the test-ids are:
              prefix-input, prefix-text, prefix-edit and prefix-save,
              where prefix is the given test-id."
  [{name ::name editing? ::editing?} {:keys [value callback disabled?
                                             test-id]}]
  (letfn [(save-fn []
            (reset! editing? false)
            (callback @name))
          (tid [target k]
            (cond-> target
              test-id (common/add-test-id test-id k)))]
    (if @editing?
      [:span.pen-input--edit
       [:input.grid-style-input.row-text
        (tid {:type      "text"
              :value     @name
              :on-change (common/event->state name)
              :on-key-up #(let [key (.-keyCode %)]
                            (cond
                              ;; Save on Enter
                              (and (= key 13)
                                   (not (s/blank? @name)))
                              (save-fn)

                              ;; Cancel on Esc
                              (= key 27)
                              (reset! editing? false)))}
             :input)]
       (icon-button (tid {:class     :primary
                          :disabled? (s/blank? @name)
                          :icon      :lupicon-save
                          :on-click  save-fn}
                         :save))]
      (do (common/reset-if-needed! name value)
          [:span.pen-input--view (tid {} :text) @name
           (when-not disabled?
             [:button.ghost.no-border
              (tid {:on-click #(swap! editing? not)} :edit)
              [:i.lupicon-pen]])]))))


(defn add-key-attrs
  "Adds unique key attribute to every element. Note: the attribute is
  added only if the element has an attribute map (true for
  markup/markup->tags result)."
  [form prefix]
  (walk/prewalk (fn [x]
                  (cond-> x
                    (map? x) (assoc :key (common/unique-id prefix))))
                form))


(rum/defcs markup-span < rum/reactive
  (rum/local "" ::tags)
  "Displays formatted markup. Span class is :markdown and (optional)
  other given classes."
  [{tags* ::tags} markup & class]
  (let [chn (async/chan)]
    (go (>! chn (markup/markup->tags markup)))
    (go (common/reset-if-needed! tags* (<! chn ))))
  [:span.markup
   (merge
    {:key (common/unique-id "markup-")}
    (when class
      {:class (map name class)}))
   (add-key-attrs (rum/react tags*) "markup-")])

(rum/defc link-label < rum/reactive
  "Component that alternates between link and label representation
  based on the given flag atom. The link toggles the
  atom. Options [optional]:

  text or text-loc  See common/resolve-text
  [required?]  Whether label/link is marked as required (default
               false)
  flag*        Flag atom
  [negate?]    By default, the label is shown when flag is true.
               Negate? negates that."
  [{:keys [required? flag* negate?] :as options}]
  (let [fun    (if negate? not identity)
        label? (-> flag* rum/react fun)
        class  (common/css-flags :required required?)
        text   (common/resolve-text options)]
    (if label?
      [:label {:class class} text]
      [:a {:on-click #(swap! flag* not)
           :class    class}
       text])))

(rum/defc tabbar < rum/reactive
  "Tabbar representation (only the bar). Options:

  selected* Atom that holds the selected :id. The default id is the
            first tab id.

  tabs Sequence of :id, :text-loc (or :text, see
        `common/resolve-text`) and optional :test-id maps.

  When a tab is selected its id is swapped into selected*."
  [{:keys [selected* tabs]}]
  (let [selected (or (rum/react selected*) (-> tabs first :id))]
    [:div.pate-tabbar
     (let [divider [:div.pate-tab__divider {}]
           buttons (for [{:keys [id test-id]
                          :as   tab} tabs
                         :let      [text (common/resolve-text tab)
                                    attr (common/add-test-id {} test-id)]]
                     (if (= id selected)
                       [:div.pate-tab.pate-tab--active
                        attr text]
                       [:div.pate-tab
                        attr
                        [:a {:on-click #(reset! selected* id)} text]]))]
       (map #(assoc-in % [1 :key] (common/unique-id "tabbar-"))
            (concat (interpose divider buttons)
                    [[:div.pate-tab__space]])))]))
