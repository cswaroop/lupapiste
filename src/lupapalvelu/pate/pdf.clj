(ns lupapalvelu.pate.pdf
  "PDF generation via HTML for Pate verdicts. Utilises a simple
  schema-based mechanism for the layout definiton and generation."
  (:require [clojure.java.io :as io]
            [garden.core :as garden]
            [garden.selectors :as sel]
            [lupapalvelu.application :as app]
            [lupapalvelu.application-meta-fields :as app-meta]
            [lupapalvelu.attachment :as att]
            [lupapalvelu.attachment.bind :as bind]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.logging :as logging]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.organization :as org]
            [lupapalvelu.pate.date :as date]
            [lupapalvelu.pate.markup :as markup]
            [lupapalvelu.pate.shared :as shared]
            [lupapalvelu.pate.shared-schemas :as shared-schemas]
            [lupapalvelu.pdf.html-template :as html-pdf]
            [lupapalvelu.pdf.html-template-common :as common]
            [rum.core :as rum]
            [sade.core :refer :all]
            [sade.property :as property]
            [sade.strings :as ss]
            [sade.util :as util]
            [schema.core :refer [defschema] :as sc]
            [swiss.arrows :refer :all]))

(def cell-widths (range 10 101 5))
(def row-styles [:pad-after :pad-before
                 :border-top :border-bottom
                 :page-break :bold :spaced])
(def cell-styles [:bold :center :right :nowrap])

(defschema Source
  "Value of PdfEntry :source property aka data source for the row."
  (sc/conditional
   ;; Keyword corresponds to a key in the data context.
   keyword? sc/Keyword
   :else (shared-schemas/only-one-of [:doc :dict]
                             ;; Vector is a path to application
                             ;; document data. The first item is the
                             ;; document name and rest are path within
                             ;; the data.
                             {(sc/optional-key :doc)  [sc/Keyword]
                              ;; Kw-path into published verdict data.
                              (sc/optional-key :dict) sc/Keyword})))

(defn styles
  "Definition that only allows either individual kw or subset of kws."
  [kws]
  (let [enum (apply sc/enum kws)]
    (sc/conditional
     keyword? enum
     :else    [enum])))

(defschema PdfEntry
  "An entry in the layout consists of left- and right-hand sides. The
  former contains the entry title and the latter actual data. In the
  schema, an entry is modeled as a vector, where the first element
  defines both the title and the data source for the whole entry.

  On styles: the :styles definition of the first item applies to the
  whole row. Border styles (:border-top and :border-bottom) include
  padding and margin so the adjacent rows should not add any
  padding. Cell items' :styles only apply to the corresponding cell.

  In addition to the schema definition, styles can be added in
  'runtime': if source value has ::styles property, it should be map
  with the following possible keys:

  :row    Row styles

  :cell   Cell styles (applied to every cell)

  path    Path is the value of the :path property. For example, if
  the :path has a value of :foo, then the cell could be emphasized
  with ::styles map {:foo :bold}. "
  [(sc/one {;; Localisation key for the row (left-hand) title.
            :loc                        sc/Keyword
            ;; If :loc-many is given it is used as the title key if
            ;; the source value denotes multiple values.
            (sc/optional-key :loc-many) sc/Keyword
            (sc/optional-key :source)   Source
            (sc/optional-key :styles)   (styles row-styles)}
           {})
   ;; Note that the right-hand side can consist of multiple
   ;; cells/columns. As every property is optional, the cells can be
   ;; omitted. In that case, the value of the right-hand side is the
   ;; source value.
   ;; Path within the source value. Useful, when the value is a map.
   {(sc/optional-key :path)       shared-schemas/path-type
    ;; Textual representation that is static and
    ;; independent from any source value.
    (sc/optional-key :text)       shared-schemas/keyword-or-string
    (sc/optional-key :width)      (apply sc/enum cell-widths)
    (sc/optional-key :unit)       (sc/enum :ha :m2 :m3 :kpl)
    ;; Additional localisation key prefix. Is
    ;; applied both to path and text values.
    (sc/optional-key :loc-prefix) shared-schemas/path-type
    (sc/optional-key :styles)     (styles cell-styles)}])

(defschema PdfLayout
  "PDF contents layout."
  {;; Width of the left-hand side.
   :left-width (apply sc/enum cell-widths)
   :entries    [PdfEntry]})


