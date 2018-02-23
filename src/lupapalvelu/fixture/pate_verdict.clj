(ns lupapalvelu.fixture.pate-verdict
  (:require [lupapalvelu.mongo :as mongo]
            [lupapalvelu.fixture.core :refer [deffixture]]
            [lupapalvelu.fixture.minimal :as minimal]
            [sade.core :refer :all]
            [schema.core :as sc]
            [lupapalvelu.pate.schemas :as ps]))


(def created (now))


(def users (filter (comp #{"admin" "sonja" "pena" "kaino@solita.fi" "erkki@example.com" "sipoo-r-backend"} :username) minimal/users))

(def verdic-templates-setting
  "Some PATE verdict-templates to help with testing"
  {:templates [{:id "5a7aff3e5266a1d9c1581956",
                :draft {:verdict-dates ["lainvoimainen" "anto" "julkipano"],
                        :bulletinOpDescription "",
                        :giver "viranhaltija",
                        :foremen ["vastaava-tj"],
                        :conditions {},
                        :language "fi",
                        :reviews ["5a7affbf5266a1d9c1581957" "5a7affcc5266a1d9c1581958" "5a7affe15266a1d9c1581959"],
                        :paatosteksti "Ver Dict",
                        :upload true,
                        :paloluokka true},
                :name "Päätöspohja",
                :category "r",
                :modified created,
                :deleted false,
                :published {:published created,
                            :data {:verdict-dates ["lainvoimainen" "anto" "julkipano"],
                                   :bulletinOpDescription "",
                                   :giver "viranhaltija",
                                   :plans [],
                                   :foremen ["vastaava-tj"],
                                   :language "fi",
                                   :reviews ["5a7affbf5266a1d9c1581957"
                                             "5a7affcc5266a1d9c1581958"
                                             "5a7affe15266a1d9c1581959"],
                                   :paatosteksti "Ver Dict",
                                   :upload true,
                                   :paloluokka true},
                            :settings {:verdict-code ["evatty" "hyvaksytty" "myonnetty" "ehdollinen" "annettu-lausunto"],
                                       :foremen ["vastaava-tj"],
                                       :date-deltas {:julkipano 2,
                                                     :anto 0,
                                                     :muutoksenhaku 1,
                                                     :lainvoimainen 1,
                                                     :aloitettava 1,
                                                     :voimassa 1},
                                       :plans [],
                                       :reviews [{:id "5a7affbf5266a1d9c1581957",
                                                  :name {:fi "Aloituskokous", :sv "start", :en "start"},
                                                  :type "aloituskokous"}
                                                 {:id "5a7affcc5266a1d9c1581958",
                                                  :name {:fi "Loppukatselmus", :sv "Loppu", :en "Loppu"},
                                                  :type "loppukatselmus"}
                                                 {:id "5a7affe15266a1d9c1581959",
                                                  :name {:fi "Katselmus", :sv "Syn", :en "Review"},
                                                  :type "muu-katselmus"}]}}}
               {:id "5a7c4f33d98b0fe901eee1e6",
                :draft {},
                :name "Päätöspohja",
                :category "r",
                :modified created,
                :deleted false}],
   :settings {:r {:draft {:voimassa {:delta "1"},
                          :julkipano {:delta "2"},
                          :boardname "asd",
                          :muutoksenhaku {:delta "1"},
                          :anto {:delta "0"},
                          :aloitettava {:delta "1"},
                          :foremen ["vastaava-tj"],
                          :verdict-code ["evatty" "hyvaksytty" "myonnetty" "ehdollinen" "annettu-lausunto"],
                          :lainvoimainen {:delta "1"},
                          :lautakunta-muutoksenhaku {:delta "2"}},
                  :modified created}},
   :reviews [{:id "5a7affbf5266a1d9c1581957",
              :name {:fi "Startti", :sv "start", :en "start"},
              :category "r",
              :deleted false,
              :type "aloituskokous"}
             {:id "5a7affcc5266a1d9c1581958",
              :name {:fi "Loppukatselmus", :sv "Loppu", :en "Loppu"},
              :category "r",
              :deleted false,
              :type "loppukatselmus"}
             {:id "5a7affe15266a1d9c1581959",
              :name {:fi "Katselmus", :sv "Syn", :en "Review"},
              :category "r",
              :deleted false,
              :type "muu-katselmus"}],
   :plans [{:id "5a85960a809b5a1e454f3233",
            :name {:fi "Suunnitelmat", :sv "Planer", :en "Plans"},
            :category "r",
            :deleted false}]})

(sc/validate ps/PateSavedVerdictTemplates verdic-templates-setting)

(defn update-sipoo [org]
  (if-not (= "753-R" (:id org))
    org
    (assoc org :verdict-templates verdic-templates-setting)))

(def organizations (->> minimal/organizations
                        (filter (comp (set (mapcat (comp keys :orgAuthz) users)) keyword :id))
                        (map update-sipoo)))

(deffixture "pate-verdict" {}
  (mongo/clear!)
  (mongo/insert-batch :users users)
  (mongo/insert-batch :organizations organizations))
