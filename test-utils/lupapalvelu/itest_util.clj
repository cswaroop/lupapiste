(ns lupapalvelu.itest-util
  (:require [noir.request :refer [*request*]]
            [cheshire.core :as json]
            [taoensso.timbre :as timbre :refer [trace debug info warn error fatal]]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [swiss.arrows :refer [-<>>]]
            [clj-ssh.cli :as ssh-cli]
            [clj-ssh.ssh :as ssh]
            [midje.sweet :refer :all]
            [midje.util.exceptions :refer :all]
            [slingshot.slingshot :refer [try+]]
            [sade.core :refer [fail! unauthorized not-accessible now]]
            [sade.dummy-email-server]
            [sade.env :as env]
            [sade.http :as http]
            [sade.strings :as ss]
            [sade.util :as util]
            [lupapalvelu.action :refer [*created-timestamp-for-test-actions*]]
            [lupapalvelu.attachment :as att]
            [lupapalvelu.batchrun :as batchrun]
            [lupapalvelu.cookie :as c]
            [lupapalvelu.fixture.minimal :as minimal]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.document.persistence :as doc-persistence]
            [lupapalvelu.document.document-api]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.api-common :as api-common]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.user :as u]
            [lupapalvelu.server]
            [ring.util.codec :as codec]
            [lupapalvelu.user :as usr])
  (:import org.apache.http.client.CookieStore
           java.io.FileNotFoundException))

