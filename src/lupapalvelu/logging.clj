(ns lupapalvelu.logging
  (:use [clojure.tools.logging]
        [clj-logging-config.log4j])
  (:import [org.apache.log4j DailyRollingFileAppender EnhancedPatternLayout]))

(def pattern "%-5p %d (%r) [%c %-3L]-25 - %X{sessionId} - %X{applicationId} - %m%n")

(defn daily-rolling-midnight-appender [pattern file]
  (DailyRollingFileAppender. (EnhancedPatternLayout. pattern) file "'.'yyyy-MM-dd"))

(def default {:level :debug :pattern pattern})

(set-loggers! ["sade" "lupapalvelu"] default
              "ontolog.excel"        default
              "events"               {:out (daily-rolling-midnight-appender pattern "target/logs/events.log")})

(defn unsecure-log-event [level event]
  (with-logs "events" level level
    (println event)))

(defn log-event [level event]
  (try
    (unsecure-log-event level event)
    (catch Exception e
      (error "can't write to event log:" event))))
