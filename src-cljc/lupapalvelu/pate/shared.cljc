(ns lupapalvelu.pate.shared
  "Schema instantiations for Pate schemas."
  (:require [clojure.set :as set]
            [clojure.string :as s]
            [lupapalvelu.pate.shared-schemas :as schemas]
            [sade.shared-util :as util]
            [schema.core :as sc]))

(def supported-languages [:fi :sv :en])

;; identifier - KuntaGML-paatoskoodi (yhteiset.xsd)
(def verdict-code-map
  {:annettu-lausunto            "annettu lausunto"
   :asiakirjat-palautettu       "asiakirjat palautettu korjauskehotuksin"
   :ehdollinen                  "ehdollinen"
   :ei-lausuntoa                "ei lausuntoa"
   :ei-puollettu                "ei puollettu"
   :ei-tiedossa                 "ei tiedossa"
   :ei-tutkittu-1               "ei tutkittu"
   :ei-tutkittu-2               "ei tutkittu (oikaisuvaatimusvaatimus tai lupa pysyy puollettuna)"
   :ei-tutkittu-3               "ei tutkittu (oikaisuvaatimus tai lupa pysyy ev\u00e4ttyn\u00e4)"
   :evatty                      "ev\u00e4tty"
   :hallintopakko               "hallintopakon tai uhkasakkoasian k\u00e4sittely lopetettu"
   :hyvaksytty                  "hyv\u00e4ksytty"
   :ilmoitus-tiedoksi           "ilmoitus merkitty tiedoksi"
   :konversio                   "muutettu toimenpideluvaksi (konversio)"
   :lautakunta-palauttanut      "asia palautettu uudelleen valmisteltavaksi"
   :lautakunta-poistanut        "asia poistettu esityslistalta"
   :lautakunta-poydalle         "asia pantu p\u00f6yd\u00e4lle kokouksessa"
   :maarays-peruutettu          "m\u00e4\u00e4r\u00e4ys peruutettu"
   :muutti-evatyksi             "muutti ev\u00e4tyksi"
   :muutti-maaraysta            "muutti m\u00e4\u00e4r\u00e4yst\u00e4 tai p\u00e4\u00e4t\u00f6st\u00e4"
   :muutti-myonnetyksi          "muutti my\u00f6nnetyksi"
   :myonnetty                   "my\u00f6nnetty"
   :myonnetty-aloitusoikeudella "my\u00f6nnetty aloitusoikeudella"
   :osittain-myonnetty          "osittain my\u00f6nnetty"
   :peruutettu                  "peruutettu"
   :puollettu                   "puollettu"
   :pysytti-evattyna            "pysytti ev\u00e4ttyn\u00e4"
   :pysytti-maarayksen-2        "pysytti m\u00e4\u00e4r\u00e4yksen tai p\u00e4\u00e4t\u00f6ksen"
   :pysytti-myonnettyna         "pysytti my\u00f6nnettyn\u00e4"
   :pysytti-osittain            "pysytti osittain my\u00f6nnettyn\u00e4"
   :siirretty-maaoikeudelle     "siirretty maaoikeudelle"
   :suunnitelmat-tarkastettu    "suunnitelmat tarkastettu"
   :tehty-hallintopakkopaatos-1 "tehty hallintopakkop\u00e4\u00e4t\u00f6s (ei velvoitetta)"
   :tehty-hallintopakkopaatos-2 "tehty hallintopakkop\u00e4\u00e4t\u00f6s (asetettu velvoite)"
   :tehty-uhkasakkopaatos       "tehty uhkasakkop\u00e4\u00e4t\u00f6s"
   :tyohon-ehto                 "ty\u00f6h\u00f6n liittyy ehto"
   :valituksesta-luovuttu-1     "valituksesta on luovuttu (oikaisuvaatimus tai lupa pysyy puollettuna)"
   :valituksesta-luovuttu-2     "valituksesta on luovuttu (oikaisuvaatimus tai lupa pysyy ev\u00e4ttyn\u00e4)"})

(def review-type-map
  {:muu-katselmus             "muu katselmus"
   :muu-tarkastus             "muu tarkastus"
   :aloituskokous             "aloituskokous"
   :paikan-merkitseminen      "rakennuksen paikan merkitseminen"
   :paikan-tarkastaminen      "rakennuksen paikan tarkastaminen"
   :pohjakatselmus            "pohjakatselmus"
   :rakennekatselmus          "rakennekatselmus"
   :lvi-katselmus             "l\u00e4mp\u00f6-, vesi- ja ilmanvaihtolaitteiden katselmus"
   :osittainen-loppukatselmus "osittainen loppukatselmus"
   :loppukatselmus            "loppukatselmus"
   :ei-tiedossa               "ei tiedossa"})

(def foreman-codes [:vastaava-tj :vv-tj :iv-tj :erityis-tj :tj])

(def verdict-dates [:julkipano :anto :muutoksenhaku :lainvoimainen
                    :aloitettava :voimassa])

(def p-verdict-dates [:julkipano :anto :valitus :lainvoimainen
                      :aloitettava :voimassa])

