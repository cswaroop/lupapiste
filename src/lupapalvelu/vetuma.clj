(ns lupapalvelu.vetuma
  (:use [clojure.set :only [rename-keys]]
        [noir.core :only [defpage]]
        [noir.response :only [redirect status json]]
        [hiccup.core :only [html]]
        [clj-time.local :only [local-now]]
        [hiccup.form]
        [lupapalvelu.log])
  (:require [digest]
            [clojure.string :as string]
            [noir.request :as request]
            [noir.session :as session]
            [clj-time.core :as time]
            [clj-time.format :as format]))

;;
;; Configuration
;;

(def ^:dynamic *path-session-key* "vetuma-return-paths")
(def ^:dynamic *user-session-key* "vetuma-user")

(def request-mac-keys  [:rcvid :appid :timestmp :so :solist :type :au :lg :returl :canurl :errurl :ap :extradata :appname :trid])
(def response-mac-keys [:rcvid :timestmp :so :userid :lg :returl :canurl :errurl :subjectdata :extradata :status :trid :vtjdata])

(def constants
  {:url       "https://testitunnistus.suomi.fi/VETUMALogin/app"
   :rcvid     "***REMOVED***1"
   :appid     "VETUMA-APP2"
   :so        "6"
   :solist    "6,11"
   :type      "LOGIN"
   :au        "EXTAUTH"
   :lg        "fi"
   :returl    "{host}vetuma"
   :canurl    "{host}vetuma/cancel"
   :errurl    "{host}vetuma/error"
   :ap        "***REMOVED***"
   :appname   "Lupapiste"
   :extradata "VTJTT=VTJ-VETUMA-Perus"
   :key       "***REMOVED***"})

;;
;; Helpers
;;

(def time-format (format/formatter-local "yyyyMMddHHmmssSSS"))

(defn- timestamp [] (format/unparse time-format (local-now)))

(defn- generate-stamp [] (apply str (take 20 (repeatedly #(rand-int 10)))))

(defn- keys-as [f m] (into {} (for [[k v] m] [(f k) v])))
(defn- keys-as-strings [m] (keys-as #(.toUpperCase (name %)) m))
(defn- keys-as-keywords [m] (keys-as #(keyword (.toLowerCase %)) m))

(defn- logged [m] (info "%s" (str m)) m)

(defn- apply-template [v m]
  (string/replace v #"\{(\w+)\}"
                  (fn [[_ word]]
                    (or (m (keyword word)) ""))))

(defn- apply-templates [m]
  (into {} (for [[k v] m] [k (apply-template v m)])))

;;
;; Mac
;;

(defn- secret [{rcvid :rcvid key :key}] (str rcvid "-" key))
(defn- mac [data]  (-> data digest/sha-256 .toUpperCase))

(defn- mac-of [m keys]
  (->
    (for [k keys] (k m))
    vec
    (conj (secret m))
    (conj "")
    (->> (string/join "&"))
    mac))

(defn- with-mac [m] (merge m {:mac (mac-of m request-mac-keys)}))
(defn- mac-verified [m] (if (= (:mac m) (mac-of m response-mac-keys)) m {}))

;;
;; response parsing
;;

(defn- extract-subjectdata [{s :subjectdata}]
  (-> s
    (string/split #", ")
    (->> (map #(string/split % #"=")))
    (->> (into {}))
    keys-as-keywords
    (rename-keys {:etunimi :firstname})
    (rename-keys {:sukunimi :lastname})))

(defn- extract-userid [{s :extradata}]
  {:userid (last (string/split s #"="))})

(defn- extract-request-id [{id :trid}]
  {:stamp id})

(defn- user-extracted [m]
  (merge (extract-subjectdata m)
         (extract-userid m)
         (extract-request-id m)))

;;
;; Request & Response mapping to clojure
;;

(defn- request-data [host]
  (-> constants
    (assoc :trid (generate-stamp))
    (assoc :timestmp (timestamp))
    (assoc :host  host)
    apply-templates
    logged
    with-mac
    (dissoc :key)
    keys-as-strings))

(defn- parsed [m]
  (-> m
    keys-as-keywords
    (assoc :key (:key constants))
    mac-verified
    (dissoc :key)))

;;
;; Persistent storage
;;

(defonce mem (atom {}))

;;
;; Web stuff
;;

(defn- field [[k v]]
  (hidden-field k v))

(defn- non-local? [& rest] (some #(not= -1 (.indexOf % ":")) rest))

(defn host []
  (let [request (request/ring-request)]
    (str (name (:scheme request)) "://" (get-in request [:headers "host"]) "/")))

; TODO: does not strip unneeded parameters
(defpage "/vetuma" {:keys [success cancel error] :as paths}
  (if (non-local? success cancel error)
    (status 400 (format "invalid return paths: %s" paths))
    (do
      (session/put! *path-session-key* paths)
      (html
        (form-to [:post (:url constants)]
          (map field (request-data (host)))
          (submit-button "submit"))))))

(defpage [:post "/vetuma"] []
  (let [user (-> (:form-params (request/ring-request))
               logged
               parsed
               user-extracted
               logged)]
    (session/put! *user-session-key* user)
    (swap! mem assoc (:stamp user) user)
    (redirect (:success (session/get *path-session-key*)))))

(defpage [:post "/vetuma/:status"] {status :status}
  (redirect ((session/get *path-session-key*) (keyword status))))

(defpage "/vetuma/user" []
  (json (session/get *user-session-key*)))

(defpage "/vetuma/stamp/:stamp" {:keys [stamp]}
  (let [user (@mem stamp)]
    (swap! mem dissoc stamp)
    (json user)))