(ns lupapalvelu.matti-itest
  (:require [lupapalvelu.factlet :refer :all]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.matti-itest-util :refer :all]
            [midje.sweet :refer :all]
            [sade.strings :as ss]
            [sade.util :as util]))

(apply-remote-minimal)

(defn err [error]
  (partial expected-failure? error))

(defn mangle-keys [m fun]
  (reduce-kv (fn [acc k v]
               (assoc acc (fun k) v))
             {}
             m))

(defn prefix-keys [m prefix]
  (mangle-keys m (util/fn->> name (str (name prefix)) keyword)))

(defn toggle-sipoo-matti [flag]
  (fact {:midje/description (str "Sipoo Matti: " flag)}
    (command admin :set-organization-boolean-path
             :organizationId "753-R"
             :path "matti-enabled"
             :value flag) => ok?))

(facts "Matti enabled"
  (fact "Disable Matti in Sipoo"
    (toggle-sipoo-matti false)
    (query sipoo :matti-enabled) => (err :error.matti-disabled))
  (fact "Enable Matti in Sipoo"
    (toggle-sipoo-matti true)
    (query sipoo :matti-enabled) => ok?))

(facts "Settings"
  (fact "Bad category"
    (query sipoo :verdict-template-settings
           :category "foo") => (err :error.invalid-category))
  (fact "No settings"
    (query sipoo :verdict-template-settings
           :category "r")=> (just {:ok true}))
  (fact "Save to bad path"
    (command sipoo :save-verdict-template-settings-value
                   :category "r"
                   :path [:one :two]
                   :value ["a" "b" "c"])
    => (err :error.invalid-value-path))
  (fact "Save bad value"
    (command sipoo :save-verdict-template-settings-value
             :category "r"
             :path [:foremen]
             :value [:bad-tj])
    => (err :error.invalid-value))
  (fact "Save settings draft"
    (let [{modified :modified}
          (command sipoo :save-verdict-template-settings-value
                   :category "r"
                   :path [:verdict-code]
                   :value [:ehdollinen :ei-puollettu
                           :evatty :hyvaksytty])]
      modified => pos?
      (fact "Query settings"
        (query sipoo :verdict-template-settings
               :category "r")
        => (contains {:settings {:draft    {:verdict-code ["ehdollinen" "ei-puollettu"
                                                           "evatty" "hyvaksytty"]}
                                 :modified modified}})))
    (fact "Select three foremen"
      (command sipoo :save-verdict-template-settings-value
               :category :r
               :path [:foremen]
               :value [:vastaava-tj :iv-tj :erityis-tj])
      => ok?)))

(fact "Sipoo categories"
  (:categories (query sipoo :verdict-template-categories))
  => (contains ["r" "p" "ymp" "kt"] :in-any-order))