(defn find-user-from-minimal [username] (some #(when (= (:username %) username) %) minimal/users))
(defn find-user-from-minimal-by-apikey [apikey] (u/with-org-auth (some #(when (= (get-in % [:private :apikey]) apikey) %) minimal/users)))
(defn- id-for [username] (:id (find-user-from-minimal username)))
(defn id-for-key [apikey] (:id (find-user-from-minimal-by-apikey apikey)))
(defn apikey-for [username] (get-in (find-user-from-minimal username) [:private :apikey]))

(defn email-for [username] (:email (find-user-from-minimal username)))
(defn email-for-key [apikey] (:email (find-user-from-minimal-by-apikey apikey)))

(defn organization-from-minimal-by-id [org-id]
  (some #(when (= (:id %) org-id) %) minimal/organizations))

(defn company-from-minimal-by-id [id]
  (some #(when (= (:id %) id) %) minimal/companies))

(def kaino       (apikey-for "kaino@solita.fi"))
(def kaino-id    (id-for "kaino@solita.fi"))
(def erkki       (apikey-for "erkki@example.com"))
(def erkki-id    (id-for "erkki@example.com"))
(def pena        (apikey-for "pena"))
(def pena-id     (id-for "pena"))
(def mikko       (apikey-for "mikko@example.com"))
(def mikko-id    (id-for "mikko@example.com"))
(def teppo       (apikey-for "teppo@example.com"))
(def teppo-id    (id-for "teppo@example.com"))
(def sven        (apikey-for "sven@example.com"))
(def sven-id     (id-for "sven@example.com"))
(def veikko      (apikey-for "veikko"))
(def veikko-id   (id-for "veikko"))
(def sonja       (apikey-for "sonja"))
(def sonja-id    (id-for "sonja"))
(def sonja-muni  "753")
(def ronja       (apikey-for "ronja"))
(def ronja-id    (id-for "ronja"))
(def luukas      (apikey-for "luukas"))
(def luukas-id   (id-for "luukas"))
(def kosti       (apikey-for "kosti"))
(def kosti-id    (id-for "kosti"))
(def sipoo       (apikey-for "sipoo"))
(def sipoo-ya    (apikey-for "sipoo-ya"))
(def tampere-ya  (apikey-for "tampere-ya"))
(def naantali    (apikey-for "admin@naantali.fi"))
(def oulu        (apikey-for "ymp-admin@oulu.fi"))
(def dummy       (apikey-for "dummy"))
(def admin       (apikey-for "admin"))
(def admin-id    (id-for "admin"))
(def raktark-jarvenpaa (apikey-for "rakennustarkastaja@jarvenpaa.fi"))
(def raktark-jarvenpaa-id   (id-for "rakennustarkastaja@jarvenpaa.fi"))
(def arto       (apikey-for "arto"))
(def kuopio     (apikey-for "kuopio-r"))
(def velho      (apikey-for "velho"))
(def velho-muni "297")
(def velho-id   (id-for "velho"))
(def jarvenpaa  (apikey-for "admin@jarvenpaa.fi"))
(def olli       (apikey-for "olli"))
(def olli-id    (id-for "olli"))
(def raktark-helsinki (apikey-for "rakennustarkastaja@hel.fi"))
(def jussi      (apikey-for "jussi"))
(def jussi--id  (id-for "jussi"))
(def digitoija  (apikey-for "digitoija@jarvenpaa.fi"))
(def torsti     (apikey-for "torsti"))
(def torsti-id  (id-for "torsti"))


(def sipoo-property-id "75300000000000")
(def jarvenpaa-property-id "18600000000000")
(def tampere-property-id "83700000000000")
(def kuopio-property-id "29700000000000")
(def oir-property-id "43300000000000")
(def oulu-property-id "56400000000000")
(def no-backend-property-id oulu-property-id)

(def sipoo-general-handler-id "abba1111111111111111acdc")
(def sipoo-kvv-handler-id     "abba1111111111111112acdc")
(def jarvenpaa-general-handler-id "abba11111111111111111186")


(defn server-address [] (env/server-address))

;; use in place of server-address to use loopback interface over configured hostname in testing, eg autologin
(defn target-server-or-localhost-address [] (System/getProperty "target_server" "http://localhost:8000"))

(def dev-env? env/dev-env?)

(def get-files-from-sftp-server? dev-env?)

(defn decode-response [resp]
  (http/decode-response resp))

(defn decode-body [resp]
  (:body (decode-response resp)))

(defn printed [x] (println x) x)

;;
;; HTTP Client cookie store
;;

(defonce test-db-name (str "test_" (now)))

(defn ->cookie-store [store]
  (proxy [CookieStore] []
    (getCookies []       (or (vals @store) []))
    (addCookie [cookie]  (swap! store assoc (.getName cookie) cookie))
    (clear []            (reset! store {}))
    (clearExpired [])))

(def test-db-cookie (c/->lupa-cookie "test_db_name" test-db-name))

(defn get-anti-csrf [store-atom]
  (-> (get @store-atom "anti-csrf-token") .getValue codec/url-decode))

(defn http [verb url options]
  (let [store (atom {})
        cookies (or (:cookie-store options) (->cookie-store store))]
    (.addCookie cookies (if (:test-db-name options)
                          (c/->lupa-cookie "test_db_name"
                                           (:test-db-name options))
                          test-db-cookie))
    (verb url (assoc options :cookie-store cookies))))

(def http-get (partial http http/get))
(def http-post (partial http http/post))

(defn raw [apikey action & args]
  (let [params (apply hash-map args)
        options (util/assoc-when {:oauth-token apikey
                                  :query-params (dissoc params :as :cookie-store)
                                  :throw-exceptions false
                                  :follow-redirects false
                                  :cookie-store (:cookie-store params)}
                                 :as (:as params))]
    (http-get (str (server-address) "/api/raw/" (name action)) options)))

(defn raw-query [apikey query-name & args]
  (decode-response
    (http-get
      (str (server-address) "/api/query/" (name query-name))
      {:headers {"accepts" "application/json;charset=utf-8"}
       :oauth-token apikey
       :query-params (apply hash-map args)
       :follow-redirects false
       :throw-exceptions false})))

(defn query [apikey query-name & args]
  (let [{status :status body :body} (apply raw-query apikey query-name args)]
    (when (= status 200)
      body)))

(defn decoded-get [url params]
  (decode-response (http-get url params)))

(defn decoded-simple-post [url params]
  (decode-response (http-post url params)))

(defn ->arg-map [args]
  (if (map? (first args))
    (first args)
    (apply hash-map args)))

(defn decode-post [action-type apikey command-name & args]
  (decode-response
    (http-post
      (str (server-address) "/api/" (name action-type) "/" (name command-name))
      (let [args         (->arg-map args)
            cookie-store (:cookie-store args)
            test-db-name (:test-db-name args)
            args (dissoc args :cookie-store :test-db-name)]
        {:headers {"content-type" "application/json;charset=utf-8"}
         :oauth-token apikey
         :body (json/encode args)
         :follow-redirects false
         :cookie-store cookie-store
         :test-db-name test-db-name
         :throw-exceptions false}))))


(defn raw-command [apikey command-name & args]
  (apply decode-post :command apikey command-name args))

(defn command [apikey command-name & args]
  (let [{status :status body :body} (apply raw-command apikey command-name args)]
    (if (= status 200)
      body
      (error status body))))

(defn datatables [apikey query-name & args]
  (let [{status :status body :body} (apply decode-post :datatables apikey query-name args)]
    (when (= status 200)
      body)))

(defn apply-remote-fixture [fixture-name]
  (let [resp (decode-response (http-get (str (server-address) "/dev/fixture/" fixture-name) {}))]
    (assert (-> resp :body :ok) (str "Response not ok: fixture: \"" fixture-name "\": response: " (pr-str resp)))))

(def apply-remote-minimal (partial apply-remote-fixture "minimal"))

(defn get-by-id [collection id & args]
  (decode-response (http-get (str (server-address) "/dev/by-id/" (name collection) "/" id) (apply hash-map args))))

(defn integration-messages [app-id & args]
  (-> (http-get (str (server-address) "/dev/integration-messages/" app-id) (apply hash-map args))
      (decode-response)
      (get-in [:body :data])))

(defn clear-collection [collection]
  (let [resp (decode-response (http-get (str (server-address) "/dev/clear/" collection) {}))]
    (assert (-> resp :body :ok) (str "Response not ok: clearing collection: \"" collection "\": response: " (pr-str resp)))))

(defn clear-ajanvaraus-db []
  (let [resp (decode-response (http-get (str (server-address) "/dev/ajanvaraus/clear") {}))]
    (assert (-> resp :body :ok) (str "Response not ok: clearing ajanvaraus-db" (pr-str resp)))))

(def create-app-default-args {:operation "kerrostalo-rivitalo"
                              :propertyId "75312312341234"
                              :x 444444 :y 6666666
                              :address "foo 42, bar"})

(defn create-app-with-fn [f apikey & args]
  (let [args (apply hash-map args)
        params (->> args
                 (merge create-app-default-args)
                 (mapcat seq))]
    (apply f apikey :create-application params)))

(defn create-app
  "Runs the create-application command, returns reply map. Use ok? to check it."
  [apikey & args]
  (apply create-app-with-fn command apikey args))


;;
;; Test predicates
;;

(defn success [resp]
  (fact (:text resp) => nil)
  (:ok resp))

(defn invalid-csrf-token? [{:keys [status body]}]
  (and
    (= status 403)
    (= (:ok body) false)
    (= (:text body) "error.invalid-csrf-token")))

(fact "invalid-csrf-token?"
  (invalid-csrf-token? {:status 403 :body {:ok false :text "error.invalid-csrf-token"}}) => true
  (invalid-csrf-token? {:status 403 :body {:ok false :text "error.SOME_OTHER_REASON"}}) => false
  (invalid-csrf-token? {:status 200 :body {:ok true}}) => false)

(defchecker expected-failure? [expected-text e]
  (cond
    (sequential? (:errors e)) (some (partial = (keyword expected-text)) (map (comp keyword :text) (:errors e)))
    (map? e)                (and (= (:ok e) false) (= (-> e :text name) (name expected-text)))
    (captured-throwable? e) (= (some-> e throwable .getData :text name) (name expected-text))
    :else (throw (Exception. (str "'expected-failure?' called with invalid error parameter " e)))))

(def unauthorized? (partial expected-failure? (:text unauthorized)))
(def not-accessible? (partial expected-failure? (:text not-accessible)))


(facts "unauthorized?"
  (fact "with map"
    (unauthorized? unauthorized) => true
    (unauthorized? {:ok false :text "error.SOME_OTHER_REASON"}) => false
    (unauthorized? {:ok true}) => false)
  (fact "with exception"
    (fail! (:text unauthorized)) => unauthorized?
    (fail! "error.SOME_OTHER_REASON") =not=> unauthorized?))

(defn in-state? [state]
  (fn [application] (= (:state application) (name state))))

(fact "in-state?"
  ((in-state? :open) {:state "open"}) => true
  ((in-state? :open) {:state "closed"}) => false)

(defn ok? [resp]
  (= (:ok resp) true))

(def fail? (complement ok?))

(fact "ok?"
  (ok? {:ok true}) => true
  (ok? {:ok false}) => false)

(defn http200? [{:keys [status]}]
  (= status 200))

(defn http302? [{:keys [status]}]
  (= status 302))

(defn http303? [{:keys [status]}]
  (= status 303))

(defn http400? [{:keys [status]}]
  (= status 400))

(defn http401? [{:keys [status]}]
  (= status 401))

(defn http404? [{:keys [status]}]
  (= status 404))

(defn redirects-to [to {headers :headers :as resp}]
  (and (http302? resp) (ss/ends-with (headers "location") to)))

;;
;; DSLs
;;

(defn remove-krysp-xml-overrides [apikey org-id permit-type & provided-args]
  (let [org  (organization-from-minimal-by-id org-id)
        args (select-keys (get-in org [:krysp permit-type]) [:url :version])
        args (assoc args :permitType permit-type :username "" :password "")]
    (command apikey :set-krysp-endpoint
             (merge args (->arg-map provided-args)))))

(defn override-krysp-xml [apikey org-id permit-type overrides & provided-args]
  (let [org         (organization-from-minimal-by-id org-id)
        current-url (get-in org [:krysp permit-type :url])
        new-url     (str current-url "?overrides=" (json/generate-string overrides))
        args        (select-keys (get-in org [:krysp permit-type]) [:version])
        args        (assoc args :permitType permit-type :url new-url :username "" :password "")]
    (command apikey :set-krysp-endpoint
             (merge args (->arg-map provided-args)))))

(defn set-anti-csrf! [value]
  (fact (command pena :set-feature :feature "disable-anti-csrf" :value (not value)) => ok?))

(defn get-anti-csrf-from-store [store]
  (-> (get @store "anti-csrf-token") .getValue codec/url-decode))

(defn feature? [& feature]
  (boolean (-<>> :features (query pena) :features (into {}) (get <> (map name feature)))))

(defmacro with-anti-csrf [& body]
  `(let [old-value# (feature? :disable-anti-csrf)]
     (set-anti-csrf! true)
     (try
       (do ~@body)
       (finally
         (set-anti-csrf! (not old-value#))))))

(defn comment-application
  ([apikey id]
    (comment-application apikey id false nil))
  ([apikey id open?]
    {:pre [(instance? Boolean open?)]}
    (comment-application apikey id open? nil))
  ([apikey id open? to]
    (command apikey :add-comment :id id :text "hello" :to to :target {:type "application"} :openApplication open? :roles [])))

(defn change-application-urgency
  ([apikey id urgency]
    (command apikey :change-urgency :id id :urgency urgency)))

(defn add-authority-notice
  ([apikey id notice]
    (command apikey :add-authority-notice :id id :authorityNotice notice)))

(defn print-and-return [tag v]
  (println tag v)
  v)

(defn query-application
  "Fetch application from server.
   Asserts that application is found and that the application data looks sane.
   Takes an optional query function (query or local-query)"
  ([apikey id] (query-application query apikey id))
  ([f apikey id]
    {:pre  [apikey id]
     :post [(:id %)
            (:created %) (pos? (:created %))
            (:modified %) (pos? (:modified %))
            (contains? % :opened)
            (:permitType %)
            (contains? % :permitSubtype)
            (contains? % :infoRequest)
            (contains? % :openInfoRequest)
            (:primaryOperation %)
            (:secondaryOperations %)
            (:state %)
            (:municipality %)
            (:location %)
            (:organization %)
            (:address %)
            (:propertyId %)
            (:title %)
            (:auth %) (pos? (count (:auth %)))
            (:comments %)
            (:schema-version %)
            (:documents %)
            (:attachments %)
            (every? (fn [a] (or (empty? (:versions a)) (= (:latestVersion a) (last (:versions a))))) (:attachments %))]}
    (let [{:keys [application ok text]} (f apikey :application :id id)]
      (assert ok (str "not ok: " text))
      application)))

(defn query-bulletin
  "Fetch application bulletin from server.
   Asserts that bulletin is found and that the bulletin data looks sane.
   Takes an optional query function (query or local-query)"
  ([apikey id] (query-bulletin query apikey id))
  ([f apikey id]
    {:pre  [id]
     :post [(:id %)
            (:modified %) (pos? (:modified %))
            (:primaryOperation %)
            (:state %)
            (:bulletinState %)
            (:municipality %)
            (:location %)
            (:address %)
            (:propertyId %)
            (:documents %)
            (:attachments %)
            (every? (fn [a] (or (empty? (:versions a)) (= (:latestVersion a) (last (:versions a))))) (:attachments %))]}
    (let [{:keys [bulletin ok text]} (f apikey :bulletin :bulletinId id)]
      (assert ok (str "not ok: " text))
      bulletin)))

(defn- test-application-create-successful [resp app-id]
  (fact "Application created"
    resp => ok?
    app-id => truthy))

(defn create-app-id
  "Verifies that an application was created and returns it's ID"
  [apikey & args]
  {:post [%]}
  (let [{app-id :id :as resp} (apply create-app apikey args)]
    (test-application-create-successful resp app-id)
    app-id))

(defn create-application
  "Runs the create-application command, returns application."
  [apikey & args]
  (let [{app-id :id :as resp} (apply create-app apikey args)]
    (test-application-create-successful resp app-id)
    (query-application apikey app-id)))

(defn create-and-open-application
  "Creates a new application, opens it, and returns the application map"
  [apikey & args]
  (let [id (apply create-app-id apikey args)]
    (comment-application apikey id true)
    (query-application apikey id)))

(defn create-and-submit-application
  "Returns the application map"
  [apikey & args]
  (let [id    (apply create-app-id apikey args)
        resp  (command apikey :submit-application :id id)]
    (fact "Submit OK" resp => ok?)
    (query-application apikey id)))

(defn create-and-send-application
  "Returns the application map"
  [apikey & args]
  (let [id    (apply create-app-id apikey args)
        _     (command apikey :submit-application :id id)
        _     (command apikey :update-app-bulletin-op-description :id id :description "otsikko julkipanoon")
        resp  (command apikey :approve-application :id id :lang "fi")]
    (fact "Submit OK" resp => ok?)
    (query-application apikey id)))

(defn give-verdict-with-fn [f apikey application-id
                            & {:keys [verdictId status name given official agreement]
                               :or   {verdictId "aaa", status 1, name "Name", given 12300000000, official 12400000000 agreement false}}]
  (let [new-verdict-resp (f apikey :new-verdict-draft :id application-id :lang "fi")
        verdict-id (or (:verdictId new-verdict-resp))]
    (if-not (ok? new-verdict-resp)
      new-verdict-resp
      (do
       (f apikey :save-verdict-draft :id application-id :verdictId verdict-id :backendId verdictId :status status :name name :given given :official official :text "" :agreement agreement :section "" :lang "fi")
       (assoc
         (f apikey :publish-verdict :id application-id :verdictId verdict-id :lang "fi")
         :verdict-id verdict-id)))))

(defn give-verdict [apikey application-id & args]
  (apply give-verdict-with-fn command apikey application-id args))

(defn allowed? [action & args]
  (fn [apikey]
    (let [{:keys [ok actions]} (apply query apikey :allowed-actions args)
          allowed? (-> actions action :ok)]
      (and ok allowed?))))

(defn last-email
  "Returns the last email (or nil) and clears the inbox"
  ([] (last-email true))
  ([reset]
  {:post [(or (nil? %)
            (and (:to %) (:subject %) (not (.contains (:subject %) "???")) (-> % :body :html) (-> % :body :plain))
            (println %))]}
  (Thread/sleep 20) ; A little wait to allow mails to be delivered
  (let [{:keys [ok message]} (query pena :last-email :reset reset)] ; query with any user will do
    (assert ok)
    message)))

(defn sent-emails
  "Returns a list of emails and clears the inbox"
  []
  (Thread/sleep 20) ; A little wait to allow mails to be delivered
  (let [{:keys [ok messages]} (query admin :sent-emails :reset true)] ; query with any user will do
    (assert ok)
    messages))

(defn contains-application-link? [application-id role {body :body}]
  (let [[href r a-id] (re-find #"(?sm)http.+/app/fi/(applicant|authority)#!/application/([A-Za-z0-9-]+)" (:plain body))]
    (and (= role r) (= application-id a-id))))

(defn contains-application-link-with-tab? [application-id tab role {body :body}]
  (let [[href r a-id a-tab] (re-find #"(?sm)http.+/app/fi/(applicant|authority)#!/application/([A-Za-z0-9-]+)/([a-z]+)" (:plain body))]
    (and (= role r) (= application-id a-id) (= tab a-tab))))


;; API for local operations

(defn make-local-request [apikey]
  {:scheme "http", :user (usr/session-summary (find-user-from-minimal-by-apikey apikey))})

(defn- execute-local [apikey web-fn action & args]
  (let [params (->arg-map args)]
    (i18n/with-lang (:lang params)
      (binding [*request* (make-local-request apikey)]
        (web-fn (name action) params *request*)))))

(defn local-command [apikey command-name & args]
  (apply execute-local apikey api-common/execute-command command-name args))

(defn local-query [apikey query-name & args]
  (apply execute-local apikey api-common/execute-query query-name args))

(defn local-query-with-timestamp [ts apikey query-name & args]
  (binding [*created-timestamp-for-test-actions* ts]
    (apply execute-local apikey api-common/execute-query query-name args)))

(defn create-local-app
  "Runs the create-application command locally, returns reply map. Use ok? to check it."
  [apikey & args]
  (apply create-app-with-fn local-command apikey args))

(defn create-and-submit-local-application
  "Returns the application map"
  [apikey & args]
  (let [id    (:id (apply create-local-app apikey args))
        resp  (local-command apikey :submit-application :id id)]
    resp => ok?
    (query-application local-query apikey id)))

(defn give-local-verdict [apikey application-id & args]
  (apply give-verdict-with-fn local-command apikey application-id args))

(defn create-foreman-application [project-app-id apikey userId role difficulty]
  (let [{foreman-app-id :id} (command apikey :create-foreman-application :id project-app-id :taskId "" :foremanRole role :foremanEmail "")
        foreman-app          (query-application apikey foreman-app-id)
        foreman-doc          (domain/get-document-by-name foreman-app "tyonjohtaja-v2")]
    (command apikey :set-user-to-document :id foreman-app-id :documentId (:id foreman-doc) :userId userId :path "" :collection "documents")
    (command apikey :update-doc :id foreman-app-id :doc (:id foreman-doc) :updates [["patevyysvaatimusluokka" difficulty]])
    foreman-app-id))

(defn finalize-foreman-app [apikey authority foreman-app-id application?]
  (facts "Finalize foreman application"
    (command apikey :change-permit-sub-type :id foreman-app-id
             :permitSubtype (if application?  "tyonjohtaja-hakemus" "tyonjohtaja-ilmoitus")) => ok?
    (command apikey :submit-application :id foreman-app-id) => ok?
    (if application?
      (command authority :check-for-verdict :id foreman-app-id)
      (command authority :approve-application :lang :fi :id foreman-app-id)) => ok?))

(defn invite-company-and-accept-invitation [apikey app-id company-id company-admin-apikey]
  (command apikey :company-invite :id app-id :company-id company-id) => ok?
  (command company-admin-apikey :approve-invite :id app-id :invite-type :company))

(defn http-token-call
  ([token body]
   (let [url (str (server-address) "/api/token/" token)]
     (http-post url {:follow-redirects false
                  :throw-exceptions false
                  :content-type     :json
                  :body (json/encode body)})))
  ([token]
   (fact "Call api/token"
         (http-token-call token {:ok true}) => (contains {:status 200}))))

(defn token-from-email
  ([email]
   (token-from-email email (last-email)))
  ([email email-data]
   {:pre [(ss/not-blank? email)]}
   (fact {:midje/description (str "Read email for " email)}
     (s/index-of (:to email-data) email) => (complement neg?))
   (last (re-find #"http.+/app/fi/welcome#!/.+/([A-Za-z0-9-]+)"
                  (:plain (:body email-data))))))

(defn activation-email->token [email-address email]
  {:pre [(ss/not-blank? email-address)]}
  (fact {:midje/description (str "Read email for " email-address)}
    (s/index-of (:to email) email-address) => (complement neg?))
  (last (re-find #"http.+/app/security/activate/([A-Za-z0-9-]+)"
                 (:plain (:body email)))))

(defn login
  ([u p]
    (login u p {}))
  ([u p params]
  (get (decode-response
        (http-post (str (server-address) "/api/login")
                   (merge
                     {:follow-redirects false
                      :throw-exceptions false
                      :form-params {:username u :password p}}
                     params)))
       :body)))

;;
;; Stuffin' data in
;;

;; VTJ-PRT

(defn api-update-building-data-call [application-id params]
  (http-post (format "%s/rest/application/%s/update-building-data" (server-address) application-id)
             (merge params {:throw-exceptions false})))

;; attachments

(defn sign-attachment [apikey id attachmentId password]
  (let [uri (str (server-address) "/api/command/sign-attachments")
        resp (http-post uri
               {:headers {"content-type" "application/json;charset=utf-8"}
                :oauth-token apikey
                :body (json/encode {:id id
                                    :attachmentIds [attachmentId]
                                    :password password})
                :follow-redirects false
                :throw-exceptions false})]
    (facts "Signed succesfully"
      (fact "Status code" (:status resp) => 200))))

(defn- parse-attachment-id-from-location [location]
  (->> (ss/split (ss/suffix location "#")  #"&") ; currently only one parameter, but future proof for more
       (map #(ss/split % #"="))
       (reduce (fn [acc [k v]] (assoc acc (keyword k) v)) {})
       :attachmentId))

(defn upload-attachment
  "Returns attachment ID"
  [apikey application-id {attachment-id :id attachment-type :type operation-id :op-id} expect-to-succeed & {:keys [filename text] :or {filename "dev-resources/test-gif-attachment.gif", text ""}}]
  (let [uploadfile  (io/file filename)
        uri         (str (server-address) "/api/upload/attachment")
        resp        (http-post uri
                               {:oauth-token apikey
                                :multipart (remove nil?
                                             [{:name "applicationId"  :content application-id}
                                              {:name "text"           :content text}
                                              {:name "Content/type"   :content "image/gif"}
                                              {:name "attachmentType" :content (str
                                                                                 (get attachment-type :type-group "muut") "."
                                                                                 (get attachment-type :type-id "muu"))}
                                              {:name "operationId"    :content (or operation-id "")}
                                              (when attachment-id {:name "attachmentId"   :content attachment-id})
                                              {:name "upload"         :content uploadfile}])})
        location (get-in resp [:headers "location"])
        parsed-id (parse-attachment-id-from-location location)]
    (if expect-to-succeed
      (and
        (facts "Upload succesfully"
          (fact "Status code" (:status resp) => 302)
          (fact "location"    location => (contains "/lp-static/html/upload-success.html#")))
        (fact "Parsed id matches"
          (when attachment-id
            parsed-id => attachment-id))
        ; Return the attachment id, can be new or existing attachment
        parsed-id)
      (and
        (facts "Upload should fail"
          (fact "Status code" (:status resp) => 302)
          (fact "location"    location => #"/lp-static/html/upload-[\d\.]+\.html?.*errorMessage=.+"))
        ; Return the original id
        attachment-id))))

(defn upload-attachment-to-target
  "Returns attachment ID"
  [apikey application-id attachment-id expect-to-succeed target-id target-type & [attachment-type]]
  {:pre [target-id target-type]}
  (let [filename    "dev-resources/test-attachment.txt"
        uploadfile  (io/file filename)
        application (query-application apikey application-id)
        uri         (str (server-address) "/api/upload/attachment")
        resp        (http-post uri
                      {:oauth-token apikey
                       :multipart (remove nil?
                                    [{:name "applicationId"  :content application-id}
                                     {:name "Content/type"   :content "text/plain"}
                                     {:name "attachmentType" :content (or attachment-type "muut.muu")}
                                     (when attachment-id {:name "attachmentId"   :content attachment-id})
                                     {:name "upload"         :content uploadfile}
                                     {:name "targetId"       :content target-id}
                                     {:name "targetType"     :content target-type}])})
        location (get-in resp [:headers "location"])]
    (if expect-to-succeed
      (do
        (facts "upload to target succesfully"
          (fact "Status code" (:status resp) => 302)
          (fact "location"    location => (contains "/lp-static/html/upload-success.html#")))
        (parse-attachment-id-from-location location))
      (do
        (facts "upload to target should fail"
          (fact "Status code" (:status resp) => 302)
          (fact "location"    location => #"/lp-static/html/upload-[\d\.]+\.html?.*errorMessage=.+"))
        attachment-id))))

(defn upload-user-attachment [apikey attachment-type expect-to-succeed & [filename]]
  (let [filename    (or filename "dev-resources/test-attachment.txt")
        uploadfile  (io/file filename)
        uri         (str (server-address) "/api/upload/user-attachment")
        resp        (http-post uri
                               {:oauth-token apikey
                                :multipart [{:name "attachmentType"  :content attachment-type}
                                            {:name "files[]"         :content uploadfile}]})
        body        (:body (decode-response resp))]
    (if expect-to-succeed
      (facts "successful"
        resp => http200?
        body => ok?)
      (facts "should fail"
        body => fail?))
    body))

(defn get-attachment-ids [application] (->> application :attachments (map :id)))

(defn get-attachment-by-id [apikey application-id attachment-id]
  (att/get-attachment-info (query-application apikey application-id) attachment-id))

(defn upload-attachment-to-all-placeholders [apikey application]
  (doseq [attachment (:attachments application)]
    (upload-attachment apikey (:id application) attachment true)))

;; NOTE: For this to work properly,
;;       the :operations-attachments must be set correctly for organization (in minimal.clj)
;;       and also the :attachments for operation (in operations.clj).
(defn generate-attachment [{id :id :as application} apikey password]
  (let [aloitusoikeus? (= (-> application :primaryOperation :name) "aloitusoikeus")]
    (when-let [first-attachment (or
                                  (get-in application [:attachments 0])
                                  (if aloitusoikeus?
                                    {:type {:type-group "paapiirustus", :type-id "asemapiirros"}}))]
      (upload-attachment apikey id first-attachment true)
      (when-not aloitusoikeus?
        (sign-attachment apikey id (:id first-attachment) password)))))

(defn generate-construction-time-attachment [{id :id :as application} authority-apikey password]
  (let [attachment-type {:type-group "muut" :type-id "muu"}
        resp (command authority-apikey :create-attachments :id id :attachmentTypes [attachment-type] :group nil)
        attachment-id (-> resp :attachmentIds first)]
    (fact "attachment created"
      resp => ok?)
    (upload-attachment authority-apikey id {:id attachment-id :type attachment-type} true)
    (sign-attachment authority-apikey id attachment-id password)
    (fact "set attachment as construction time"
      (command sonja :set-attachment-as-construction-time :id id :attachmentId attachment-id :value true))) => ok?)

;; File upload

(defn upload-file
  "Upload file to raw upload-file endpoint."
  [apikey filename & {:keys [cookie-store]}]
  (let [uploadfile  (io/file filename)
        uri         (str (server-address) (if apikey
                                            "/api/raw/upload-file-authenticated"
                                            "/api/raw/upload-file"))]
    (:body
      (decode-response
        (http-post uri
                   {:oauth-token apikey
                    :multipart [{:name "files[]" :content uploadfile}]
                    :throw-exceptions false
                    :cookie-store cookie-store})))))

(defn- job-done? [resp]
  (= (get-in resp [:job :status]) "done"))

(defn- timeout? [resp]
  (= (get-in resp [:result]) "timeout"))

(defn poll-job [apikey command id version limit]
  (loop [version version retries 0]
    (let [resp (query apikey (keyword command) :jobId id :version version)]
      (cond
        (job-done? resp)  resp
        (timeout? resp)   (assoc resp :jobId id :ok false)
        (< limit retries) (merge resp {:ok false :desc "Retry limit exeeded"})
        :else (do (Thread/sleep 200)
                  (recur (get-in resp [:job :version]) (inc retries)))))))

(defn upload-file-and-bind
  "Uploads file and then bind using bind-attachments. To upload new file, specify metadata using filedata.
  If upload to existing attachment, filedata can be empty but :attachment-id should be defined."
  [apikey id filedata & {:keys [fails attachment-id]}]
  (let [file-id (get-in (upload-file apikey (or (:filename filedata) "dev-resources/test-attachment.txt")) [:files 0 :fileId])
        data (if attachment-id
               {:attachmentId attachment-id}
               (select-keys filedata [:type :group :target :contents :constructionTime :sign]))
        {job :job :as resp} (command apikey :bind-attachments :id id :filedatas [(assoc data :fileId file-id)])]
    (if-not fails
      (do
        (fact "Bind-attachments command OK" resp => ok?)
        (fact "Job id is returned" (:id job) => truthy))
      (do
        (fact "Bind-attachment should fail" resp => fail?)
        (when (or (string? fails) (keyword? fails))
          (fact "Bind-attachments expected failure" resp => (partial expected-failure? fails)))))
    (when (and (:ok resp) (not= "done" (:status job)))
      (poll-job apikey :bind-attachments-job (:id job) (:version job) 25) => ok?)
    file-id))

;; statements

(defn upload-attachment-for-statement [apikey application-id attachment-id expect-to-succeed statement-id]
  (upload-attachment-to-target apikey application-id attachment-id expect-to-succeed statement-id "statement"))

(defn get-statement-by-user-id [application user-id]
  (some #(when (= user-id (get-in % [:person :userId])) %) (:statements application)))

;; This has a side effect which generates a attachement to appliction
(defn generate-statement [application-id apikey]
  (let [resp (query sipoo :get-organizations-statement-givers) => ok?
        statement-giver (->> resp :data (some #(when (= (email-for-key apikey) (:email %)) %))) => truthy
        create-statement-result (command apikey :request-for-statement
                                  :functionCode nil
                                  :id application-id
                                  :selectedPersons [statement-giver]
                                  :saateText "saate"
                                  :dueDate 1450994400000) => ok?
        updated-application (query-application apikey application-id)
        statement-id (:id (get-statement-by-user-id updated-application (id-for-key apikey)))
        upload-statement-attachment-result (upload-attachment-for-statement apikey application-id "" true statement-id)
        give-statement-result (command apikey :give-statement
                                :id application-id
                                :statementId statement-id
                                :status "puollettu"
                                :lang "fi"
                                :text "Annanpa luvan urakalle.")]
    (query-application apikey application-id)))

(defn generate-documents [application apikey & [local?]]
  (doseq [document (:documents application)]
    (let [data    (tools/create-document-data (model/get-document-schema document) (partial tools/dummy-values (id-for-key apikey)))
          updates (tools/path-vals data)
          updates (map (fn [[p v]] [(butlast p) v]) updates)
          updates (map (fn [[p v]] [(s/join "." (map name p)) v]) updates)
          user-role (:role (find-user-from-minimal-by-apikey apikey))
          updates (filterv (fn [[path value]]
                             (try
                               (let [splitted-path (ss/split path #"\.")]
                                 (doc-persistence/validate-against-whitelist! document [splitted-path] user-role application)
                                 (doc-persistence/validate-readonly-updates! document [splitted-path]))
                               true
                               (catch Exception _
                                 false)))
                          updates)
          f (if local? local-command command)]
      (fact "Document is updated"
        (f apikey :update-doc
          :id (:id application)
          :doc (:id document)
          :updates updates) => ok?))))

;;
;; Vetuma
;;

(defn vetuma! [{:keys [userid firstname lastname] :as data}]
  (->
    (http-get
      (str (server-address) "/dev/api/vetuma")
      {:query-params (select-keys data [:userid :firstname :lastname])})
    decode-response
    :body))

(defn vetuma-stamp! [] ; Used by neighbor_itest
  (-> {:userid "123"
       :firstname "Pekka"
       :lastname "Banaani"}
    vetuma!
    :stamp))

;; File actions


(defn get-local-filename [directory file-prefix-or-pred]
  (let [pred (if (fn? file-prefix-or-pred)
               file-prefix-or-pred
               #(and (.startsWith (.getName %) file-prefix-or-pred)
                     (.endsWith (.getName %) ".xml")))]
    (if-let [filename (some->> (file-seq (io/file directory))
                               (filter pred)
                               (sort-by #(.getName %))
                               last
                               (.getName))]
      (str directory filename)
      (throw (AssertionError. (str "File not found: " directory file-prefix-or-pred ".xml"))))))

(def dev-password "Lupapiste")

(defn get-file-from-server
  ([user server file-to-get target-file-name]
    (timbre/info "sftp" (str user "@" server ":" file-to-get) target-file-name)
    (ssh-cli/sftp server :get file-to-get target-file-name :username user :password dev-password :strict-host-key-checking :no)
    target-file-name)
  ([user server file-prefix-or-some-pred target-file-name path-to-file]
    (try
      (let [some-pred (if (fn? file-prefix-or-some-pred)
                        file-prefix-or-some-pred
                        #(when (and (.startsWith (.getFilename %) file-prefix-or-some-pred) (.endsWith (.getFilename %) ".xml"))
                           (.getFilename %)))
            agent (ssh/ssh-agent {})
            session (ssh/session agent server {:username user :password dev-password :strict-host-key-checking :no})]
       (ssh/with-connection session
         (let [channel (ssh/ssh-sftp session)]
           (ssh/with-channel-connection channel
             (timbre/info "sftp: ls" path-to-file)
             (let [filename (some some-pred
                              (sort-by #(.getMTime (.getAttrs %)) > (ssh/sftp channel {} :ls path-to-file)))
                   file-to-get (str path-to-file filename)]
               (if filename
                 (do (timbre/info "sftp: get" file-to-get)
                     (ssh/sftp channel {} :get file-to-get target-file-name)
                     (timbre/info "sftp: done.")
                     target-file-name)
                 (throw (FileNotFoundException. (str "file not found from sftp, pred:" file-prefix-or-some-pred)))))))))
      (catch com.jcraft.jsch.JSchException e
        (error e (str "SSH connection " user "@" server))))))

(defn upload-area [apikey & [filename]]
  (let [filename    (or filename "dev-resources/sipoon_alueet.zip")
        uploadfile  (io/file filename)
        uri         (str (server-address) "/api/raw/organization-area")]
    (http-post uri
               {:oauth-token apikey
                :multipart [{:name "files[]" :content uploadfile}]
                :throw-exceptions false})))

(defn appeals-for-verdict [apikey app-id verdict-id]
  (-> (query apikey :appeals :id app-id)
      :data
      (get (keyword verdict-id))))

(defn ->xml
  "Transforms map into XML structure."
  [m]
  (for [[k v] m]
    {:tag (keyword k)
     :attrs nil
     :content (cond
                (map? v)        (->xml v)
                (sequential? v) (apply concat (map ->xml v))
                :default        [(str v)])}))

;;
;; Assignments
;;

(defn create-assignment [from to application-id targets desc]
  (command from :create-assignment
           :id            application-id
           :recipientId   to
           :targets       targets
           :description   desc))
(defn update-assignment [who id assignment-id recipient description]
  (command who :update-assignment
           :id id
           :assignmentId assignment-id
           :recipientId recipient
           :description description))

(defn complete-assignment [user assignment-id]
  (command user :complete-assignment :assignmentId assignment-id))

(defn get-user-assignments [apikey]
  (let [resp (query apikey :assignments)]
    (fact "assignments query ok"
      resp => ok?)
    (:assignments resp)))

(defn fetch-verdicts [& [{:keys [jms? wait-ms] :or {jms? false wait-ms 2000}}]]
  (let [resp (batchrun/fetch-verdicts-default {:jms? jms?})]
    (when jms?
      (Thread/sleep wait-ms))
    resp))
