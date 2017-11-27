(ns lupapalvelu.ui.pate.placeholder
  "In Pate, a placeholder is a component that displays data differently
  than a 'traditional' model-based (internal state) based
  component. The name refers to the scenario, where as a placeholder
  the displayed data is initially calculated and only fixed after
  verdict (e.g., application id). Recently, this namespace is a
  collection of various components that are needed for the
  implmentation of :placeholder schemas (see shared.cljc)."
  (:require [clojure.string :as s]
            [lupapalvelu.ui.common :as common]
            [lupapalvelu.ui.components :as components]
            [lupapalvelu.ui.pate.path :as path]
            [lupapalvelu.ui.pate.service :as service]
            [lupapalvelu.ui.pate.state :as state]
            [rum.core :as rum]
            [sade.shared-util :as util]))

(defmulti placeholder (fn [options & _]
                        (-> options :schema :type)))

;; Neighbors value is a list of property-id, timestamp maps.  Before
;; publishing the verdict, the neighbors are taken from the
;; applicationModel. On publishing the neighbor states are frozen into
;; mongo.
(defmethod placeholder :neighbors
  [{:keys [state path] :as options}]
  [:div.tabby.neighbor-states
   (map (fn [{:keys [property-id done]}]
          [:div.tabby__row.neighbor
           [:div.tabby__cell.property-id (js/util.prop.toHumanFormat property-id)]
           [:div.tabby__cell
            (if done
              (common/loc :neighbors.closed
                          (js/util.finnishDateAndTime done
                                                      "D.M.YYYY HH:mm"))
              (common/loc :neighbors.open))]])
        (path/value path state))])

(defmethod placeholder :application-id
  [_]
  [:span.formatted (js/lupapisteApp.services.contextService.applicationId)])

(defmethod placeholder :building
  [{:keys [state path]}]
  (let [{:keys [operation building-id tag description]} (path/value (butlast path) state)]
    [:span.formatted (->> [(path/loc :operations operation)
                           (s/join ": " (remove s/blank? [tag description]))
                           building-id]
                          (remove s/blank?)
                          (s/join " \u2013 "))]))
