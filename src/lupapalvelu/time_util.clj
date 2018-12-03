(ns lupapalvelu.time-util
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [sade.util :refer [to-finnish-date]]))

(def time-format (tf/formatter "dd.MM.YYYY"))

(defn tomorrow []
  (-> (t/today)
      (t/plus (t/days 1))
      (tc/to-date-time)))

(defn day-before [date]
  (t/plus date (t/days -1)))

(defn ->date [date-str]
  (tf/parse time-format date-str))

(defn ->date-str [date]
  (tf/unparse time-format date))

(defn tomorrow-or-later? [date-str]
  (println ">> tomorrow-or-later " date-str)
  (println "tomorrow as findate2: " (to-finnish-date (lupapalvelu.time-util/tomorrow)))
  (if date-str
    (not (t/before? (->date date-str) (lupapalvelu.time-util/tomorrow)))))
