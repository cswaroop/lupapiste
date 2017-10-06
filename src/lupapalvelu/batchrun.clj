(ns lupapalvelu.batchrun
  (:require [taoensso.timbre :refer [debug debugf error errorf info]]
            [me.raynes.fs :as fs]
            [monger.operators :refer :all]
            [clojure.set :as set]
            [slingshot.slingshot :refer [try+]]
            [clj-time.coerce :as c]
            [clj-time.core :as t]
            [lupapalvelu.action :refer :all]
            [lupapalvelu.application-state :as app-state]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.integrations.matti :as matti]
            [lupapalvelu.logging :as logging]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.neighbors-api :as neighbors]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.prev-permit :as prev-permit]
            [lupapalvelu.review :as review]
            [lupapalvelu.states :as states]
            [lupapalvelu.tasks :as tasks]
            [lupapalvelu.ttl :as ttl]
            [lupapalvelu.user :as user]
            [lupapalvelu.verdict :as verdict]
            [lupapalvelu.xml.krysp.reader :as krysp-reader]
            [lupapalvelu.xml.krysp.application-from-krysp :as krysp-fetch]
            [sade.core :refer :all]
            [sade.env :as env]
            [sade.dummy-email-server]
            [sade.http :as http]
            [sade.strings :as ss]
            [sade.util :refer [fn-> pcond->] :as util]
            [lupapalvelu.xml.asianhallinta.reader :as ah-reader])
  (:import [org.xml.sax SAXParseException]))


(defn- older-than [timestamp] {$lt timestamp})

(defn- newer-than [timestamp] {$gt timestamp})

(defn- get-app-owner [application]
  (let [owner (auth/get-auths-by-role application :owner)]
    (user/get-user-by-id (-> owner first :id))))

(defn system-not-in-lockdown? []
  (-> (http/get "http://127.0.0.1:8000/system/status")
      http/decode-response
      :body :data :not-in-lockdown :data))

;; Email definition for the "open info request reminder"

(defn- oir-reminder-base-email-model [{{token :token-id created-date :created-date} :data :as command} _ recipient]
  (merge (notifications/create-app-model command nil recipient)
         {:link (fn [lang] (str (env/value :host) "/api/raw/openinforequest?token-id=" token "&lang=" (name lang)))
          :inforequest-created created-date}))

(def- oir-reminder-email-conf
  {:recipients-fn  notifications/from-data
   :subject-key    "open-inforequest-reminder"
   :model-fn       oir-reminder-base-email-model
   :application-fn (fn [{id :id}] (mongo/by-id :applications id))})

(notifications/defemail :reminder-open-inforequest oir-reminder-email-conf)

;; Email definition for the "Neighbor reminder"

(notifications/defemail :reminder-neighbor (assoc neighbors/email-conf :subject-key "neighbor-reminder"))

;; Email definition for the "Request statement reminder"

(defn- statement-reminders-email-model [{{:keys [created-date statement]} :data application :application :as command} _ recipient]
  (merge (notifications/create-app-model command nil recipient)
    {:link     #(notifications/get-application-link application "/statement" % recipient)
     :statement-request-created created-date
     :due-date (util/to-local-date (:dueDate statement))
     :message  (:saateText statement)}))

(notifications/defemail :reminder-request-statement
  {:recipients-fn  :recipients
   :subject-key    "statement-request-reminder"
   :model-fn       statement-reminders-email-model})

;; Email definition for the "Statement due date reminder"

(notifications/defemail :reminder-statement-due-date
  {:recipients-fn  :recipients
   :subject-key    "reminder-statement-due-date"
   :model-fn       statement-reminders-email-model})

;; Email definition for the "Application state reminder"

(notifications/defemail :reminder-application-state
  {:subject-key    "active-application-reminder"
   :recipients-fn  notifications/from-user})

;; Email definition for the "YA work time is expiring"

(defn- ya-work-time-is-expiring-reminder-email-model [{{work-time-expires-date :work-time-expires-date} :data :as command} _ recipient]
  (assoc
    (notifications/create-app-model command nil recipient)
    :work-time-expires-date work-time-expires-date))

(notifications/defemail :reminder-ya-work-time-is-expiring
  {:subject-key    "ya-work-time-is-expiring-reminder"
   :model-fn       ya-work-time-is-expiring-reminder-email-model})




