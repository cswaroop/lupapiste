(ns lupapalvelu.attachment.stamping-itest
  (:require [midje.sweet :refer :all]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.factlet :refer :all]
            [lupapalvelu.attachment :refer [get-attachment-info]]
            [lupapalvelu.attachment.util :refer [attachment-state]]
            [sade.util :as util]))

(apply-remote-minimal)

(facts "Stamping"
  (let [application (create-and-submit-application pena :propertyId sipoo-property-id)
        application-id (:id application)
        attachment (first (:attachments application))
        _ (upload-attachment pena application-id attachment true :filename "dev-resources/test-pdf.pdf")
        application (query-application pena application-id)
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
          pena-app (query-application pena application-id)
          comments-after (:comments pena-app)]

      (fact "Attachment has stamp and no new comments"
        (get-in attachment [:latestVersion :stamped]) => true
        comments-after => comments)
      (fact "Pena can't delete attachemnt, as it's bound to application by operation"
        pena =not=> (allowed? :delete-attachment :id application-id :attachmentId (:id attachment)))

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

(facts* "Stamped attachment can't be deleted LPK-3335"
  (let [application (create-and-submit-application pena :propertyId sipoo-property-id)
        application-id (:id application)
        attachment (first (:attachments application))
        ; upload to placeholder
        _ (upload-file-and-bind pena application-id {:filename "dev-resources/test-pdf.pdf"} :attachment-id (:id attachment))
        ; we upload new attachment, as attachment created on app creation can't be deleted
        _ (upload-file-and-bind pena application-id {:type  {:type-group "suunnitelmat"
                                                             :type-id    "valaistussuunnitelma"}
                                                     :group {:groupType  "operation"
                                                             :operations [{:id (-> application :primaryOperation :id)}]}}) => string?
        application (query-application pena application-id)
        new-attachment (util/find-first #(= "valaistussuunnitelma" (get-in % [:type :type-id])) (:attachments application))
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
                              :files [(:id new-attachment)
                                      (:id attachment)]
                              :lang "fi"
                              :stamp stamp)
        file-id (get-in (:value job) [(-> job :value keys first) :fileId])]
    new-attachment => truthy

    (fact "stamp is validated against schema"
      (command
        sonja
        :stamp-attachments
        :id application-id
        :timestamp ""
        :files [(:id new-attachment)
                (:id attachment)]
        :lang "fi"
        :stamp (assoc stamp :page "foo")) => (partial expected-failure? :error.illegal-value:schema-validation))

    (fact "not stamped by default"
      (get-in (get-attachment-info application (:id attachment)) [:latestVersion :stamped]) => falsey
      (get-in (get-attachment-info application (:id new-attachment)) [:latestVersion :stamped]) => falsey)

    (fact "Attachment state is not ok"
      (attachment-state (get-attachment-info application (:id attachment))) =not=> :ok
      (attachment-state (get-attachment-info application (:id new-attachment))) =not=> :ok)

    resp => ok?
    (fact "Job id is returned" (:id job) => truthy)
    (fact "FileId is returned" file-id => truthy)

    ; Poll for 5 seconds
    (when-not (= "done" (:status job)) (poll-job sonja :stamp-attachments-job (:id job) (:version job) 25))

    (let [application (query-application sonja application-id)
          regular-attachment (get-attachment-info application (:id attachment))
          new-attachment (get-attachment-info application (:id new-attachment))]

      (fact "Attachments have stamp"
        (get-in new-attachment [:latestVersion :stamped]) => true
        (get-in regular-attachment [:latestVersion :stamped]) => true)
      (fact "Pena can't delete attachemnt, as it's stamped"
        pena =not=> (allowed? :delete-attachment :id application-id :attachmentId (:id new-attachment)))
      (fact "Sonja can delete as she is authority"
        sonja => (allowed? :delete-attachment :id application-id :attachmentId (:id new-attachment)))

      (fact "Attachment state is now ok"
        (attachment-state new-attachment) => :ok)
      (fact "New fileid is in response" (get-in new-attachment [:latestVersion :fileId]) =not=> file-id))))

(facts "Stamping copies all signings"
  (let [{application-id :id :as response} (create-app pena :propertyId sipoo-property-id :operation "kerrostalo-rivitalo")
        application (query-application pena application-id)
        attachment-id (:id (first (:attachments application)))
        _ (upload-attachment pena application-id {:id attachment-id :type {:type-group "paapiirustus" :type-id "asemapiirros"}} true) => ok?
        _ (command pena :invite-with-role :id application-id :email "mikko@example.com" :text  "" :documentName "" :documentId "" :path "" :role "writer") => ok?
        _ (command mikko :approve-invite :id application-id) => ok?
        stamp {:id         "123456789012345678901234"
               :name       "Oletusleima"
               :position   {:x 10 :y 200}
               :background 0
               :page       "all"
               :qrCode     true
               :rows       [[{:type "custom-text" :value "Stamp"}]]}]

    (fact "Both guys has sign attachment"
      (command pena :sign-attachments :id application-id :attachmentIds [attachment-id] :password "pena") => ok?
      (command mikko :sign-attachments :id application-id :attachmentIds [attachment-id] :password "mikko123") => ok?
      (count (:signatures (first (:attachments (query-application pena application-id))))) => 2)

    (fact "Sonja stamps the attachment"
      (command pena :submit-application :id application-id) = ok?
      (let [{job :job} (command sonja :stamp-attachments :id application-id :timestamp (sade.core/now) :files [attachment-id] :lang :fi :stamp stamp) => ok?]
        ; Wait that stamping job is done
        (when-not (= "done" (:status job)) (poll-job sonja :stamp-attachments-job (:id job) (:version job) 25))))

    (fact "Stamped attachment version have both signatures"
      (let [{attachments :attachments} (query-application sonja application-id)
            stamped-attachment (first attachments)]
        (count (:signatures stamped-attachment)) => 4
        (:version (get (:signatures stamped-attachment) 2)) => {:major 1 :minor 1}
        (:version (get (:signatures stamped-attachment) 3)) => {:major 1 :minor 1}))))

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

