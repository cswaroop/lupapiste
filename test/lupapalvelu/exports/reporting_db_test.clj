(ns lupapalvelu.exports.reporting-db-test
  (:require [midje.sweet :refer :all]
            [lupapalvelu.exports.reporting-db :refer :all]
            [lupapalvelu.organization :as org]
            [lupapalvelu.rakennuslupa-canonical-util :refer [application-rakennuslupa]]))

(facts "->reporting-result"
       (against-background
         (org/pate-scope? irrelevant) => false)
      (->reporting-result (assoc application-rakennuslupa :state "closed") "fi")
      => (contains {:luvanTilanne "closed"}))
