(ns lupapalvelu.assignment-itest
  (:require [midje.sweet :refer :all]
            [schema.core :as sc]
            [lupapalvelu.itest-util :refer [expected-failure?]]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.assignment :refer [Assignment]]
            [lupapalvelu.assignment-api :refer :all]
            [sade.env :as env]))

(when (env/feature? :assignments)
  (apply-remote-minimal)

  (def ^:private not-completed?
    (partial expected-failure? "error.assignment-not-completed"))

  (def ^:private application-not-accessible?
    (partial expected-failure? "error.application-not-accessible"))

  (def ^:private invalid-receiver?
    (partial expected-failure? "error.invalid-assignment-receiver"))

  (defn create-assignment [from to application-id target desc]
    (command from :create-assignment
             :id          application-id
             :recipient   to
             :target      target
             :description desc))

  (defn complete-assignment [user assignment-id]
    (command user :complete-assignment :assignmentId assignment-id))

  (facts "Querying assignments"
    (fact "only authorities can see assignments"
      (query sonja :assignments) => ok?
      (query pena :assignments)  => unauthorized?)
    (fact "authorities can only see assignments belonging to their organizations"
      (let [{id :id} (create-app sonja :propertyId sipoo-property-id)
            {assignment-id :id} (create-assignment sonja "ronja" id ["target"] "Valmistuva")]
        (-> (query sonja :assignments) :assignments count)  => pos?
        (-> (query veikko :assignments) :assignments count) => zero?))
    (fact "assignments can be fetched by application id"
      (let [{id1 :id} (create-app sonja :propertyId sipoo-property-id)
            {id2 :id} (create-app ronja :propertyId sipoo-property-id)
            {assignment-id1-1 :id} (create-assignment sonja "ronja" id1 ["target"] "Hakemus 1")
            {assignment-id1-2 :id} (create-assignment sonja "ronja" id1 ["target"] "Hakemus 1")
            {assignment-id2-1 :id} (create-assignment sonja "ronja" id2 ["target"] "Hakemus 1")]
        (-> (query sonja :assignments-for-application :id id1) :assignments count) => 2
        (-> (query ronja :assignments-for-application :id id1) :assignments count) => 2
        (-> (query sonja :assignments-for-application :id id2) :assignments count) => 1
        (-> (query ronja :assignments-for-application :id id2) :assignments count) => 1
        (query veikko :assignments-for-application :id id1) => application-not-accessible?)))

  (facts "Creating assignments"
    (let [{id :id} (create-app sonja :propertyId sipoo-property-id)]
      (fact "only authorities can create assignments"
        (create-assignment sonja "ronja" id ["target"] "Kuvaus") => ok?
        (create-assignment pena "sonja" id ["target"] "Hommaa") => unauthorized?)
      (fact "only authorities can receive assignments"
        (create-assignment sonja "pena" id ["target"] "Penalle")        => invalid-receiver?
        (create-assignment sonja "does_not_exist" id ["target"] "Desc") => invalid-receiver?)
      (fact "authorities can only create assignments for applications in their organizations"
        (create-assignment veikko "sonja" id ["target"] "Ei onnistu") => application-not-accessible?)
      (fact "after calling create-assignment, the assignment is created"
        (let [assignment-id (:id (create-assignment sonja "ronja" id ["target"] "Luotu?"))
              assignment    (query sonja :assignment :assignmentId assignment-id)]
          (:assignment assignment) => truthy
          (sc/check Assignment (:assignment assignment)) => nil?
          (-> assignment :assignment :creator :username)   => "sonja"
          (-> assignment :assignment :recipient :username) => "ronja"))))

  (facts "Completing assignments"
    (let [{id :id}            (create-app sonja :propertyId sipoo-property-id)
          {assignment-id1 :id} (create-assignment sonja "ronja" id ["target"] "Valmistuva")
          {assignment-id2 :id} (create-assignment sonja "ronja" id ["target"] "Valmistuva")]
      (fact "Only authorities within the same organization can complete assignment"
        (complete-assignment pena assignment-id1)   => unauthorized?
        (complete-assignment veikko assignment-id1) => not-completed?
        (complete-assignment ronja assignment-id1)  => ok?
        (complete-assignment ronja assignment-id1)  => not-completed?)
      (fact "Authorities CAN complete other authorities' assignments within their organizations"
        (complete-assignment sonja assignment-id2) => ok?)
      (fact "After calling complete-assignment, the assignment is completed"
        (-> (query sonja :assignment :assignmentId assignment-id1) :assignment :status) => "completed"))))