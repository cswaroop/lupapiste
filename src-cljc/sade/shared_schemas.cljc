(ns sade.shared-schemas
  (:require [schema.core :refer [defschema] :as sc]
            [sade.validators :as v]))

(defn matches? [re s] (boolean (when (string? s) (re-matches re s))))

(defschema Nat
  "A schema for natural number integer"
  (sc/constrained sc/Int (comp not neg?) "Natural number"))

(def object-id-pattern #"^[0-9a-f]{24}$")

(defschema ObjectIdStr
  (sc/pred (partial matches? object-id-pattern) "ObjectId hex string"))

(defschema ObjectIdKeyword
  (sc/pred #(and (keyword? %)
                 (matches? object-id-pattern (name %)))
           "ObjectId hex keyword"))

(def uuid-pattern #"^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$")

(defschema UUIDStr
  (sc/pred (partial matches? uuid-pattern) "UUID hex string"))

(defschema FileId
  (sc/cond-pre UUIDStr ObjectIdStr))

(defn in-lower-case? [^String s]
  (if s
    (= s (.toLowerCase s))
    false))

(defn max-length-constraint [max-len]
  (fn [v] (<= (count v) max-len)))

(defschema Email
  "A simple schema for email"
  (sc/constrained sc/Str (every-pred v/valid-email? in-lower-case? (max-length-constraint 254)) "Email"))

(defschema StorageSystem
  (sc/enum :mongodb :s3))