(def page-number-script
  [:script
   {:dangerouslySetInnerHTML
    {:__html
     (-> common/wkhtmltopdf-page-numbering-script-path
         io/resource
         slurp)}}])


(defn html [body & [script?]]
  (str "<!DOCTYPE html>"
       (rum/render-static-markup
        [:html
         [:head
          [:meta {:http-equiv "content-type"
                  :content    "text/html; charset=UTF-8"}]
          [:style
           {:type "text/css"
            :dangerouslySetInnerHTML
            {:__html (garden/css
                      [[:* {:font-family "'Carlito', sans-serif"}]
                       [:.permit {:text-transform :uppercase
                                  :font-weight    :bold}]
                       [:.preview {:text-transform :uppercase
                                   :color          :red
                                   :font-weight    :bold
                                   :letter-spacing "0.2em"}]
                       [:div.header {:padding-bottom "1em"}]
                       [:div.footer {:padding-top "1em"}]
                       [:.page-break {:page-break-before :always}]
                       [:.section {:display :table
                                   :width   "100%"}
                        [:&.border-top {:margin-top  "1em"
                                        :border-top  "1px solid black"
                                        :padding-top "1em"}]
                        [:&.border-bottom {:margin-bottom  "1em"
                                           :border-bottom  "1px solid black"
                                           :padding-bottom "1em"}]
                        [:&.header {:padding       0
                                    :border-bottom "1px solid black"}]
                        [:&.footer {:border-top "1px solid black"}]
                        [:>.row {:display :table-row}
                         [:&.border-top [:>.cell {:border-top "1px solid black"}]]
                         [:&.border-bottom [:>.cell {:border-bottom "1px solid black"}]]
                         [:&.pad-after [:>.cell {:padding-bottom "0.5em"}]]
                         [:&.pad-before [:>.cell {:padding-top "0.5em"}]]
                         [:.cell {:display       :table-cell
                                  :white-space   :pre-wrap
                                  :padding-right "1em"}
                          [:&:last-child {:padding-right 0}]
                          [:&.right {:text-align :right}]
                          [:&.center {:text-align :center}]
                          [:&.bold {:font-weight :bold}]
                          [:&.nowrap {:white-space :nowrap}]]
                         (map (fn [n]
                                [(keyword (str ".cell.cell--" n))
                                 {:width (str n "%")}])
                              cell-widths)
                         [:&.spaced
                          [(sel/+ :.row :.row)
                           [:.cell {:padding-top "0.5em"}]]]]]
                       [:.markup
                        [:p {:margin-top    "0"
                             :margin-bottom "0.25em"}]
                        [:ul {:margin-top    "0"
                              :margin-bottom "0"}]
                        [:ol {:margin-top    "0"
                              :margin-bottom "0"}]
                        ;; wkhtmltopdf does not seem to support text-decoration?
                        [:span.underline {:border-bottom "1px solid black"}]]])}}]]
         [:body body (when script?
                       page-number-script)]])))

;; ------------------------------
;; Entries
;; ------------------------------

(defn entry--simple
  ([dict styles]
   [{:loc    (case dict
               :address       :pate.address
               :buyout        :pate.buyout
               :collateral    :pate-collateral
               :deviations    :pate-deviations
               :extra-info    :pate-extra-info
               :fyi           :pate.fyi
               :giving        :pate.verdict-giving
               :legalese      :pate.legalese
               :next-steps    :pate.next-steps
               :purpose       :pate-purpose
               :rationale     :pate.verdict-rationale
               :rights        :pate-rights
               :start-info    :pate-start-info
               :neighbors     :phrase.category.naapurit
               :inform-others :pate-inform-others)
     :source {:dict dict}
     :styles styles}])
  ([dict]
   (entry--simple dict :pad-before)))

(def entry--application-id [{:loc    :pate-verdict.application-id
                             :source :application-id
                             :styles [:bold :pad-after]}])

