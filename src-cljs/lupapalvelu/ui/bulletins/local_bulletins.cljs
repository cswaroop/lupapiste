(ns lupapalvelu.ui.bulletins.local-bulletins
  (:require [rum.core :as rum]
            [lupapalvelu.ui.bulletins.state :as state]
            [lupapalvelu.ui.common :as common]
            [lupapalvelu.ui.hub :as hub]
            [sade.shared-util :as util]))

(defonce args (atom {}))

(defn init
  [init-state props]
  (reset! state/current-organization (:organization @args))
  (common/query :local-bulletins-page-settings
                (fn [{:keys [enabled texts]}]
                  (when-not enabled
                    (set! js/window.location "/"))
                  (reset! state/local-bulletins-page-settings {:texts texts}))
                :organization @state/current-organization)
  (common/query :local-application-bulletins
                (fn [{:keys [data]}] (reset! state/local-bulletins data))
                :organization @state/current-organization :searchText "" :page 1)
  init-state)

(defn open-bulletin [id]
  (js/pageutil.openPage "bulletin" id))

(rum/defc bulletins-table < rum/reactive
  [_]
  (let [bulletins (rum/react state/local-bulletins)]
    [:table.application-bulletins-list
     [:thead
      [:tr
       [:th (common/loc :bulletin.verdict-section)]
       [:th (common/loc :bulletin.lupatunnus)]
       [:th (common/loc :bulletin.building-site)]
       [:th (common/loc :bulletin.type)]
       [:th (common/loc :bulletin.verdict-giver)]
       [:th (common/loc :bulletin.verdict.verdictGivenAt)]
       [:th (common/loc :bulletin.rectification-period-ends)]]]
      [:tbody
       (for [{:keys [id address verdictGivenAt application-id
                     appealPeriodEndsAt bulletinOpDescription]
              {:keys [category code status section contact]} :verdictData}  bulletins]
         [:tr
          {:key id
           :on-click #(open-bulletin id)}
          [:td (str section " "
                    (when (and category code)
                      (common/loc (str "matti-" category ".verdict-code." code)))
                    (when status
                      (common/loc (str "verdict.status." status))))]
          [:td (or application-id id)]
          [:td address]
          [:td bulletinOpDescription]
          [:td contact]
          [:td (common/format-timestamp verdictGivenAt)]
          [:td (common/format-timestamp appealPeriodEndsAt)]])]]))

(rum/defc heading < rum/reactive
  [_]
  (let [lang  (common/get-current-language)
        texts (get-in (rum/react state/local-bulletins-page-settings) [:texts (keyword lang)])]
    [:div
     [:div.full.content.orange-bg
      [:div.content-center
       [:h1.slogan.municipal-heading (:heading1 texts)] ; NB: These will be read from db, no need now for localization
       [:h2.slogan.municipal-heading (:heading2 texts)]]]
     [:div.full.content
      [:div.content-center.municipal-caption
       (for [[idx paragraph] (util/indexed (:caption texts))]
         [:p {:key idx} paragraph])]]]))

(rum/defc local-bulletins < {:init init}
  [_]
  [:div
   (heading)
   [:div.full.content
    [:div.content-center
     (bulletins-table)]]])

(defn mount-component []
  (rum/mount (local-bulletins)
             (.getElementById js/document (:dom-id @args))))

(defn ^:export start [domId componentParams]
  (swap! args assoc :dom-id (name domId) :organization (aget componentParams "organization"))
  (mount-component))