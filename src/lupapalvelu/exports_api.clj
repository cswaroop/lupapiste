(ns lupapalvelu.exports-api
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn error fatal]]
            [monger.operators :refer :all]
            [sade.strings :as ss]
            [sade.util :as util]
            [sade.core :refer [ok]]
            [lupapalvelu.action :refer [defexport] :as action]
            [lupapalvelu.application :as application]
            [lupapalvelu.exports :as exports :refer [application-to-salesforce exported-application validate-application-export-data]]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.domain :as domain]))


(defn- export [collection query fields]
  (ok collection (mongo/snapshot collection query fields)))

(defexport export-applications
  {:user-roles #{:trusted-etl}}
  [{{ts :modifiedAfterTimestampMillis} :data user :user}]
  (let [query (merge
                (domain/application-query-for user)
                {:primaryOperation.id {$exists true}}
                (when (ss/numeric? ts)
                  {:modified {$gte (Long/parseLong ts 10)}}))
        fields {:address 1 :applicant 1 :authority 1 :closed 1 :created 1 :convertedToApplication 1
                :infoRequest 1 :modified 1 :municipality 1 :opened 1 :openInfoRequest 1
                :primaryOperation 1 :secondaryOperations 1 :organization 1 :propertyId 1
                :permitSubtype 1 :permitType 1 :sent 1 :started 1 :state 1 :submitted 1
                :verdicts 1
                :documents.data.kaytto.kayttotarkoitus.value 1
                :documents.schema-info.op.id 1}
        raw-applications (mongo/snapshot :applications query fields)
        applications-with-operations (map
                                       (fn [a] (assoc a :operations (application/get-operations a)))
                                       raw-applications)]
    (ok :applications (map exported-application applications-with-operations))))

(defexport salesforce-export
  {:user-roles #{:trusted-salesforce}
   :on-success validate-application-export-data}
  [{{after  :modifiedAfterTimestampMillis
     before :modifiedBeforeTimestampMillis} :data user :user}]
  (let [query (merge
                (domain/application-query-for user)
                {:primaryOperation.id {$exists true}}
                (when (or (ss/numeric? after) (ss/numeric? before))
                  {:modified (util/assoc-when {}
                                              $gte (when after (Long/parseLong after 10))
                                              $lt  (when before (Long/parseLong before 10)))}))
        fields [:address :archived :closed :created
                :infoRequest :modified :municipality :opened :openInfoRequest :organization
                :primaryOperation :propertyId :permitSubtype :permitType
                :secondaryOperations :sent :started :state :submitted
                :documents.data.kaytto.kayttotarkoitus.value
                :documents.schema-info.op.id]
        raw-applications (mongo/snapshot :applications query fields)]
    (ok :applications (map application-to-salesforce raw-applications))))

(defexport export-archive-api-usage
  {:user-roles #{:trusted-salesforce}
   :on-success exports/validate-archive-api-export-data}
  [{{start-ts :startTimestampMillis
     end-ts   :endTimestampMillis} :data user :user}]
  (ok :documents (exports/archive-api-usage-to-salesforce start-ts end-ts)))


(defexport export-organizations
  {:user-roles #{:trusted-etl}}
  [_]
  (export :organizations {:scope.0 {$exists true}} [:name :scope]))