(def entry--rakennuspaikka
  (list [{:loc    :rakennuspaikka._group_label
          :styles :bold}]
        [{:loc    :rakennuspaikka.kiinteisto.kiinteistotunnus
          :source :property-id}]
        (entry--simple :address [])
        [{:loc    :rakennuspaikka.kiinteisto.tilanNimi
          :source {:doc [:rakennuspaikka :kiinteisto.tilanNimi]}}]
        [{:loc    :pdf.pinta-ala
          :source {:doc [:rakennuspaikka :kiinteisto.maapintaala]}}
         {:unit :ha}]
        [{:loc    :rakennuspaikka.kaavatilanne._group_label
          :source {:doc [:rakennuspaikka :kaavatilanne]}
          :styles :pad-after}
         {:loc-prefix :rakennuspaikka.kaavatilanne}]))

(defn entry--applicant [loc loc-many]
  [{:loc      loc
    :loc-many loc-many
    :source   :applicants
    :styles   [:pad-before :border-bottom]}])

(def entry--operation [{:loc      :applications.operation
                        :loc-many :operations
                        :source   :operations
                        :styles   :bold}
                       {:path     :text}])

(def entry--complexity [{:loc    :pate.complexity
                         :source :complexity
                         :styles [:spaced :pad-after :pad-before]}])

(def entry--designers '([{:loc    :pdf.design-complexity
                          :source :designers
                          :styles :pad-before}
                         {:path   :role
                          :styles :nowrap}
                         {:path       :difficulty
                          :width      100
                          :loc-prefix :osapuoli.suunnittelutehtavanVaativuusluokka}]
                        [{:loc      :pdf.designer
                          :loc-many :pdf.designers
                          :source   :designers
                          :styles   :pad-before}
                         {:path   :role
                          :styles :nowrap}
                         {:path  :person
                          :width 100}]))

(def entry--dimensions '([{:loc    :verdict.kerrosala
                           :source :primary
                           :styles :pad-before}
                          {:path :mitat.kerrosala
                           :unit :m2}]
                         [{:loc    :verdict.kokonaisala
                           :source :primary}
                          {:path :mitat.kokonaisala
                           :unit :m2}]
                         [{:loc    :pdf.volume
                           :source :primary}
                          {:path :mitat.tilavuus
                           :unit :m3}]
                         [{:loc    :purku.mitat.kerrosluku
                           :source :primary}
                          {:path :mitat.kerrosluku}]))

(def entry--buildings '([{:loc    :pate-buildings.info.paloluokka
                          :source :paloluokka}]
                        [{:loc    :pdf.parking
                          :source :parking
                          :styles :pad-before}
                         {:path   :text
                          :styles :nowrap}
                         {:path   :amount
                          :styles :right}
                         {:text  ""
                          :width 100}]))

(def entry--statements '([{:loc      :statement.lausunto
                           :loc-many :pate-statements
                           :source   :statements
                           :styles   [:bold :border-top]}]))

(def entry--neighbors (entry--simple :neighbors [:bold :pad-before]))

(def entry--attachments [{:loc      :pdf.attachment
                          :loc-many :verdict.attachments
                          :source   :attachments
                          :styles   [:bold :pad-before]}
                         {:path   :text
                          :styles :nowrap}
                         {:path   :amount
                          :styles [:right :nowrap]
                          :unit   :kpl}
                         {:text  ""
                          :width 100}])

(def entry--verdict '([{:loc    :pate-verdict
                        :source {:dict :verdict-code}
                        :styles [:bold :border-top]}
                       {:loc-prefix :pate-r.verdict-code}]
                      [{:loc    :empty
                        :source {:dict :verdict-text}
                        :styles :pad-before}]))

(def entry--foremen [{:loc      :pdf.required-foreman
                      :loc-many :verdict.vaaditutTyonjohtajat
                      :source   {:dict :foremen}
                      :styles   :pad-before}
                     {:loc-prefix :pate-r.foremen}])

(def entry--reviews [{:loc      :pdf.required-review
                      :loc-many :verdict.vaaditutKatselmukset
                      :source   :reviews
                      :styles   :pad-before}])

(def entry--review-info [{:loc    :empty
                          :source :review-info}])

(def entry--plans [{:loc      :pdf.required-plan
                    :loc-many :verdict.vaaditutErityissuunnitelmat
                    :source   :plans
                    :styles   :pad-before}])

(def entry--conditions [{:loc      :pdf.condition
                         :loc-many :pdf.conditions
                         :source   :conditions
                         :styles   [:pad-before :spaced]}])

(def entry--collateral [{:loc    :pate-collateral
                         :source :collateral
                         :styles :pad-before}])

