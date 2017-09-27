(ns lupapalvelu.attachment-api-test
  (:require [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [monger.operators :refer :all]
            [lupapalvelu.action :refer [execute] :as action]
            [lupapalvelu.attachment-api :as api]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.itest-util :as itest]
            [lupapalvelu.tiedonohjaus-api :refer :all]
            [sade.env :as env]
            [sade.util :as util]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.attachment :as att]
            [lupapalvelu.archiving :as archiving]
            [lupapalvelu.archiving-util :as archiving-util]
            [lupapalvelu.assignment :as assignment]))

(facts "attachment-not-readOnly"

  (let [base-command {:application {:organization "753-R"
                                    :id           "ABC123"
                                    :state        "submitted"
                                    :permitType   "R"
                                    :attachments  [{:id "5234" :readOnly true}]}
                      :created     1000
                      :user        {:orgAuthz      {:753-R #{:authority}}
                                    :role          :authority}
                      :data        {:id           "ABC123"
                                    :attachmentId "5234"}}]

    (fact "pre-check on its own"
      (att/attachment-not-readOnly base-command) => itest/fail?)

    (fact "attachment data cannot be modified if the attachment is read-only"
      (let [command (util/deep-merge base-command {:action "set-attachment-meta"
                                                   :data   {:meta {:contents "foobar"}}})]
        (execute command) => {:ok   false
                              :text "error.unauthorized"
                              :desc "Attachment is read only."}))

    (fact "new attachment version cannot be uploaded if the attachment is read-only"
      (let [command (util/deep-merge base-command {:action "upload-attachment"
                                                   ;attachmentType op filename tempfile size
                                                   :data   {:attachmentType {:type-group "paapiirustus"
                                                                             :type-id    "asemapiirros"}
                                                            :group          {:groupType "parties"}
                                                            :size           500
                                                            :filename       "foobar.pdf"
                                                            :tempfile       ""}})]
        (execute command) => {:ok   false
                              :text "error.unauthorized"
                              :desc "Attachment is read only."}))

    (fact "forman application attachments are readonly after sent"
      (let [authority-tj-command (util/deep-merge base-command {:application {:attachments [{:id "5234" :readOnly false}]
                                                                              :primaryOperation {:name "tyonjohtajan-nimeaminen-v2"}
                                                                              :state "submitted"}})
            applicant-tj-command (assoc-in authority-tj-command [:user :role] :applicant)]

        (fact "initially not read only"
          (att/attachment-not-readOnly applicant-tj-command) => nil)

        (fact "read only in sent state"
          (att/attachment-not-readOnly (assoc-in applicant-tj-command [:application :state] "sent")) => itest/fail?)

        (fact "read only in verdict given state"
          (att/attachment-not-readOnly (assoc-in applicant-tj-command [:application :state] "foremanVerdictGiven")) => itest/fail?)

        (fact "not read only in verdict given state for authority"
          (att/attachment-not-readOnly (assoc-in authority-tj-command [:application :state] "foremanVerdictGiven")) => nil)))))

(facts "readonly pre-checks are in place"
  (->> (action/get-actions)
       (filter (fn [[k v]] (and (= (:type v) :command) (-> v :user-roles :applicant) (-> v :categories :attachments))))
       (map (fn [[k v]] [k (remove nil? (map (comp #(re-matches #".*readOnly.*" %) str class) (:pre-checks v)))]))
       (filter (comp empty? second))
       (map first)
       set) => #{:create-ram-attachment ; does not alter the original
                 :rotate-pdf ; original is left in db, this is fine
                 :set-attachment-not-needed ; does not alter content or meta data
                 :sign-attachments ; allow signing attachments always
                 })

(facts "only authority may edit attachments in terminal state"
  (let [base-command {:application {:organization "753-R"
                                    :id           "ABC123"
                                    :state        "closed"
                                    :permitType   "R"
                                    :attachments  [{:id "5234"}]}
                      :created     1000
                      :user        {:orgAuthz      {:753-R #{:authority}}
                                    :role          :applicant}
                      :data        {:id           "ABC123"
                                    :attachmentId "5234"}}
        meta-command (util/deep-merge base-command {:action "set-attachment-meta"
                                                    :data   {:meta {:contents "foobar"}}})
        type-command (util/deep-merge base-command {:action "set-attachment-type"
                                                    :data   {:attachmentType "paapiirustus.asemapiirros"}})
        archivist-user {:orgAuthz      {:753-R #{:authority :archivist}}
                        :role          :authority}]
    (execute meta-command) => {:ok   false
                               :text "error.command-illegal-state"
                               :state "closed"}
    (execute (assoc meta-command :user archivist-user)) => {:ok true}

    (provided
      (att/update-attachment-data! anything "5234" anything 1000) => {:ok true})

    (execute type-command) => {:ok   false
                               :text "error.command-illegal-state"
                               :state "closed"}
    (execute (assoc type-command :user archivist-user)) => {:ok true}

    (provided
      (att/update-attachment-data! anything "5234" anything 1000) => {:ok true})))

(facts "Allowed only for authority when application sent"
       (let [app {:organization "753-R"
                  :id           "ABC123"
                  :state        "sent"
                  :auth [{:role "owner"
                          :id "foo"}]}
             fail {:ok false :text "error.unauthorized"}]
         (fact "Applicant"
               (att/allowed-only-for-authority-when-application-sent
                   {:application app :user {:id "foo" :role "applicant"}})
               => fail)
         (fact "Outside authority 1"
               (att/allowed-only-for-authority-when-application-sent
                   {:application app :user {:id "foo" :role "authority" :orgAuthz {:888-R #{:authority}}}})
               => fail)
         (fact "Outside authority 2"
               (att/allowed-only-for-authority-when-application-sent
                   {:application app :user {:id "foo" :role "authority"}})
               => fail)
         (fact "Application authority"
               (att/allowed-only-for-authority-when-application-sent
                   {:application app :user {:id "bar" :role "authority" :orgAuthz {:753-R #{:authority}}}})
               => nil)))

(facts "mark-application-archived-if-done is called if organisation has archive enabled and attachments are being removed"
       (let [archivist-user {:orgAuthz      {:753-R #{:authority :archivist}}
                             :role          :authority}
             base-command {:application {:organization "753-R"
                                         :id           "ABC123"
                                         :state        "constructionStarted"
                                         :permitType   "R"
                                         :archived     {:application 1233 :completed nil}
                                         :attachments  [{:id "5234"} {:id "5744" :metadata {:tila :arkistoitu}} {:id "5988" :metadata {:tila :arkistoitu}}]}
                           :created     1000
                           :user        archivist-user
                           :data        {:id           "ABC123"
                                         :attachmentId "5234"}}
             delete-command (util/deep-merge base-command {:action "delete-attachment"
                                                           :data   {:id "ABC123"
                                                                    :attachmentId "5234"}})]

         (execute delete-command) => {:ok true}
         (provided
           (organization/some-organization-has-archive-enabled? #{"753-R"}) => true
           (archiving-util/mark-application-archived-if-done anything anything nil) => nil
           (action/update-application anything anything) => nil
           (assignment/remove-target-from-assignments anything anything) => nil)))
