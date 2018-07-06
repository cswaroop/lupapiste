(ns lupapalvelu.storage.file-storage
  (:require [lupapalvelu.storage.s3 :as s3]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.action :as action]
            [sade.env :as env]
            [sade.strings :as ss]
            [monger.operators :refer :all]
            [sade.util :as util]
            [clojure.java.io :as io]
            [pandect.core :as pandect]
            [taoensso.timbre :as timbre]
            [lupapalvelu.domain :as domain]
            [clojure.string :as str]
            [lupapiste-commons.external-preview :as ext-preview])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.time ZonedDateTime ZoneId]
           [java.util Date]))

;; UPLOAD

(def process-bucket "sign-process")
(def application-bucket "application-files")
(def user-bucket "user-files")
(def unlinked-bucket s3/unlinked-bucket)
(def bulletin-bucket "bulletin-files")

(defn bucket-name [{:keys [application user-id uploader-user-id sessionId]}]
  (cond
    application application-bucket
    user-id user-bucket
    uploader-user-id unlinked-bucket
    sessionId unlinked-bucket))

(defn s3-id [metadata-or-id file-id & [preview?]]
  (if (map? metadata-or-id)
    (let [{:keys [application user-id uploader-user-id sessionId]} metadata-or-id
          context-id (or application user-id uploader-user-id sessionId)]
      (if (and (string? context-id) (> (count context-id) 5))
        (str context-id "/" file-id (when preview? "-preview"))
        (throw (ex-info "The metadata map for upload must contain either application, user-id, uploaded-user-id or sessionId"
                        metadata-or-id))))
    (str metadata-or-id "/" file-id (when preview? "-preview"))))

(defn upload [file-id filename content-type content metadata]
  {:pre [(map? metadata)]}
  (if (env/feature? :s3)
    (s3/put-file-or-input-stream (bucket-name metadata) (s3-id metadata file-id) filename content-type content metadata)
    (mongo/upload file-id filename content-type content metadata)))

(defn upload-process-file [process-id filename content-type ^ByteArrayInputStream is metadata]
  {:pre [(map? metadata)]}
  (if (env/feature? :s3)
    (s3/put-input-stream process-bucket process-id filename content-type is (.available is) metadata)
    (mongo/upload process-id filename content-type is metadata)))

;; DOWNLOAD

