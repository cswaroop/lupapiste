(ns lupapalvelu.ui.printing-order.pricing
  (:require [lupapalvelu.ui.printing-order.state :as state]
            [lupapalvelu.ui.common :refer [loc]]
            [rum.core :as rum]
            [lupapalvelu.ui.rum-util :as rum-util]))

(defn vat [total-vat-included]
  (-> total-vat-included
      (/ 124)
      (* 2400)
      Math/floor
      (/ 100)
      double
      ))

(defn price-for-order-amount [amount]
  (let [{:keys [delivery volume]} (-> @state/component-state :pricing)
        {:keys [fixed] :as price-class} (sade.shared-util/find-first
                                          (fn [{:keys [min max]}] (and (>= amount min) (<= amount max))) volume)]
    (js/console.log @state/component-state)
    (cond
      (nil? price-class) nil
      fixed              (let [total (+ delivery fixed)]
                           {:total total
                            :vat   (vat total)}))))

(rum/defc order-summary-pricing < rum/reactive []
  (let [order-rows (rum/react (rum/cursor-in state/component-state [:order]))
        total-amount (reduce + (vals order-rows))
        {total-price :total vat :vat} (price-for-order-amount total-amount)]
    [:div.order-summary-pricing-block
     [:table
      [:thead
       [:tr
        [:td.first
         [:span.h3 (loc :printing-order.summary.total-amount (str total-amount))]]
        [:td.second
         [:span.h3 (loc "printing-order.summary.total-price")]]
        [:td.third
         [:span.h3 (str total-price " €")]]]
       [:tr
        [:td.first]
        [:td.second
         [:span "Sis. alv (24%)"]]
        [:td.third
         [:span (str vat " €")]]]]]]))