;; "Lausuntopyynto: Pyyntoon ei ole vastattu viikon kuluessa ja hakemuksen tila on valmisteilla tai vireilla. Lahetetaan viikoittain uudelleen."
(defn statement-request-reminder []
  (let [timestamp-now (now)
        timestamp-1-week-ago (util/get-timestamp-ago :week 1)
        apps (mongo/select :applications
                           {:state {$in ["open" "submitted"]}
                            :statements {$elemMatch {:requested (older-than timestamp-1-week-ago)
                                                     :given nil
                                                     $or [{:reminder-sent {$exists false}}
                                                          {:reminder-sent nil}
                                                          {:reminder-sent (older-than timestamp-1-week-ago)}]}}}
                           [:statements :state :modified :infoRequest :title :address :municipality :primaryOperation])]
    (doseq [app apps
            statement (:statements app)
            :let [requested (:requested statement)
                  due-date (:dueDate statement)
                  reminder-sent (:reminder-sent statement)]
            :when (and
                    (nil? (:given statement))
                    (< requested timestamp-1-week-ago)
                    (or (nil? reminder-sent) (< reminder-sent timestamp-1-week-ago))
                    (or (nil? due-date) (> due-date timestamp-now)))]
      (logging/with-logging-context {:applicationId (:id app)}
        (notifications/notify! :reminder-request-statement {:application app
                                                            :recipients [(user/get-user-by-email (get-in statement [:person :email]))]
                                                            :data {:created-date (util/to-local-date requested)
                                                                   :statement statement}})
        (update-application (application->command app)
          {:statements {$elemMatch {:id (:id statement)}}}
          {$set {:statements.$.reminder-sent timestamp-now}})))))



;; "Lausuntopyynnon maaraika umpeutunut, mutta lausuntoa ei ole annettu. Muistutus lahetetaan viikoittain uudelleen."
(defn statement-reminder-due-date []
  (let [timestamp-now (now)
        timestamp-1-week-ago (util/get-timestamp-ago :week 1)
        apps (mongo/select :applications
                           {:state {$nin (map name (clojure.set/union states/post-verdict-states states/terminal-states))}
                            :statements {$elemMatch {:given nil
                                                     $and [{:dueDate {$exists true}}
                                                           {:dueDate (older-than timestamp-now)}]
                                                     $or [{:duedate-reminder-sent {$exists false}}
                                                          {:duedate-reminder-sent nil}
                                                          {:duedate-reminder-sent (older-than timestamp-1-week-ago)}]}}}
                           [:statements :state :modified :infoRequest :title :address :municipality :primaryOperation])]
    (doseq [app apps
            statement (:statements app)
            :let [due-date (:dueDate statement)
                  duedate-reminder-sent (:duedate-reminder-sent statement)]
            :when (and
                    (nil? (:given statement))
                    (number? due-date)
                    (< due-date timestamp-now)
                    (or (nil? duedate-reminder-sent) (< duedate-reminder-sent timestamp-1-week-ago)))]
      (logging/with-logging-context {:applicationId (:id app)}
        (notifications/notify! :reminder-statement-due-date {:application app
                                                             :recipients [(user/get-user-by-email (get-in statement [:person :email]))]
                                                             :data {:due-date (util/to-local-date due-date)
                                                                    :statement statement}})
        (update-application (application->command app)
          {:statements {$elemMatch {:id (:id statement)}}}
          {$set {:statements.$.duedate-reminder-sent (now)}})))))



;; "Neuvontapyynto: Neuvontapyyntoon ei ole vastattu viikon kuluessa eli neuvontapyynnon tila on avoin. Lahetetaan viikoittain uudelleen."
(defn open-inforequest-reminder []
  (let [timestamp-1-week-ago (util/get-timestamp-ago :week 1)
        oirs (mongo/select :open-inforequest-token {:created (older-than timestamp-1-week-ago)
                                                    :last-used nil
                                                    $or [{:reminder-sent {$exists false}}
                                                         {:reminder-sent nil}
                                                         {:reminder-sent (older-than timestamp-1-week-ago)}]})]
    (doseq [oir oirs]
      (let [application (mongo/by-id :applications (:application-id oir) [:state :modified :title :address :municipality :primaryOperation])]
        (logging/with-logging-context {:applicationId (:id application)}
          (when (= "info" (:state application))
            (notifications/notify! :reminder-open-inforequest {:application application
                                                               :data {:email (:email oir)
                                                                      :token-id (:id oir)
                                                                      :created-date (util/to-local-date (:created oir))}})
            (mongo/update-by-id :open-inforequest-token (:id oir) {$set {:reminder-sent (now)}})
            ))))))