(defn reference-list [path extra]
  {:reference-list (merge {:label? false
                           :type   :multi-select
                           :path   path}
                          extra)})

(defn  multi-section [id link-ref]
  {:loc-prefix (str "pate-" (name id))
   :id         (name id)
   :grid       {:columns 1
                :rows    [[{:align      :full
                            :loc-prefix (str "pate-settings." (name id))
                            :hide?      link-ref
                            :dict       :link-to-settings-no-label}
                           {:show? link-ref
                            :dict  id }]]}})

(defn text-section [id]
  {:loc-prefix (str "pate-" (name id))
   :id         (name id)
   :grid       {:columns 1
                :rows    [[{:dict id}]]}})

(defn date-delta-row [kws]
  (->> kws
       (reduce (fn [acc kw]
                 (conj acc
                       (when (seq acc)
                         {:dict :plus
                          :css [:date-delta-plus]})
                       {:col 2 :dict kw :id (name kw)}))
               [])
       (remove nil?)))

(def language-select {:select {:loc-prefix :pate-verdict.language
                               :items supported-languages}})
(def verdict-giver-select {:select {:loc-prefix :pate-verdict.giver
                                    :items      [:lautakunta :viranhaltija]
                                    :sort-by    :text}})
(def complexity-select {:select {:loc-prefix :pate.complexity
                                 :items      [:small :medium :large :extra-large]}})

(def collateral-type-select {:select {:loc-prefix :pate.collateral-type
                                      :items      [:shekki :panttaussitoumus]
                                      :sort-by    :text}})

(defn req [m]
  (assoc m :required? true))

(defmulti default-verdict-template (fn [arg]
                                     arg))

(defmethod default-verdict-template :r [_]
  {:dictionary {:language                  language-select
                :verdict-dates             {:multi-select {:items           verdict-dates
                                                           :sort?           false
                                                           :i18nkey         :pate-verdict-dates
                                                           :item-loc-prefix :pate-verdict}}
                :giver                     (req verdict-giver-select)
                :verdict-code              {:reference-list {:path       :settings.verdict-code
                                                             :type       :select
                                                             :loc-prefix :pate-r.verdict-code}}
                :paatosteksti              {:phrase-text {:category :paatosteksti}}
                :bulletinOpDescription     {:phrase-text {:category :toimenpide-julkipanoon
                                                          :i18nkey  :phrase.category.toimenpide-julkipanoon}}
                :link-to-settings          {:link {:text-loc :pate.settings-link
                                                   :click    :open-settings}}
                :link-to-settings-no-label {:link {:text-loc :pate.settings-link
                                                   :label?   false
                                                   :click    :open-settings}}
                ;; The following keys are whole sections
                :foremen                   (reference-list :settings.foremen {:item-loc-prefix :pate-r.foremen})
                :plans                     (reference-list :plans {:item-key :id
                                                                   :term     {:path       [:plans]
                                                                              :extra-path [:name]
                                                                              :match-key  :id}})
                :reviews                   (reference-list :reviews {:item-key :id
                                                                     :term     {:path       [:reviews]
                                                                                :extra-path [:name]
                                                                                :match-key  :id}})
                :conditions                {:repeating {:condition        {:phrase-text {:i18nkey  :pate-condition
                                                                                         :category :lupaehdot}}
                                                        :remove-condition {:button {:i18nkey :remove
                                                                                    :label?  false
                                                                                    :icon    :lupicon-remove
                                                                                    :css     [:primary :outline]
                                                                                    :remove  :conditions}}}}
                :add-condition             {:button {:icon    :lupicon-circle-plus
                                                     :i18nkey :pate-conditions.add
                                                     :css     :positive
                                                     :add     :conditions}}
                :neighbors                 {:loc-text :pate-neighbors.text}

                :appeal           {:phrase-text {:category :muutoksenhaku
                                                 :i18nkey  :phrase.category.muutoksenhaku}}
                :collateral       {:loc-text :pate-collateral.text}
                ;; Complexity section
                :complexity       complexity-select
                :complexity-text  {:phrase-text {:label?   false
                                                 :category :vaativuus}}
                ;; Text sections
                :extra-info       {:loc-text :pate-extra-info.text}
                :deviations       {:loc-text :pate-deviations.text}
                :rights           {:loc-text :pate-rights.text}
                :purpose          {:loc-text :pate-purpose.text}
                :statements       {:loc-text :pate-statements.text}
                ;; Buildings section
                :autopaikat       {:toggle {}}
                :vss-luokka       {:toggle {}}
                :paloluokka       {:toggle {}}
                ;; Attachments section
                :upload           {:toggle {}}
                ;; Removable sections
                :removed-sections {:keymap (zipmap [:foremen :reviews :plans
                                                    :conditions :neighbors
                                                    :appeal :statements :collateral
                                                    :extra-info :deviations
                                                    :complexity :rights :purpose
                                                    :buildings :attachments]
                                                   (repeat false))}}
   :sections [{:id         "verdict"
               :loc-prefix :pate-verdict
               :grid       {:columns 12
                            :rows    [[{:col  6
                                        :dict :language}]
                                      [{:col  12
                                        :dict :verdict-dates}]
                                      [{:col  3
                                        :dict :giver}
                                       {:align      :full
                                        :col        3
                                        :loc-prefix :pate-r.verdict-code
                                        :hide?      :*ref.settings.verdict-code
                                        :dict       :link-to-settings}
                                       {:align :full
                                        :col   3
                                        :show? :*ref.settings.verdict-code
                                        :dict  :verdict-code}]
                                      [{:col  12
                                        :dict :paatosteksti}]]}}
              {:id         "bulletin"
               :loc-prefix :bulletin
               :grid       {:columns 1
                            :rows    [[{:col  1
                                        :dict :bulletinOpDescription}]]}}
              (multi-section :foremen :*ref.settings.foremen)
              (multi-section :reviews :*ref.reviews)
              (multi-section :plans :*ref.plans)
              {:id         "conditions"
               :loc-prefix :phrase.category.lupaehdot
               :grid       {:columns 1
                            :rows    [[{:grid {:columns   8
                                               :repeating :conditions
                                               :rows      [[{:col  6
                                                             :dict :condition}
                                                            {}
                                                            {:dict :remove-condition}]]}}]
                                      [{:dict  :add-condition}]]}}
              (text-section :neighbors)
              {:id         "appeal"
               :loc-prefix :pate-appeal
               :grid       {:columns 1
                            :rows    [[{:dict :appeal }]]}}
              (text-section :collateral)
              {:id         "complexity"
               :loc-prefix :pate.complexity
               :grid       {:columns 1
                            :rows    [[{:dict :complexity }]
                                      [{:id   "text"
                                        :dict :complexity-text}]]}}
              (text-section :extra-info)
              (text-section :deviations)
              (text-section :rights)
              (text-section :purpose)
              (text-section :statements)
              {:id         "buildings"
               :loc-prefix :pate-buildings
               :grid       {:columns 1
                            :rows    [[{:loc-prefix :pate-buildings.info
                                        :list       {:title   "pate-buildings.info"
                                                     :labels? false
                                                     :items   (mapv (fn [check]
                                                                      {:dict check
                                                                       :id   check
                                                                       :css  [:pate-condition-box]})
                                                                    [:autopaikat :vss-luokka :paloluokka])}}]]}}
              {:id         "attachments"
               :loc-prefix :application.verdict-attachments
               :grid       {:columns 1
                            :rows    [[{:loc-prefix :pate.attachments
                                        :list       {:labels? false
                                                     :items   [{:id   :upload
                                                                :dict :upload}]}}]]}}]})

