(ns lupapalvelu.smoketest.user-smoke-tests
  (:require [lupapiste.mongocheck.core :refer [mongocheck]]
            [schema.core :as sc]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.user :as usr]))

(def user-keys (map #(if (keyword? %) % (:k %)) (keys usr/User)))

(mongocheck :users
  #(when-let [res (sc/check usr/User (mongo/with-id %))]
     (assoc (select-keys % [:username]) :errors res))
  user-keys)

(mongocheck :users
            (fn [{pid :personId source :personIdSource :as user}]
              (cond
                (and pid (sc/check usr/PersonIdSource source))
                (format "User %s has invalid person id source" (:username user))

                (and (usr/applicant? user)
                     (not (usr/company-user? user))
                     (not (usr/company-admin? user))
                     (not (usr/verified-person-id? user)))
                (format "Applicant user %s has unverified person id" (:username user))))
            :personId :personIdSource :username :company :role)

(mongocheck :users
  #(when (and (= "dummy" (:role %)) (not (:enabled %)) (-> % :private :password))
     (format "Dummy user %s has password" (:username %)))
  :role :private :username :enabled)