(defn- find-by-file-id [id {:keys [versions]}]
  (->> versions
       (filter #((set ((juxt :fileId :originalFileId) %)) id))
       first))

(defn- find-by-file-id-from-attachments [id attachments]
  (some
    #(find-by-file-id id %)
    attachments))

(defn ^{:perfmon-exclude true} download
  "Downloads file from Mongo GridFS or S3
   When the backing system is S3, make sure to always close the content input stream even if you do not use it."
  ([file-id]
   (if (env/feature? :s3)
     (s3/download nil file-id)
     (mongo/download-find {:_id file-id})))
  ([application file-id]
   {:pre [(map? application) (string? file-id)]}
   (let [{:keys [storageSystem]} (find-by-file-id-from-attachments file-id (:attachments application))]
     (if (and (env/feature? :s3) (= (keyword storageSystem) :s3))
       (s3/download application-bucket (s3-id (:id application) file-id))
       (mongo/download-find {:_id file-id}))))
  ([application-id file-id attachment]
   {:pre [(string? application-id) (string? file-id) (map? attachment)]}
   (let [{:keys [storageSystem]} (find-by-file-id file-id attachment)]
     (if (and (env/feature? :s3) (= (keyword storageSystem) :s3))
       (s3/download application-bucket (s3-id application-id file-id))
       (mongo/download-find {:_id file-id})))))

(defn download-many
  "Downloads multiple files from Mongo GridFS or S3"
  [application file-ids]
  (if (env/feature? :s3)
    (pmap #(download application %) file-ids)
    (mongo/download-find-many {:_id {$in file-ids}})))

(defn ^{:perfmon-exclude true} download-from-system
  [application-id file-id storage-system]
  (if (= (keyword storage-system) :s3)
    (s3/download application-bucket (s3-id application-id file-id))
    (mongo/download-find {:_id file-id})))

(defn- find-user-attachment-storage-system [user-id file-id]
  (->> (mongo/select-one :users
                         {:_id                        user-id
                          :attachments.attachment-id file-id}
                         [:attachments.$])
       :attachments
       first
       :storageSystem))

(defn ^{:perfmon-exclude true} download-user-attachment
  "Downloads user attachment file from Mongo GridFS or S3"
  ([user-id file-id]
   (->> (find-user-attachment-storage-system user-id file-id)
        (download-user-attachment user-id file-id)))
  ([user-id file-id storage-system]
   (if (and (env/feature? :s3) (= (keyword storage-system) :s3))
     (s3/download user-bucket (s3-id user-id file-id))
     (mongo/download-find {:_id file-id :metadata.user-id user-id}))))

(defn ^{:perfmon-exclude true} download-preview
  "Downloads preview file from Mongo GridFS or S3"
  [application-id file-id attachment]
  (let [{:keys [storageSystem]} (find-by-file-id file-id attachment)]
    (if (and (env/feature? :s3) (= (keyword storageSystem) :s3))
      (s3/download application-bucket (s3-id application-id file-id :preview))
      (mongo/download-find {:_id (str file-id "-preview")}))))

(defn ^{:perfmon-exclude true} download-unlinked-file
  [user-or-session-id file-id]
  (if (env/feature? :s3)
    (s3/download unlinked-bucket (s3-id user-or-session-id file-id))
    (mongo/download-find {$and [{:_id file-id}
                                {$or [{:metadata.linked false}
                                      {:metadata.linked {$exists false}}]}
                                {$or [{:metadata.sessionId user-or-session-id}
                                      {:metadata.uploader-user-id user-or-session-id}]}]})))

(defn ^{:perfmon-exclude true} download-process-file
  [process-id]
  (if (env/feature? :s3)
    (s3/download process-bucket process-id)
    (mongo/download-find {:_id process-id})))

(defn ^{:perfmon-exclude true} download-bulletin-comment-file
  [bulletin-id file-id storage-system]
  (let [dl (if (and (env/feature? :s3) (= (keyword storage-system) :s3))
             (s3/download bulletin-bucket (s3-id bulletin-id file-id))
             (mongo/download-find {:_id file-id :metadata.bulletinId bulletin-id}))]
    (-> (assoc dl :bulletin bulletin-id)
        (dissoc :application))))

;; LINK

(defn link-files-to-application [user-or-session-id app-id file-ids]
  {:pre [(seq file-ids) (not-any? ss/blank? (conj file-ids app-id user-or-session-id))]}
  (if (env/feature? :s3)
    (do (doseq [file-id file-ids]
          (s3/move-file-object unlinked-bucket
                               application-bucket
                               (s3-id user-or-session-id file-id)
                               (s3-id app-id file-id)))
        (count file-ids))
    (mongo/update-by-query :fs.files
                           {$and [{:_id {$in file-ids}}
                                  {$or [{:metadata.linked false}
                                        {:metadata.linked {$exists false}}]}
                                  {$or [{:metadata.sessionId user-or-session-id}
                                        {:metadata.uploader-user-id user-or-session-id}]}]}
                           {$set {:metadata.application app-id
                                  :metadata.linked true}})))

(defn link-files-to-bulletin [session-id bulletin-id file-ids]
  {:pre [(seq file-ids) (not-any? ss/blank? (conj file-ids bulletin-id))]}
  (if (env/feature? :s3)
    (do (doseq [file-id file-ids]
          (s3/move-file-object unlinked-bucket bulletin-bucket (s3-id session-id file-id) (s3-id bulletin-id file-id)))
        (count file-ids))
    (mongo/update-by-query :fs.files
                           {:_id {$in file-ids}
                            :metadata.sessionId session-id}
                           {$set {:metadata.bulletinId bulletin-id
                                  :metadata.linked true}})))

;; EXISTS

(defn unlinked-files-exist? [user-or-session-id file-ids]
  {:pre [(sequential? file-ids) (not-any? ss/blank? (conj file-ids user-or-session-id))]}
  (->> (if (env/feature? :s3)
         (map #(s3/object-exists? unlinked-bucket (s3-id user-or-session-id %)) file-ids)
         (map #(mongo/any? :fs.files
                           {$and [{:_id %}
                                  {$or [{:metadata.linked false}
                                        {:metadata.linked {$exists false}}]}
                                  {:metadata.bulletinId {$exists false}}
                                  {$or [{:metadata.sessionId user-or-session-id}
                                        {:metadata.uploader-user-id user-or-session-id}]}]})
              file-ids))
       (every? true?)))

(defn application-file-exists? [application-id file-id]
  (if (env/feature? :s3)
    (s3/object-exists? application-bucket (s3-id application-id file-id))
    (map? (mongo/file-metadata {:id file-id}))))

;; DELETE

(defn delete [application file-id]
  (let [{:keys [storageSystem]} (find-by-file-id-from-attachments file-id (:attachments application))]
    (if (and (env/feature? :s3) (= (keyword storageSystem) :s3))
      (do (s3/delete application-bucket (s3-id (:id application) file-id))
          (s3/delete application-bucket (s3-id (:id application) file-id :preview)))
      (do (mongo/delete-file-by-id file-id)
          (mongo/delete-file-by-id (str file-id "-preview"))))))

(defn delete-unlinked-file
  "Deletes a file uploaded to temporary storage with session id.
   Guarantees that the file is not linked to anything at the time of deletion."
  [user-or-session-id file-id]
  (if (env/feature? :s3)
    (s3/delete unlinked-bucket (s3-id user-or-session-id file-id))
    (mongo/delete-file {$and [{:_id file-id}
                              {$or [{:metadata.sessionId user-or-session-id}
                                    {:metadata.uploader-user-id user-or-session-id}]}
                              {:metadata.application {$exists false}}
                              {:metadata.bulletinId {$exists false}}
                              {$or [{:metadata.linked false}
                                    {:metadata.linked {$exists false}}]}]})))

(defn ^{:perfmon-exclude true} delete-user-attachment
  ([user-id file-id]
   (->> (find-user-attachment-storage-system user-id file-id)
        (delete-user-attachment user-id file-id)))
  ([user-id file-id storage-system]
   (if (and (env/feature? :s3) (= (keyword storage-system) :s3))
     (s3/delete user-bucket (s3-id user-id file-id))
     (mongo/delete-file {:id file-id :metadata.user-id user-id}))))

(defn ^{:perfmon-exclude true} delete-process-file
  [process-id]
  (if (env/feature? :s3)
    (s3/delete process-bucket process-id)
    (mongo/delete-file {:_id process-id})))

(defn delete-from-any-system [application-id file-id]
  (when-not @mongo/connection
    (mongo/connect!))
  (mongo/delete-file-by-id file-id)
  (when (env/feature? :s3)
    (s3/delete application-bucket (s3-id application-id file-id))))

(defn- ts-two-hours-ago []
  ; Matches vetuma session TTL
  (util/get-timestamp-ago :hour 2))

(defn- date-two-hours-ago []
  (-> (ZonedDateTime/now)
      (.minusHours 2)
      (.toInstant)
      (Date/from)))

(defn delete-old-unlinked-files []
  (if (env/feature? :s3)
    (s3/delete-unlinked-files (date-two-hours-ago))
    (do
      (when-not @mongo/connection
        (mongo/connect!))
      (mongo/delete-file {$and [{$or [{:metadata.linked false}
                                      {:metadata.linked {$exists false}}]}
                                {:metadata.application {$exists false}}
                                {:metadata.uploaded {$lt (ts-two-hours-ago)}}]}))))

;; MIGRATION

(defn move-application-mongodb-files-to-s3 [id]
  {:pre [(string? id)]}
  (assert (env/feature? :s3) "s3 feature must be enabled")
  (let [{:keys [attachments] :as application} (domain/get-application-no-access-checking id
                                                                                         [:attachments :organization])
        preview-placeholder-sha1 (pandect/sha1 (ext-preview/placeholder-image-is))]
    (doseq [{:keys [versions latestVersion] att-id :id} attachments
            [idx {:keys [fileId originalFileId storageSystem]}] (map-indexed vector versions)
            :when (and (= (keyword storageSystem) :mongodb)
                       (some? fileId))]
      (timbre/info "Migrating attachment" att-id "version" idx)
      (doseq [file-id (if (= fileId originalFileId)
                        [fileId (str fileId "-preview")]
                        [fileId originalFileId (str fileId "-preview")])]
        (let [{:keys [content contentType filename metadata]} (mongo/download file-id)
              bos (ByteArrayOutputStream.)]
          (if content
            (do (with-open [is (content)]
                  (io/copy is bos))
                (let [mongo-data (.toByteArray bos)
                      mongo-data-sha1 (pandect/sha1 mongo-data)]
                  ; Do not copy the preview image if it is only the placeholder
                  (when (not= mongo-data-sha1 preview-placeholder-sha1)
                    (timbre/info "Uploading file" file-id "to s3")
                    (s3/put-file-or-input-stream application-bucket
                                                 (s3-id id file-id)
                                                 filename
                                                 contentType
                                                 (ByteArrayInputStream. mongo-data)
                                                 metadata)
                    (with-open [s3-data ((:content (s3/download application-bucket (s3-id id file-id))))]
                      (when (not= mongo-data-sha1 (pandect/sha1 s3-data))
                        (throw (Exception. (str "Data in MongoDB and S3 do not match for " (s3-id id file-id)))))))))
            (when-not (str/ends-with? file-id "preview")
              (timbre/error "File" file-id "not found in GridFS but linked on" id "attachment" att-id)))))
      (timbre/info "Changing attachment" att-id "version" idx "storageSystem to s3")
      (action/update-application
        (action/application->command application)
        {:attachments.id att-id}
        {$set (cond-> {(str "attachments.$.versions." idx ".storageSystem") :s3}
                      (= fileId (:fileId latestVersion))
                      (assoc "attachments.$.latestVersion.storageSystem" :s3))})
      (mongo/delete-file-by-id fileId)
      (when-not (= fileId originalFileId)
        (timbre/info "Deleting attachment" att-id "version" idx "original file" originalFileId "from GridFS")
        (mongo/delete-file-by-id originalFileId))
      (mongo/delete-file-by-id (str fileId "-preview"))
      (when (not= (:fileId latestVersion) (:fileId (last versions)))
        (timbre/error "Latest version fileId does not match the fileId of last element in versions in attachment" att-id)))))
