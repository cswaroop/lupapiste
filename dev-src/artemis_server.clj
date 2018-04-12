(ns artemis-server
  "Embedded ActiveMQ Artemis server"
  (:require [taoensso.timbre :refer [info infof]])
  (:import (org.apache.activemq.artemis.core.config.impl ConfigurationImpl)
           (org.apache.activemq.artemis.core.server ActiveMQServers ActiveMQServer)))

(info "Requiring ActiveMQ Artemis...")
(def conf (doto (ConfigurationImpl.)
            (.setPersistenceEnabled false)
            (.setJournalDirectory "target/artemis_journal")
            (.setSecurityEnabled false)
            (.addAcceptorConfiguration "invm" "vm://0")))

(def embedded-broker ^ActiveMQServer
  (ActiveMQServers/newActiveMQServer conf))

(defn start []
  (let [ts (double (System/currentTimeMillis))]
    (info "Starting Artemis...")
    (.start embedded-broker)
    (infof "Started embedded ActiveMQ Artemis message broker, took %.3f s" (/ (- (System/currentTimeMillis) ts) 1000))))
