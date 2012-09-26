(ns lupapalvelu.web
  (:use noir.core
        noir.request
        [noir.response :only [json redirect content-type]]
        lupapalvelu.log
        [clojure.walk :only [keywordize-keys]]
        monger.operators)
  (:require [noir.response :as resp]
            [noir.session :as session]
            [noir.server :as server]
            [cheshire.core :as json]
            [lupapalvelu.env :as env] 
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.fixture :as fixture]
            [lupapalvelu.command :as command]
            [lupapalvelu.singlepage :as singlepage]
            [lupapalvelu.security :as security]))

;;
;; Helpers
;;

(defn from-json []
  (json/parse-string (slurp (:body (ring-request))) true))

(defn current-user []
  "fetches the current user from 1) http-session 2) apikey from headers"
  (or (session/get :user) ((ring-request) :user)))

(defn logged-in? []
  (not (nil? (current-user))))

(defn logged-in-as-authority? []
  (and logged-in? (= :authority (keyword (:role (current-user))))))

(defmacro secured [path params & content]
  `(defpage ~path ~params
     (if (logged-in?)
       (do ~@content)
       (json {:ok false :text "user not logged in"})))) ; should return 401?

;;
;; REST API:
;;

(defpage "/rest/buildinfo" []
  (json {:ok true :data (read-string (slurp (.getResourceAsStream (clojure.lang.RT/baseLoader) "buildinfo.clj")))}))

(defpage "/rest/ping" []
  (json {:ok true}))

(secured "/rest/application" []
  (let [user (current-user)]
    (json
      (case (keyword (:role user))
        :applicant {:ok true :applications (mongo/select mongo/applications {:roles.applicant.userId (:id user)} ) }
        :authority {:ok true :applications (mongo/select mongo/applications {:authority (:authority user)})}
        {:ok false :text "invalid role to load applications"}))))

(secured "/rest/application/:id" {id :id}
    (let [user (current-user)]
	    (json
	      (case (keyword (:role user))
	        :applicant {:ok true :applications 
	                    (mongo/select mongo/applications {$and [{:_id id} {:roles.applicant.userId (:id user)}]} ) }
	        :authority {:ok true :applications 
	                    (mongo/select mongo/applications {$and [{:_id id} {:authority (:authority user)}]})}
	        {:ok false :text "invalid role to load application"}))))

(defpage "/rest/user" []
  (json
    (if-let [user (current-user)]
      {:ok true :user user}
      {:ok false :message "No session"})))

;;
;; Commands
;;

(defn create-command [data type]
  {:command (:command data)
   :user (current-user)
   :type type
   :created (System/currentTimeMillis) 
   :data (dissoc data :command) })

(defn- foreach-command []
  (let [json (from-json)]
    (map #(create-command (merge json {:command % :method :post})) (keys (command/get-actions)))))

(defn- validated [command]
  {(:command command) (command/validate command)})

(env/in-dev 
  (defpage "/rest/commands" []
    (json {:ok true :commands (command/get-actions)})))

  (defpage [:post "/rest/commands/valid"] []
    (json {:ok true :commands (into {} (map validated (foreach-command)))}))

(defpage [:post "/rest/command"] []
  (json (command/execute (create-command (from-json) :command))))

(defpage "/rest/query" []
  (json 
    (command/execute 
      (create-command 
        (keywordize-keys (:query-params (ring-request))) 
        :query))))

(defpage [:get "/rest/command"] []
  (json (command/validate (create-command (from-json)))))

(secured "/rest/genid" []
  (json {:ok true :id (mongo/create-id)}))

;;
;; Web UI:
;;

(defpage "/" [] (resp/redirect "/welcome#"))

(defpage "/welcome" [] (session/clear!) (singlepage/compose-singlepage-html "welcome"))
(defpage "/welcome.js" [] (singlepage/compose-singlepage-js "welcome"))
(defpage "/welcome.css" [] (singlepage/compose-singlepage-css "welcome"))

(defpage "/lupapiste" [] (if (logged-in?) (singlepage/compose-singlepage-html "lupapiste") (resp/redirect "/welcome#")))
(defpage "/lupapiste.js" [] (if (logged-in?) (singlepage/compose-singlepage-js "lupapiste") {:status 401}))
(defpage "/lupapiste.css" [] (if (logged-in?) (singlepage/compose-singlepage-css "lupapiste") {:status 401}))

(defpage "/authority" [] (if (logged-in-as-authority?) (singlepage/compose-singlepage-html "authority") (resp/redirect "/welcome#")))
(defpage "/authority.js" [] (if (logged-in-as-authority?) (singlepage/compose-singlepage-js "authority") {:status 401}))
(defpage "/authority.css" [] (if (logged-in-as-authority?) (singlepage/compose-singlepage-css "authority") {:status 401}))

;;
;; Login/logout:
;;

(def applicationpage-for {:applicant "/lupapiste"
                          :authority "/authority"})

(defpage [:post "/rest/login"] {:keys [username password]}
  (json
    (if-let [user (security/login username password)] 
      (do
        (info "login: successful: username=%s" username)
        (session/put! :user user)
        (let [userrole (keyword (:role user))]
          {:ok true :user user :applicationpage (userrole applicationpage-for) }))
      (do
        (info "login: failed: username=%s" username)
        {:ok false :message "Tunnus tai salasana on v\u00E4\u00E4rin."}))))

(defpage [:post "/rest/logout"] []
  (session/clear!)
  (json {:ok true}))

;; 
;; Apikey-authentication
;;

(defn- parse [key value]
  (let [value-string (str value)]
    (if (.startsWith value-string key)
      (.trim (.substring value-string (.length key))))))

(defn apikey-authentication
  "Reads apikey from 'Auhtorization' headers, pushed it to :user request header
   'curl -H \"Authorization: apikey APIKEY\" http://localhost:8000/rest/application"
  [handler]
  (fn [request]
    (let [authorization (get-in request [:headers "authorization"])
          apikey        (parse "apikey" authorization)]
      (handler (assoc request :user (security/login-with-apikey apikey))))))

(server/add-middleware apikey-authentication)

;;
;; File upload/download:
;;

(defpage [:post "/rest/upload"] {applicationId :applicationId attachmentId :attachmentId name :name upload :upload}
  (debug "upload: %s: %s" name (str upload))
  (json
    (command/execute
      (create-command (assoc upload :command "upload-attachment" 
                                    :id applicationId
                                    :attachmentId attachmentId
                                    :name (or name ""))))))

(defpage "/rest/download/:attachmentId" {attachmentId :attachmentId}
  (debug "file download: attachmentId=%s" attachmentId)
  (if-let [attachment (mongo/download attachmentId)]
    {:status 200
     :body ((:content attachment))
     :headers {"Content-Type" (:content-type attachment)
               "Content-Length" (str (:content-length attachment))}}))

;;
;; Development thingies
;;

(env/in-dev

  (defpage "/fixture/:name" {name :name}
    (fixture/apply-fixture name)
    (str name " data set initialized"))

  (defpage "/fixture" []
    (json (keys @fixture/fixtures)))

  (defpage "/verdict" {:keys [id ok text]}
    (command/execute 
      (merge 
        (create-command {:command "give-application-verdict"}) 
        {:user (security/login-with-apikey "505718b0aa24a1c901e6ba24")
         :data {:id id :ok ok :text text}})))

  (def speed-bump (atom 0))  
  (server/add-middleware
    (fn [handler]
      (fn [request]
        (let [bump @speed-bump]
          (when (> bump 0)
            (warn "Hit speed bump %d ms: %s" bump (:uri request))
            (Thread/sleep bump)))
        (handler request)))))

