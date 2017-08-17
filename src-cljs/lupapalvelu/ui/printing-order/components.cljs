(ns lupapalvelu.ui.printing-order.components
  (:require [rum.core :as rum]
            [lupapalvelu.ui.util :as util]
            [lupapalvelu.ui.common :refer [loc] :as common]
            [lupapalvelu.ui.printing-order.state :as state]))

(rum/defc grid-text-input [col-class ltext required?]
  [:div
   {:class (name col-class)}
   [:label
    (when required?
      {:class "required"})
    (loc ltext)]
   [:input.grid-style-input--wide
    {:type "text"}]])

(rum/defc grid-radio-button < rum/reactive
  [state value col-class ltext]
  [:div
   {:class (name col-class)}
   [:div
    {:class ["radio-wrapper"]}
    [:input {:type    "radio"
             :checked (= (rum/react state) value)
             :value   value}]
    [:label.radio-label
     {:on-click #(reset! state value)}
     (loc ltext)]]])