(defn entry--verdict-giver [handler-loc]
  (list [{:loc    :empty
          :source {:dict :verdict-date}
          :styles :pad-before}]
        [{:loc handler-loc
          :source :handler
          :styles :pad-before}]
        [{:loc    :empty
          :source :organization
          :styles :pad-after}]))

(def entry--dates '([{:loc    :pdf.julkipano
                      :source {:dict :julkipano}}]
                    [{:loc    :pdf.anto
                      :source {:dict :anto}}]
                    [{:loc    :pdf.muutoksenhaku
                      :source :muutoksenhaku}]
                    [{:loc    :pdf.voimassa
                      :source :voimassaolo}]))

(def entry--dates-ya '([{:loc    :pdf.julkipano
                         :source {:dict :julkipano}}]
                       [{:loc    :pdf.anto
                         :source {:dict :anto}}]
                       [{:loc    :pdf.muutoksenhaku
                         :source :muutoksenhaku}]
                       [{:loc    :pdf.voimassa
                         :source :voimassaolo-ya}]))

(def entry--appeal ;; Page break
  [{:loc    :pate-verdict.muutoksenhaku
    :source {:dict :appeal}
    :styles [:bold :page-break]}])

(def entry--link-permits '([{:loc      :linkPermit.dialog.header
                             :loc-many :application.linkPermits
                             :source :link-permits
                             :styles :pad-before}
                            {:path :id
                             :styles :nowrap}
                            {:path :operation
                             :loc-prefix :operations}]))


(defn combine-entries
  "Entries that are lists (not vectors!) are interpreted as multiple
  entries."
  [& entries]
  (reduce (fn [acc entry]
            (concat acc (cond-> entry
                          (not (list? entry)) vector)))
          []
          entries))

(def r-pdf-layout
  {:left-width 30
   :entries (combine-entries entry--application-id
                             entry--rakennuspaikka
                             (entry--simple :purpose)
                             (entry--applicant :pdf.achiever :pdf.achievers)
                             entry--operation
                             (entry--simple :extra-info)
                             entry--complexity
                             (entry--simple :rights)
                             entry--designers
                             entry--dimensions
                             entry--buildings
                             (entry--simple :deviations)
                             entry--statements
                             entry--neighbors
                             entry--attachments
                             entry--verdict
                             entry--foremen
                             entry--reviews
                             entry--plans
                             entry--conditions
                             entry--collateral
                             (entry--verdict-giver :applications.authority)
                             entry--dates
                             entry--appeal)})

(sc/validate PdfLayout r-pdf-layout)

(def p-pdf-layout
  {:left-width 30
   :entries (combine-entries entry--application-id
                             entry--rakennuspaikka
                             (entry--simple :purpose)
                             (entry--applicant :applicant :pdf.applicants)
                             entry--operation
                             (entry--simple :deviations)
                             entry--statements
                             entry--neighbors
                             (entry--simple :start-info)
                             entry--conditions
                             (entry--verdict-giver :pate.prepper)
                             entry--verdict
                             (entry--simple :rationale)
                             (entry--simple :legalese)
                             (entry--simple :giving)
                             entry--dates
                             (entry--simple :next-steps)
                             (entry--simple :buyout)
                             entry--attachments
                             (entry--simple :fyi)
                             entry--collateral
                             entry--appeal)})

(sc/validate PdfLayout p-pdf-layout)

(def ya-pdf-layout
  {:left-width 30
   :entries (combine-entries entry--application-id
                             entry--rakennuspaikka
                             (entry--applicant :pdf.achiever :pdf.achievers)
                             entry--operation
                             entry--statements
                             (entry--simple :inform-others)
                             entry--attachments
                             entry--verdict
                             entry--reviews
                             entry--review-info
                             entry--plans
                             entry--conditions
                             (entry--verdict-giver :applications.authority)
                             entry--dates-ya
                             entry--link-permits
                             entry--appeal)})

(sc/validate PdfLayout ya-pdf-layout)

(def pdf-layouts
  {:r  r-pdf-layout
   :p  p-pdf-layout
   :ya ya-pdf-layout})


(defn join-non-blanks
  "Trims and joins."
  [separator & coll]
  (->> coll
       flatten
       (map ss/trim)
       (remove ss/blank?)
       (ss/join separator)))

(defn loc-non-blank
  "Localized string or nil if the last part is blank."
  [lang & parts]
  (when-not (-> parts last ss/blank?)
    (i18n/localize lang parts)))