(defmethod default-verdict-template :p [_]
  {:dictionary {:language                  language-select
                :verdict-dates             {:multi-select {:items           p-verdict-dates
                                                           :sort?           false
                                                           :i18nkey         :pate-verdict-dates
                                                           :item-loc-prefix :pate-verdict}}
                :giver                     (req verdict-giver-select)
                :verdict-code              {:reference-list {:path       :settings.verdict-code
                                                             :type       :select
                                                             :loc-prefix :pate-r.verdict-code}}
                :paatosteksti              {:phrase-text {:category :paatosteksti}}
                :link-to-settings          {:link {:text-loc :pate.settings-link
                                                   :click    :open-settings}}
                :link-to-settings-no-label {:link {:text-loc :pate.settings-link
                                                   :label?   false
                                                   :click    :open-settings}}
                ;; The following keys are whole sections
                :add-condition             {:button {:icon    :lupicon-circle-plus
                                                     :i18nkey :pate-conditions.add
                                                     :css     :positive
                                                     :add     :conditions}}
                :neighbors                 {:loc-text :pate-neighbors.text}

                :appeal           {:phrase-text {:category :muutoksenhaku
                                                 :i18nkey  :phrase.category.muutoksenhaku}}
                :collateral       {:loc-text :pate-collateral.text}
                ;; Complexity section
                :complexity       complexity-select
                :complexity-text  {:phrase-text {:label?   false
                                                 :category :vaativuus}}
                ;; Text sections
                :extra-info       {:loc-text :pate-extra-info.text}
                :deviations       {:loc-text :pate-deviations.text}
                :rights           {:loc-text :pate-rights.text}
                :purpose          {:loc-text :pate-purpose.text}
                :statements       {:loc-text :pate-statements.text}
                ;; Buildings section
                :autopaikat       {:toggle {}}
                :vss-luokka       {:toggle {}}
                :paloluokka       {:toggle {}}
                ;; Attachments section
                :upload           {:toggle {}}
                ;; Removable sections
                :removed-sections {:keymap (zipmap [:foremen :reviews :plans
                                                    :conditions :neighbors
                                                    :appeal :statements :collateral
                                                    :extra-info :deviations
                                                    :complexity :rights :purpose
                                                    :buildings :attachments]
                                                   (repeat false))}}
   :sections [{:id         "verdict"
               :loc-prefix :pate-verdict
               :grid       {:columns 12
                            :rows    [[{:col  6
                                        :dict :language}]
                                      [{:col  12
                                        :dict :verdict-dates}]
                                      [{:col  3
                                        :dict :giver}
                                       {:align      :full
                                        :col        3
                                        :loc-prefix :pate-r.verdict-code
                                        :hide?      :*ref.settings.verdict-code
                                        :dict       :link-to-settings}
                                       {:align :full
                                        :col   3
                                        :show? :*ref.settings.verdict-code
                                        :dict  :verdict-code}]
                                      [{:col  12
                                        :dict :paatosteksti}]]}}
              (text-section :neighbors)
              {:id         "appeal"
               :loc-prefix :pate-appeal
               :grid       {:columns 1
                            :rows    [[{:dict :appeal }]]}}
              (text-section :collateral)
              {:id         "complexity"
               :loc-prefix :pate.complexity
               :grid       {:columns 1
                            :rows    [[{:dict :complexity }]
                                      [{:id   "text"
                                        :dict :complexity-text}]]}}
              (text-section :extra-info)
              (text-section :deviations)
              (text-section :rights)
              (text-section :purpose)
              (text-section :statements)
              {:id         "attachments"
               :loc-prefix :application.verdict-attachments
               :grid       {:columns 1
                            :rows    [[{:loc-prefix :pate.attachments
                                        :list       {:labels? false
                                                     :items   [{:id   :upload
                                                                :dict :upload}]}}]]}}]})

