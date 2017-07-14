(ns lupapalvelu.ui.common)

(defn get-current-language []
  (.getCurrentLanguage js/loc))

(defn loc [& args]
  (apply js/loc (map name args)))

(defn query [query-name success-fn & kvs]
  (-> (js/ajax.query (clj->js query-name) (-> (apply hash-map kvs) clj->js))
      (.success (fn [js-result]
                  (success-fn (js->clj js-result :keywordize-keys true))))
      .call))

(defn command [command-name success-fn & kvs]
  (-> (js/ajax.command (clj->js command-name) (-> (apply hash-map kvs) clj->js))
      (.success (fn [js-result]
                  (js/util.showSavedIndicator js-result)
                  (success-fn (js->clj js-result :keywordize-keys true))))
      .call))

(defn reset-if-needed!
  "Resets atom with value if needed. True if reset."
  [atom* value]
  (when (not= @atom* value)
    (do (reset! atom* value)
        true)))

(defn event->state [state]
  #(reset-if-needed! state (.. % -target -value)))