;; "Naapurin kuuleminen: Kuulemisen tila on "Sahkoposti lahetetty", eika allekirjoitusta ole tehty viikon kuluessa ja hakemuksen tila on valmisteilla tai vireilla. Muistutus lahetetaan kerran."
(defn neighbor-reminder []
  (let [timestamp-1-week-ago (util/get-timestamp-ago :week 1)
        apps (mongo/select :applications
                           {:state {$in ["open" "submitted"]}
                            :neighbors.status {$elemMatch {$and [{:state {$in ["email-sent"]}}
                                                                 {:created (older-than timestamp-1-week-ago)}
                                                                 ]}}}
                           [:neighbors :state :modified :title :address :municipality :primaryOperation])]
    (doseq [app apps
            neighbor (:neighbors app)
            :let [statuses (:status neighbor)]]
      (logging/with-logging-context {:applicationId (:id app)}
        (when (not-any? #(or
                           (= "reminder-sent" (:state %))
                           (= "response-given-ok" (:state %))
                           (= "response-given-comments" (:state %))
                           (= "mark-done" (:state %))) statuses)

          (doseq [status statuses]

            (when (and
                    (= "email-sent" (:state status))
                    (< (:created status) timestamp-1-week-ago))
              (notifications/notify! :reminder-neighbor {:application app
                                                         :user        {:email (:email status)}
                                                         :data        {:token      (:token status)
                                                                       :expires    (util/to-local-datetime (+ ttl/neighbor-token-ttl (:created status)))
                                                                       :neighborId (:id neighbor)}})
              (update-application (application->command app)
                {:neighbors {$elemMatch {:id (:id neighbor)}}}
                {$push {:neighbors.$.status {:state    "reminder-sent"
                                             :token    (:token status)
                                             :created  (now)}}}))))))))



;; "YA hakemus: Hakemukselle merkitty tyoaika umpeutuu viikon kuluessa ja hakemuksen tila on valmisteilla tai vireilla. Lahetetaan viikoittain uudelleen."
(defn ya-work-time-is-expiring-reminder []
  (let [timestamp-1-week-in-future (util/get-timestamp-from-now :week 1)
        apps (mongo/select :applications
                           {:permitType "YA"
                            :state {$in ["verdictGiven" "constructionStarted"]}
                            ;; Cannot compare timestamp directly against date string here (e.g against "08.10.2015"). Must do it in function body.
                            :documents {$elemMatch {:schema-info.name "tyoaika"}}
                            :work-time-expiring-reminder-sent {$exists false}}
                           [:documents :auth :state :modified :title :address :municipality :infoRequest :primaryOperation])]
    (doseq [app apps
            :let [tyoaika-doc (some
                                (fn [doc]
                                  (when (= "tyoaika" (-> doc :schema-info :name)) doc))
                                (:documents app))
                  work-time-expires-timestamp (-> tyoaika-doc :data :tyoaika-paattyy-ms :value)]
            :when (and
                    work-time-expires-timestamp
                    (> work-time-expires-timestamp (now))
                    (< work-time-expires-timestamp timestamp-1-week-in-future))]
      (logging/with-logging-context {:applicationId (:id app)}
        (notifications/notify! :reminder-ya-work-time-is-expiring {:application app
                                                                   :user (get-app-owner app)
                                                                   :data {:work-time-expires-date (util/to-local-date work-time-expires-timestamp)}})
        (update-application (application->command app)
          {$set {:work-time-expiring-reminder-sent (now)}})))))



;; "Hakemus: Hakemuksen tila on luonnos tai valmisteilla, mutta edellisesta paivityksesta on aikaa yli kuukausi ja alle puoli vuotta. Lahetetaan kuukausittain uudelleen."
(defn application-state-reminder []
  (let [apps (mongo/select :applications
                           {:state {$in ["draft" "open"]}
                            $and [{:modified (older-than (util/get-timestamp-ago :month 1))}
                                  {:modified (newer-than (util/get-timestamp-ago :month 6))}]
                            $or [{:reminder-sent {$exists false}}
                                 {:reminder-sent nil}
                                 {:reminder-sent (older-than (util/get-timestamp-ago :month 1))}]}
                           [:auth :state :modified :title :address :municipality :infoRequest :primaryOperation])]
    (doseq [app apps]
      (logging/with-logging-context {:applicationId (:id app)}
        (notifications/notify! :reminder-application-state {:application app
                                                            :user (get-app-owner app)})
        (update-application (application->command app)
          {$set {:reminder-sent (now)}})))))


