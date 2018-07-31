(ns lupapalvelu.xml.krysp.application-as-krysp-to-backing-system-test
  (:require [lupapalvelu.xml.krysp.application-as-krysp-to-backing-system :refer :all]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.organization :as org]
            [sade.core :refer [now]]
            [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]))

(testable-privates lupapalvelu.xml.krysp.application-as-krysp-to-backing-system remove-unsupported-attachments remove-non-approved-designers filter-attachments-by-state)

(fact "Remove function removes unsupported attachments"
  (let [application {:attachments [{:type {:type-group :muut
                                           :type-id :paatos}}
                                   {:type {:type-group :muut
                                           :type-id :muu}}
                                   {:type {:type-group :paapiirustus
                                           :type-id :asemapiirros}}]
                     :id "LP-123456"}]
    (remove-unsupported-attachments application) => {:attachments [{:type {:type-group :muut
                                                                           :type-id :muu}}
                                                                   {:type {:type-group :paapiirustus
                                                                           :type-id :asemapiirros}}]
                                                     :id "LP-123456"}))

(fact "filter attachments by state"
  (let [application {:attachments [{:applicationState "draft"}
                                   {:applicationState "submitted"}
                                   {:applicationState "verdictGiven"}
                                   {:applicationState "constructionStarted"}]
                     :id "LP-123456"}]

    (fact "post verdict attachments are filtered in  pre verdict state"
      (filter-attachments-by-state "submitted" application) => {:attachments [{:applicationState "draft"}
                                                                              {:applicationState "submitted"}]
                                                                :id "LP-123456"})

    (fact "no filtering in post verdict state"
      (filter-attachments-by-state "verdictGiven" application) => {:attachments [{:applicationState "draft"}
                                                                                 {:applicationState "submitted"}
                                                                                 {:applicationState "verdictGiven"}
                                                                                 {:applicationState "constructionStarted"}]
                                                                   :id "LP-123456"})

    (fact "no filtering in unknown state"
      (filter-attachments-by-state nil application) => {:attachments [{:applicationState "draft"}
                                                                      {:applicationState "submitted"}
                                                                      {:applicationState "verdictGiven"}
                                                                      {:applicationState "constructionStarted"}]
                                                        :id "LP-123456"})))

(facts "Designer documents that have not been approved are removed"
  (let [documents (mapv #(model/new-document (schemas/get-schema 1 %) 123) ["paasuunnittelija" "suunnittelija" "hakija-r"])
        approved-docs (mapv #(assoc-in % [:meta :_approved] {:value "approved" :timestamp 124}) documents)
        application {:documents documents}]

    (fact "Approved have not been removed"
      (let [filtered (:documents (remove-non-approved-designers {:documents approved-docs}))]
        (count filtered) => 3
        (get-in (first filtered) [:schema-info :name]) => "paasuunnittelija"
        (get-in (second filtered) [:schema-info :name]) => "suunnittelija"
        (get-in (last filtered) [:schema-info :name]) => "hakija-r"))

    (fact "Non-approved have been removed"
      (let [filtered (:documents (remove-non-approved-designers {:documents documents}))]
        (count filtered) => 1
        (get-in (first filtered) [:schema-info :name]) => "hakija-r"))

    (fact "Modified, previously approved document is removed"
      (let [modified (assoc-in approved-docs [0 :data :henkilotiedot :etunimi :modified] 125)
            filtered (:documents (remove-non-approved-designers {:documents modified}))]
        (count filtered) => 2
        (get-in (first filtered) [:schema-info :name]) => "suunnittelija"
        (get-in (last filtered) [:schema-info :name]) => "hakija-r"))))

(defn write-xml [dir filename kayttotapaus]
  (let [file (io/file (str dir "/" filename))]
    (spit file
          (str "<?xml version='1.0' encoding='UTF-8' ?>"
               "<hii:TopLevel><rakval:RakennusvalvontaAsia>"
               "<bar:kayttotapaus>" kayttotapaus "</bar:kayttotapaus>"
               "</rakval:RakennusvalvontaAsia></hii:TopLevel>"))
    (fact {:midje/description (str file)} (.exists file) => true)
    file))

(defonce application-id (str "Foobar-" (now)))
(defonce foo-org  {:krysp {:R {:ftpUser "test_foo"}}})

(facts "Deprecated messages cleanup"
       (let [app {:id application-id :organization "FOO-R" :permitType "R"}
             dir (resolve-output-directory foo-org (:permitType app))
             safe-dir (str dir "/safe")]
         (fact "Output directory exists"
               (fs/mkdirs dir)
               (.isDirectory (io/file dir)) => true)
         (fact "Make safe subdir"
               (fs/mkdir safe-dir)
               (.isDirectory (io/file safe-dir)) => true)
         (let [rm1   (write-xml dir (str (:id app) "_first.xml") "Rakentamisen aikainen muutos")
               rm2   (write-xml dir (str (:id app) "_second.XML") "Uusi hakemus")
               stay1 (write-xml dir (str (:id app) "_third.xml") "Uusi katselmus")
               bad1  (write-xml dir (str (:id app) "fourth.xml") "Uusi hakemus")
               bad2  (write-xml dir (str (:id app) "_fifthxml") "Uusi hakemus")
               bad3  (write-xml dir (str (:id app) "_sixth.xmlx") "Uusi hakemus")
               stay2 (write-xml dir (str (:id app) "_seventh.xml") "Uusi hakemus")
               stay3 (write-xml dir (str (:id app) "_eighth.xml") "Uusi hakemus")
               bad4  (write-xml dir (str "bad" (:id app) "_ninth.xml") "Uusi hakemus")
               safe  (write-xml safe-dir (str (:id app) "_safe.xml") "Uusi hakemus")]
           (spit stay2 "Garbage instead of good XML. Zhen daomei!")
           (spit stay3 (str "<?xml version='1.0' encoding='UTF-8' ?>"
              "<hii:TopLevel><rakval:RakennusvalvontaAsia>"
              "<bar:baz>Uusi hakemus</bar:baz>"
              "</rakval:RakennusvalvontaAsia></hii:TopLevel>"))
           (fact "KRYSP file count before cleanup" (count (krysp-xml-files app)) => 5) ;; rm + stay

           (fact "Cleanup does not throw exceptions"
                 (cleanup-output-dir app) => nil
                 (provided (org/get-organization "FOO-R") => foo-org))
           (fact "KRYSP file count after cleanup" (count (krysp-xml-files app)) => 3) ;; stay

           (fact "Correct files have been deleted"
                 (.exists rm1) => false
                 (.exists rm2) => false)

           (fact "All the others still exist"
                 (.exists stay1) => true
                 (.exists bad1) => true
                 (.exists bad2) => true
                 (.exists bad3) => true
                 (.exists stay2) => true
                 (.exists stay3) => true
                 (.exists bad4) => true
                 (.exists safe) => true)
           (fact "Unknown organization fails silently"
                 (krysp-xml-files (assoc app :organization "Unknown")) => nil
                 (cleanup-output-dir (assoc app :organization "Unknown")) => nil)
           (fact "Unknown permit-type fails silently"
                 (krysp-xml-files (assoc app :permitType "XYZ")) => nil
                 (cleanup-output-dir (assoc app :permitType "XYZ")) => nil)
           (fact "No messages for the application"
                 (krysp-xml-files (assoc app :id "Meiyou")) => empty?
                 (cleanup-output-dir (assoc app :id "Meiyou")) => nil)))
       (against-background
        (org/get-organization "FOO-R") => foo-org
        (org/get-organization "Unknown") => nil))
