(ns lupapalvelu.info-links-itest
  (:require [lupapalvelu.factlet :refer [facts*]]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.info-links :as info-links]
            [sade.util :as util]
            [midje.sweet :refer :all]
            [sade.env :as env]))

(apply-remote-minimal)

(facts "info links"
 
  (let [{application-id :id :as app} 
          (create-application pena :propertyId sipoo-property-id)]
   
    application-id => truthy
   
    (let [response (query pena :info-links :id application-id)]
      response => ok?
      (fact "Initially Pena sees no links" (:links response) => []))

    (let [response (command sonja :info-link-upsert :id application-id :text "link text" :url "http://example.org/1")]
      response => ok?
      (fact "Sonja can add an info-link"
        (:linkId response) => 1))

    (let [response (query sonja :info-links :id application-id)]
      response => ok?
      (fact "Sonja sees the infolink, it is not new and is editable"
        (:text (first (:links response))) => "link text"
        (:url  (first (:links response))) => "http://example.org/1"
        ;(:isNew (first (:links response))) => false
        (:canEdit (first (:links response))) => true))
     
    (let [response (command sonja :info-link-upsert :id application-id :text "second" :url "http://example.org/2")]
      response => ok?
      (fact "Sonja adds another info-link"
         (:linkId response) => 2))

    (let [response (command sonja :info-link-upsert :id application-id :text "third url" :url "http://example.org/3")]
      response => ok?
      (fact "Sonja adds a third info-link"
        (:linkId response) => 3))
       
    (let [response (command sonja :info-link-reorder :id application-id :linkIds [3 2 1])]
      response => ok?
      (fact "Sonja reorders the links"
        (:res response) => true))

    (let [response (query sonja :info-links :id application-id)]
      response => ok?
      (fact "Sonja sees ordered links"
        (map :linkId (:links response)) => [3 2 1]))
         
    (let [response (command sonja :info-link-reorder :id application-id :linkIds [1 1 2 5])]
      response => ok?
      (fact "Sonja reorders the links badly"
        (:res response) => false))

    (let [response (command sonja :info-link-delete :id application-id :linkId 2)]
      (fact "Sonja can delete infolinks" 
        response => ok?))
    
    (let [response (command pena :info-link-delete :id application-id :linkId 1)]
      (fact "Pena can't delete infolinks" 
        response =not=> ok?))
    
    (let [response (query sonja :info-links :id application-id)]
      response => ok?
      (fact "Sonja no longer sees the deleted link"
        (map :linkId (:links response)) => [3 1]))
     
    (let [response (query pena :info-links :id application-id)]
      response => ok?
      (fact "Pena hasn't seen the links yet and sees he cant' modify them"
        (map :isNew (:links response)) => [true true]
        (map :canEdit (:links response)) => [false false]))

    (let [resp (command pena :mark-seen :id application-id :type "info-links")
          response (query pena :info-links :id application-id)]
      response => ok?
      (fact "Pena has seen the links after calling mark-seen" (map :isNew (:links response)) => [false false]))
  
    (let [response (command sonja :info-link-upsert :id application-id :text "new text" :url "http://example.org/1-new" :linkId "one")]
      (fact "Sonja fails to update link with bad id"
         response =not=> ok?))
  
    (let [response (command sonja :info-link-upsert :id application-id :text "new text" :url "http://example.org/1-new" :linkId 1)]
      response => ok?
      (fact "Sonja updates an infolink"
        (:linkId response) => 1))
  
    (let [response (command pena :info-link-upsert :id application-id :text "new text bad" :url "http://example.org/1-bad" :linkId 1)]
      (fact "Pena fails to update an infolink"
        response =not=> ok?))
  
    (let [response (query pena :info-links :id application-id)]
      response => ok?
      (fact "Pena sees the new updated link by Sonja as a new one at right position"
        (:isNew (nth (:links response) 1)) => true
        (:url (nth (:links response) 1)) => "http://example.org/1-new"
        (:text (nth (:links response) 1)) => "new text"))

))  
