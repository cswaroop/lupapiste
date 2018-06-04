(ns lupapalvelu.conversion.util
  (:require [sade.core :refer :all]
            [sade.strings :as ss]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.prev-permit :as prev-permit]
            [lupapalvelu.xml.krysp.application-from-krysp :as krysp-fetch]
            [lupapalvelu.xml.krysp.reader :as krysp-reader]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.permit :as permit]))

(defn fetch-prev-local-application!
  "A variation of lupapalvelu.prev-permit/fetch-prev-local-application! that exists for conversion
  and testing purposes. Creates an application from Krysp message in a local file. To use a local Krysp
  file:
  1) The local MongoDB has to contain the location info for the municipality in question (here Vantaa)
  2) this function needs to be called from prev-permit-api/create-application-from-previous-permit instead of
  prev-permit/fetch-prev-application!"
  [{{:keys [organizationId kuntalupatunnus authorizeApplicants]} :data :as command}]
  (let [organizationId        "092-R" ;; Vantaa, bypass the selection from form
        operation             "aiemmalla-luvalla-hakeminen"
        permit-type           (operations/permit-type-of-operation operation)
        dummy-application     {:id "" :permitType permit-type :organization organizationId}
        filename              (str "./src/lupapalvelu/conversion/test-data/" kuntalupatunnus ".xml")
        xml                   (krysp-fetch/get-local-application-xml-by-filename filename permit-type)
        app-info              (krysp-reader/get-app-info-from-message xml kuntalupatunnus)
        location-info         (prev-permit/get-location-info command app-info)
        organization          (apply organization/resolve-organization (ss/split organizationId #"-"))
        validation-result     (permit/validate-verdict-xml permit-type xml organization)
        organizations-match?  (= organizationId (:id organization))
        no-proper-applicants? (not-any? prev-permit/get-applicant-type (:hakijat app-info))]
    (cond
      (empty? app-info)                 (fail :error.no-previous-permit-found-from-backend)
      (not location-info)               (fail :error.more-prev-app-info-needed :needMorePrevPermitInfo true)
      (not (:propertyId location-info)) (fail :error.previous-permit-no-propertyid)
      (not organizations-match?)        (fail :error.previous-permit-found-from-backend-is-of-different-organization)
      validation-result                 validation-result
      :else                             (let [{id :id} (prev-permit/do-create-application-from-previous-permit command
                                                                                                   operation
                                                                                                   xml
                                                                                                   app-info
                                                                                                   location-info
                                                                                                   authorizeApplicants)]
                                          (if no-proper-applicants?
                                            (ok :id id :text :error.no-proper-applicants-found-from-previous-permit)
                                            (ok :id id))))))

(defn db-format->permit-id
  "Viitelupien tunnukset on Factassa tallennettu 'tietokantaformaatissa', josta ne on tunnuksella
  hakemista varten muunnettava yleiseen formaattiin.
  Esimerkki: 12-0477-A 63 -> 63-0447-12-A"
  [id]
  (let [parts (zipmap '(:vuosi :no :tyyppi :kauposa) (ss/split id #"[- ]"))]
    (ss/join "-" ((juxt :kauposa :no :vuosi :tyyppi) parts))))
