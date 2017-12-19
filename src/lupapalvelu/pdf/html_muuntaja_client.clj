(ns lupapalvelu.pdf.html-muuntaja-client
  (:require [cheshire.core :as json]
            [lupapalvelu.mongo :as mongo]
            [sade.core :refer :all]
            [sade.env :as env]
            [sade.http :as http]
            [sade.strings :as ss]
            [taoensso.timbre :as timbre])
    (:import [java.io InputStream]))

(def html2pdf-path "/api/wkhtml2pdf")

(defn convert-html-to-pdf [target-name template-name html-content header-content footer-content]
  (timbre/infof "Sending '%s' template for %s to muuntaja for processing" template-name target-name)
  (try
    (let [request-opts {:as           :stream
                        :content-type :json
                        :encoding     "UTF-8"
                        :body         (json/encode {:html html-content
                                                    :footer footer-content
                                                    :header header-content})}
          resp         (http/post (str (env/value :muuntaja :url) html2pdf-path)
                                  request-opts)]

      (cond
        (and (= (:status resp) 200) (:body resp))
        (do
          (timbre/infof "Successfully converted '%s' template for %s" template-name  target-name)
          (ok :pdf-file-stream (:body resp)))
        :else

        (do
          (timbre/warnf "Unable to convert '%s' template for %s: %s" template-name target-name (:error resp))
          (fail (:error resp)))))

    (catch Exception ex
      (timbre/error ex)
      (fail :unknown))))

(defn upload-pdf-stream
  "Uploads pdf stream into given file-id using filename provided that
  the given response is ok."
  [file-id filename {stream :pdf-file-stream :as resp}]
  (if (ok? resp)
    (with-open [s stream]
      (ok :file-id file-id
          :mongo-file (mongo/upload file-id
                                    filename
                                    "application/pdf"
                                    s)))
    resp))