(defn send-reminder-emails [& args]
  (when (env/feature? :reminders)
    (mongo/connect!)
    (statement-request-reminder)
    (statement-reminder-due-date)
    (open-inforequest-reminder)
    (neighbor-reminder)
    (application-state-reminder)
    (ya-work-time-is-expiring-reminder)

    (mongo/disconnect!)))

(defn fetch-verdicts []
  (let [orgs-with-wfs-url-defined-for-some-scope (organization/get-organizations
                                                   {$or [{:krysp.R.url {$exists true}}
                                                         {:krysp.YA.url {$exists true}}
                                                         {:krysp.P.url {$exists true}}
                                                         {:krysp.MAL.url {$exists true}}
                                                         {:krysp.VVVL.url {$exists true}}
                                                         {:krysp.YI.url {$exists true}}
                                                         {:krysp.YL.url {$exists true}}
                                                         {:krysp.KT.url {$exists true}}]}
                                                   {:krysp 1})
        orgs-by-id (util/key-by :id orgs-with-wfs-url-defined-for-some-scope)
        org-ids (keys orgs-by-id)
        apps (mongo/select :applications {:state {$in ["sent"]} :organization {$in org-ids}})
        eraajo-user (user/batchrun-user org-ids)]
    (doall
      (pmap
        (fn [{:keys [id permitType organization] :as app}]
          (logging/with-logging-context {:applicationId id, :userId (:id eraajo-user)}
            (let [url (get-in orgs-by-id [organization :krysp (keyword permitType) :url])]
              (try
                (if-not (ss/blank? url)
                  (let [command (assoc (application->command app) :user eraajo-user :created (now) :action "fetch-verdicts")
                        result (verdict/do-check-for-verdict command)]
                    (when (-> result :verdicts count pos?)
                      ;; Print manually to events.log, because "normal" prints would be sent as emails to us.
                      (logging/log-event :info {:run-by "Automatic verdicts checking" :event "Found new verdict"})
                      (notifications/notify! :application-state-change command))
                    (when (or (nil? result) (fail? result))
                      (logging/log-event :error {:run-by "Automatic verdicts checking"
                                                 :event "Failed to check verdict"
                                                 :failure (if (nil? result) :error.no-app-xml result)
                                                 :organization {:id organization :permit-type permitType}
                                                 })))

                  (logging/log-event :info {:run-by "Automatic verdicts checking"
                                            :event "No Krysp WFS url defined for organization"
                                            :organization {:id organization :permit-type permitType}}))
                (catch Throwable t
                  (logging/log-event :error {:run-by "Automatic verdicts checking"
                                             :event "Unable to get verdict from backend"
                                             :exception-message (.getMessage t)
                                             :application-id id
                                             :organization {:id organization :permit-type permitType}}))))))
          apps))))

(defn check-for-verdicts [& args]
  (when-not (system-not-in-lockdown?)
    (logging/log-event :info {:run-by "Automatic verdict checking" :event "Not run - system in lockdown"})
    (fail! :system-in-lockdown))
  (mongo/connect!)
  (fetch-verdicts))

(defn- get-asianhallinta-ftp-users [organizations]
  (->> (for [org organizations
             scope (:scope org)]
         (get-in scope [:caseManagement :ftpUser]))
    (remove nil?)
    distinct))