(defn loc-fill-non-blank
  "Localize and fill if every value is non-blank"
  [lang loc-key & values]
  (when (every? (comp ss/not-blank? str) values)
    (apply (partial i18n/localize-and-fill lang loc-key) values)))

(defn applicants
  "Returns list of name, address maps for properly filled party
  documents. If a doc does not have name information it is omitted."
  [{app :application lang :lang}]
  (->> (domain/get-applicant-documents (:documents app))
       (map :data)
       (map (fn [{:keys [_selected henkilo yritys]}]
              (if (util/=as-kw :yritys _selected)
                yritys
                henkilo)))
       (map (fn [{:keys [yritysnimi henkilotiedot osoite]}]
              {:name (or (-> yritysnimi ss/trim not-empty)
                         (join-non-blanks " "
                                          (:etunimi henkilotiedot)
                                          (:sukunimi henkilotiedot)))
               :address (let [{:keys [katu postinumero postitoimipaikannimi
                                      maa]} osoite]
                          (->> [katu (str postinumero " " postitoimipaikannimi)
                                (when (util/not=as-kw maa :FIN)
                                  (i18n/localize lang :country maa))]
                               (join-non-blanks ", ")))}))
       (remove (util/fn-> :name ss/blank?))))

(defn pathify [kw-path]
  (map keyword (ss/split (name kw-path) #"\.")))

(defn doc-value [application doc-name kw-path]
  (get-in (domain/get-document-by-name application (name doc-name))
          (cons :data (pathify kw-path))))

(defn dict-value [{:keys [dictionary verdict]} kw-path]
  (let [path             (pathify kw-path)
        value            (get-in verdict (cons :data path))
        {schema :schema} (shared/dict-resolve path dictionary)]
    (cond
      (and (:phrase-text schema) (ss/not-blank? value))
      (list [:div.markup (markup/markup->tags value)])

      (and (:date schema) (integer? value))
      (date/finnish-date value)

      :else
      value)))

(defn add-unit
  "Result is nil for blank value."
  [lang unit v]
  (when-not (ss/blank? (str v))
    (case unit
      :ha      (str v " " (i18n/localize lang :unit.hehtaaria))
      :m2      [:span v " m" [:sup 2]]
      :m3      [:span v " m" [:sup 3]]
      :kpl     (str v " " (i18n/localize lang :unit.kpl))
      :section (str "\u00a7" v)
      :eur     (str v "\u20ac"))))

(defn complexity [{lang :lang :as options}]
  (not-empty (filter not-empty
                     [(loc-non-blank lang
                                     :pate.complexity
                                     (dict-value options :complexity))
                      (dict-value options :complexity-text)])))

(defn property-id [application]
  (join-non-blanks "-"
                   [(-> application :propertyId
                        property/to-human-readable-property-id)
                    (util/pcond->> (doc-value application
                                              :rakennuspaikka
                                              :kiinteisto.maaraalaTunnus)
                                   ss/not-blank? (str "M"))]))

(defn value-or-other [lang value other & loc-keys]
  (if (util/=as-kw value :other)
    other
    (if (seq loc-keys)
      (i18n/localize lang loc-keys value)
      value)))

(defn designers [{lang :lang app :application}]
  (let [role-keys [:pdf :kuntaRoolikoodi]
        head-loc (i18n/localize lang role-keys "p\u00e4\u00e4suunnittelija")
        heads    (->> (domain/get-documents-by-name app :paasuunnittelija)
                      (map :data)
                      (map #(assoc % :role head-loc)))
        others   (map :data
                      (domain/get-documents-by-name app :suunnittelija))]
    (->> (concat heads others)
         (remove nil?)
         (map (fn [{role-code    :kuntaRoolikoodi
                    other-role   :muuSuunnittelijaRooli
                    difficulty   :suunnittelutehtavanVaativuusluokka
                    info         :henkilotiedot
                    skills       :patevyys
                    role         :role}]
                (let [designer-name (join-non-blanks " "
                                                     [(:etunimi info)
                                                      (:sukunimi info)])]
                  (when-not (ss/blank? designer-name)
                    {:role       (or role
                                     (value-or-other lang
                                                     role-code other-role
                                                     role-keys))
                     :difficulty difficulty
                     :person (->> [designer-name
                                   (value-or-other lang
                                                   (:koulutusvalinta skills)
                                                   (:koulutus skills))]
                                  (join-non-blanks ", "))}))))
         (remove nil?)
         (sort (fn [a b]
                 (if (= (:role a) head-loc) -1 1))))))

(defn resolve-source
  [{:keys [application] :as data} {doc-source  :doc
                                   dict-source :dict
                                   :as         source}]
  (cond
    doc-source  (apply doc-value (cons application doc-source))
    dict-source (dict-value data dict-source)
    :else       (get data source)))

(defn resolve-class [all selected & extra]
  (->> (util/intersection-as-kw all (flatten [selected]))
       (concat extra)
       (remove nil?)))

(defn resolve-cell [{lang :lang :as data}
                    source-value
                    {:keys [text width unit loc-prefix styles] :as cell}]
  (let [path (some-> cell :path pathify)
        class (resolve-class cell-styles
                             styles
                             (when width (str "cell--" width))
                             (or (when (seq path)
                                   (get-in source-value
                                           (cons ::styles path)))
                                 (get-in source-value
                                         [::styles ::cell])))
        value (or text (get-in source-value path source-value))]
    [:div.cell {:class class}
     (cond->> value
       loc-prefix (i18n/localize lang loc-prefix)
       unit (add-unit lang unit))]))

(defn entry-row
  [left-width {:keys [lang dictionary] :as data} [{:keys [loc loc-many source styles]} & cells]]
  (let [source-value (util/pcond-> (resolve-source data source) string? ss/trim)
        multiple?    (and (sequential? source-value)
                          (> (count source-value) 1))]
    (when (or (nil? source) (not-empty source-value))
      (let [section-styles [:page-break :border-top :border-bottom]
            row-styles     (resolve-class row-styles styles
                                          (get-in source-value [::styles :row]))]
        [:div.section
         {:class (util/intersection-as-kw section-styles row-styles)}
         [:div.row
          {:class (util/difference-as-kw row-styles section-styles)}
          [:div.cell
           {:class (resolve-class [:bold] styles (when left-width
                                                   (str "cell--" left-width)))}
           (i18n/localize lang (if multiple? (or loc-many loc) loc))]
          (if (or (> (count cells) 1) multiple?)
            [:div.cell
             [:div.section
              (for [v (if (sequential? source-value)
                        source-value
                        (vector source-value))]
                [:div.row
                 {:class (get-in v [::styles :row])}
                 (map (partial resolve-cell data v)
                      (if (empty? cells) [{}] cells))])]]
            (resolve-cell data
                          (util/pcond-> source-value
                                        sequential? first)
                          (first cells)))]]))))

(defn content
  [data {:keys [left-width entries]}]
  (->> entries
       (map (partial entry-row left-width data))
       (filter not-empty)))

(defn primary-operation-data [application]
  (->> application
       :primaryOperation
       :id
       (domain/get-document-by-operation application)
       :data))

(defn operation-infos
  [application]
  (mapv (util/fn-> :schema-info :op)
        (app/get-sorted-operation-documents application)))

(defn operations
  "If the verdict has an :operation property, its value overrides the
  application primary operation."
  [{:keys [lang verdict application]}]
  (let [infos     (map (util/fn->> :name
                                   (i18n/localize lang :operations)
                                   (hash-map :text))
                       (operation-infos application))
        operation (ss/trim (get-in verdict [:data :operation]))]
    (vec (if (ss/not-blank? operation)
           (cons {:text operation} (rest infos))
           infos))))

(defn verdict-buildings [{:keys [application] :as options}]
  (let [buildings (reduce-kv (fn [acc k {flag? :show-building :as v}]
                               (cond-> acc
                                 flag? (assoc k v)))
                             {}
                             (dict-value options :buildings))]
    (->> (map (comp keyword :id) (operation-infos application))
         (map #(get buildings %))
         (remove nil?))))

(defn building-parking [lang {:keys [description tag building-id]
                              :as   building}]
  (letfn [(park [kw]
            (hash-map :text (i18n/localize lang :pate-buildings.info kw)
                      :amount (kw building)))]
    (-<>> [:kiinteiston-autopaikat :rakennetut-autopaikat]
          (map park)
          (sort-by :text)
          vec
          (conj <> (park :autopaikat-yhteensa))
          (remove (comp ss/blank? :amount))
          (cons {:text   (-<>> [tag description]
                               (join-non-blanks ": ")
                               (join-non-blanks " \u2013 " <> building-id)
                               (vector :strong))
                 :amount ""}))))

(defn parking-section [lang buildings]
  (let [rows (->> buildings
                  (map (partial building-parking lang))
                  (remove nil?))]
    (when (seq rows)
      [:div.section rows])))

(defn verdict-attachments [{lang :lang :as options}]
  (->> (dict-value options :attachments)
       (map (fn [{:keys [type-group type-id amount]}]
              {:text   (i18n/localize lang :attachmentType type-group type-id)
               :amount amount}))
       (sort-by :text)))

(defn references-included? [{:keys [verdict]} kw]
  (get-in verdict [:data (keyword (str (name kw) "-included"))]))

(defn references [{:keys [lang verdict] :as options} kw]
  (when (references-included? options kw)
    (let [ids (dict-value options kw)]
     (->> (get-in verdict [:references kw])
          (filter #(util/includes-as-kw? ids (:id %)))
          (map (keyword lang))
          sort))))

(defn review-info [options]
  (when (references-included? options :reviews)
    (dict-value options :review-info)))

(defn conditions [options]
  (->> (dict-value options :conditions)
       (map (fn [[k v]]
              {:id   (name k)
               :text (:condition v)}))
       (sort-by :id)
       (map (comp markup/markup->tags :text))))

(defn statements [{lang :lang :as options}]
  (->> (dict-value options :statements)
       (filter :given)
       (map (fn [{:keys [given text status]}]
              (join-non-blanks ", "
                               text
                               (date/finnish-date given)
                               (i18n/localize lang :statement status))))
       not-empty))

(defn collateral [{:keys [lang] :as options}]
  (when (dict-value options :collateral-flag)
    (join-non-blanks ", "
                     [(add-unit lang :eur (dict-value options :collateral))
                      (loc-non-blank lang :pate.collateral-type
                                     (dict-value options :collateral-type))
                      (dict-value options :collateral-date)])))

(defn organization-name [lang {organization :organization}]
  (org/get-organization-name organization lang))

(defn handler
  "Handler with title (if given)"
  [options]
  (->> [:handler-title :handler]
       (map (partial dict-value options))
       (map ss/trim)
       (remove ss/blank?)
       (ss/join " ")))

(defn link-permits
  "Since link-permits resolutive is quite database intensive operation
  it is only done for YA category."
  [{:keys [verdict application]}]
  (when (util/=as-kw :ya (:category verdict))
    (:linkPermitData (app-meta/enrich-with-link-permit-data application))))

(defn verdict-properties
  "Adds all kinds of different properties to the options. It is then up
  to category-specific verdict-body methods and corresponding
  pdf-layouts whether every property is displayed in the pdf or not."
  [{:keys [lang application verdict] :as options}]
  (let [buildings                (verdict-buildings options)
        {:keys [category
                schema-version]} verdict
        opts                     (assoc options
                                        :dictionary
                                        (:dictionary (shared/verdict-schema category
                                                                            schema-version)))]
    (assoc opts
           :application-id (:id application)
           :property-id (property-id application)
           :applicants (->> (applicants opts)
                            (map #(format "%s\n%s"
                                        (:name %) (:address %)))
                          (interpose "\n"))
         :operations (assoc-in (operations opts)
                               [0 ::styles :text] :bold)
         :complexity (complexity opts)
         :designers (designers opts)
         :primary (primary-operation-data application)
         :paloluokka (->> buildings
                          (map :paloluokka)
                          (remove ss/blank?)
                          distinct
                          (ss/join " / "))
         :parking (->>  buildings
                        (map (partial building-parking lang))
                        (interpose {:text    "" :amount ""
                                    ::styles {:row :pad-before}})
                        flatten)
         :attachments (verdict-attachments opts)
         :reviews (references opts :reviews)
         :review-info (review-info opts)
         :plans   (references opts :plans)
         :conditions (conditions opts)
         :statements (statements opts)
         :collateral (collateral opts)
         :organization (organization-name lang application)
         :muutoksenhaku (loc-fill-non-blank lang
                                            :pdf.not-later-than
                                            (dict-value opts
                                                        :muutoksenhaku))
         :voimassaolo (loc-fill-non-blank lang
                                          :pdf.voimassa.text
                                          (dict-value opts
                                                      :aloitettava)
                                          (dict-value opts
                                                      :voimassa))
         :voimassaolo-ya (loc-fill-non-blank lang
                                          :pdf.voimassa.text
                                          (dict-value opts
                                                      :start-date)
                                          (dict-value opts
                                                      :end-date))
         :handler (handler opts)
         :link-permits (link-permits opts))))

(defn verdict-body [{verdict :verdict :as options}]
  (->> verdict
       :category
       keyword
       (get pdf-layouts)
       (content (verdict-properties options))))

(defn verdict-header
  [lang application {:keys [category published] :as verdict}]
  [:div.header
   [:div.section.header
    [:div.row.pad-after
     [:div.cell.cell--40
      (organization-name lang application)
      (when-let [boardname (some-> verdict :references :boardname)]
        [:div boardname])]
     [:div.cell.cell--20.center
      [:div (if published
              (i18n/localize lang (case (keyword category)
                                    :p :pdf.poikkeamispaatos
                                    :attachmentType.paatoksenteko.paatos))
              [:span.preview (i18n/localize lang :pdf.preview)])]]
     [:div.cell.cell--40.right
      [:div.permit (i18n/localize lang :pdf category :permit)]]]
    [:div.row
     [:div.cell.cell--40
      (add-unit lang :section (dict-value verdict :verdict-section))]
     [:div.cell.cell--20.center
      [:div (dict-value verdict :verdict-date)]]
     [:div.cell.cell--40.right (i18n/localize lang :pdf.page) " " [:span#page-number ""]]]]])

(defn verdict-footer []
  [:div.footer
   [:div.section
    [:div.row.pad-after.pad-before
     [:cell.cell--100 {:dangerouslySetInnerHTML {:__html "&nbsp;"}}]]]])

(defn language [verdict]
  (-> verdict :data :language))

(defn verdict-html
  "returns :header, :body, :footer map."
  [application verdict]
  {:body   (html (verdict-body {:lang        (language verdict)
                                :application (tools/unwrapped application)
                                :verdict     verdict}))
   :header (html (verdict-header (language verdict) application verdict) true)
   :footer (html (verdict-footer))})

(defn upload-verdict-pdf [application {:keys [published] :as verdict}]
  (let [{:keys [body header footer]} (verdict-html application verdict)]
    (html-pdf/create-and-upload-pdf
     application
     "pate-verdict"
     body
     {:filename (i18n/localize-and-fill (language verdict)
                                        (if published
                                          :pdf.filename
                                          :pdf.draft)
                                        (:id application)
                                        (util/to-local-datetime (or published (now))))
      :header header
      :footer footer})))


(defn create-verdict-attachment
  "1. Create PDF file for the verdict.
   2. Create verdict attachment.
   3. Bind 1 into 2."
  [{:keys [application created] :as command} verdict]
  (let [{:keys [file-id]}          (upload-verdict-pdf application verdict)
        _                          (when-not file-id
                                     (fail! :pate.pdf-verdict-error))
        {attachment-id :id
         :as           attachment} (att/create-attachment!
                                    application
                                    {:created           created
                                     :set-app-modified? false
                                     :attachment-type   {:type-group "paatoksenteko"
                                                         :type-id    "paatos"}
                                     :target            {:type "verdict"
                                                         :id   (:id verdict)}
                                     :locked            true
                                     :read-only         true
                                     :contents          (i18n/localize (language verdict)
                                                                       :pate-verdict)})]
    (bind/bind-single-attachment! (update-in command
                                             [:application :attachments]
                                             #(conj % attachment))
                                  (mongo/download file-id)
                                  {:fileId       file-id
                                   :attachmentId attachment-id}
                                  nil)))

(defn create-verdict-preview
  "Creates draft version of the verdict
  PDF. Returns :pdf-file-stream, :filename map or :error map."
  [{:keys [application created] :as command} verdict]
  (let [pdf (html-pdf/html->pdf application
                                "pate-verdict-draft"
                                (verdict-html application verdict))]
    (if (:ok pdf)
      (assoc pdf :filename (i18n/localize-and-fill (language verdict)
                                                   :pdf.draft
                                                   (:id application)
                                                   (date/finnish-date created)))
      {:error :pate.pdf-verdict-error})))
