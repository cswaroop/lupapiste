(ns lupapalvelu.ui.common
  (:require [clojure.string :as s]
            [cljs-time.format :as tf]
            [cljs-time.coerce :as tc]
            [cljs-time.core :as t]))

(defn get-current-language []
  (.getCurrentLanguage js/loc))

(defn loc [& args]
  (apply js/loc (map name args)))

(def date-formatter (tf/formatter "d.M.yyyy"))

(defn format-timestamp [tstamp]
  (tf/unparse date-formatter (doto (t/time-now) (.setTime (tc/to-long tstamp)))))

(defn query [query-name success-fn & kvs]
  (-> (js/ajax.query (clj->js query-name) (-> (apply hash-map kvs) clj->js))
      (.success (fn [js-result]
                  (success-fn (js->clj js-result :keywordize-keys true))))
      .call))


(defmulti command (fn [a & _]
                    (cond
                      (map? a)     :map
                      (string? a)  :string
                      (keyword? a) :string)))

(defmethod command :map
  [{:keys [command  show-saved-indicator? success]} & kvs]
  (-> (js/ajax.command (clj->js command) (-> (apply hash-map kvs) clj->js))
      (.success (fn [js-result]
                  (when show-saved-indicator?
                    (js/util.showSavedIndicator js-result))
                  (when success
                    (success (js->clj js-result :keywordize-keys true)))))
      .call))

(defmethod command :string
  [command-name success-fn & kvs]
  (apply command (cons {:command               command-name
                        :show-saved-indicator? true
                        :success               success-fn }
                       kvs)))


(defn reset-if-needed!
  "Resets atom with value if needed. True if reset."
  [atom* value]
  (when (not= @atom* value)
    (do (reset! atom* value)
        true)))

(defn event->state [state]
  #(reset-if-needed! state (.. % -target -value)))

(defn response->state [state kw]
  (fn [response]
    (swap! state #(assoc % kw (kw response)))))

(defn feature? [feature]
  (boolean (js/features.enabled (name feature))))