(defn fetch-asianhallinta-messages []
  (let [ah-organizations (mongo/select :organizations
                                       {"scope.caseManagement.ftpUser" {$exists true}}
                                       {"scope.caseManagement.ftpUser" 1})
        ftp-users (if (string? (env/value :ely :sftp-user))
                    (conj (get-asianhallinta-ftp-users ah-organizations) (env/value :ely :sftp-user))
                    (get-asianhallinta-ftp-users ah-organizations))
        eraajo-user (user/batchrun-user (map :id ah-organizations))]
    (logging/log-event :info {:run-by "Asianhallinta reader"
                              :event (format "Reader process start - %d ftp users to be checked" (count ftp-users))})
    (doseq [ftp-user ftp-users
            :let [path (str
                         (env/value :outgoing-directory) "/"
                         ftp-user "/"
                         "asianhallinta/to_lupapiste/")]
            zip (util/get-files-by-regex path #".+\.zip$")]
      (fs/mkdirs (str path "archive"))
      (fs/mkdirs (str path "error"))
      (let [zip-path (.getPath zip)
            result (try
                     (ah-reader/process-message zip-path ftp-user eraajo-user)
                     (catch Throwable e
                       (logging/log-event :error {:run-by "Asianhallinta reader"
                                                  :event "Unable to process ah zip file"
                                                  :exception-message (.getMessage e)})
                       ;; (error e "Error processing zip-file in asianhallinta verdict batchrun")
                       (fail :error.unknown)))
            target (str path (if (ok? result) "archive" "error") "/" (.getName zip))]
        (logging/log-event (if (ok? result) :info :error)
                           (util/assoc-when {:run-by "Asianhallinta reader"
                                             :event (if (ok? result)  "Succesfully processed message" "Failed to process message")
                                             :zip-path zip-path}
                                            :text (:text result)))
        (when-not (fs/rename zip target)
          (errorf "Failed to rename %s to %s" zip-path target))))
    (logging/log-event :info {:run-by "Asianhallinta reader" :event "Reader process finished"})))

(defn check-for-asianhallinta-messages [& args]
  (when-not (system-not-in-lockdown?)
    (logging/log-event :info {:run-by "Asianhallinta reader" :event "Not run - system in lockdown"})
    (fail! :system-in-lockdown))
  (mongo/connect!)
  (fetch-asianhallinta-messages))

(defn orgs-for-review-fetch [& organization-ids]
  (mongo/select :organizations (merge {:krysp.R.url {$exists true},
                                          :krysp.R.version {$gte "2.1.5"}}
                                         (when (seq organization-ids) {:_id {$in organization-ids}}))
                               {:krysp 1}))

(defn- save-reviews-for-application [user application {:keys [updates added-tasks-with-updated-buildings attachments-by-task-id] :as result}]
  (logging/with-logging-context {:applicationId (:id application) :userId (:id user)}
    (when (ok? result)
      (try
        (review/save-review-updates (assoc (application->command application) :user user)
                                    updates
                                    added-tasks-with-updated-buildings
                                    attachments-by-task-id)
        (catch Throwable t
          {:ok false :desc (.getMessage t)})))))

(defn- read-reviews-for-application
  [user created application app-xml & [overwrite-background-reviews?]]
  (try
    (when (and application app-xml)
      (logging/with-logging-context {:applicationId (:id application) :userId (:id user)}
        (let [{:keys [review-count updated-tasks validation-errors] :as result} (review/read-reviews-from-xml user created application app-xml overwrite-background-reviews?)]
          (cond
            (and (ok? result) (pos? review-count)) (logging/log-event :info {:run-by "Automatic review checking"
                                                                             :event "Reviews found"
                                                                             :updated-tasks updated-tasks})
            (ok? result)                           (logging/log-event :info {:run-by "Automatic review checking"
                                                                             :event "No reviews"})
            (fail? result)                         (logging/log-event :error {:run-by "Automatic review checking"
                                                                              :event "Failed to read reviews"
                                                                              :validation-errors validation-errors}))
          result)))
    (catch Throwable t
      (errorf "error.integration - Could not read reviews for %s" (:id application)))))

(defn- fetch-reviews-for-organization-permit-type-consecutively [organization permit-type applications]
  (logging/log-event :info {:run-by "Automatic review checking"
                            :event "Fetch consecutively"
                            :organization-id (:id organization)
                            :application-count (count applications)
                            :applications (map :id applications)})
  (->> (map (fn [app]
              (try
                (krysp-fetch/fetch-xmls-for-applications organization permit-type [app])
                (catch Throwable t
                  (logging/log-event :error {:run-by "Automatic review checking"
                                             :application-id (:id app)
                                             :organization-id (:id organization)
                                             :exception (.getName (class t))
                                             :message (.getMessage t)
                                             :event (format "Unable to get reviews for %s from %s backend"  permit-type (:id organization))})
                  nil)))
            applications)
       (apply concat)
       (remove nil?)))

(defn- fetch-reviews-for-organization-permit-type [eraajo-user organization permit-type applications]
  (try+

   (logging/log-event :info {:run-by "Automatic review checking"
                             :event "Start fetching xmls"
                             :organization-id (:id organization)
                             :application-count (count applications)
                             :applications (map :id applications)})

   (krysp-fetch/fetch-xmls-for-applications organization permit-type applications)

   (catch SAXParseException e
     (logging/log-event :error {:run-by "Automatic review checking"
                                :organization-id (:id organization)
                                :event (format "Could not understand response when getting reviews in chunks from %s backend" (:id organization))})
     ;; Fallback into fetching xmls consecutively
     (fetch-reviews-for-organization-permit-type-consecutively organization permit-type applications))

   (catch [:sade.core/type :sade.core/fail
           :status         404] _
     (logging/log-event :error {:run-by "Automatic review checking"
                                :organization-id (:id organization)
                                :event (format "Unable to get reviews in chunks from %s backend: Got HTTP status 404" (:id organization))})
     ;; Fallback into fetching xmls consecutively
     (fetch-reviews-for-organization-permit-type-consecutively organization permit-type applications))


   (catch [:sade.core/type :sade.core/fail] t
     (logging/log-event :error {:run-by "Automatic review checking"
                                :organization-id (:id organization)
                                :event (format "Unable to get reviews from %s backend: %s" (:id organization) (select-keys t [:status :text]))}))

   (catch Object o
     (logging/log-event :error {:run-by "Automatic review checking"
                                :organization-id (:id organization)
                                :exception (.getName (class o))
                                :message (get &throw-context :message "")
                                :event (format "Unable to get reviews in chunks from %s backend: %s - %s"
                                               (:id organization) (.getName (class o)) (get &throw-context :message ""))}))))

(defn- organization-applications-for-review-fetching
  [organization-id permit-type projection & application-ids]
  (let [eligible-application-states (set/difference states/post-verdict-but-terminal #{:foremanVerdictGiven})]
    (mongo/select :applications (merge {:state {$in eligible-application-states}
                                        :permitType permit-type
                                        :organization organization-id
                                        :primaryOperation.name {$nin ["tyonjohtajan-nimeaminen-v2" "suunnittelijan-nimeaminen"]}}
                                       (when (not-empty application-ids)
                                         {:_id {$in application-ids}}))
                  (merge app-state/timestamp-key
                         (pcond-> projection
                                  sequential? (zipmap (repeat true)))))))

(defn mark-reviews-faulty-for-application [application {:keys [new-faulty-tasks]}]
  (when (not (empty? new-faulty-tasks))
    (let [timestamp (now)]
      (doseq [task-id new-faulty-tasks]
        (tasks/task->faulty (assoc (application->command application)
                                   :created timestamp)
                            task-id)))))

(defn- log-review-results-for-organization [organization-id applications-with-results]
  (logging/log-event :info {:run-by "Automatic review checking"
                            :event "Review checking finished for organization"
                            :organization-id organization-id
                            :application-count (count applications-with-results)
                            :faulty-tasks (->> applications-with-results
                                               (map (juxt (comp :id first)
                                                          (comp :new-faulty-tasks
                                                                second)))
                                               (into {}))}))

(defn- fetch-reviews-for-organization
  [eraajo-user created {org-krysp :krysp :as organization} permit-types applications {:keys [overwrite-background-reviews?]}]
  (let [fields [:address :primaryOperation :permitSubtype :history :municipality :state :permitType :organization :tasks :verdicts :modified]
        projection (cond-> (distinct (concat fields matti/base-keys))
                     overwrite-background-reviews? (conj :attachments))
        permit-types (remove (fn-> keyword org-krysp :url ss/blank?) permit-types)
        grouped-apps (if (seq applications)
                       (group-by :permitType applications)
                       (->> (map #(organization-applications-for-review-fetching (:id organization) % projection) permit-types)
                            (zipmap permit-types)))]
    (->> (mapcat (partial apply fetch-reviews-for-organization-permit-type eraajo-user organization) grouped-apps)
         (map (fn [[{app-id :id permit-type :permitType} app-xml]]
                (let [app    (first (organization-applications-for-review-fetching (:id organization) permit-type projection app-id))
                      result (read-reviews-for-application eraajo-user created app app-xml overwrite-background-reviews?)
                      save-result (save-reviews-for-application eraajo-user app result)]
                  ;; save-result is nil when application query fails (application became irrelevant for review fetching) -> no actions taken
                  (when (fail? save-result)
                    (logging/log-event :info {:run-by "Automatic review checking"
                                              :event  "Failed to save review updates for application"
                                              :reason (:desc save-result)
                                              :application-id app-id
                                              :result result}))
                  (when (ok? save-result)
                    (mark-reviews-faulty-for-application app result)
                    [app result]))))
         (remove nil?)
         (log-review-results-for-organization (:id organization)))))

(defn poll-verdicts-for-reviews
  [& {:keys [application-ids organization-ids overwrite-background-reviews?] :as options}]
  (let [applications  (when (seq application-ids)
                        (mongo/select :applications {:_id {$in application-ids}}))
        permit-types  (-> (map (comp keyword :permitType) applications) distinct not-empty (or [:R]))
        organizations (->> (map :organization applications) distinct (concat organization-ids) (apply orgs-for-review-fetch))
        eraajo-user   (user/batchrun-user (map :id organizations))
        threads       (mapv (fn [org]
                             (util/future* (fetch-reviews-for-organization eraajo-user (now) org permit-types (filter (comp #{(:id org)} :organization) applications) options)))
                           organizations)]
    (loop []
      (when-not (every? realized? threads)
        (Thread/sleep 1000)
        (recur)))))

(defn check-for-reviews [& args]
  (when-not (system-not-in-lockdown?)
    (logging/log-event :info {:run-by "Automatic review checking" :event "Not run - system in lockdown"})
    (fail! :system-in-lockdown))
  (logging/log-event :info {:run-by "Automatic review checking" :event "Started"})
  (mongo/connect!)
  (poll-verdicts-for-reviews)
  (logging/log-event :info {:run-by "Automatic review checking" :event "Finished"}))

(defn check-reviews-for-orgs [& args]
  (when-not (system-not-in-lockdown?)
    (logging/log-event :info {:run-by "Automatic review checking" :event "Not run - system in lockdown"})
    (fail! :system-in-lockdown))
  (logging/log-event :info {:run-by "Automatic review checking" :event "Started" :organizations args})
  (mongo/connect!)
  (poll-verdicts-for-reviews :organization-ids args)
  (logging/log-event :info {:run-by "Automatic review checking" :event "Finished" :organizations args}))

(defn overwrite-reviews-for-orgs [& args]
  (when-not (system-not-in-lockdown?)
    (logging/log-event :info {:run-by "Review checking with overwrite" :event "Not run - system in lockdown"})
    (fail! :system-in-lockdown))
  (when (empty? args)
    (logging/log-event :info {:run-by "Review checking with overwrite" :event "Not run - no organizations specified"})
    (fail! :no-organizations))
  (logging/log-event :info {:run-by "Review checking with overwrite" :event "Started" :organizations args})
  (mongo/connect!)
  (poll-verdicts-for-reviews :organization-ids args
                             :overwrite-background-reviews? true)
  (logging/log-event :info {:run-by "Review checking with overwrite" :event "Finished" :organizations args}))

(defn check-reviews-for-ids [& args]
  (when-not (system-not-in-lockdown?)
    (logging/log-event :info {:run-by "Automatic review checking" :event "Not run - system in lockdown"})
    (fail! :system-in-lockdown))
  (logging/log-event :info {:run-by "Automatic review checking" :event "Started" :applications args})
  (mongo/connect!)
  (poll-verdicts-for-reviews :application-ids args)
  (logging/log-event :info {:run-by "Automatic review checking" :event "Finished" :applications args}))

(defn extend-previous-permit [& args]
  (mongo/connect!)
  (if (= (count args) 1)
    (if-let [application (domain/get-application-no-access-checking (first args))]
      (let [kuntalupatunnus (get-in application [:verdicts 0 :kuntalupatunnus])
            app-xml  (krysp-fetch/get-application-xml-by-backend-id application kuntalupatunnus)
                    #_(sade.xml/parse (slurp "verdict-r-extend-prev-permit.xml"))
            app-info (krysp-reader/get-app-info-from-message app-xml kuntalupatunnus)]
        (prev-permit/extend-prev-permit-with-all-parties application app-xml app-info)
        0)
      (do
        (println "Cannot find application")
        2))
    (do
      (println "No application id given.")
      1)))

(defn pdfa-convert-review-pdfs [& args]
  (mongo/connect!)
  (debug "# of applications with background generated tasks:"
           (mongo/count :applications {:tasks.source.type "background"}))
  (let [eraajo-user (user/batchrun-user (map :id (orgs-for-review-fetch)))]
    (doseq [application (mongo/select :applications {:tasks.source.type "background"})]
      (let [command (assoc (application->command application) :user eraajo-user :created (now))]
        (doseq [task (:tasks application)]
          (if (= "background" (:type (:source task)))
            (do
              (doseq [att (:attachments application)]
                (if (= (:id task) (:id (:source att)))
                  (do
                    (debug "application" (:id (:application command)) "- converting task" (:id task) "-> attachment" (:id att) )
                    (attachment/convert-existing-to-pdfa! (:application command) (:user command) att)))))))))))

(defn pdf-to-pdfa-conversion [& args]
  (info "Starting pdf to pdf/a conversion")
  (mongo/connect!)
  (let [organization (first args)
        start-ts (c/to-long (c/from-string (second args)))
        end-ts (c/to-long (c/from-string (second (next args))))]
  (doseq [application (mongo/select :applications {:organization organization :state :verdictGiven})]
    (let [command (application->command application)
          last-verdict-given-date (:ts (last (sort-by :ts (filter #(= (:state % ) "verdictGiven") (:history application)))))]
      (logging/with-logging-context {:applicationId (:id application)}
        (when (and (= (:state application) "verdictGiven") (< start-ts last-verdict-given-date end-ts))
          (info "Converting attachments of application" (:id application))
          (doseq [attachment (:attachments application)]
            (when (:latestVersion attachment)
              (when-not (get-in attachment [:latestVersion :archivable])
                  (do
                    (info "Trying to convert attachment" (get-in attachment [:latestVersion :filename]))
                    (let [result (attachment/convert-existing-to-pdfa! (:application command) nil attachment)]
                      (if (:archivabilityError result)
                        (error "Conversion failed to" (:id application) "/" (:id attachment) "/" (get-in attachment [:latestVersion :filename]) "with error:" (:archivabilityError result))
                        (info "Conversion succeed to" (get-in attachment [:latestVersion :filename]) "/" (:id application))))))))))))))


(defn fetch-verdict-attachments
  [start-timestamp end-timestamp organizations]
  {:pre [(number? start-timestamp)
         (number? end-timestamp)
         (< start-timestamp end-timestamp)
         (vector? organizations)]}
  (let [apps (verdict/applications-with-missing-verdict-attachments
              {:start         start-timestamp
               :end           end-timestamp
               :organizations organizations})
        eraajo-user (user/batchrun-user (map :organization apps))]
    (->> (doall
          (pmap
           (fn [{:keys [id permitType organization] :as app}]
             (logging/with-logging-context {:applicationId id, :userId (:id eraajo-user)}
               (try
                 (let [command (assoc (application->command app) :user eraajo-user :created (now) :action :fetch-verdict-attachments)
                       app-xml (krysp-fetch/get-application-xml-by-application-id app)
                       result  (verdict/update-verdict-attachments-from-xml! command app-xml)]
                   (when (-> result :updated-verdicts count pos?)
                     ;; Print manually to events.log, because "normal" prints would be sent as emails to us.
                     (logging/log-event :info {:run-by "Automatic verdict attachments checking"
                                               :event "Found new verdict attachments"
                                               :updated-verdicts (-> result :updated-verdicts)}))
                   (when (or (nil? result) (fail? result))
                     (logging/log-event :error {:run-by "Automatic verdict attachment checking"
                                                :event "Failed to check verdict attachments"
                                                :failure (if (nil? result) :error.no-app-xml result)
                                                :organization {:id organization :permit-type permitType}
                                                }))

                   ;; Return result for testing purposes
                   result)
                 (catch Throwable t
                   (logging/log-event :error {:run-by "Automatic verdict attachments checking"
                                              :event "Unable to get verdict from backend"
                                              :exception-message (.getMessage t)
                                              :application-id id
                                              :organization {:id organization :permit-type permitType}})))))
           apps))
         (remove #(empty? (:updated-verdicts %)))
         (hash-map :updated-applications)
         (merge {:start start-timestamp
                 :end   end-timestamp
                 :organizations organizations
                 :applications (map :id apps)}))))

(defn check-for-verdict-attachments
  "Fetch missing verdict attachments for verdicts given in the time
  interval between start-timestamp and end-timestamp (last 3 months by
  default), for the organizations whose id's are provided as
  arguments (all organizations by default)."
  [& [start-timestamp end-timestamp & organizations]]
  (when-not (system-not-in-lockdown?)
    (logging/log-event :info {:run-by "Automatic verdict attachment checking" :event "Not run - system in lockdown"})
    (fail! :system-in-lockdown))
  (mongo/connect!)
  (fetch-verdict-attachments (or (when start-timestamp
                                   (util/to-millis-from-local-date-string start-timestamp))
                                 (-> 3 t/months t/ago c/to-long))
                             (or (when end-timestamp
                                   (util/to-millis-from-local-date-string end-timestamp))
                                 (now))
                             (or (vec organizations) [])))
