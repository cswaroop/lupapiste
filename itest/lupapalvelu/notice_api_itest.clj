(ns lupapalvelu.notice-api-itest
  (require [midje.sweet :refer :all]
           [lupapalvelu.itest-util :refer :all]))

(apply-remote-minimal) ; minimal ensures wanted organization tags exist

(defn application-notice [username id notice]
  (fact {:midje/description (format "Notice for %s: %s" username notice)}
    (:authorityNotice (query-application (apikey-for username) id))
    => notice))

(fact "adding notice"
  (let [{id :id} (create-and-submit-application pena)]
    (fact "user can't set application urgency"
      pena =not=> (allowed? :change-urgency :id id :urgency "urgent")
      (change-application-urgency pena id "urgent") =not=> ok?)

    (fact "authority can set application urgency"
      sonja => (allowed? :change-urgency :id id :urgency "urgent")
      (change-application-urgency sonja id "urgent") => ok?
      (:urgency (query-application sonja id)) => "urgent")

    (fact "user can't set notice message"
      pena =not=> (allowed? :add-authority-notice :id id :authorityNotice "foobar")
      (add-authority-notice pena id "foobar") =not=> ok?)

    (fact "read-only authority can't set notice message"
      luukas =not=> (allowed? :add-authority-notice :id id :authorityNotice "foobar")
      (add-authority-notice luukas id "foobar") =not=> ok?)

    (fact "authority can set notice message"
      sonja => (allowed? :add-authority-notice :id id :authorityNotice "respect my authority")
      (add-authority-notice sonja id "respect my athority") => ok?
      (:authorityNotice (query-application sonja id)) => "respect my athority")

    (facts "Application tags" ; tags are in minimal fixture
      (fact "user can't set application tags"
        (command pena :add-application-tags :id id :tags ["111111111111111111111111" "222222222222222222222222"]) =not=> ok?)
      (fact "authority can set application tags"
        (command sonja :add-application-tags :id id :tags ["111111111111111111111111" "222222222222222222222222"]) => ok?)
      (fact "authority can't set tags that are not defined in organization"
        (command sonja :add-application-tags :id id :tags ["foo" "bar"]) => (partial expected-failure? "error.unknown-tags"))

      (let [query    (query-application sonja id)
            org-tags (get-in query [:organizationMeta :tags])]
        (:tags query) => ["111111111111111111111111" "222222222222222222222222"]

        (fact "application's organization meta includes correct tags with ids as keys"
          org-tags => {:111111111111111111111111 "yl\u00E4maa", :222222222222222222222222 "ullakko"}))

      (fact "When tag is removed, it is also removed from applications"
        (let [id (create-app-id sonja)]
          (command sipoo :save-organization-tags :tags [{:id "123000000000000000000000" :label "foo"} {:id "321000000000000000000000" :label "bar"}]) => ok?
          (command sonja :add-application-tags :id id :tags ["123000000000000000000000" "321000000000000000000000"]) => ok?
          (:tags (query-application sonja id)) => (just ["123000000000000000000000" "321000000000000000000000"])
          (command sipoo :save-organization-tags :tags [{:id "123000000000000000000000" :label "foo"}]) => ok?

          (fact "only 123 is left as 321 was removed"
            (:tags (query-application sonja id)) => (just ["123000000000000000000000"])))))

    (facts "Statement givers and notice panel"
      (let [err (just {:ok   false
                       :text "error.not-organization-statement-giver"})]
        (fact "Jussi is an organisation statement giver"
          (command sipoo :create-statement-giver
                   :email (email-for-key jussi)
                   :text "Moro"
                   :name "Jussi") => ok?)
        (fact "Request statements from Jussi and Teppo"
          (command sonja :request-for-statement
                   :id id
                   :functionCode nil
                   :selectedPersons [{:email (email-for-key jussi)
                                      :name  "Jussi" :text "moro"}
                                     {:email (email-for-key teppo)
                                      :name  "Teppo" :text "hei"}]) => ok?)
        (facts "Authority notice pseudo query"
          (query pena :authority-notice :id id) => unauthorized?
          (query sonja :authority-notice :id id) => ok?
          (query ronja :authority-notice :id id) => ok?
          (query luukas :authority-notice :id id) => ok?
          (query jussi :authority-notice :id id) => ok?
          (query teppo :authority-notice :id id) => err)
        (facts "Change urgency"
          (change-application-urgency jussi id "normal") => ok?
          (change-application-urgency teppo id "pending") => err)
        (facts "Add tags"
          (command jussi :add-application-tags :id id
                   :tags ["123000000000000000000000"])=> ok?
          (command teppo :add-application-tags :id id
                   :tags ["321000000000000000000000"]) => err)
        (facts "Add authority notice"
          (add-authority-notice jussi id "Hello world!") => ok?
          (add-authority-notice teppo id "Foo bar") => err)
        (facts "Read authority notice"
          (application-notice "sonja" id "Hello world!")
          (application-notice "jussi" id "Hello world!")
          (application-notice "pena" id nil)
          (application-notice "luukas" id "Hello world!")
          (application-notice "teppo@example.com" id nil))
        (facts "Application organization tags"
          (let [tags (contains {:tags [{:id    "123000000000000000000000"
                                        :label "foo"}]})]
            (query sonja :application-organization-tags :id id) => tags
            (query jussi :application-organization-tags :id id) => tags
            (query teppo :application-organization-tags :id id) => err
            (query luukas :application-organization-tags :id id) => fail?
            (query pena :application-organization-tags :id id) => fail?))))
    (facts "Authority + application statementGiver combo LP-365545"
      (fact "Ronja can see notices"
        (query ronja :authority-notice :id id) => ok?)
      ; invite Ronja as statementGiver to reveal bug presented in LP-365545
      (command sonja :request-for-statement :functionCode nil :id id
               :selectedPersons [{:name "R0NJ4" :text "Testi" :email (email-for "ronja")}]
               :saateText "ronja testi" :dueDate 1450994400000) => ok?
      (fact "Ronja can still see notices"
        (query ronja :authority-notice :id id) => ok?))
    (facts "Guests and notice panel"
      (fact "Add Veikko as a guest authority"
        (command sipoo :update-guest-authority-organization
                 :description "Vexi"
                 :email "veikko.viranomainen@tampere.fi"
                 :firstName "Veikko"
                 :lastName "Viranomainen"))
      (fact "Sonja adds Veikko as a guest authority to application"
        (command sonja :invite-guest :id id
                 :role "guestAuthority"
                 :email "veikko.viranomainen@tampere.fi"
                 :text "Invitation!") => ok?)
      (fact "Veikko cannot do notice queries"
        (query veikko :authority-notice :id id) => fail?
        (query veikko :application-organization-tags :id id) => fail?)
      (fact "Pena invites Mikko as a guest to application"
        (command sonja :invite-guest :id id
                 :role "guest"
                 :email "mikko.intonen@example.com"
                 :text "Invitation!") => ok?)
      (fact "Mikko cannot do notice queries"
        (query mikko :authority-notice :id id) => fail?
        (query mikko :application-organization-tags :id id) => fail?))))
