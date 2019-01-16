(ns lupapalvelu.conversion.conversion-runner
  "A namespace that provides an endpoint for the KuntaGML conversion pipeline."
  (:require [taoensso.timbre :refer [info warn]]
            [clojure.java.io :as io]
            [sade.core :refer [now]]
            [sade.strings :as ss]
            [lupapalvelu.application :as app]
            [lupapalvelu.backing-system.krysp.application-from-krysp :as krysp-fetch]
            [lupapalvelu.conversion.kuntagml-converter :as conv]
            [lupapalvelu.conversion.util :as conv-util]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.user :as usr]))

(defn- vakuustieto? [kuntalupatunnus]
  (-> kuntalupatunnus conv-util/destructure-permit-id :tyyppi (= "VAK")))

(defn update-links!
  "This is a separate function so that it can be run as a separate process if needed.
  This step must be run only after all the applications have been imported."
  [kuntalupa-ids]
  (when-not @mongo/connection
    (mongo/connect!))
  (info (str "Updating links from kuntalupatunnus -> Lupapiste-id for " (count kuntalupa-ids) " applications."))
  (doseq [id kuntalupa-ids]
    (app/update-app-links! id)))

(defn convert!
  "Takes a list of kuntalupatunnus-ids."
  [kuntalupa-ids]
  (mongo/connect!)
  (let [vakuus-ids (filter vakuustieto? kuntalupa-ids)
        others (filter (complement vakuustieto?) kuntalupa-ids)]
    ;; Phase 1. Convert applications and save to db.
    (info (str "Converting " (count others) " applications from KuntaGML -> Lupapiste."))
    (doseq [id others]
      (try
        (conv/fetch-prev-application! {:created (now)
                                       :data {:kuntalupatunnus id}
                                       :user (usr/batchrun-user '("092-R"))}
                                      false) ;; The last flag determines if we fetch local applications or not. Must be `false` for production!
        (catch Exception e
          (warn (.getMessage e)))))
    ;; Phase 2. Add vakuustieto notification to the verdict of their main application.
    (info (str "Adding vakuustieto notifications to " (count vakuus-ids) " applications."))
    (doseq [id vakuus-ids]
      (try
        (->> {:id id :organization "092-R" :permitType "R"}
             krysp-fetch/get-application-xml-by-application-id
             conv-util/add-vakuustieto!)
        (catch Exception e
          (warn (.getMessage e)))))
    ;; Phase 3. Update app-links - they should be linked by LP id, not kuntalupatunnus
    (update-links! kuntalupa-ids)))