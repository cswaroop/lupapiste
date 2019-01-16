(ns lupapalvelu.conversion.kuntagml-converter
  (:require [clojure.string :refer [includes?]]
            [lupapalvelu.action :as action]
            [lupapalvelu.application :as app]
            [lupapalvelu.application-meta-fields :as meta-fields]
            [lupapalvelu.backing-system.krysp.application-from-krysp :as krysp-fetch]
            [lupapalvelu.backing-system.krysp.building-reader :as building-reader]
            [lupapalvelu.backing-system.krysp.reader :as krysp-reader]
            [lupapalvelu.conversion.util :as conv-util]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.logging :as logging]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.organization :as org]
            [lupapalvelu.pate.verdict-date :as verdict-date]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.prev-permit :as prev-permit]
            [lupapalvelu.review :as review]
            [lupapalvelu.statement :as statement]
            [lupapalvelu.user :as usr]
            [lupapalvelu.verdict :as verdict]
            [sade.core :refer :all]
            [sade.util :as util]
            [taoensso.timbre :refer [info infof warn error errorf]]))

(defn convert-application-from-xml [command operation organization xml app-info location-info authorize-applicants]
  (let [{:keys [hakijat]} app-info
        municipality "092"
        buildings-and-structures (building-reader/->buildings-and-structures xml)
        document-datas (prev-permit/schema-datas app-info buildings-and-structures)
        command (update-in command [:data] merge
                           {:operation operation :infoRequest false :messages []}
                           location-info)

        operations (:toimenpiteet app-info)
        kuntalupatunnus (krysp-reader/xml->kuntalupatunnus xml)
        id (conv-util/make-converted-application-id kuntalupatunnus)
        description (or (building-reader/->asian-tiedot xml)
                        "") ;; So that regex checks on this don't throw errors, should the field be empty.
        primary-op-name (if (seq operations)
                          (conv-util/deduce-operation-type kuntalupatunnus description (first operations))
                          (conv-util/deduce-operation-type kuntalupatunnus description))

        schema-name (-> primary-op-name conv-util/op-name->schema-name name)

        manual-schema-datas {schema-name (app/sanitize-document-datas (schemas/get-schema 1 schema-name) (first document-datas))}

        secondary-op-names (map (partial conv-util/deduce-operation-type kuntalupatunnus description) (rest operations))

        make-app-info {:id              id
                       :organization    organization
                       :operation-name  primary-op-name
                       :location        (app/->location (:x location-info) (:y location-info))
                       :propertyId      (:propertyId location-info)
                       :address         (:address location-info)
                       :municipality    municipality}

        created-application (conv-util/remove-empty-party-documents
                              (app/make-application make-app-info
                                                    []            ; messages
                                                    (:user command)
                                                    (:created command)
                                                    manual-schema-datas))

        new-parties (remove empty?
                            (concat (map prev-permit/suunnittelija->party-document (:suunnittelijat app-info))
                                    (map prev-permit/osapuoli->party-document (:muutOsapuolet app-info))
                                    (map prev-permit/hakija->party-document hakijat)
                                    (when (includes? kuntalupatunnus "TJO")
                                      (map prev-permit/tyonjohtaja->tj-document (:tyonjohtajat app-info)))))

        location-document (->> xml
                               building-reader/->rakennuspaikkatieto
                               (conv-util/rakennuspaikkatieto->rakennuspaikka-kuntagml-doc kuntalupatunnus)
                               (conj []))

        structure-descriptions (map :description buildings-and-structures)

        other-building-docs (map (partial app/document-data->op-document created-application) (rest document-datas) secondary-op-names)

        secondary-ops (mapv #(assoc (-> %1 :schema-info :op) :description %2 :name %3) other-building-docs (rest structure-descriptions) secondary-op-names)

        structures (->> xml krysp-reader/->rakennelmatiedot (map conv-util/rakennelmatieto->kaupunkikuvatoimenpide))

        statements (->> xml krysp-reader/->lausuntotiedot (map prev-permit/lausuntotieto->statement))

        ;; Siirretaan lausunnot luonnos-tilasta "lausunto annettu"-tilaan
        given-statements (for [st statements
                               :when (map? st)
                               :let [puoltotieto (some-> st
                                                         (get-in [:metadata :puoltotieto])
                                                         (clojure.string/replace #"\s" "-"))]]
                           ;;^ Normalization, since the input data from KuntaGML contains values like 'ei huomautettavaa' (should be 'ei-huomautettavaa') etc.
                           (try
                             (statement/give-statement st
                                                       (:saateText st)
                                                       puoltotieto
                                                       (mongo/create-id)
                                                       (mongo/create-id)
                                                       false)
                             (catch Exception e
                               (errorf "Moving statement to statement given -state failed: %s" (.getMessage e)))))

        history-array (conv-util/generate-history-array xml)

        created-application (-> created-application
                                (assoc-in [:primaryOperation :description] (first structure-descriptions))
                                (conv-util/add-description-and-deviation-info xml document-datas) ;; Add poikkeamat and kuvaus to the "hankkeen-kuvaus" doc.
                                conv-util/remove-empty-rakennuspaikka ;; Remove empty rakennuspaikka-document that comes from the template
                                (conv-util/add-timestamps history-array) ;; Add timestamps for different state changes
                                (update-in [:documents] concat other-building-docs new-parties structures ;; Assemble the documents-array
                                           (when-not (includes? kuntalupatunnus "TJO") location-document))
                                (update-in [:secondaryOperations] concat secondary-ops)
                                (assoc :statements (or given-statements [])
                                       :opened (:created command)
                                       :history history-array
                                       :state :closed ;; Asetetaan hanke "päätös annettu"-tilaan
                                       :facta-imported true))

        ;; attaches the new application, and its id to path [:data :id], into the command
        command (util/deep-merge command (action/application->command created-application))]
    (logging/with-logging-context {:applicationId (:id created-application)}
      ;; The application has to be inserted first, because it is assumed to be in the database when checking for verdicts (and their attachments).
      (app/insert-application created-application)
      (infof "Inserted converted app: org=%s kuntalupatunnus=%s" (:organization created-application) (get-in command [:data :kuntalupatunnus]))
      ;; Get verdicts for the application
      (when-let [updates (verdict/find-verdicts-from-xml command xml false)]
        (action/update-application command updates)
        (verdict-date/update-verdict-date (:id created-application)))

      (let [updated-application (mongo/by-id :applications (:id created-application))
            {:keys [updates added-tasks-with-updated-buildings attachments-by-task-id]} (review/read-reviews-from-xml usr/batchrun-user-data (now) updated-application xml false true)
            review-command (assoc (action/application->command updated-application (:user command)) :action "prev-permit-review-updates")
            update-result (review/save-review-updates review-command updates added-tasks-with-updated-buildings attachments-by-task-id true)]
        (if (:ok update-result)
          (info "Saved review updates")
          (infof "Reviews were not saved: %s" (:desc update-result))))

      ;; The database may already include the same kuntalupatunnus as in the to be imported application
      ;; (e.g., the application has been imported earlier via previous permit (paperilupa) mechanism).
      ;; This kind of application 1) has the same kuntalupatunnus and 2) :facta-imported is falsey.
      ;; After import, the two applications are linked (viitelupien linkkaus).
      (let [app-links (map conv-util/normalize-permit-id (krysp-reader/->viitelupatunnukset xml))
            duplicate-ids (conv-util/get-duplicate-ids kuntalupatunnus)
            all-links (clojure.set/union (set app-links) (set duplicate-ids))]
        (infof (format "Linking %d app-links to application %s" (count all-links) (:id created-application)))
        (doseq [link all-links]
          (try
            (app/do-add-link-permit created-application link)
            (catch Exception e
              (error "Adding app-link %s -> %s failed: %s" (:id created-application) link (.getMessage e))))))

      (let [fetched-application (mongo/by-id :applications (:id created-application))]
        (mongo/update-by-id :applications (:id fetched-application) (meta-fields/applicant-index-update fetched-application))
        fetched-application))))

(def supported-import-types #{:TJO :A :B :C :D :E :P :Z :AJ :AL :MAI :BJ :PI :BL :DJ :CL :PJ})

(defn- validate-permit-type [permittype]
  (when-not (contains? supported-import-types (keyword permittype))
    (error-and-fail! (str "Unsupported import type " permittype) :error.unsupported-permit-type)))

(defn fetch-prev-application!
  "A variant of `lupapalvelu.prev-permit/fetch-prev-local-application!` that exists for conversion
  and testing purposes. To use a local KuntaGML file:
  1) The local MongoDB has to contain the location info for the municipality in question (here Vantaa)
  2) this function needs to be called with the `local?` argument set to `true`"
  ([command]
   (fetch-prev-application! command true))
  ([{{:keys [kuntalupatunnus authorizeApplicants]} :data :as command} local?] ;; If the `local` flag is false, the application is fetched from backed system.
  (let [organizationId        "092-R" ;; Vantaa, bypass the selection from form
        destructured-permit-id (conv-util/destructure-permit-id kuntalupatunnus)
        operation             "konversio"
        filename              (format "%s/%s.xml" (:resource-path conv-util/config) kuntalupatunnus ".xml")
        permit-type           "R"
        xml                   (if local?
                                (krysp-fetch/get-local-application-xml-by-filename filename permit-type)
                                (krysp-fetch/get-application-xml-by-application-id {:id kuntalupatunnus
                                                                                    :organization "092-R"
                                                                                    :permitType "R"}))
        app-info              (krysp-reader/get-app-info-from-message xml kuntalupatunnus)
        location-info         (or (prev-permit/get-location-info command app-info)
                                  prev-permit/default-location-info)
        organization          (org/get-organization organizationId)
        validation-result     (permit/validate-verdict-xml permit-type xml organization)
        no-proper-applicants? (not-any? prev-permit/get-applicant-type (:hakijat app-info))]
    (validate-permit-type (:tyyppi destructured-permit-id))
    (when validation-result
      (warn "Has invalid verdict: " (:text validation-result)))
    (cond
      (empty? app-info)                 (error-and-fail! "No app-info available" :error.no-previous-permit-found-from-backend)
      (not location-info)               (error-and-fail! "No location info" :error.more-prev-app-info-needed)
      (not (:propertyId location-info)) (error-and-fail! "No property-id" :error.previous-permit-no-propertyid)
      :else                             (let [{id :id} (convert-application-from-xml command
                                                                                     operation
                                                                                     organization
                                                                                     xml
                                                                                     app-info
                                                                                     location-info
                                                                                     authorizeApplicants)]
                                          (if no-proper-applicants?
                                            (ok :id id :text :error.no-proper-applicants-found-from-previous-permit)
                                            (ok :id id)))))))

(defn debug [command]
  (fetch-prev-application! command))
