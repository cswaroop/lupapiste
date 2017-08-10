(ns lupapalvelu.attachment-itest
  (:require [lupapalvelu.factlet :refer [facts*]]
            [lupapalvelu.attachment :refer :all]
            [lupapalvelu.pdf.pdfa-conversion :as pdfa]
            [lupapalvelu.pdf.libreoffice-conversion-client :as libre]
            [lupapalvelu.itest-util :refer :all]
            [sade.util :as util]
            [midje.sweet :refer :all]
            [sade.env :as env]))

(apply-remote-minimal)

(facts "attachments"
       (let [{application-id :id :as response} (create-app pena :propertyId tampere-property-id :operation "kerrostalo-rivitalo")]

         response => ok?

         (comment-application pena application-id true) => ok?

         (fact "Signing not possible"
               (query pena :signing-possible :id application-id) => fail?)
         (facts "by default 4 attachments exist"
                (let [application (query-application pena application-id)
                      op-id (-> application :primaryOperation :id)]
                  (fact "counting all attachments"
                        (count (:attachments application)) => 4)
                  (fact "asemapiirros, pohjapiirustus and vastonsuojasuunnitelma are related to operation 'kerrostalo-rivitalo'"
                        (map :type (get-attachments-by-operation application op-id)) => (just #{{:type-group "paapiirustus" :type-id "asemapiirros"} {:type-group "paapiirustus" :type-id "pohjapiirustus"} {:type-group "pelastusviranomaiselle_esitettavat_suunnitelmat" :type-id "vaestonsuojasuunnitelma"}} :in-any-order))
                  (fact "the attachments have 'required', 'notNeeded' and 'requestedByAuthority' flags correctly set"
                        (every? (fn [a]
                                  (every? #{"required" "notNeeded" "requestedByAuthority"} a) => truthy
                                  (:required a) => true
                                  (:notNeeded a) => false
                                  (:requestedByAuthority a) => false)
                                (:attachments application)) => truthy
                        )))

         (let [resp (command veikko
                             :create-attachments
                             :id application-id
                             :attachmentTypes [{:type-group "paapiirustus" :type-id "asemapiirros"}
                                               {:type-group "paapiirustus" :type-id "pohjapiirustus"}]
                             :group nil)
               attachment-ids (:attachmentIds resp)]

           (fact "Veikko can create an attachment"
                 (success resp) => true)

           (fact "Two attachments were created in one call"
                 (fact (count attachment-ids) => 2))

           (fact "attachment has been saved to application"
                 (get-attachment-by-id veikko application-id (first attachment-ids)) => (contains
                                                                                         {:type                 {:type-group "paapiirustus" :type-id "asemapiirros"}
                                                                                          ;;:state                "requires_user_action"
                                                                                          :requestedByAuthority true
                                                                                          :versions             []})
                 (get-attachment-by-id veikko application-id (second attachment-ids)) => (contains
                                                                                          {:type                 {:type-group "paapiirustus" :type-id "pohjapiirustus"}
                                                                                           ;;:state                "requires_user_action"
                                                                                           :requestedByAuthority true
                                                                                           :versions             []}))

           (fact "uploading files"
                 (let [application (query-application pena application-id)
                       _ (upload-attachment-to-all-placeholders pena application)
                       application (query-application pena application-id)]

                   (facts "Each attachment has Pena's auth"
                          (let [p-id pena-id]
                            (doseq [{auth :auth} (:attachments application)]
                              (fact "Pena as uploader"
                                    (some #(when (#{p-id} (:id %)) (:role %)) auth) => "uploader"))))
                   (fact "Signing id possible"
                         (query pena :signing-possible :id application-id) => ok?)

                   (fact "download all"
                         (let [resp (raw pena "download-all-attachments" :id application-id)]
                           resp => http200?
                           (get-in resp [:headers "content-disposition"]) => "attachment;filename=\"liitteet.zip\"")
                         (fact "p\u00e5 svenska"
                               (get-in (raw pena "download-all-attachments" :id application-id :lang "sv") [:headers "content-disposition"])
                               => "attachment;filename=\"bilagor.zip\""))

                   (fact "pdf export"
                         (raw pena "pdf-export" :id application-id) => http200?)

                   (doseq [attachment-id (get-attachment-ids application)
                           :let [file-id (attachment-latest-file-id application attachment-id)]]

                     (fact "view-attachment anonymously should not be possible"
                           (raw nil "view-attachment" :attachment-id file-id) => http401?)

                     (fact "view-attachment as pena should be possible"
                           (raw pena "view-attachment" :attachment-id file-id) => http200?)

                     (fact "download-attachment anonymously should not be possible"
                           (raw nil "download-attachment" :attachment-id file-id) => http401?)

                     (fact "download-attachment as pena should be possible"
                           (raw pena "download-attachment" :attachment-id file-id) => http200?))
                   (fact "operation info"
                     (upload-file-and-bind pena application-id {:type {:type-group "muut"
                                                                       :type-id    "muu"}
                                                                :group {:groupType "operation"
                                                                        :operations [{:id "foo"}]}}
                                           :fails :error.illegal-attachment-operation)
                     (upload-file-and-bind pena application-id {:type {:type-group "muut"
                                                                       :type-id    "muu"}
                                                                :group {:groupType "operation"
                                                                        :operations [{:id (-> application :primaryOperation :id)}]}})
                     (->> (query-application pena application-id) :attachments last :op (map :name)) => ["kerrostalo-rivitalo"])))

           (fact "Pena submits the application"
                 (command pena :submit-application :id application-id) => ok?
                 (:state (query-application veikko application-id)) => "submitted")

           (fact "Pena signs attachments"
                 (fact "meta" attachment-ids => seq)

                 (fact "Signing fails if password is incorrect"
                       (command pena :sign-attachments :id application-id :attachmentIds attachment-ids :password "not-pena") => (partial expected-failure? "error.password"))

                 (fact "Signing succeeds if password is correct"
                       (command pena :sign-attachments :id application-id :attachmentIds attachment-ids :password "pena") => ok?)

                 (fact "Signature is set"
                       (let [application (query-application pena application-id)
                             attachments (get-attachments-infos application attachment-ids)]
                         (doseq [{signatures :signatures latest :latestVersion} attachments]
                           (count signatures) => 1
                           (let [{:keys [user created version]} (first signatures)]
                             (:username user) => "pena"
                             (:id user) => pena-id
                             (:firstName user) => "Pena"
                             (:lastName user) => "Panaani"
                             created => pos?
                             version => (:version latest))))))


           (fact "Pena change attachment metadata"
                 (let [{:keys [primaryOperation]} (query-application pena application-id)
                       op-id (:id primaryOperation)]

                   (fact "Invalid operation fails"
                     (command pena :set-attachment-meta
                              :id application-id
                              :attachmentId (first attachment-ids)
                              :meta {:group {:groupType :operation :operations [{:id "fail"}]}}) => (partial expected-failure? :error.illegal-attachment-operation))
                   (fact "Pena can change operation"
                         (command pena :set-attachment-meta :id application-id :attachmentId (first attachment-ids) :meta {:group {:groupType :operation :operations [{:id op-id}]}}) => ok?)
                   (fact "Pena can change contents"
                         (command pena :set-attachment-meta :id application-id :attachmentId (first attachment-ids) :meta {:contents "foobart"}) => ok?)
                   (fact "Pena can change size"
                         (command pena :set-attachment-meta :id application-id :attachmentId (first attachment-ids) :meta {:size "A4"}) => ok?)
                   (fact "Pena can change scale"
                         (command pena :set-attachment-meta :id application-id :attachmentId (first attachment-ids) :meta {:scale "1:500"}) => ok?)

                   (fact "Metadata is set"
                         (let [application (query-application pena application-id)
                               {:keys [op groupType contents size scale]} (get-attachment-info application (first attachment-ids))]
                           (map :id op) => [op-id]
                           groupType => "operation"
                           contents => "foobart"
                           size => "A4"
                           scale => "1:500"))

                   (fact "Pena resets attachment group to nil"
                         (command pena :set-attachment-meta :id application-id :attachmentId (first attachment-ids) :meta {:group nil}) => ok?
                         (let [application (query-application pena application-id)
                               {:keys [groupType]} (get-attachment-info application (first attachment-ids))]
                           groupType => nil))

                   (fact "Operation id must exist in application"
                         (command pena :set-attachment-meta
                                  :id application-id
                                  :attachmentId (first attachment-ids)
                                  :meta {:group {:groupType :operation :operations [{:id "aaabbbcccdddeeefff000111"}]}}) => (partial expected-failure? :error.illegal-attachment-operation))

                   (fact "Operation metadata can be set to null"
                         (fact "but id can't be nil"
                               (command pena :set-attachment-meta
                                        :id application-id
                                        :attachmentId (first attachment-ids)
                                        :meta {:group {:groupType :operation :operations [{:id nil}]}}) => (partial expected-failure? :error.illegal-attachment-operation))
                         (command pena :set-attachment-meta
                                  :id application-id
                                  :attachmentId (first attachment-ids)
                                  :meta {:group {:groupType nil}}) => ok?)))

           (let [versioned-attachment (first (:attachments (query-application veikko application-id)))]
             (last-email) ; Inbox zero

             (fact "Meta"
                   (get-in versioned-attachment [:latestVersion :version :major]) => 1
                   (get-in versioned-attachment [:latestVersion :version :minor]) => 0)

             (fact "Veikko uploads a new version"
                   (upload-attachment veikko application-id versioned-attachment true)
                   (let [updated-attachment (get-attachment-by-id veikko application-id (:id versioned-attachment))]
                     (get-in updated-attachment [:latestVersion :version :major]) => 1
                     (get-in updated-attachment [:latestVersion :version :minor]) => 1

                     (fact "upload has Veikko's auth"
                           (get-in updated-attachment [:auth 1 :id]) => veikko-id)

                     (fact "Pena receives email pointing to comment page"
                           (let [emails (sent-emails)
                                 email  (first emails)
                                 pena-email  (email-for "pena")]
                             (count emails) => 1
                             email => (partial contains-application-link-with-tab? application-id "conversation" "applicant")
                             (:to email) => (contains pena-email)))

                     (fact "Delete version"
                           (command veikko
                                    :delete-attachment-version
                                    :id application-id
                                    :attachmentId (:id versioned-attachment)
                                    :fileId (get-in updated-attachment [:latestVersion :fileId])
                                    :originalFileId (get-in updated-attachment [:latestVersion :originalFileId])) => ok?
                           (let [ver-del-attachment (get-attachment-by-id veikko application-id (:id versioned-attachment))]
                             (get-in ver-del-attachment [:latestVersion :version :major]) => 1
                             (get-in ver-del-attachment [:latestVersion :version :minor]) => 0))

                     (fact "Applicant cannot delete attachment that is required"
                           (command pena :delete-attachment :id application-id :attachmentId (:id versioned-attachment)) => (contains {:ok false :text "error.unauthorized"}))
                     )))

           (let [versioned-attachment (first (:attachments (query-application pena application-id)))]
             (fact "Pena upload new version"
                   (upload-attachment pena application-id versioned-attachment true))
             (fact "Pena signs the attachment version"
                   (command pena :sign-attachments :id application-id :attachmentIds [(:id versioned-attachment)] :password "pena") => ok?)
             (let [signed-attachment (get-attachment-by-id pena application-id (:id versioned-attachment))]

               (fact "Pena has only one auth entry, although has many versions uploaded"
                     (count (filter #(= (-> % :user :id) (id-for-key pena)) (:versions signed-attachment))) => 2
                     (count (filter #(= (:id %) (id-for-key pena)) (:auth signed-attachment))) => 1)

               (fact "Attachment is signed"
                     (count (:signatures signed-attachment)) => 1)

               (fact "Delete version and its signature"
                     (command veikko
                              :delete-attachment-version
                              :id application-id
                              :attachmentId (:id versioned-attachment)
                              :fileId (get-in signed-attachment [:latestVersion :fileId])
                              :originalFileId (get-in signed-attachment [:latestVersion :originalFileId]))=> ok?
                     (fact (count (:signatures (get-attachment-by-id veikko application-id (:id versioned-attachment)))) => 0))

               (fact "Deleting the last version clears attachment auth"
                     (let [attachment (get-attachment-by-id veikko application-id (:id versioned-attachment))]
                       (count (:versions attachment )) => 1
                       (command veikko
                                :delete-attachment-version
                                :id application-id
                                :attachmentId (:id attachment)
                                :fileId (get-in attachment [:latestVersion :fileId])
                                :originalFileId (get-in attachment [:latestVersion :originalFileId])) => ok?)
                     (:auth (get-attachment-by-id veikko application-id (:id versioned-attachment))) => empty?)

               (fact "Authority deletes attachment"
                     (command veikko :delete-attachment :id application-id :attachmentId (:id versioned-attachment)) => ok?
                     (get-attachment-by-id veikko application-id (:id versioned-attachment)) => nil?))
             ))
         (facts "Authority-added attachment"
                (let [attachment-id (upload-attachment veikko application-id {:type {:type-id "tutkintotodistus"
                                                                                     :type-group "osapuolet"}} true)]
                  (fact "Applicant cannot delete"
                        (command pena :delete-attachment :id application-id :attachmentId attachment-id)
                        => (partial expected-failure? :error.unauthorized))
                  (fact "Authority can delete"
                        (command veikko :delete-attachment :id application-id :attachmentId attachment-id)
                        => ok?)))))

(facts* "Signing signs corrrect attachments"
  (let [{application-id :id :as response} (create-app pena :propertyId tampere-property-id :operation "kerrostalo-rivitalo")
        _ (comment-application pena application-id true) => ok? ; visible to Veikko
        application (query-application pena application-id)
        old-attachment (-> application :attachments last)
        old-id         (:id old-attachment)
        _ (upload-attachment-to-all-placeholders pena application)
        _ (count (:attachments application)) => 4
        resp       (command veikko
                            :create-attachments
                            :id application-id
                            :attachmentTypes [{:type-group "muut" :type-id "muu"}
                                              {:type-group "paapiirustus" :type-id "pohjapiirustus"}]
                            :group nil) => ok?
        attachment-ids (:attachmentIds resp)
        hidden-id (first attachment-ids)
        visible-id (second attachment-ids)
        _ (upload-attachment veikko application-id {:id hidden-id :type {:type-group "muut" :type-id "muu"}} true)
        _ (upload-attachment pena   application-id {:id visible-id :type {:type-group "paapiirustus" :type-id "pohjapiirustus"}} true)
        _ (command veikko :set-attachment-visibility
                   :id application-id
                   :attachmentId hidden-id
                   :value "viranomainen") => ok?
        ]

    (fact "error if user doesn't have access to requested attachment ids"
      (command pena :sign-attachments :id application-id :attachmentIds [hidden-id] :password "pena") => (partial expected-failure? "error.unknown-attachment"))
    (fact "Signing works if some of the attachments are hidden for user"
      (command pena :sign-attachments :id application-id :attachmentIds [old-id visible-id] :password "pena") => ok?
      (let [{attachments :attachments} (query-application veikko application-id)
            hidden      (util/find-first #(= (:id %) hidden-id) attachments)
            visible     (util/find-first #(= (:id %) visible-id) attachments)
            old     (util/find-first #(= (:id %) old-id) attachments)]
        (fact "Hidden doesn't have signatures"
          (count (:signatures hidden)) => 0)
        (fact "Only visible and old have signatures"
          (map :id (filter #(seq (:signatures %)) attachments)) => (just [visible-id old-id] :in-any-order)
          (count (:signatures visible)) => 1
          (count (:signatures old)) => 1)))))

(facts* "Post-verdict attachments"
  (let [{application-id :id :as response} (create-app pena :propertyId sipoo-property-id :operation "kerrostalo-rivitalo")
        application (query-application pena application-id)
        _ (upload-attachment-to-all-placeholders pena application)
        _ (command pena :submit-application :id application-id)
        _ (command sonja :check-for-verdict :id application-id) => ok?
        application (query-application pena application-id)
        attachment1 (-> application :attachments first)]
    (:state application) => "verdictGiven"
    (count (:attachments application)) => 6
    (fact "Uploading versions to pre-verdict attachment is not possible"
      (upload-attachment pena application-id attachment1 false :filename "dev-resources/test-pdf.pdf"))
    (fact "Uploading new post-verdict attachment is possible"
      (upload-attachment pena application-id {:id "" :type {:type-group "selvitykset" :type-id "energiatodistus"}} true :filename "dev-resources/test-pdf.pdf"))

    (count (:attachments (query-application pena application-id))) => 7))

(facts "Attachments query"
  (let [{application-id :id :as create-resp} (create-and-submit-application pena :propertyId sipoo-property-id)
        {verdict-id :verdictId :as verdict-resp} (command sonja :new-verdict-draft :id application-id)]
    (facts "initialization"
      (fact "verdict" verdict-resp => ok?)
      (fact "verdict attachment" (upload-attachment-to-target sonja application-id nil true verdict-id "verdict") => truthy))

    (facts "Authority"
      (let [{attachments :attachments :as query-resp} (query sonja :attachments :id application-id)]
        query-resp => ok?
        (count attachments) => 5
        (fact "verdict draft included"
          (count (filter (comp #{"verdict"} :type :target) attachments)) => 1)))

    (facts "Applicant"
      (let [{attachments :attachments :as query-resp} (query pena :attachments :id application-id)]
        query-resp => ok?
        (count attachments) => 4
        (fact "verdict draft is filtered out"
          (count (filter (comp #{"verdict"} :type :target) attachments)) => 0)))

    (fact "Unauthorized"
      (let [query-resp (query mikko :attachments :id application-id)]
       query-resp => (partial expected-failure? "error.application-not-accessible")))))

(facts "Single attachment query"
  (let [{application-id :id :as create-resp} (create-and-submit-application pena :propertyId sipoo-property-id)
        {verdict-id :verdictId :as verdict-resp} (command sonja :new-verdict-draft :id application-id)]
    (facts "initialization"
      (fact "verdict" verdict-resp => ok?)
      (fact "verdict attachment" (upload-attachment-to-target sonja application-id nil true verdict-id "verdict") => truthy)
      (let [{attachments :attachments :as attachments-resp} (query sonja :attachments :id application-id)
            attachment-id (->> attachments (filter (comp #{"verdict"} :type :target)) first :id)]
        (fact "all attachments" attachments-resp => ok?)
        (fact "attachment id" attachment-id => string?)

        (facts "Authority"
          (let [{attachment :attachment :as query-resp} (query sonja :attachment :id application-id :attachmentId attachment-id)]
            query-resp => ok?
            attachment => map?))

        (facts "Applicant does not see the verdict attachment"
          (let [{attachment :attachment :as query-resp} (query pena :attachment :id application-id :attachmentId attachment-id)]
            query-resp => (partial expected-failure? "error.attachment-not-found")))

        (fact "Unauthorized"
          (let [query-resp (query mikko :attachment :id application-id :attachmentId attachment-id)]
            query-resp => (partial expected-failure? "error.application-not-accessible")))))))

(facts "Attachment-groups query"
  (let [{application-id :id :as create-resp} (create-and-submit-application pena :propertyId sipoo-property-id)
        {groups :groups :as query-resp} (query sonja :attachment-groups :id application-id)]

    query-resp => ok?

    (fact "Five groups"
      (count groups) => 5)

    (fact "Five different kind of groups"
      (map :groupType groups) => (just ["building-site" "parties" "operation" "reports" "technical-reports"] :in-any-order))

    (fact "Operation groupType has operation specific info fields"
      (keys (util/find-first (comp #{"operation"} :groupType) groups)) => (contains [:groupType :id :name :description] :in-any-order :gaps-ok))

    (command sonja :add-operation :id application-id :operation "puun-kaataminen") => ok?

    (fact "Six groups"
      (-> (query sonja :attachment-groups :id application-id)
          :groups
          count) => 6)))

(fact "pdf works with YA-lupa"
  (let [{application-id :id :as response} (create-app pena :propertyId sipoo-property-id :operation "ya-katulupa-vesi-ja-viemarityot")
        application (query-application pena application-id)]
    response => ok?
    (:organization application) => "753-YA"
    pena => (allowed? :pdf-export :id application-id)
    (raw pena "pdf-export" :id application-id) => http200?))

(facts "Rotate PDF"
  (let [application (create-and-submit-application sonja :propertyId sipoo-property-id)
        application-id (:id application)
        attachment1 (first (:attachments application))
        attachment2 (last (:attachments application))]

    attachment1 =not=> attachment2

    (upload-attachment sonja application-id attachment1 true :filename "dev-resources/test-pdf.pdf")
    (upload-attachment sonja application-id attachment2 true :filename "dev-resources/test-gif-attachment.gif")

    (fact "Can rotate PDF"
      (command sonja :rotate-pdf :id application-id :attachmentId (:id attachment1) :rotation 90) => ok?)

    (fact "Can not rotate PDF 0 degrees"
      (command sonja :rotate-pdf :id application-id :attachmentId (:id attachment1) :rotation 0) => fail?)

    (fact "Can not rotate gif"
      (command sonja :rotate-pdf :id application-id :attachmentId (:id attachment2) :rotation 90) => fail?)))

(facts "Rotate PDF - versions and files"
  (let [application (create-and-submit-application pena :propertyId sipoo-property-id)
        application-id (:id application)
        attachment (first (:attachments application))
        attachment-id (:id attachment)]

    (upload-attachment pena application-id attachment true :filename "dev-resources/test-pdf.pdf")

    (let [application (query-application sonja application-id)
          attachment (get-attachment-by-id sonja application-id  attachment-id)
          v1-versions (:versions (get-attachment-by-id pena application-id attachment-id))
          v1 (first v1-versions)]
      (fact "one version" (count v1-versions) => 1)
      (fact "one comment" (count (:comments application)) => 1)
      (fact "fileID is set" (:fileId v1) => truthy)
      (fact "fileID is set" (:originalFileId v1) => truthy)
      (fact "File is original before rotation" (:fileId v1) => (:originalFileId v1))
      (fact "Uploader is Pena" (-> v1 :user :id) => pena-id)
      (fact "Version is not modified" (:modified v1) => nil)
      (fact "Attachment modified timestamp is the same as created"
        (:modified attachment) => (:created v1))

      (fact "version number is set" (:version v1) => {:major 1 :minor 0})

      (command sonja :rotate-pdf :id application-id :attachmentId attachment-id :rotation 90) => ok?

      (let [application (query-application sonja application-id)
            attachment (get-attachment-by-id sonja application-id  attachment-id)
            v2-versions (:versions attachment)
            v2          (first v2-versions)]
        (fact "No new version" (count v2-versions) => 1)
        (fact "No new comment" (count (:comments application)) => 1)
        (fact "Created timestamp is intact" (:created v1) => (:created v2))
        (fact "Attachment modified timestamp is intact" (:modified attachment) => (:created v2))
        (fact "Version has now modified timestamp" (- (:modified v2) (:created v2)) => pos?)
        (fact "File is changed" (:fileId v2) =not=> (:fileId v1))
        (fact "Original file is the same" (:originalFileId v2) => (:originalFileId v1))
        (fact "Uploader is still Pena" (-> v2 :user :id) => pena-id)

        (fact "version number is not changed" (:version v2) => (:version v1))

        (command sonja :rotate-pdf :id application-id :attachmentId attachment-id :rotation 90) => ok?

        (let [v3 (->> (get-attachment-by-id sonja application-id  attachment-id)
                      :versions
                      first)]
          (fact "File is changed again" (:fileId v3) =not=> (:fileId v2))
          (fact "Original file is still the same" (:originalFileId v3) => (:originalFileId v1)))))))

(when pdfa/pdf2pdf-enabled?
  (facts "Explictly convert to PDF/A"
    (fact "Temporarily disable permanent archive for Jarvenpaa"
      (command admin :set-organization-boolean-attribute :attribute "permanent-archive-enabled"
               :enabled false :organizationId "186-R"))
    (let [application (create-and-submit-application pena :propertyId jarvenpaa-property-id)
          application-id (:id application)
          attachment (first (:attachments application))
          attachment-id (:id attachment)]

    (upload-attachment pena application-id attachment true :filename "dev-resources/invalid-pdfa.pdf")

    (let [application (query-application pena application-id)
          attachment (get-attachment-by-id pena application-id  attachment-id)
          v1-versions (:versions attachment)
          v1 (first v1-versions)]
      (fact "one version" (count v1-versions) => 1)
      (fact "one comment" (count (:comments application)) => 1)
      (fact "fileID is set" (:fileId v1) => truthy)
      (fact "fileID is set" (:originalFileId v1) => truthy)
      (fact "File is original before conversion" (:fileId v1) => (:originalFileId v1))
      (fact "Uploader is Pena" (-> v1 :user :id) => pena-id)
      (fact "Version is not modified" (:modified v1) => nil)
      (fact "Attachment modified timestamp is the same as created"
        (:modified attachment) => (:created v1))

      (fact "version number is set" (:version v1) => {:major 1 :minor 0})

      (fact "Enable permanent archive for Jarvenpaa"
        (command admin :set-organization-boolean-attribute :attribute "permanent-archive-enabled"
                 :enabled true :organizationId "186-R"))
      (command raktark-jarvenpaa :convert-to-pdfa :id application-id :attachmentId attachment-id) => ok?

      (let [application (query-application pena application-id)
            attachment (get-attachment-by-id pena application-id  attachment-id)
            v2-versions (:versions attachment)
            v2          (first v2-versions)]
        (fact "No new version" (count v2-versions) => 1)
        (fact "No new comment" (count (:comments application)) => 1)
        (fact "Created timestamp is intact" (:created v1) => (:created v2))
        (fact "Attachment modified timestamp is intact" (:modified attachment) => (:created v2))
        (fact "Version has now modified timestamp" (- (:modified v2) (:created v2)) => pos?)
        (fact "File is changed" (:fileId v2) =not=> (:fileId v1))
        (fact "Original file is the same" (:originalFileId v2) => (:originalFileId v1))
        (fact "Uploader is still Pena" (-> v2 :user :id) => pena-id)

        (fact "version number is not changed" (:version v2) => (:version v1))))))

  (facts* "Rotate PDF - PDF/A converted files"                 ; Jarvenpaa has archive enabled in minimal
          (let [application (create-and-submit-application pena :operation "pientalo" :propertyId jarvenpaa-property-id)
                application-id (:id application)
                type {:type-group "paapiirustus" :type-id "asemapiirros"}
                resp (command raktark-jarvenpaa
                              :create-attachments
                              :id application-id
                              :attachmentTypes [type]
                              :group nil) => ok?
                attachment {:id (first (:attachmentIds resp))
                            :type type}
                attachment-id (:id attachment)]

            (upload-attachment pena application-id attachment true :filename "dev-resources/invalid-pdfa.pdf")

            (let [v1-versions (:versions (get-attachment-by-id pena application-id attachment-id))
                  v1 (first v1-versions)]
              (fact "one version" (count v1-versions) => 1)
              (fact "fileID is set" (:fileId v1) => truthy)
              (fact "fileID is set" (:originalFileId v1) => truthy)
              (fact "PDF/A has ref to original before rotate" (:fileId v1) =not=> (:originalFileId v1))
              (fact "Uploader is Pena" (-> v1 :user :id) => pena-id)
              (fact "version number is set" (:version v1) => {:major 1 :minor 0})
              (fact "Auto conversion flag is set" (:autoConversion v1) => true)

              (command raktark-jarvenpaa :rotate-pdf :id application-id :attachmentId attachment-id :rotation 90) => ok?

              (let [v2-versions (:versions (get-attachment-by-id raktark-jarvenpaa application-id  attachment-id))
                    v2          (first v2-versions)]
                (fact "No new version" (count v2-versions) => 1)
                (fact "File is changed" (:fileId v2) =not=> (:fileId v1))
                (fact "Original file is the same" (:originalFileId v2) => (:originalFileId v1))
                (fact "Uploader is still Pena" (-> v2 :user :id) => pena-id)
                (fact "version number is not changed" (:version v2) => (:version v1))
                (fact "Auto conversion flag is preserved" (:autoConversion v2) => true))))))

(if (libre/enabled?)
  (facts "Convert attachment to PDF/A with Libre"
   (let [application (create-and-submit-application sonja :propertyId sipoo-property-id)
         application-id (:id application)
         attachment (first (:attachments application))]

     (upload-attachment sonja application-id attachment true :filename "dev-resources/test-attachment.txt")

     (let [attachment (first (:attachments (query-application sonja application-id)))]
       (fact "One version, but original file id points to original file"
         (count (:versions attachment)) => 1
         (get-in attachment [:latestVersion :fileId]) =not=> (get-in attachment [:latestVersion :originalFileId]))

       (fact "Conversion flags"
         (fact "Auto conversion to PDF/A should be done to txt -file"
           (get-in attachment [:latestVersion :autoConversion]) => true
           (get-in attachment [:latestVersion :archivable]) => true
           (get-in attachment [:latestVersion :archivabilityError]) => nil
           (get-in attachment [:latestVersion :contentType]) => "application/pdf"
           (get-in attachment [:latestVersion :filename]) => "test-attachment.pdf"))


       (fact "Latest version should be 0.1 after conversion"
         (get-in attachment [:latestVersion :version :major]) => 0
         (get-in attachment [:latestVersion :version :minor]) => 1)

       (fact "Preview image is created"
         (raw sonja "preview-attachment" :attachment-id (get-in attachment [:latestVersion :fileId])) => http200?))

     (fact "Invalid mime not converted with Libre"
       (upload-attachment sonja application-id (second (:attachments application)) true :filename "dev-resources/test-gif-attachment.gif")

       (let [attachment (second (:attachments (query-application sonja application-id)))]
         (fact "One version, but original file id points to original file"
           (count (:versions attachment)) => 1
           (get-in attachment [:latestVersion :fileId]) => (get-in attachment [:latestVersion :originalFileId]))

         (fact "Conversion flags for invalid-mime-type"
           (get-in attachment [:latestVersion :autoConversion]) => falsey
           (get-in attachment [:latestVersion :archivable]) => falsey
           (get-in attachment [:latestVersion :archivabilityError]) => "invalid-mime-type"
           (get-in attachment [:latestVersion :filename]) => "test-gif-attachment.gif")))))
  (println "Skipped attachment-itest libreoffice tests!"))

(when pdfa/pdf2pdf-enabled?
  (facts "PDF -> PDF/A with pdf2pdf"                          ; Jarvenpaa has permanent-archive enabled, so PDFs are converted to PDF/A
    (let [application (create-and-submit-application pena :operation "pientalo" :propertyId jarvenpaa-property-id)
          application-id (:id application)
          type {:type-group "paapiirustus" :type-id "asemapiirros"}
          resp (command raktark-jarvenpaa
                        :create-attachments
                        :id application-id
                        :attachmentTypes [type]
                        :group nil) => ok?
          attachment {:id (first (:attachmentIds resp))
                      :type type}
          attachment-id (:id attachment)]

      (upload-attachment pena application-id attachment true :filename "dev-resources/invalid-pdfa.pdf")

      (let [attachment (first (:attachments (query-application raktark-jarvenpaa application-id)))]
        (fact "One version, but original file id points to original file"
          (count (:versions attachment)) => 1
          (get-in attachment [:latestVersion :fileId]) =not=> (get-in attachment [:latestVersion :originalFileId]))

        (fact "Conversion flags"
          (fact "Auto conversion to PDF/A should be done to txt -file"
            (get-in attachment [:latestVersion :autoConversion]) => true
            (get-in attachment [:latestVersion :archivable]) => true
            (get-in attachment [:latestVersion :archivabilityError]) => nil
            (get-in attachment [:latestVersion :contentType]) => "application/pdf"
            (get-in attachment [:latestVersion :filename]) => "invalid-pdfa.pdf"))


        (fact "Latest version should be 1.0 after conversion"
          (get-in attachment [:latestVersion :version :major]) => 1
          (get-in attachment [:latestVersion :version :minor]) => 0)))))

(facts "Stamping"
  (let [application (create-and-submit-application sonja :propertyId sipoo-property-id)
        application-id (:id application)
        attachment (first (:attachments application))
        _ (upload-attachment sonja application-id attachment true :filename "dev-resources/test-pdf.pdf")
        application (query-application sonja application-id)
        comments (:comments application)
        stamp {:id         "123456789012345678901234"
               :name       "Oletusleima"
               :position   {:x 10 :y 200}
               :background 0
               :page       :first
               :qrCode     true
               :rows       [[{:type :custom-text :value "Hyv\u00e4ksytty"} {:type :current-date :value (sade.util/to-local-date (sade.core/now))}]
                            [{:type :backend-id :value "17-0753-R"}]
                            [{:type :organization :value "Sipoon rakennusvalvonta"}]]}
        {job :job :as resp} (command
                              sonja
                              :stamp-attachments
                              :id application-id
                              :timestamp ""
                              :files [(:id attachment)]
                              :lang "fi"
                              :stamp stamp)
        file-id (get-in (:value job) [(-> job :value keys first) :fileId])]

    (fact "stamp is validated against schema"
      (command
        sonja
        :stamp-attachments
        :id application-id
        :timestamp ""
        :files [(:id attachment)]
        :lang "fi"
        :stamp (assoc stamp :page "foo")) => (partial expected-failure? :error.illegal-value:schema-validation))

    (fact "not stamped by default"
      (get-in (get-attachment-info application (:id attachment)) [:latestVersion :stamped]) => falsey)

    (fact "Attachment state is not ok"
      (attachment-state (get-attachment-info application (:id attachment))) =not=> :ok)

    resp => ok?
    (fact "Job id is returned" (:id job) => truthy)
    (fact "FileId is returned" file-id => truthy)

    ; Poll for 5 seconds
    (when-not (= "done" (:status job)) (poll-job sonja :stamp-attachments-job (:id job) (:version job) 25))

    (let [attachment (get-attachment-by-id sonja application-id (:id attachment))
          comments-after (:comments (query-application sonja application-id))]

      (fact "Attachment has stamp and no new comments"
        (get-in attachment [:latestVersion :stamped]) => true
        comments-after => comments)

       (fact "Attachment has Sonja's stamper auth"
         (get-in attachment [:auth 1 :id]) => sonja-id
         (get-in attachment [:auth 1 :role]) => "stamper")

      (fact "Attachment state is ok"
        (attachment-state attachment) => :ok)

      (fact "New fileid is in response" (get-in attachment [:latestVersion :fileId]) =not=> file-id)

      (facts "re-stamp"
        (let [{job :job :as resp} (command
                              sonja
                              :stamp-attachments
                              :id application-id
                              :timestamp ""
                              :files [(:id attachment)]
                              :lang "fi"
                              :stamp stamp)]
          resp => ok?
          ; Poll for 5 seconds
          (when-not (= "done" (:status job)) (poll-job sonja :stamp-attachments-job (:id job) (:version job) 25))

          (fact "Latest version has chaned"
            (let [attachment-after-restamp (get-attachment-by-id sonja application-id (:id attachment))]
             (:latestVersion attachment) =not=> (:latestVersion attachment-after-restamp)
             (get-in attachment [:latestVersion :stamped]) => true)))))))

(facts* "Attachments visibility"
  (let [{application-id :id :as response} (create-app pena :propertyId tampere-property-id :operation "kerrostalo-rivitalo")
        {attachments :attachments :as application} (query-application pena application-id)
        _ (command pena :set-attachment-visibility
                   :id application-id
                   :attachmentId (get-in attachments [0 :id])
                   :value "viranomainen") => (partial expected-failure? :error.attachment.no-versions)
        _ (upload-attachment-to-all-placeholders pena application)
        {attachments :attachments :as application} (query-application pena application-id)
        user-has-auth? (fn [username {auth :auth}]
                         (= username (:username (first auth))))
        att1 (first attachments)
        att2 (second attachments)
        aid1 (get-in attachments [0 :id])
        aid2 (get-in attachments [1 :id])]

    (fact "each attachment has Pena's auth"
      (every? (partial user-has-auth? "pena") attachments) => true)

    (fact "Can't set unknown visibility value"
      (command pena :set-attachment-visibility :id application-id :attachmentId aid1 :value "testi") => (partial expected-failure? :error.invalid-nakyvyys-value))
    (fact "Set attachment as public"
      (command pena :set-attachment-visibility :id application-id :attachmentId aid1 :value "julkinen") => ok?)

       ; authorize mikko
    (command pena :invite-with-role
             :id application-id
             :email "mikko@example.com"
             :text  ""
             :documentName ""
             :documentId ""
             :path ""
             :role "writer") => ok?
    (command mikko :approve-invite :id application-id) => ok?
    (fact "Mikko sees all uploaded attachments, one of them has visibility 'julkinen'"
      (let [{attachments :attachments} (query-application mikko application-id)]
        (count attachments) => 4
        (count (filter #(contains? (:metadata %) :nakyvyys) attachments)) => 1
        (util/find-by-id aid1 attachments) => (contains {:metadata {:nakyvyys "julkinen"}})))

    (facts "Mikko uploads personal CV"
      (upload-attachment mikko application-id {:id "" :type {:type-group "osapuolet" :type-id "cv"}} true) => truthy
      (let [{attachments :attachments} (query-application mikko application-id)
            mikko-att (last attachments)]
        (fact "Mikko has auth"
          (user-has-auth? "mikko@example.com" mikko-att) => true)
        (fact "Mikko hides attachment from other parties (visibility: 'viranomainen')"
          (command mikko :set-attachment-visibility :id application-id :attachmentId (:id mikko-att) :value "viranomainen") => ok?)
        (fact "Mikko can download, but Pena can't"
          (raw mikko "download-attachment" :attachment-id (:fileId (:latestVersion mikko-att))) => http200?
          (raw pena "download-attachment" :attachment-id (:fileId (:latestVersion mikko-att))) => http404?
          (raw nil "download-attachment" :attachment-id (:fileId (:latestVersion mikko-att))) => http401?)
        (fact "Mikko can access attachment, Pena's app query doesn't contain attachment"
          (util/find-by-id (:id mikko-att) (:attachments (query-application pena application-id))) => nil
          (util/find-by-id (:id mikko-att) (:attachments (query-application mikko application-id))) => map?)))

    (facts "Pena sets attachment visiblity to parties ('asiakas-ja-viranomainen')"
     (command pena :set-attachment-visibility :id application-id :attachmentId aid2 :value "asiakas-ja-viranomainen") => ok?
     (fact "Parties can download"
       (raw mikko "download-attachment" :attachment-id (:fileId (:latestVersion att2))) => http200?
       (raw pena "download-attachment" :attachment-id (:fileId (:latestVersion att2))) => http200?)
     (fact "Parties have attachment available in query"
       (util/find-by-id aid2 (:attachments (query-application mikko application-id))) => (contains {:metadata {:nakyvyys "asiakas-ja-viranomainen"}})
       (util/find-by-id aid2 (:attachments (query-application pena application-id))) => (contains {:metadata {:nakyvyys "asiakas-ja-viranomainen"}})))

    (command pena :submit-application :id application-id) => ok?

    (fact "Veikko uploads only-authority attachment"
      (upload-attachment veikko application-id {:id "" :type {:type-group "ennakkoluvat_ja_lausunnot"
                                                              :type-id "elyn_tai_kunnan_poikkeamapaatos"}} true) => truthy)
    (let [{attachments :attachments} (query-application veikko application-id)
          veikko-att (last attachments)]
      (count attachments) => 6
      (fact "Veikko sets his attachment authority-only"
        (command veikko :set-attachment-visibility :id application-id :attachmentId (:id veikko-att) :value "viranomainen") => ok?)
      (fact "Pena sees 4 attachments"                     ; does not see Mikkos CV or Veikkos attachment
        (let [attachments (:attachments (query-application pena application-id))]
          (count attachments) => 4
          (map :id attachments) =not=> (contains (:id veikko-att))))
      (fact "Mikko sees 5 attachments"                     ; does not see Veikkos attachment
        (let [attachments (:attachments (query-application mikko application-id))]
          (count attachments) => 5
          (map :id attachments) =not=> (contains (:id veikko-att)))))

    (fact "Veikko uploads attachment for parties"
      (upload-attachment veikko application-id {:id "" :type {:type-group "ennakkoluvat_ja_lausunnot"
                                                                  :type-id "naapurin_suostumus"}} true) => truthy)

    (let [{attachments :attachments} (query-application veikko application-id)
          veikko-att-id (:id (last attachments))
          _ (command veikko :set-attachment-visibility :id application-id :attachmentId veikko-att-id :value "asiakas-ja-viranomainen") => ok?
          _ (upload-attachment pena application-id {:id veikko-att-id :type {:type-group "ennakkoluvat_ja_lausunnot"
                                                                             :type-id "naapurin_suostumus"}} true) => truthy
          mikko-app (query-application mikko application-id)
          latest-attachment (last (:attachments mikko-app))]
      (fact "Mikko sees Veikko's/Pena's attachment"
        (:id latest-attachment) => veikko-att-id
        (-> latest-attachment :latestVersion :user :username) => "pena")
      (fact "Mikko can't change visibility (not authed to attachment)"
        (command mikko :set-attachment-visibility
                 :id application-id
                 :attachmentId veikko-att-id
                 :value "julkinen") => (partial expected-failure? :error.attachment.no-auth))
      (fact "Veikko sets visibility to only authority"
        (command veikko :set-attachment-visibility :id application-id :attachmentId veikko-att-id :value "viranomainen") => ok?)
      (fact "Mikko can't see attachment anymore"
        (map :id (get (query-application mikko application-id) :attachments)) =not=> (contains veikko-att-id))
      (fact "Pena can see attachment, because he is authed"
        (map :id (get (query-application pena application-id) :attachments)) => (contains veikko-att-id))
      (fact "Pena can change visibility, as he is authed"
        (command pena :set-attachment-visibility :id application-id :attachmentId veikko-att-id :value "julkinen") => ok?)
      )))

(facts "Uploading PDF should not create duplicate comments"
  (let [application    (create-and-submit-application pena :operation "pientalo" :propertyId jarvenpaa-property-id)
        application-id (:id application)
        _ (upload-attachment pena application-id {:type {:type-group "osapuolet" :type-id "cv"}} true :filename "dev-resources/invalid-pdfa.pdf")
        {attachments :attachments comments :comments} (query-application pena application-id)
        pdf-attachment (first attachments)]
    (fact "is PDF"
      (-> pdf-attachment :latestVersion :contentType) => "application/pdf")

    (when pdfa/pdf2pdf-enabled? ; test PDF/A when pdf2pdf converter is enabled
      (facts "Is archivable after PDF/A conversion"
        (count (:versions pdf-attachment)) => 1             ; only one version
        (get-in pdf-attachment [:latestVersion :fileId]) =not=> (get-in pdf-attachment [:latestVersion :originalFileId]) ; original file is known
        (-> pdf-attachment :latestVersion :archivable) => true))

    (fact "After upload comments count is + 1"
      (count (:comments application)) => 0 ; before upload
      (count comments) => 1)))


(facts "RAM attachments"
  (let [application (create-and-submit-application pena :propertyId sipoo-property-id)
        application-id (:id application)
        {attachments :attachments} (query-application pena application-id)
        base-attachment (first attachments)]

    (defn latest-attachment []
      (-> (query-application pena application-id) :attachments last))

    (defn first-attachment []
      (-> (query-application pena application-id) :attachments first))

    (fact "Upload file to attachment"
          (upload-attachment pena application-id base-attachment true) => truthy)

    (fact "Ronja is set as general handler"
      (command sonja :upsert-application-handler :id application-id :userId ronja-id :roleId sipoo-general-handler-id) => ok?)

    (fact "Cannot create ram attachment before verdict is given"
      (command pena :create-ram-attachment :id application-id :attachmentId (:id base-attachment))
      => (partial expected-failure? :error.command-illegal-state))

    (fact "Give verdict"
      (command sonja :check-for-verdict :id application-id)
      => ok?)

    (fact "Attachment id should match attachment in the application"
      (command pena :create-ram-attachment :id application-id :attachmentId "invalid_id")
      => (partial expected-failure? :error.attachment.id))

    (fact "RAM link cannot be created on unapproved attachment"
          (command pena :create-ram-attachment :id application-id :attachmentId (:id base-attachment))
          => (partial expected-failure? :error.attachment-not-approved))

    (fact "RAM link is created after approval"
          (command sonja :approve-attachment :id application-id
                   :fileId (-> (first-attachment) :latestVersion :fileId)) => ok?
          (command pena :create-ram-attachment :id application-id :attachmentId (:id base-attachment))
          => ok?)

    (fact "Email notification about new RAM is sent"
      (let [email (last-email)]
        (:to email) => (contains (email-for-key ronja))
        (:subject email) => "Lupapiste: foo 42, bar, Sipoo - Ilmoitus uudesta RAM:sta"))

    (fact "RAM link cannot be created twice on same attachment"
          (command pena :create-ram-attachment :id application-id :attachmentId (:id base-attachment))
          => (partial expected-failure? :error.ram-linked))

    (facts "Pena uploads new post-verdict attachment and corresponding RAM attachment"
           (upload-attachment pena application-id {:id "" :type {:type-group "paapiirustus"
                                                                 :type-id "pohjapiirustus"}} true) => truthy
          (let [base (latest-attachment)]
            (fact "RAM creation fails do to unapproved base attachment"
                  (command pena :create-ram-attachment :id application-id :attachmentId (:id base))
                  => (partial expected-failure? :error.attachment-not-approved) )

            (fact "Approve and create RAM"
                  (command sonja :approve-attachment :id application-id
                           :fileId (-> (latest-attachment) :latestVersion :fileId)) => ok?
                  (command pena :create-ram-attachment :id application-id :attachmentId (:id base)) => ok?)
            (upload-attachment pena application-id {:id (:id (latest-attachment)) :type {:type-group "paapiirustus"
                                                                                         :type-id "pohjapiirustus"}} true) => truthy
            (fact "Applicant cannot delete base attachment"
                  (command pena :delete-attachment :id application-id :attachmentId (:id base))
                  => (partial expected-failure? :error.ram-linked))
            (fact "Applicant cannot delete base attachment version"
                  (command pena :delete-attachment-version :id application-id :attachmentId (:id base)
                           :fileId (-> base :latestVersion :fileId) :originalFileId (-> base :latestVersion :originalFileId))
                  => (partial expected-failure? :error.ram-linked))))
    (let [ram-id (:id (latest-attachment))]
      (facts "Latest attachment has RAM link"
             (let [{[a b] :ram-links} (query pena :ram-linked-attachments :id application-id :attachmentId ram-id)]
              (fact "Base attachment has no link" (:ram-link a) => nil)
              (fact "RAM attachment links to base attachment" (:ramLink b) => (:id a))))
      (fact "Authority can query RAM links"
            (query sonja :ram-linked-attachments :id application-id :attachmentId ram-id) => ok?)
      (fact "Reader authority can query RAM links"
            (query luukas :ram-linked-attachments :id application-id :attachmentId ram-id) => ok?)
      (fact "Sonja approves RAM attachment"
            (command sonja :approve-attachment :id application-id :fileId (-> (latest-attachment) :latestVersion :fileId)) => ok?)
      (let [{{:keys [fileId originalFileId]} :latestVersion :as ram} (latest-attachment)]
        (fact "RAM is approved" (attachment-state ram) => :ok)
        (fact "Sonja cannot delete approved RAM"
              (command sonja :delete-attachment :id application-id :attachmentId ram-id) => (partial expected-failure? :error.ram-approved))
        (fact "Sonja cannot delete approved RAM version"
              (command sonja :delete-attachment-version :id application-id :attachmentId ram-id
                       :fileId fileId :originalFileId originalFileId) => (partial expected-failure? :error.ram-approved))
        (fact "Pena cannot delete approved RAM"
              (command pena :delete-attachment :id application-id :attachmentId ram-id) => (partial expected-failure? :error.ram-approved))
        (fact "Pena cannot delete approved RAM version"
              (command pena :delete-attachment-version :id application-id :attachmentId ram-id
                       :fileId fileId :originalFileId originalFileId) => (partial expected-failure? :error.ram-approved))
        (fact "Sonja rejects RAM attachment"
              (command sonja :reject-attachment :id application-id :fileId (-> (latest-attachment) :latestVersion :fileId)) => ok?)
        (fact "Pena cannot delete rejected RAM version"
              (command pena :delete-attachment-version :id application-id :attachmentId ram-id
                       :fileId fileId :originalFileId originalFileId) => (partial expected-failure? :error.unauthorized))
        (fact "Pena can delete rejected RAM"
              (command pena :delete-attachment :id application-id :attachmentId ram-id) => ok?)
        (let [base (latest-attachment)]
          (fact "Pena again creates RAM attachment"
                (command pena :create-ram-attachment :id application-id :attachmentId (:id base)) => ok?)
          (fact "Sonja cannot delete base attachment"
                (command sonja :delete-attachment :id application-id :attachmentId (:id base)) => (partial expected-failure? :error.ram-linked))
          (fact "Fill and approve RAM and create one more link"
                (upload-attachment pena application-id (latest-attachment) true) => truthy
                (let [middle (latest-attachment)]
                  (command sonja :approve-attachment :id application-id
                           :fileId (-> middle :latestVersion :fileId)) => ok?
                  (command pena :create-ram-attachment :id application-id :attachmentId (:id middle)) => ok?
                  (fact "Sonja can reject the middle RAM"
                        (command sonja :reject-attachment :id application-id
                                 :fileId (-> middle :latestVersion :fileId)) => ok?)
                  (fact "... but cannot delete it"
                        (command sonja :delete-attachment :id application-id :attachmentId (:id middle))
                        => (partial expected-failure? :error.ram-linked))
                  (fact "... or its version"
                        (command sonja :delete-attachment-version :id application-id
                                 :attachmentId (:id middle)
                                 :fileId (-> middle :latestVersion :fileId)
                                 :originalFileId (-> middle :latestVersion :originalFileId))
                        => (partial expected-failure? :error.ram-linked)))))))
    (facts "Pena uploads new post-verdict attachment that does not support RAMs"
           (upload-attachment pena application-id {:id "" :type {:type-group "osapuolet"
                                                                 :type-id "cv"}} true) => truthy
          (let [base (latest-attachment)]
            (fact "Approve"
                  (command sonja :approve-attachment :id application-id
                           :fileId (-> (latest-attachment) :latestVersion :fileId)) => ok?)
            (fact "RAM creation fails"
                  (command pena :create-ram-attachment :id application-id :attachmentId (:id base))
                  => (partial expected-failure? :error.ram-not-allowed))))))

(facts "Marking attachment manually as as construction time attachment"
  (let [application (create-and-submit-application pena :propertyId sipoo-property-id)
        application-id (:id application)
        attachment (-> (query pena :attachments :id application-id) :attachments first)
        attachment-id (:id attachment)]

    (facts "attachment-manually-set-construction-time - not set"
      (:manuallySetConstructionTime attachment) => false)
    (fact "applicationState before update"
      (:applicationState attachment) => "draft")
    (fact "originalApplicationState before update"
      (:originalApplicationState attachment) => nil)

    (facts "set-attachment-as-construction-time"
      (fact "applicant"
        (command pena :set-attachment-as-construction-time :id application-id :attachmentId (:id attachment) :value true) => (partial expected-failure? :error.unauthorized))
      (fact "auhtority"
        (command sonja :set-attachment-as-construction-time :id application-id :attachmentId (:id attachment) :value true) => ok?))

    (facts "attachment is updated"
      (let [{{app-state :applicationState orig-app-state :originalApplicationState msct :manuallySetConstructionTime} :attachment} (query pena :attachment :id application-id :attachmentId attachment-id)]
        (fact "manually set construction time"
          msct => true)
        (fact "applicationState"
          app-state => "verdictGiven")
        (fact "originalApplicationState"
          orig-app-state => "draft")))

    (fact "reset attachment as construction time"
      (command sonja :set-attachment-as-construction-time :id application-id :attachmentId (:id attachment) :value true) => ok?)

    (facts "reset does not change data"
      (let [{{app-state :applicationState orig-app-state :originalApplicationState msct :manuallySetConstructionTime} :attachment} (query pena :attachment :id application-id :attachmentId attachment-id)]
        (fact "manually set construction time"
          msct => true)
        (fact "applicationState"
          app-state => "verdictGiven")
        (fact "originalApplicationState"
          orig-app-state => "draft")))

    (fact "unset attachment as construction time"
      (command sonja :set-attachment-as-construction-time :id application-id :attachmentId (:id attachment) :value false) => ok?)

    (facts "attachment is updated"
      (let [{{app-state :applicationState orig-app-state :originalApplicationState msct :manuallySetConstructionTime} :attachment} (query pena :attachment :id application-id :attachmentId attachment-id)]
        (fact "manually set construction time"
          msct => false)
        (fact "applicationState"
          app-state => "draft")
        (fact "originalApplicationState"
          orig-app-state => nil)))

    (fact "unset non construction time attachment"
      (command sonja :set-attachment-as-construction-time :id application-id :attachmentId (:id attachment) :value false) => (partial expected-failure? :error.attachment-not-manually-set-construction-time))

    (facts "set construction time attachment which is added in post verdict"
      (fact "Give verdict"
        (command sonja :check-for-verdict :id application-id) => ok?)
      (facts "Applicant adds ventilation plan - does not get manually set as construction time"
        (let [resp (command pena :create-attachments :id application-id :attachmentTypes [{:type-group "erityissuunnitelmat" :type-id "iv_suunnitelma"}] :group nil)
              attachment-id (-> resp :attachmentIds first)
              attachment (:attachment (query pena :attachment :id application-id :attachmentId attachment-id))]
             (fact "applicationState"
                   (:applicationState attachment) => nil)
             (fact "originalApplicationState"
                   (:originalApplicationState attachment) => nil)))
      (facts "add attachment"
        (let [resp (command sonja :create-attachments :id application-id :attachmentTypes [{:type-group "muut" :type-id "muu"}] :group nil)
              post-verdict-attachment-id (-> resp :attachmentIds first)
              attachment (:attachment (query pena :attachment :id application-id :attachmentId post-verdict-attachment-id))]
          (fact "added"
            resp => ok?)
          (fact "attachemnt id"
            post-verdict-attachment-id => string?)
          (fact "applicationState"
            (:applicationState attachment) => "verdictGiven")
          (fact "originalApplicationState"
            (:originalApplicationState attachment) => nil)

          (fact "attachment manually set construction time"
            (:manuallySetConstructionTime attachment) = false)

          (fact "set-attachment-as-construction-time"
            (command sonja :set-attachment-as-construction-time :id application-id :attachmentId (:id attachment) :value true) => (partial expected-failure? :error.command-illegal-state)))))))

(facts "Not needed..."
  (let [application (create-application pena :propertyId sipoo-property-id)
        application-id (:id application)
        {attachments :attachments} (query-application pena application-id)
        att1 (first attachments)]
    (fact "Initially not needed can be toggled"
      (command pena :set-attachment-not-needed :id application-id :notNeeded true
               :attachmentId (:id att1)) => ok?
      (command pena :set-attachment-not-needed :id application-id :notNeeded false
               :attachmentId (:id att1)) => ok?)
    (fact "If set notNeeded, upload not possible"
      (command pena :set-attachment-not-needed :id application-id :notNeeded true
               :attachmentId (:id att1)) => ok?
      (upload-attachment pena application-id att1 false))
    (fact "Upload possible when attachment is needed"
      (command pena :set-attachment-not-needed :id application-id :notNeeded false
               :attachmentId (:id att1)) => ok?
      (upload-attachment pena application-id att1 true))
    (fact "Can't be set notNeeded when file is uploaded"
      (command pena :set-attachment-not-needed :id application-id :notNeeded false
               :attachmentId (:id att1)) => fail?)
    (let [{attachments :attachments} (query-application pena application-id)
          updated-attachment (first attachments)]
      (command pena
               :delete-attachment-version
               :id application-id
               :attachmentId (:id updated-attachment)
               :fileId (get-in updated-attachment [:latestVersion :fileId])
               :originalFileId (get-in updated-attachment [:latestVersion :originalFileId])) => ok?
      (fact "Not needed can be set after version deletion"
        (command pena :set-attachment-not-needed :id application-id :notNeeded true
                 :attachmentId (:id updated-attachment)) => ok?))))

(facts stamp-templates
  (let [result (query sipoo :stamp-templates)
        stamps (:stamps result)]

    (fact "stamps in query result"
      (keys result) =contains=> :stamps)

    (fact "two stamps by default"
      (count stamps) => 2)))

(facts "editing stamp templates"
  (let [add-result (command sipoo :upsert-stamp-template
                            :name "zero stamp" :page "first" :background 0
                            :qrCode false :position {:x 0 :y 0}
                            :rows [[{:type "custom-text" :text "Hello World"}]])
        stamp-id (:stamp-id add-result)]
    (fact "add new stamp"
      add-result => ok?
      stamp-id => string?)

    (fact "stamp is added"
      (let [stamps (:stamps (query sipoo :stamp-templates))
            new-stamp (last stamps)]
        (count stamps) => 3

        (fact "stamp data is ok"
          (keys new-stamp) => (just [:id :name :page :background
                                     :qrCode :position :rows]
                                    :in-any-order :gaps-ok)
          (:id new-stamp) => stamp-id
          (:name new-stamp) => "zero stamp"
          (:page new-stamp) => "first"
          (:background new-stamp) => 0
          (:qrCode new-stamp) => false
          (:position new-stamp) => {:x 0 :y 0}
          (:rows new-stamp) => [[{:type "custom-text" :text "Hello World"}]])))

    (facts "edit existing stamp"
      (let [edit-result (command sipoo :upsert-stamp-template
                                 :stamp-id stamp-id :name "zero stamp"
                                 :page "last" :background 20 :qrCode false
                                 :position {:x 1 :y 2}
                                 :rows [[{:type "custom-text"
                                          :text "Hello World"}]])]
        (fact "edit ok"
          edit-result => ok?
          (:stamp-id edit-result) => stamp-id)

        (fact "stamp is added"
          (let [stamps (:stamps (query sipoo :stamp-templates))
                edited-stamp (last stamps)]
            (count stamps) => 3

            (fact "stamp data is ok"
              (keys edited-stamp) => (just [:id :name :page :background
                                            :qrCode :position :rows]
                                           :in-any-order :gaps-ok)
              (:id edited-stamp) => stamp-id
              (:name edited-stamp) => "zero stamp"
              (:page edited-stamp) => "last"
              (:background edited-stamp) => 20
              (:qrCode edited-stamp) => false
              (:position edited-stamp) => {:x 1 :y 2}
              (:rows edited-stamp) => [[{:type "custom-text" :text "Hello World"}]])))))))

(facts "remove stamp"
  (let [stamps (:stamps (query sipoo :stamp-templates))
        stamp-id (:id (last stamps))]

    stamp-id => string?

    (command sipoo :delete-stamp-template :stamp-id stamp-id) => ok?

    (fact "stamp is removed"
      (let [updated-stamps (:stamps (query sipoo :stamp-templates))]
        (count updated-stamps) => (dec (count stamps))
        (util/find-by-id stamp-id updated-stamps) => nil))))