(sc/validate schemas/PateVerdictTemplate (default-verdict-template :r))
(sc/validate schemas/PateVerdictTemplate (default-verdict-template :p))



(def r-settings
  {:title      "pate-r"
   :dictionary {:verdict-dates            {:loc-text :pate-verdict-dates}
                :plus                     {:loc-text :plus}
                :julkipano                (req {:date-delta {:unit :days}})
                :anto                     (req {:date-delta {:unit :days}})
                :muutoksenhaku            (req {:date-delta {:unit :days}})
                :lainvoimainen            (req {:date-delta {:unit :days}})
                :aloitettava              (req {:date-delta {:unit :years}})
                :voimassa                 (req {:date-delta {:unit :years}})
                :verdict-code             (req {:multi-select {:label? false
                                                               :items  (keys verdict-code-map)}})
                :lautakunta-muutoksenhaku (req {:date-delta {:unit :days}})
                :boardname                (req {:text {}})
                :foremen                  {:multi-select {:label? false
                                                          :items  foreman-codes}}
                :plans                    {:reference-list {:label?   false
                                                            :path     [:plans]
                                                            :item-key :id
                                                            :type     :list
                                                            :sort?    true
                                                            :term     {:path       :plans
                                                                       :extra-path :name}}}
                :reviews                  {:reference-list {:label?   false
                                                            :path     [:reviews]
                                                            :item-key :id
                                                            :type     :list
                                                            :sort?    true
                                                            :term     {:path       :reviews
                                                                       :extra-path :name}}}}
   :sections   [{:id         "verdict-dates"
                 :loc-prefix :pate-verdict-dates
                 :grid       {:columns    17
                              :loc-prefix :pate-verdict
                              :rows       [(date-delta-row verdict-dates)]
                              }}
                {:id         "verdict"
                 :required?  true
                 :loc-prefix :pate-settings.verdict
                 :grid       {:columns    1
                              :loc-prefix :pate-r.verdict-code
                              :rows       [[{:dict :verdict-code}]]}}
                {:id         "board"
                 :loc-prefix :pate-verdict.giver.lautakunta
                 :grid       {:columns 4
                              :rows    [[{:loc-prefix :pate-verdict.muutoksenhaku
                                          :dict       :lautakunta-muutoksenhaku}]
                                        [{:col        1
                                          :align      :full
                                          :loc-prefix :pate-settings.boardname
                                          :dict       :boardname}]]}}
                {:id         "foremen"
                 :loc-prefix :pate-settings.foremen
                 :grid       {:columns    1
                              :loc-prefix :pate-r.foremen
                              :rows       [[{:dict :foremen}]]}}
                {:id         "plans"
                 :loc-prefix :pate-settings.plans
                 :grid       {:columns 1
                              :rows    [[{:dict :plans}]]}}
                {:id         "reviews"
                 :loc-prefix :pate-settings.reviews
                 :grid       {:columns 1
                              :rows    [[{:dict :reviews}]]}}]})

(def settings-schemas
  {:r r-settings
   :p r-settings})

(sc/validate schemas/PateSettings r-settings)

(def required-in-verdict {:toggle {:i18nkey :pate.template-removed}})
(def verdict-handler (req {:text {:loc-prefix :pate-verdict.handler}}))
(def app-id-placeholder {:placeholder {:loc-prefix :pate-verdict.application-id
                                       :type :application-id}})

