(ns lupapalvelu.ui.attachment.file-upload)

(defn bindToElem [options]
  (.bindFileInput js/lupapisteApp.services.fileUploadService options (js-obj "replaceFileInput" false)))

(defn subscribe-files-uploaded [input-id callback]
  (.subscribe js/hub
              (js-obj "eventType" "fileuploadService::filesUploaded"
                      "input" input-id
                      "status" "success")
              callback))

(defn bind-attachments [files]
  (.bindAttachments js/lupapisteApp.services.attachmentsService files))