(fact "Create new template"
  (let [{:keys [id name draft modified category]} (init-verdict-template sipoo :r)]
    id => string?
    name => "P\u00e4\u00e4t\u00f6spohja"
    draft => {}
    modified => pos?
    category => "r"
    (fact "Fetch draft"
      (query sipoo :verdict-template :template-id id)
      => (contains {:id       id
                    :name     name
                    :draft    {}
                    :modified modified}))
    (fact "Template list"
      (query sipoo :verdict-templates)
      => (contains {:verdict-templates [{:id        id
                                         :name      name
                                         :modified  modified
                                         :deleted   false
                                         :category  "r"
                                         :published nil}]}))
    (fact "Change the name"
      (let [{later :modified}
            (command sipoo :set-verdict-template-name
                     :template-id id
                     :name "Uusi nimi")]
        (- later modified) => pos?
        (fact "Save draft data value"
          (let [{even-later :modified}
                (command sipoo :save-verdict-template-draft-value
                         :template-id id
                         :path [:giver]
                         :value :viranhaltija)]
            (- even-later later) => pos?
            (fact "Fetch draft again"
              (query sipoo :verdict-template :template-id id)
              => (contains {:id       id
                            :name     "Uusi nimi"
                            :draft    {:giver "viranhaltija"}
                            :modified even-later}))
            (fact "Enable anto date delta"
              (command sipoo :save-verdict-template-draft-value
                       :template-id id
                       :path [:anto :enabled]
                       :value true) => ok?)
            (fact "Set anto date delta"
              (command sipoo :save-verdict-template-draft-value
                       :template-id id
                       :path [:anto :delta]
                       :value 2)=> ok?)
            (fact "vv-tj is not supported by settings"
              (command sipoo :save-verdict-template-draft-value
                       :template-id id
                       :path [:foremen]
                       :value [:vv-tj])
              => (err :error.invalid-value))
            (fact "Set foremen removed"
              (command sipoo :save-verdict-template-draft-value
                       :template-id id
                       :path [:removed-sections :foremen]
                       :value true) => ok?)
            (fact "Set plans removed"
              (let [{last-edit :modified}
                    (command sipoo :save-verdict-template-draft-value
                             :template-id id
                             :path [:removed-sections :plans]
                             :value true) => ok?]
                (fact "Fetch draft to see the compound items are OK"
                  (query sipoo :verdict-template :template-id id)
                  => (contains {:id    id
                                :name  "Uusi nimi"
                                :draft {:giver            "viranhaltija"
                                        :anto             {:enabled true
                                                           :delta   2}
                                        :removed-sections {:foremen true
                                                           :plans   true}}
                                :modified last-edit}))
                (fact "Publish template"
                  (let [{published :published} (publish-verdict-template sipoo id)]
                    (- published last-edit) => pos?
                    (fact "Delete template"
                      (command sipoo :toggle-delete-verdict-template
                               :template-id id
                               :delete true) => ok?)
                    (fact "Template list again. Publishing and deletion do not affect modified timestamp."
                      (query sipoo :verdict-templates)
                      => (contains {:verdict-templates [{:id        id
                                                         :name      "Uusi nimi"
                                                         :modified  last-edit
                                                         :deleted   true
                                                         :category  "r"
                                                         :published published}]}))
                    (fact "Name change not allowed for deleted template"
                      (command sipoo :set-verdict-template-name
                               :template-id id
                               :name "Foo")
                      => (err :error.verdict-template-deleted))
                    (fact "Draft data update not allowed for deleted template"
                      (command sipoo :save-verdict-template-draft-value
                               :template-id id
                               :path [:hii]
                               :value "Foo")
                      => (err :error.verdict-template-deleted))
                    (fact "Publish not allowed for deleted template"
                          (publish-verdict-template sipoo id)
                      => (err :error.verdict-template-deleted))
                    (fact "Fetch draft not allowed for deleted template"
                      (query sipoo :verdict-template
                             :template-id id)
                      => (err :error.verdict-template-deleted))
                    (fact "Copying is allowed also for deleted templates"
                      (let [{:keys [copy-id copy-modified copy-published
                                    copy-deleted copy-draft copy-name
                                    copy-category]}
                            (prefix-keys (command sipoo :copy-verdict-template
                                                  :template-id id)
                                         :copy-)]
                        copy-id =not=> id
                        (- copy-modified published) => pos?
                        copy-published => nil
                        copy-category => "r"
                        copy-name => "Uusi nimi (kopio)"
                        copy-draft => {:giver            "viranhaltija"
                                       :anto             {:enabled true
                                                          :delta   2}
                                       :removed-sections {:foremen true
                                                          :plans   true}}
                        (fact "Editing copy draft does not affect original"
                          (command sipoo :save-verdict-template-draft-value
                                   :template-id copy-id
                                   :path [:paatosteksti]
                                   :value  "This is the verdict.") => ok?
                          (fact "Copy has new data"
                            (query sipoo :verdict-template
                                   :template-id copy-id)
                            => (contains {:draft {:giver            "viranhaltija"
                                                  :anto             {:enabled true
                                                                     :delta   2}
                                                  :removed-sections {:foremen true
                                                                     :plans   true}
                                                  :paatosteksti     "This is the verdict."}
                                          :name  "Uusi nimi (kopio)"}))
                          (fact "Restore the deleted template"
                            (command sipoo :toggle-delete-verdict-template
                                     :template-id id
                                     :delete false) => ok?)
                          (fact "The original (restored) template does not have new data"
                            (query sipoo :verdict-template
                                   :template-id id)
                            => (contains {:draft {:giver            "viranhaltija"
                                                  :anto             {:enabled true
                                                                     :delta   2}
                                                  :removed-sections {:foremen true
                                                                     :plans   true}}
                                          :name  "Uusi nimi"}))
                          (fact "Template list has both templates"
                            (->> (query sipoo :verdict-templates)
                                 :verdict-templates
                                 (map #(select-keys % [:id :name])))
                            =>  [{:id id :name "Uusi nimi"}
                                 {:id copy-id :name "Uusi nimi (kopio)"}]))))))))))))))

(fact "Delete nonexisting template"
  (command sipoo :toggle-delete-verdict-template
           :template-id "bad-id" :delete true)
  => (err :error.verdict-template-not-found))

(fact "Copy nonexisting template"
  (command sipoo :copy-verdict-template
           :template-id "bad-id")
  => (err :error.verdict-template-not-found))

(facts "Data path and value validation"
  (let [{id :id} (init-verdict-template sipoo :r)]
    (command sipoo :save-verdict-template-draft-value
             :template-id id
             :path [:julkipano :enabled]
             :value "bad")=> (err :error.invalid-value)
    (command sipoo :save-verdict-template-draft-value
             :template-id id
             :path [:bad :path]
             :value false)=> (err :error.invalid-value-path)
    (command sipoo :save-verdict-template-draft-value
             :template-id id
             :path [:valitus :delta]
             :value -8) => (err :error.invalid-value)
    (command sipoo :save-verdict-template-draft-value
             :template-id id
             :path [:verdict-code]
             :value :bad) => (err :error.invalid-value)
    (command sipoo :save-verdict-template-draft-value
             :template-id id
             :path [:verdict-code]
             :value :hyvaksytty) => ok?))

(facts "Reviews"
  (fact "Initially empty"
    (query sipoo :verdict-template-reviews :category "r")
    => {:ok      true
        :reviews []})
  (fact "Add new review"
    (let [{id :id} (:review (command sipoo :add-verdict-template-review
                                     :category "r"))]
      id => truthy
      (fact "Fetch reviews again"
        (:reviews (query sipoo :verdict-template-reviews :category "r"))
        => (just [(contains {:name     {:fi "Katselmus"
                                        :sv "Syn"
                                        :en "Review"}
                             :category "r"
                             :deleted  false
                             :type     "muu-katselmus"})]))
      (fact "Give name to the review"
        (:review (command sipoo :update-verdict-template-review
                          :review-id id
                          :fi "Nimi" :sv "Namn" :en "Name"))
        => (contains {:id       id
                      :name     {:fi "Nimi" :sv "Namn" :en "Name"}
                      :deleted  false
                      :category "r"
                      :type     "muu-katselmus"}))
      (fact "New name available"
        (:reviews (query sipoo :verdict-template-reviews :category "r"))
        => (just [(contains {:name     {:fi "Nimi" :sv "Namn" :en "Name"}
                             :category "r"
                             :deleted  false
                             :type     "muu-katselmus"})]))
      (facts "Update review details"
        (fact "Finnish name"
          (command sipoo :update-verdict-template-review
                   :review-id id
                   :fi "Moro")
          => (contains {:review (contains {:id id
                                           :name {:fi "Moro"
                                                  :sv "Namn"
                                                  :en "Name"}})}))
        (fact "Name cannot be empty"
          (command sipoo :update-verdict-template-review
                   :review-id id
                   :en "  ")
          => (err :error.name-blank))
        (fact "Review type"
          (command sipoo :update-verdict-template-review
                   :review-id id
                   :type :aloituskokous)
          => (contains {:review (contains {:id id
                                           :type "aloituskokous"})}))
        (fact "Invalid review type"
          (command sipoo :update-verdict-template-review
                   :review-id id
                   :type :hiihoo)
          => (err :error.invalid-review-type))
        (fact "Swedish and English names, review type"
          (command sipoo :update-verdict-template-review
                   :review-id id
                   :sv "Stockholm" :en "London" :type :lvi-katselmus)
          => (contains {:review (contains {:id id
                                           :name {:fi "Moro"
                                                  :sv "Stockholm"
                                                  :en "London"}
                                           :type "lvi-katselmus"})}))
        (fact "Unsupported params"
          (command sipoo :update-verdict-template-review
                   :review-id id
                   :type :aloituskokous
                   :foo "bar")
          => (err :error.unsupported-parameters))
        (fact "Mark review as deleted"
          (command sipoo :update-verdict-template-review
                   :review-id id
                   :deleted true)
          => (contains {:review (contains {:id id
                                           :deleted true})}))
        (fact "Deleted review cannot be re-deleted"
          (command sipoo :update-verdict-template-review
                   :review-id id
                   :deleted true)
          => (err :error.settings-item-deleted))
        (fact "Deleted reviews cannot be edited"
          (command sipoo :update-verdict-template-review
                   :review-id id
                   :type :aloituskokous
                   :fi "Hei")
          => (err :error.settings-item-deleted))
        (fact "Deleted reviews can be restored (and edited)"
          (command sipoo :update-verdict-template-review
                   :review-id id
                   :type :aloituskokous
                   :fi "Hei"
                   :deleted false)
          => (contains {:review (contains {:id id
                                           :type "aloituskokous"
                                           :name (contains {:fi "Hei"})
                                           :deleted false})}))
        (fact "Review not found"
          (command sipoo :update-verdict-template-review
                   :review-id "notfoun"
                   :type :aloituskokous
                   :fi "Hei")
          => (err :error.settings-item-not-found))
        (fact "Select review"
          (command sipoo :save-verdict-template-settings-value
                   :category "r"
                   :path [:reviews]
                   :value [id]) => ok?)))))

(facts "Plans"
  (fact "Initially empty"
    (query sipoo :verdict-template-plans :category "r")
    => {:ok      true
        :plans []})
  (fact "Add new plan"
    (let [{id :id} (:plan (command sipoo :add-verdict-template-plan
                                     :category "r"))]
      id => truthy
      (fact "Fetch plans again"
        (:plans (query sipoo :verdict-template-plans :category "r"))
        => (just [(contains {:name     {:fi "Suunnitelmat"
                                        :sv "Planer"
                                        :en "Plans"}
                             :category "r"
                             :deleted  false})]))
      (fact "Give name to the plan"
        (:plan (command sipoo :update-verdict-template-plan
                          :plan-id id
                          :fi "Nimi" :sv "Namn" :en "Name"))
        => (contains {:id       id
                      :name     {:fi "Nimi" :sv "Namn" :en "Name"}
                      :deleted  false
                      :category "r"}))
      (fact "New name available"
        (:plans (query sipoo :verdict-template-plans :category "r"))
        => (just [(contains {:name     {:fi "Nimi" :sv "Namn" :en "Name"}
                             :category "r"
                             :deleted  false})]))
      (facts "Update plan details"
        (fact "Finnish name"
          (command sipoo :update-verdict-template-plan
                   :plan-id id
                   :fi "Moro")
          => (contains {:plan (contains {:id id
                                           :name {:fi "Moro"
                                                  :sv "Namn"
                                                  :en "Name"}})}))
        (fact "Name cannot be empty"
          (command sipoo :update-verdict-template-plan
                   :plan-id id
                   :en "  ")
          => (err :error.name-blank))
        (fact "Swedish and English names"
          (command sipoo :update-verdict-template-plan
                   :plan-id id
                   :sv "Stockholm" :en "London")
          => (contains {:plan (contains {:id id
                                           :name {:fi "Moro"
                                                  :sv "Stockholm"
                                                  :en "London"}})}))
        (fact "Unsupported params"
          (command sipoo :update-verdict-template-plan
                   :plan-id id
                   :type :aloituskokous)
          => (err :error.unsupported-parameters))
        (fact "Mark plan as deleted"
          (command sipoo :update-verdict-template-plan
                   :plan-id id
                   :deleted true)
          => (contains {:plan (contains {:id id
                                         :deleted true})}))
        (fact "Deleted plan cannot be re-deleted"
          (command sipoo :update-verdict-template-plan
                   :plan-id id
                   :deleted true)
          => (err :error.settings-item-deleted))
        (fact "Deleted plans cannot be edited"
          (command sipoo :update-verdict-template-plan
                   :plan-id id
                   :fi "Hei")
          => (err :error.settings-item-deleted))
        (fact "Deleted plans can be restored (and edited)"
          (command sipoo :update-verdict-template-plan
                   :plan-id id
                   :fi "Hei"
                   :deleted false)
          => (contains {:plan (contains {:id id
                                         :name (contains {:fi "Hei"})
                                         :deleted false})}))
        (fact "Plan not found"
          (command sipoo :update-verdict-template-plan
                   :plan-id "notfound"
                   :fi "Hei")
          => (err :error.settings-item-not-found))
        (fact "Select plan"
          (command sipoo :save-verdict-template-settings-value
                   :category "r"
                   :path [:plans]
                   :value [id]) => ok?)))))

(facts "Operation default verdict template"
  (fact "No defaults yet"
    (:templates (query sipoo :default-operation-verdict-templates))
    => {})
  (let [{template-id :id} (init-verdict-template sipoo :r)]
    (fact "Verdict template not published"
      (command sipoo :set-default-operation-verdict-template
               :operation "pientalo" :template-id template-id)
      => (err :error.verdict-template-not-published))
    (fact "Publish the verdict template"
      (publish-verdict-template sipoo template-id)
      => ok?)
    (fact "Delete verdict template"
      (command sipoo :toggle-delete-verdict-template
               :template-id template-id :delete true)
      => ok?)
    (fact "Verdict template not editable"
      (command sipoo :set-default-operation-verdict-template
               :operation "pientalo" :template-id template-id)
      => (err :error.verdict-template-deleted))
    (fact "Delete verdict template"
      (command sipoo :toggle-delete-verdict-template
               :template-id template-id :delete false)
      => ok?)
    (fact "Bad operation"
      (command sipoo :set-default-operation-verdict-template
               :operation "bad-operation" :template-id template-id)
      => (err :error.unknown-operation))
    (fact "Bad operation category"
      (command sipoo :set-default-operation-verdict-template
               :operation "rasitetoimitus" :template-id template-id)
      => (err :error.invalid-category))
    (fact "Set template for operation"
      (command sipoo :set-default-operation-verdict-template
               :operation "pientalo" :template-id template-id)
      => ok?)
    (fact "Defaults are no longer empty"
      (:templates (query sipoo :default-operation-verdict-templates))
      => {:pientalo template-id})
    (fact "Set template for another operation"
      (command sipoo :set-default-operation-verdict-template
               :operation "muu-laajentaminen" :template-id template-id)
      => ok?)
    (fact "Defaults has two items"
      (:templates (query sipoo :default-operation-verdict-templates))
      => {:pientalo template-id :muu-laajentaminen template-id})
    (fact "Empty template-id clears default"
      (command sipoo :set-default-operation-verdict-template
               :operation "muu-laajentaminen" :template-id "")
      => ok?
      (:templates (query sipoo :default-operation-verdict-templates))
      => {:pientalo template-id})
    (fact "Deleted template can no longer be default"
      (command sipoo :toggle-delete-verdict-template
               :template-id template-id :delete true)
      => ok?
      (:templates (query sipoo :default-operation-verdict-templates))
      => {})
    (fact "Undoing deletion makes template default again"
      (command sipoo :toggle-delete-verdict-template
               :template-id template-id :delete false)
      => ok?
      (:templates (query sipoo :default-operation-verdict-templates))
      => {:pientalo template-id})

    (facts "Application verdict templates"
      (let [{app-id :id} (create-and-submit-application pena
                                                        :operation :pientalo
                                                        :propertyId sipoo-property-id)]
        (fact "Create and delete verdict template"
          (let [{tmp-id :id} (init-verdict-template sipoo :r)]
            (command sipoo :toggle-delete-verdict-template
                     :template-id tmp-id :delete true) => ok?))
        (fact "Rename assumed default"
          (command sipoo :set-verdict-template-name
                   :template-id template-id
                   :name "Oletus"))
        (:templates (query sonja :application-verdict-templates :id app-id))
        => (just [(just {:id template-id
                         :default? true
                         :name "Oletus"})
                  (just {:id #"\w+"
                         :default? false
                         :name "Uusi nimi"})] :in-any-order)))))

(facts "Verdicts"
  (let [{template-id :id} (init-verdict-template sipoo :r)
        plan              (-> (query sipoo :verdict-template-plans :category "r")
                              :plans first)
        review            (-> (query sipoo :verdict-template-reviews :category "r")
                              :reviews first)]
    (fact "Plan" plan =not=> nil)
    (fact "Review" review =not=> nil)
    (letfn [(set-date-delta [k v]
              (set-template-draft-values template-id [k :enabled] true
                                                     [k :delta] v))]
      (fact "Full template"
        (command sipoo :set-verdict-template-name
                 :template-id template-id
                 :name "Full template") => ok?)
      (facts "Enable and set all deltas"
        (set-date-delta "julkipano" 1)
        (set-date-delta "anto" 2)
        (set-date-delta "valitus" 3)
        (set-date-delta "lainvoimainen" 4)
        (set-date-delta "aloitettava" 1) ;; years
        (set-date-delta "voimassa" 2) ;; years
        )
      (set-template-draft-values template-id
                        "giver" :lautakunta
                        "verdict-code" :ehdollinen
                        "paatosteksti" "Verdict text."
                        :foremen [:iv-tj :erityis-tj]
                        "plans" [(:id plan)]
                        "reviews" [(:id review)]
                        "conditions" "Other conditions."
                        "appeal" "Humble appeal."
                        "complexity" "medium"
                        "complexity-text" "Complex explanation."
                        "autopaikat" true
                        "paloluokka" true
                        "vss-luokka" true)

      (facts "New verdict using Full template"
        (let [{app-id :id
               :as    application} (create-and-submit-application pena
                                                                  :propertyId sipoo-property-id
                                                                  :operation  :sisatila-muutos)
              verdict-fn-factory   (fn [verdict-id]
                                     {:open #(query sonja :matti-verdict :id app-id
                                                    :verdict-id verdict-id)
                                      :edit #(command sonja :edit-matti-verdict :id app-id
                                                      :verdict-id verdict-id
                                                      :path (map name (flatten [%1]))
                                                      :value %2)})]
          (fact "Error: unpublished template-id for verdict draft"
            (command sonja :new-matti-verdict-draft :id app-id :template-id template-id)
            => fail?)
          (fact "Publish Full template"
            (publish-verdict-template sipoo template-id)
            => ok?)
          (fact "Pena cannot create verdict"
            (command pena :new-matti-verdict-draft :id app-id :template-id template-id)
            => (err :error.unauthorized))
          (fact "Error: bad template-id for verdict draft"
            (command sonja :new-matti-verdict-draft :id app-id :template-id "bad")
            => fail?)
          (fact "Error: Matti disabled in Sipoo"
            (toggle-sipoo-matti false)
            (command sonja :new-matti-verdict-draft
                     :id app-id :template-id template-id)
            => (err :error.matti-disabled))
          (fact "Matti verdict tab pseudo query fails"
            (query sonja :matti-verdict-tab :id app-id)
            => (err :error.matti-disabled))
          (fact "Enable Matti in Sipoo"
            (toggle-sipoo-matti true)
            (query sonja :matti-verdict-tab :id app-id) => ok?)
          (fact "Sonja creates verdict draft"
            (let [draft                (command sonja :new-matti-verdict-draft
                                                :id app-id :template-id template-id)
                  verdict-id           (-> draft :verdict :id)
                  data                 (-> draft :verdict :data)
                  op-id                (-> data :buildings keys first keyword)
                  {open-verdict :open
                   edit-verdict :edit} (verdict-fn-factory verdict-id)
                  check-changes        (fn [{changes :changes} expected]
                                         (fact "Check changes"
                                           changes => expected)
                                         (fact "Check that verdict has been updated"
                                           (-> (open-verdict) :verdict :data)
                                           => (contains (reduce (fn [acc [x y]]
                                                                  (assoc acc
                                                                         (-> x first keyword)
                                                                         y))
                                                                {}
                                                                changes))))
                  check-error          (fn [{errors :errors} & [kw err-kw]]
                                         (if kw
                                           (fact "Check errors"
                                             (some (fn [[x y]]
                                                     (and (= kw (util/kw-path x))
                                                          (keyword y)))
                                                   errors) => (or err-kw
                                                                  :error.invalid-value))
                                           (fact "No errors"
                                             errors => nil)))]
              data => (contains {:voimassa         ""
                                 :appeal           "Humble appeal."
                                 :julkipano        ""
                                 :purpose          ""
                                 :verdict-text     "Verdict text."
                                 :anto             ""
                                 :giver            "lautakunta"
                                 :complexity       "medium"
                                 :aloitettava      ""
                                 :valitus          ""
                                 :foremen          ["iv-tj" "erityis-tj"]
                                 :verdict-code     "ehdollinen"
                                 :collateral       ""
                                 :conditions       "Other conditions."
                                 :rights           ""
                                 :plans-included   true
                                 :foremen-included true
                                 :neighbors        ""
                                 :neighbor-states  []
                                 :lainvoimainen    ""
                                 :reviews-included true
                                 :statements       ""})
              (fact "Building info is empty but contains the template fields"
                data => (contains {:buildings {op-id {:description            ""
                                                      :show-building          true
                                                      :vss-luokka             ""
                                                      :kiinteiston-autopaikat ""
                                                      :building-id            ""
                                                      :operation              "sisatila-muutos"
                                                      :rakennetut-autopaikat  ""
                                                      :tag                    ""
                                                      :autopaikat-yhteensa    ""
                                                      :paloluokka             ""}}}))

              (facts "Verdict references"
                (let [{:keys [foremen plans reviews]} (:references draft)]
                  (fact "Foremen"
                    foremen => (just ["vastaava-tj" "iv-tj" "erityis-tj"] :in-any-order))
                  (fact "Reviews"
                    (:reviews data) => [(-> reviews first :id)])
                  (fact "Plans"
                    (:plans data) => [(-> plans first :id)])))
              (facts "Verdict dates"
                (fact "Set julkipano date"
                  (edit-verdict "julkipano" "20.9.2017") => ok?)
                (fact "Set verdict date"
                  (let [{:keys [modified changes]}
                        (edit-verdict "verdict-date" "27.9.2017")]
                    changes  => []
                    modified => pos?
                    (fact "Verdict data has been updated"
                      (let [data (:verdict (open-verdict))]
                        (:modified data) => modified
                        (:data data) => (contains {:verdict-date "27.9.2017"
                                                   :julkipano    "20.9.2017"})))))
                (fact "Set automatic dates"
                  (check-changes (edit-verdict "automatic-verdict-dates" true)
                                 [[["julkipano"] "28.9.2017"]
                                  [["anto"] "29.9.2017"]
                                  [["valitus"] "2.10.2017"]
                                  [["lainvoimainen"] "2.10.2017"]
                                  [["aloitettava"] "27.9.2018"]
                                  [["voimassa"] "27.9.2019"]]))
                (fact "Clearing the verdict date does not clear automatically calculated dates"
                  (edit-verdict "verdict-date" "")
                  => (contains {:changes []}))
                (fact "Changing the verdict date recalculates others"
                  (check-changes (edit-verdict :verdict-date "6.10.2017")
                                 [[["julkipano"] "9.10.2017"]
                                  [["anto"] "9.10.2017"]
                                  [["valitus"] "9.10.2017"]
                                  [["lainvoimainen"] "10.10.2017"]
                                  [["aloitettava"] "8.10.2018"]
                                  [["voimassa"] "7.10.2019"]])))
              (facts "Verdict foremen"
                (fact "vv-tj not in the template"
                  (check-error (edit-verdict :foremen ["vv-tj"]) :foremen))
                (fact "Vastaava-tj is OK"
                  (check-error (edit-verdict :foremen ["vastaava-tj"]))))
              (facts "Verdict plans"
                (fact "Bad plan not in the template"
                  (check-error (edit-verdict :plans ["bad"]) :plans))
                (fact "Empty plans is OK"
                  (check-error (edit-verdict :plans [])))
                (fact "Set good  plan"
                  (check-error (edit-verdict :plans [(:id plan)]))))
              (facts "Verdict reviews"
                (fact "Bad review not in the template"
                  (check-error (edit-verdict :reviews ["bad"]) :reviews))
                (fact "Empty reviews is OK"
                  (check-error (edit-verdict :reviews [])))
                (fact "Set good  review"
                  (check-error (edit-verdict :reviews [(:id review)]))))
              (facts "Verdict neighbors"
                (fact "Add two neighbors to the application"
                  (let [first-prop-id  "75341600880088"
                        second-prop-id "75341600990099"
                        first-id       (:neighborId (command sonja :neighbor-add :id app-id
                                                             :name "First neighbor"
                                                             :street "Naapurintie 4"
                                                             :city "Sipoo"
                                                             :zip "12345"
                                                             :email "first.neighbor@example.com"
                                                             :propertyId first-prop-id))
                        second-id      (:neighborId (command sonja :neighbor-add :id app-id
                                                             :name "Second neighbor"
                                                             :street "Naapurintie 6"
                                                             :city "Sipoo"
                                                             :zip "12345"
                                                             :email "second.neighbor@example.com"
                                                             :propertyId second-prop-id))]
                    first-id => ss/not-blank?
                    second-id => ss/not-blank?
                    (fact "Verdict has now neighbor states"
                      (-> (open-verdict)
                          :verdict :data :neighbor-states)
                      => (just [{:property-id first-prop-id :done nil}
                                {:property-id second-prop-id :done nil}] :in-any-order))
                    (fact "Mark the first neighbor heard"
                      (command sonja :neighbor-mark-done :id app-id
                               :lang :fi
                               :neighborId first-id) => ok?)
                    (fact "Neighbor states have been updated"
                      (-> (open-verdict)
                          :verdict :data :neighbor-states)
                      => (just [(just {:property-id first-prop-id :done pos?})
                                {:property-id second-prop-id :done nil}] :in-any-order))
                    (fact "Remove the second neighbor"
                      (command sonja :neighbor-remove :id app-id
                               :neighborId second-id) => ok?)
                    (fact "Neighbor states have been updated"
                      (-> (open-verdict)
                          :verdict :data :neighbor-states)
                      => (just [(just {:property-id first-prop-id :done pos?})])))))
              (facts "Verdict buildings"
                (let [{doc-id :id} (util/find-first (util/fn-> :schema-info :op :id
                                                               (util/=as-kw op-id))
                                                    (:documents application))]
                  (fact "Select building for sisatila-muutos operation"
                    (command sonja :merge-details-from-krysp :id app-id
                             :documentId doc-id
                             :buildingId "199887766E"
                             :collection "documents"
                             :overwrite false
                             :path "buildingId") => ok?)
                  (fact "Operation description"
                    (command sonja :update-op-description :id app-id
                             :op-id op-id
                             :desc "Hello world!") => ok?)
                  (fact "Set vss-luokka for the building"
                    (edit-verdict [:buildings op-id :vss-luokka] "Foo"))
                  (fact "Verdict building info updated"
                    (-> (open-verdict) :verdict :data :buildings op-id)
                    => {:description            "Hello world!"
                        :show-building          true
                        :vss-luokka             "Foo"
                        :kiinteiston-autopaikat ""
                        :building-id            "199887766E"
                        :operation              "sisatila-muutos"
                        :rakennetut-autopaikat  ""
                        :tag                    ""
                        :autopaikat-yhteensa    ""
                        :paloluokka             ""})
                  (fact "Change building id to manual"
                    (command sonja :update-doc :id app-id
                             :doc doc-id
                             :collection "documents"
                             :updates [["buildingId" "other"]
                                       ["valtakunnallinenNumero" ""]
                                       ["manuaalinen_rakennusnro" "789"]])
                    => ok?))
                (fact "Add pientalo operation to the application"
                  (command sonja :add-operation :id app-id
                           :operation "pientalo") => ok?)
                (let [{:keys [documents
                              secondaryOperations]} (query-application sonja app-id)
                      op-id-pientalo                (-> secondaryOperations
                                                        first  :id keyword)
                      {doc-id :id}                  (util/find-first
                                                     (util/fn-> :schema-info :op :id
                                                                (util/=as-kw op-id-pientalo))
                                                     documents)]
                  (fact "New empty building in verdict"
                    (-> (open-verdict) :verdict :data :buildings)
                    => {op-id          {:description            "Hello world!"
                                        :show-building          true
                                        :vss-luokka             "Foo"
                                        :kiinteiston-autopaikat ""
                                        :building-id            "789"
                                        :operation              "sisatila-muutos"
                                        :rakennetut-autopaikat  ""
                                        :tag                    ""
                                        :autopaikat-yhteensa    ""
                                        :paloluokka             ""}
                        op-id-pientalo {:description            ""
                                        :show-building          true
                                        :vss-luokka             ""
                                        :kiinteiston-autopaikat ""
                                        :building-id            ""
                                        :operation              "pientalo"
                                        :rakennetut-autopaikat  ""
                                        :tag                    ""
                                        :autopaikat-yhteensa    ""
                                        :paloluokka             ""}})
                  (fact "Add tag and description to pientalo"
                    (command sonja :update-doc :id app-id
                             :doc doc-id
                             :collection "documents"
                             :updates [["tunnus" "Hao"]]) => ok?
                    (command sonja :update-op-description :id app-id
                             :op-id op-id-pientalo
                             :desc "Hen piaoliang!") => ok?)
                  (fact "Set kiinteiston-autopaikat for pientalo"
                    (check-error (edit-verdict [:buildings op-id-pientalo :kiinteiston-autopaikat] "8")))
                  (fact "Buildings updated"
                    (-> (open-verdict) :verdict :data :buildings)
                    => {op-id          {:description            "Hello world!"
                                        :show-building          true
                                        :vss-luokka             "Foo"
                                        :kiinteiston-autopaikat ""
                                        :building-id            "789"
                                        :operation              "sisatila-muutos"
                                        :rakennetut-autopaikat  ""
                                        :tag                    ""
                                        :autopaikat-yhteensa    ""
                                        :paloluokka             ""}
                        op-id-pientalo {:description            "Hen piaoliang!"
                                        :show-building          true
                                        :vss-luokka             ""
                                        :kiinteiston-autopaikat "8"
                                        :building-id            ""
                                        :operation              "pientalo"
                                        :rakennetut-autopaikat  ""
                                        :tag                    "Hao"
                                        :autopaikat-yhteensa    ""
                                        :paloluokka             ""}})
                  (facts "Modify template"
                    (letfn [(edit-template [path value]
                              (fact {:midje/description (format "Template draft %s -> %s" path value)}
                                (command sipoo :save-verdict-template-draft-value
                                         :template-id template-id
                                         :path (map name (flatten [path]))
                                         :value value) => ok?))]
                      (fact "Disable julkipano, valitus, lainvoimainen and aloitettava"
                        (edit-template [:julkipano :enabled] false)
                        (edit-template [:valitus :enabled] false)
                        (edit-template [:lainvoimainen :enabled] false)
                        (edit-template [:aloitettava :enabled] false))
                      (fact "Remove all the other sections except buildings (and verdict)"
                        (edit-template [:removed-sections :foremen] true)
                        (edit-template [:removed-sections :plans] true)
                        (edit-template [:removed-sections :reviews] true)
                        (edit-template [:removed-sections :conditions] true)
                        (edit-template [:removed-sections :neighbors] true)
                        (edit-template [:removed-sections :appeal] true)
                        (edit-template [:removed-sections :statements] true)
                        (edit-template [:removed-sections :collateral] true)
                        (edit-template [:removed-sections :complexity] true)
                        (edit-template [:removed-sections :rights] true)
                        (edit-template [:removed-sections :purpose] true))
                      (fact "Unselect autopaikat and vss-luokka"
                        (edit-template :autopaikat false)
                        (edit-template :vss-luokka false))
                      (fact "Publish template"
                        (publish-verdict-template sipoo template-id) => ok?)))
                  (fact "New verdict"
                    (let  [{verdict :verdict}   (command sonja :new-matti-verdict-draft
                                                         :id app-id
                                                         :template-id template-id)
                           {data       :data
                            verdict-id :id}     verdict
                           {open-verdict :open
                            edit-verdict :edit} (verdict-fn-factory verdict-id)]
                      data => {:voimassa                ""
                               :verdict-text            "Verdict text."
                               :anto                    ""
                               :giver                   "lautakunta"
                               :foremen-included        false
                               :foremen                 ["iv-tj" "erityis-tj"]
                               :verdict-code            "ehdollinen"
                               :plans-included          false
                               :plans                   [(:id plan)]
                               :reviews-included        false
                               :reviews                 [(:id review)]
                               :bulletin-op-description ""
                               :buildings
                               {op-id {:description   "Hello world!"
                                       :show-building true
                                       :building-id   "789"
                                       :operation     "sisatila-muutos"
                                       :tag           ""
                                       :paloluokka    ""}
                                op-id-pientalo {:description   "Hen piaoliang!"
                                                :show-building true
                                                :building-id   ""
                                                :operation     "pientalo"
                                                :tag           "Hao"
                                                :paloluokka    ""}}}
                      (facts "Cannot edit verdict values not in the template"
                        (let [check-fn (fn [kwp value]
                                         (check-error (edit-verdict (util/split-kw-path kwp)
                                                                    value)
                                                      kwp :error.invalid-value-path))]
                          (check-fn :julkipano "29.9.2017")
                          (check-fn :buildings.vss-luokka "12")
                          (check-fn :buildings.kiinteiston-autopaikat 34)))
                      (fact "Publish verdict"
                        (command sonja :publish-matti-verdict
                                 :id app-id
                                 :verdict-id verdict-id) => ok?)
                      (fact "Editing no longer allowed"
                        (edit-verdict :verdict-text "New verdict text")
                        => (err :error.verdict.not-draft))
                      (fact "Remove pientalo operation"
                        (command sonja :remove-doc :id app-id :docId doc-id)
                        => ok?)
                      (fact "Pientalo info still in the published verdict"
                        (let [{:keys [published modified
                                      data]} (:verdict (open-verdict))]
                          published => pos?
                          published => modified
                          (get-in data [:buildings (keyword op-id-pientalo)])
                          => {:description   "Hen piaoliang!"
                              :show-building true
                              :building-id   ""
                              :operation     "pientalo"
                              :tag           "Hao"
                              :paloluokka    ""}))
                      (fact "No pientalo in a new verdict draft"
                        (-> (command sonja :new-matti-verdict-draft
                                     :id app-id
                                     :template-id template-id)
                            :verdict :data :buildings)
                        => {op-id {:description   "Hello world!"
                                   :show-building true
                                   :building-id   "789"
                                   :operation     "sisatila-muutos"
                                   :tag           ""
                                   :paloluokka    ""}}))))))))))))
