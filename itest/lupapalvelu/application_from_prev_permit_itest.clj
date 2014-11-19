(ns lupapalvelu.application-itest
  (:require [midje.sweet :refer :all]
            [clojure.java.io :as io]
            [sade.xml :as xml]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.factlet  :refer :all]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.xml.krysp.application-from-krysp :as krysp-fetch-api]))

(apply-remote-minimal)

#_(fact (user/change-password "veikko.viranomainen@tampere.fi" "passu") => nil
     (provided (security/get-hash "passu" anything) => "hash"))


#_(defn create-app-with-fn [f apikey & args]
  (let [args (->> args
               (apply hash-map)
               (merge {:operation "asuinrakennus"
                       :propertyId "75312312341234"
                       :x 444444 :y 6666666
                       :address "foo 42, bar"
                       :municipality (or (muni-for-key apikey) sonja-muni)})
               (mapcat seq))]
    (apply f apikey :create-application args)))

;ajax.command("create-application-from-previous-permit", {
;        operation: self.operation(),
;        y: self.y(),
;        x: self.x(),
;        address: self.addressString(),
;        propertyId: util.prop.toDbFormat(self.propertyId()),
;        municipality: self.municipality().id,
;        kuntalupatunnus: self.kuntalupatunnusFromPrevPermit()
;      })

; (provided (cr/get-xml anything anything anything) => (xml/parse (slurp (clojure.java.io/resource "mml/yhteystiedot-KP.xml"))))

(defn- create-app-from-prev-permit [apikey & args]
  (let [args (->> args
               (apply hash-map)
               (merge {:operation "aiemmalla-luvalla-hakeminen"
                       :municipality "186"  ;; Jarvenpaa
                       :kuntalupatunnus "14-0241-R 3"
                       :y 0
                       :x 0
                       :address ""
                       :propertyId nil})
               (mapcat seq))]
    (apply local-command apikey :create-application-from-previous-permit args)))

(fact* "Successfully creating new application based on a prev permit"

  (let [example-xml (xml/parse (slurp (io/resource "../resources/krysp/sample/verdict-rakval-from-kuntalupatunnus-query.xml")))]
;    (println "\n example-xml: ")
;    (clojure.pprint/pprint example-xml)
;    (println "\n")


  (fact "missing parameters"
    (create-app-from-prev-permit pena :municipality "") => (partial expected-failure? "error.missing-parameters")
    (create-app-from-prev-permit pena :operation "") => (partial expected-failure? "error.missing-parameters"))


  ; 1: Kannassa on app, jonka organization ja verdictin kuntalupatunnus matchaa
  (facts "db has app that has the kuntalupatunnus in its verdict and its organization matches"
    ; 1 a: avaa hakemus, jos on oikat -> (ok :id lupapiste-tunnus)
    (fact "has rights to the found app"
      (create-app-from-prev-permit pena) => (contains {:ok true
                                                       :id "lupis-id"})
      (provided
        (domain/get-application anything) => {:id "lupis-id"}
        (domain/owner-or-writer? anything anything) => true))
    ; 1 b: jos ei oikkia -> (fail :error.lupapiste-application-already-exists-but-unauthorized-to-access-it :id (:id app-with-verdict))
    (fact "does not have rights to the found app"
      (create-app-from-prev-permit pena) => (contains {:ok false
                                                       :id "lupis-id"
                                                       :text "error.lupapiste-application-already-exists-but-unauthorized-to-access-it"})
      (provided
        (domain/get-application anything) => {:id "lupis-id"}
        (domain/owner-or-writer? anything anything) => false)))

  ; Muuten, jos kannassa ei ole appista...

  ; 2: jos taustajarjestelmasta ei saada xml-sisaltoa -> (fail :error.no-previous-permit-found-from-backend)
;  (fact "no xml content received from backend with the kuntalupatunnus"
;    (create-app-from-prev-permit pena) => (partial expected-failure? "error.no-previous-permit-found-from-backend")
;    (provided (krysp-fetch-api/get-application-xml anything false true) => nil))

  ; 3: jos (krysp-reader/get-app-info-from-message xml kuntalupatunnus) palauttaa nillin -> (fail :error.no-previous-permit-found-from-backend)

  ; 4: jos parametrina annettu organisaatio ja app-infosta ratkaistu organisaatio ei matchaa -> (fail :error.previous-permit-found-from-backend-is-of-different-organization)

  ; 5: jos sanomassa ei ollut rakennuspaikkaa, ja ei alunperin annettu tarpeeksi parametreja -> (fail :error.more-prev-app-info-needed :needMorePrevPermitInfo true)

  ; 6) sanomassa tulee lupapiste-id
;  (facts "message includes lupapiste id"
;    ; 6 a: jota ei kuitenkaan ole jarjestelmassa -> (fail :error.not-able-to-open-with-lupapiste-id-that-previous-permit-included :id lupapiste-tunnus)
;    (fact "the lupapiste id is not found from database though"
;      (create-app-from-prev-permit pena) => (contains {:ok false
;                                                       :id "LP-186-2014-00290"
;                                                       :text "error.not-able-to-open-with-lupapiste-id-that-previous-permit-included"}))
;    ; 6 b: on jarjestelmassa, mutta kayttajalla ei oikkia sille -> (fail :lupapiste-application-already-exists-but-unauthorized-to-access-it :id lupapiste-tunnus)
;    ; 6 c: on jarjestelmassa, ja kayttajalla on oikat sille     -> (ok :id lupapiste-tunnus)
;
;    )

  ; 7: do-create-application heittaa jonkin poikkeuksen -> (fail :error.no-previous-permit-found-from-backend)


;    (println "\n resp: ")
;    (clojure.pprint/pprint resp)
;    (println "\n")

    ))