;; It is advisable to reuse ids from template when possible. This
;; makes localization work automatically.
(def verdict-schemas
  {:r
   {:dictionary
    (merge
     {:language                (req language-select)
      :verdict-date            (req {:date {}})
      :automatic-verdict-dates {:toggle {}}}
     (->> [:julkipano :anto :muutoksenhaku :lainvoimainen :aloitettava :voimassa]
          (map (fn [kw]
                 [kw (req {:date {:disabled? :automatic-verdict-dates}})]))
          (into {}))
     {:boardname             {:reference {:path :*ref.boardname}}
      :handler               verdict-handler
      :handler-title         {:text {:loc-prefix :pate-verdict.handler.title}}
      :verdict-section       (req {:text {:before :section}})
      :verdict-code          (req {:reference-list {:path       :verdict-code
                                                    :type       :select
                                                    :loc-prefix :pate-r.verdict-code}})
      :verdict-text          (req {:phrase-text {:category :paatosteksti}})
      :bulletinOpDescription {:phrase-text {:category :toimenpide-julkipanoon
                                            :i18nkey  :phrase.category.toimenpide-julkipanoon}}
      :verdict-text-ref      (req {:reference {:path :verdict-text}})
      :application-id        app-id-placeholder}
     (reduce (fn [acc [loc-prefix kw term? separator?]]
               (let [included (keyword (str (name kw) "-included"))
                     path     kw]
                 (assoc acc
                        (util/kw-path kw)
                        {:reference-list
                         (merge {:enabled?   included
                                 :loc-prefix loc-prefix
                                 :path       path
                                 :type       :multi-select}
                                (when term?
                                  {:item-key :id
                                   :term     {:path       path
                                              :extra-path :name
                                              :match-key  :id}})
                                (when separator?
                                  {:separator " \u2013 "}))}
                        ;; Included toggle
                        included required-in-verdict)))
             {}
             [[:pate-r.foremen :foremen false false]
              [:pate-plans :plans true false]
              [:pate-reviews :reviews true true]])
     {:conditions-title {:loc-text :phrase.category.lupaehdot}
      :conditions       {:repeating {:condition        {:phrase-text {:label?   false
                                                                      :category :lupaehdot}}
                                     :remove-condition {:button {:i18nkey :remove
                                                                 :label?  false
                                                                 :icon    :lupicon-remove
                                                                 :css     :secondary
                                                                 :remove  :conditions}}}}
      :add-condition    {:button {:icon    :lupicon-circle-plus
                                  :i18nkey :pate-conditions.add
                                  :css     :positive
                                  :add     :conditions}}
      :statements       {:placeholder {:type :statements}}
      :neighbors        {:phrase-text {:i18nkey  :phrase.category.naapurit
                                       :category :naapurit}}
      :neighbor-states  {:placeholder {:type :neighbors}}
      :collateral       {:text {:after :eur}}
      :collateral-flag  {:toggle {:loc-prefix :pate-collateral.flag}}
      :collateral-date  {:date {}}
      :collateral-type  collateral-type-select
      :appeal           {:phrase-text {:category :muutoksenhaku}}
      :complexity       complexity-select
      :complexity-text  {:phrase-text {:label?   false
                                       :category :vaativuus}}
      :extra-info       {:phrase-text {:category :yleinen}}
      :deviations       {:phrase-text {:category :yleinen}}
      :rights           {:phrase-text {:category :rakennusoikeus}}
      :purpose          {:phrase-text {:category :kaava}}
      :buildings        {:repeating {:building-name          {:placeholder {:label? false
                                                                            :type   :building}}
                                     :rakennetut-autopaikat  {:text {}}
                                     :kiinteiston-autopaikat {:text {}}
                                     :autopaikat-yhteensa    {:text {}}
                                     :vss-luokka             {:text {}}
                                     :paloluokka             {:text {}}
                                     :show-building          required-in-verdict}
                         :sort-by   :order}
      :upload           {:attachments {:i18nkey    :application.verdict-attachments
                                       :label?     false
                                       :type-group #"paatoksenteko"
                                       :default    :paatoksenteko.paatosote
                                       :dropzone   "#application-pate-verdict-tab"
                                       :multiple?  true}}
      :attachments      {:application-attachments {:i18nkey :application.verdict-attachments}}})
    :sections
    [{:id   "pate-dates"
      :grid {:columns 7
             :rows    [[{:col   7
                         :dict  :language
                         :hide? :_meta.published?}]
                       [{:dict  :handler-title
                         :show? :_meta.editing?}
                        {:col   2
                         :show? :_meta.editing?
                         :dict  :handler}
                        {:col   3
                         :hide? :_meta.editing?
                         :list  {:title   :pate-verdict.handler
                                 :labels? false
                                 :items   [{:dict :handler-title}
                                           {:dict :handler}]}}
                        {}
                        {:col   2
                         :hide? :_meta.editing?
                         :dict  :application-id}]
                       [{:id   "verdict-date"
                         :dict :verdict-date}
                        {:id    "automatic-verdict-dates"
                         :col   2
                         :show? [:AND :_meta.editing?
                                 (cons :OR (map #(util/kw-path :? %) verdict-dates))]
                         :dict  :automatic-verdict-dates}]
                       {:id         "deltas"
                        :css        [:pate-date]
                        :loc-prefix :pate-verdict
                        :row        (map (fn [kw]
                                           (let [id (name kw)]
                                             {:show?     (util/kw-path :? kw)
                                              :disabled? :automatic-verdict-dates
                                              :id        id
                                              :dict      kw}))
                                         verdict-dates)}]}}
     {:id   "pate-verdict"
      :grid {:columns 7
             :rows    [[{:col        2
                         :loc-prefix :pate-verdict.giver
                         :hide?      :_meta.editing?
                         :show?      :*ref.boardname
                         :dict       :boardname}
                        {:col        1
                         :show?      [:OR :*ref.boardname :verdict-section]
                         :loc-prefix :pate-verdict.section
                         :dict       :verdict-section}
                        {:show? [:AND :_meta.editing? :*ref.boardname]}
                        {:col   2
                         :align :full
                         :dict  :verdict-code}]
                       [{:col   5
                         :id    "paatosteksti"
                         :show? :_meta.editing?
                         :dict  :verdict-text}
                        {:col   5
                         :hide? :_meta.editing?
                         :dict  :verdict-text-ref}]]}}
     {:id         "bulletin"
      :loc-prefix :bulletin
      :show?      :?.bulletin-op-description
      :grid       {:columns 1
                   :rows    [[{:col  1
                               :id   "toimenpide-julkipanoon"
                               :dict :bulletinOpDescription}]]}}
     {:id   "requirements"
      :grid {:columns 7
             :rows    (map (fn [dict]
                             (let [check-path (keyword (str (name dict) "-included"))]
                               {:show? [:OR :_meta.editing? check-path]
                                :row   [{:col  4
                                         :dict dict}
                                        {:col   2
                                         :align :right
                                         :show? :_meta.editing?
                                         :id    "included"
                                         :dict  check-path}]}))
                           [:foremen :plans :reviews])}}
     {:id   "conditions"
      :grid {:columns 1
             :show?   :?.conditions
             :rows    [[{:css  :pate-label
                         :dict :conditions-title}]
                       [{:grid {:columns   9
                                :repeating :conditions
                                :rows      [[{:col  7
                                              :dict :condition}
                                             {:align :right
                                              :dict  :remove-condition}]]}}]
                       [{:show? :_meta.editing?
                         :dict  :add-condition}]]}}

     {:id    "appeal"
      :show? [:OR :?.appeal :?.collateral]
      :grid  {:columns 7
              :rows    [{:show? :?.appeal
                         :row   [{:col        6
                                  :loc-prefix :verdict.muutoksenhaku
                                  :dict       :appeal}]}
                        {:show? [:AND :?.collateral :_meta.editing?]
                         :css   [:row--tight]
                         :row   [{:col  3
                                  :dict :collateral-flag}]}
                        {:show?      [:AND :?.collateral :collateral-flag]
                         :loc-prefix :pate
                         :row        [{:col  2
                                       :id   :collateral-date
                                       :dict :collateral-date}
                                      {:col  2
                                       :id   :collateral
                                       :dict :collateral}
                                      {:col  2
                                       :dict :collateral-type}]}]}}
     {:id         "statements"
      :show?      :?.statements
      :loc-prefix :pate-statements
      :buttons?   false
      :grid       {:columns 1
                   :rows    [[{:dict :statements}]]}}
     {:id    "neighbors"
      :show? :?.neighbors
      :grid  {:columns 12
              :rows    [[{:col  7
                          :id   "neighbor-notes"
                          :dict :neighbors}
                         {:col   4
                          :hide? :_meta.published?
                          :id    "neighbor-states"
                          :align :right
                          :dict  :neighbor-states}]
                        ]}}
     {:id         "complexity"
      :loc-prefix :pate.complexity
      :show?      [:OR :?.complexity :?.rights :?.purpose]
      :grid       {:columns 7
                   :rows    [{:show? :?.complexity
                              :row   [{:col  3
                                       :dict :complexity}]}
                             {:show? :?.complexity
                              :row   [{:col  6
                                       :id   "text"
                                       :dict :complexity-text}]}
                             {:show? :?.rights
                              :row   [{:col        6
                                       :loc-prefix :pate-rights
                                       :dict       :rights}]}
                             {:show? :?.purpose
                              :row   [{:col        6
                                       :loc-prefix :phrase.category.kaava
                                       :dict       :purpose}]}]}}
     {:id         "extra-info"
      :loc-prefix :pate-extra-info
      :show?      :?.extra-info
      :grid       {:columns 7
                   :rows    [[{:col  6
                               :dict :extra-info}]]}}
     {:id         "deviations"
      :loc-prefix :pate-deviations
      :show?      :?.extra-info
      :grid       {:columns 7
                   :rows    [[{:col  6
                               :dict :deviations}]]}}
     {:id    "buildings"
      :show? :?.buildings
      :grid  {:columns 7
              :rows    [[{:col  7
                          :grid {:columns    6
                                 :loc-prefix :pate-buildings.info
                                 :repeating  :buildings
                                 :rows       [{:css   [:row--tight]
                                               :show? [:OR :_meta.editing? :+.show-building]
                                               :row   [{:col  6
                                                        :dict :building-name}]}
                                              {:show? [:OR :_meta.editing? :+.show-building]
                                               :css   [:row--indent]
                                               :row   [{:col  5
                                                        :list {:css   :list--sparse
                                                               :items (map #(hash-map :id %
                                                                                      :dict %
                                                                                      :show? (util/kw-path :?+ %)
                                                                                      :enabled? :-.show-building)
                                                                           [:rakennetut-autopaikat
                                                                            :kiinteiston-autopaikat
                                                                            :autopaikat-yhteensa
                                                                            :vss-luokka
                                                                            :paloluokka])}}
                                                       {:dict  :show-building
                                                        :show? :_meta.editing?}]}
                                              ]}}]]}}
     {:id   "attachments"
      :grid {:columns 7
             :rows    [[{:col  6
                         :dict :attachments}]]}}
     {:id       "upload"
      :hide?    :_meta.published?
      :css      :pate-section--no-border
      :buttons? false
      :grid     {:columns 7
                 :rows    [[{:col  6
                             :dict :upload}]]}}]}
   :p {:dictionary
       (merge
        {:language                (req language-select)
         :verdict-date            (req {:date {}})
         :automatic-verdict-dates {:toggle {}}}
        (->> [:julkipano :anto :valitus :lainvoimainen :aloitettava :voimassa]
             (map (fn [kw]
                    [kw (req {:date {:disabled? :automatic-verdict-dates}})]))
             (into {}))
        {:boardname             {:reference {:path :*ref.boardname}}
         :contact               (req {:text {}})
         :verdict-section       (req {:text {:before :section}})
         :verdict-code          (req {:reference-list {:path       :verdict-code
                                                       :type       :select
                                                       :loc-prefix :pate-r.verdict-code}})
         :verdict-text          (req {:phrase-text {:category :paatosteksti}})
         :bulletinOpDescription {:phrase-text {:category :toimenpide-julkipanoon
                                               :i18nkey  :phrase.category.toimenpide-julkipanoon}}
         :verdict-text-ref      (req {:reference {:path :verdict-text}})
         :application-id        app-id-placeholder}
        (reduce (fn [acc [loc-prefix kw term? separator?]]
                  (let [included (keyword (str (name kw) "-included"))
                        path     kw]
                    (assoc acc
                           (util/kw-path kw)
                           {:reference-list
                            (merge {:enabled?   included
                                    :loc-prefix loc-prefix
                                    :path       path
                                    :type       :multi-select}
                                   (when term?
                                     {:item-key :id
                                      :term     {:path       path
                                                 :extra-path :name
                                                 :match-key  :id}})
                                   (when separator?
                                     {:separator " \u2013 "}))}
                           ;; Included checkbox
                           included required-in-verdict)))
                {}
                [[:pate-r.foremen :foremen false false]
                 [:pate-plans :plans true false]
                 [:pate-reviews :reviews true true]])
        {:conditions-title {:loc-text :phrase.category.lupaehdot}
         :conditions       {:repeating {:condition        {:phrase-text {:label?   false
                                                                         :category :lupaehdot}}
                                        :remove-condition {:button {:i18nkey :remove
                                                                    :label?  false
                                                                    :icon    :lupicon-remove
                                                                    :css     :secondary
                                                                    :remove  :conditions}}}}
         :add-condition    {:button {:icon    :lupicon-circle-plus
                                     :i18nkey :pate-conditions.add
                                     :css     :positive
                                     :add     :conditions}}
         :statements       {:placeholder {:type :statements}}
         :neighbors        {:phrase-text {:i18nkey  :phrase.category.naapurit
                                          :category :naapurit}}
         :neighbor-states  {:placeholder {:type :neighbors}}
         :collateral       {:text {:after :eur}}
         :collateral-date  {:date {}}
         :collateral-type  collateral-type-select
         :appeal           {:phrase-text {:category :muutoksenhaku}}
         :complexity       complexity-select
         :complexity-text  {:phrase-text {:label?   false
                                          :category :vaativuus}}
         :extra-info       {:phrase-text {:category :yleinen}}
         :deviations       {:phrase-text {:category :yleinen}}
         :rights           {:phrase-text {:category :rakennusoikeus}}
         :purpose          {:phrase-text {:category :kaava}}
         :upload           {:attachments {:i18nkey    :application.verdict-attachments
                                          :label?     false
                                          :type-group #"paatoksenteko"
                                          :default    :paatoksenteko.paatosote
                                          :dropzone   "#application-pate-verdict-tab"
                                          :multiple?  true}}
         :attachments      {:application-attachments {:i18nkey :application.verdict-attachments}}})
       :sections
       [{:id   "pate-dates"
         :grid {:columns 7
                :rows    [[{:col   7
                            :dict  :language
                            :hide? :_meta.published?}]
                          [{:id   "verdict-date"
                            :dict :verdict-date}
                           {:id    "automatic-verdict-dates"
                            :col   2
                            :show? [:AND :_meta.editing?
                                    (cons :OR (map #(util/kw-path :? %) p-verdict-dates))]
                            :dict  :automatic-verdict-dates}]
                          {:id         "deltas"
                           :css        [:pate-date]
                           :loc-prefix :pate-verdict
                           :row        (map (fn [kw]
                                              (let [id (name kw)]
                                                {:show?     (util/kw-path :? kw)
                                                 :disabled? :automatic-verdict-dates
                                                 :id        id
                                                 :dict      kw}))
                                            p-verdict-dates)}]}}
        {:id   "pate-verdict"
         :grid {:columns 7
                :rows    [[{:col        2
                            :loc-prefix :pate-verdict.giver
                            :hide?      :*ref.boardname
                            :dict       :contact}
                           {:col        2
                            :loc-prefix :pate-verdict.giver
                            :hide?      :_meta.editing?
                            :show?      :*ref.boardname
                            :dict       :boardname}
                           {:col        1
                            :show?      [:OR :*ref.boardname :verdict-section]
                            :loc-prefix :pate-verdict.section
                            :dict       :verdict-section}
                           {:hide? :verdict-section}
                           {:col   2
                            :align :full
                            :dict  :verdict-code}]
                          [{:col   5
                            :id    "paatosteksti"
                            :show? :_meta.editing?
                            :dict  :verdict-text}
                           {:col   3
                            :hide? :_meta.editing?
                            :dict  :verdict-text-ref}
                           {:col   2
                            :id    "application-id"
                            :hide? :_meta.editing?
                            :dict  :application-id}]]}}

        {:id    "appeal"
         :show? [:OR :?.appeal :?.collateral]
         :grid  {:columns 7
                 :rows    [{:show? :?.appeal
                            :row   [{:col        6
                                     :loc-prefix :verdict.muutoksenhaku
                                     :dict       :appeal}]}
                           {:show?      :?.collateral
                            :loc-prefix :pate
                            :row        [{:col  2
                                          :id   :collateral-date
                                          :dict :collateral-date}
                                         {:col  2
                                          :id   :collateral
                                          :dict :collateral}
                                         {:col  2
                                          :dict :collateral-type}]}]}}
        {:id         "statements"
         :show?      :?.statements
         :loc-prefix :pate-statements
         :buttons?   false
         :grid       {:columns 1
                      :rows    [[{:dict :statements}]]}}
        {:id    "neighbors"
         :show? :?.neighbors
         :grid  {:columns 12
                 :rows    [[{:col  7
                             :id   "neighbor-notes"
                             :dict :neighbors}
                            {:col   4
                             :hide? :_meta.published?
                             :id    "neighbor-states"
                             :align :right
                             :dict  :neighbor-states}]
                           ]}}
        {:id         "complexity"
         :loc-prefix :pate.complexity
         :show?      [:OR :?.complexity :?.rights :?.purpose]
         :grid       {:columns 7
                      :rows    [{:show? :?.complexity
                                 :row   [{:col  3
                                          :dict :complexity}]}
                                {:show? :?.complexity
                                 :row   [{:col  6
                                          :id   "text"
                                          :dict :complexity-text}]}
                                {:show? :?.rights
                                 :row   [{:col        6
                                          :loc-prefix :pate-rights
                                          :dict       :rights}]}
                                {:show? :?.purpose
                                 :row   [{:col        6
                                          :loc-prefix :phrase.category.kaava
                                          :dict       :purpose}]}]}}
        {:id         "extra-info"
         :loc-prefix :pate-extra-info
         :show?      :?.extra-info
         :grid       {:columns 7
                      :rows    [[{:col  6
                                  :dict :extra-info}]]}}
        {:id         "deviations"
         :loc-prefix :pate-deviations
         :show?      :?.extra-info
         :grid       {:columns 7
                      :rows    [[{:col  6
                                  :dict :deviations}]]}}
        {:id   "attachments"
         :grid {:columns 7
                :rows    [[{:col  6
                            :dict :attachments}]]}}
        {:id       "upload"
         :hide?    :_meta.published?
         :css      :pate-section--no-border
         :buttons? false
         :grid     {:columns 7
                    :rows    [[{:col  6
                                :dict :upload}]]}}]}})

(sc/validate schemas/PateVerdict (:r verdict-schemas))

;; Other utils

(defn permit-type->category [permit-type]
  (when-let [kw (some-> permit-type
                        s/lower-case
                        keyword)]
    (cond
      (#{:r :p :ya} kw)              kw
      (#{:kt :mm} kw)                :kt
      (#{:yi :yl :ym :vvvl :mal} kw) :ymp)))

(defn dict-resolve
  "Path format: [repeating index repeating index ... value-dict].
 Repeating denotes :repeating schema, index is arbitrary repeating
  index (skipped during resolution) and value-dict is the final dict
  for the item schema.

  Returns map with :schema and :path keys. The path is
  the remaining path (e.g., [:delta] for pate-delta). Note: the
  result is empty map if the path resolves to the repeating schema.

  Returns nil when the resolution fails."
  [path dictionary]
  (loop [[x & xs]   (->> path
                         (remove nil?)
                         (map keyword))
         dictionary dictionary]
    (when dictionary
      (if x
        (when-let [schema (get dictionary x)]
          (if (:repeating schema)
            (recur (rest xs) (:repeating schema))
            {:schema schema :path xs}))
        {}))))

(defn repeating-subpath
  "Subpath that resolves to a repeating named repeating. Nil if not
  found. Note that the actual existence of the path within data is not
  checked."
  [repeating path dictionary]
  (loop [path path]
    (cond
      (empty? path)           nil
      (= (last path)
         repeating) (when (= (dict-resolve path dictionary)
                             {})
                      path)
      :else                   (recur (butlast path)))))
