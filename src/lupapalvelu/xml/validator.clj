(ns lupapalvelu.xml.validator
  (:require [taoensso.timbre :as timbre :refer [trace tracef debug debugf info warn warnf error errorf fatal]]
            [sade.strings :as s]
            [sade.core :refer :all]
            [clojure.java.io :as io]
            [sade.env :as env])
  (:import [java.io InputStream Reader StringReader]
           [javax.xml.transform.stream StreamSource]
           [javax.xml.validation SchemaFactory]
           [org.w3c.dom.ls LSResourceResolver LSInput]))

(def- schema-factory (SchemaFactory/newInstance "http://www.w3.org/2001/XMLSchema"))
(.setResourceResolver schema-factory
  (reify LSResourceResolver
    (^LSInput resolveResource [this ^String type, ^String namespaceURI, ^String publicId, ^String systemId, ^String baseURI]
      (let [filename (s/suffix baseURI "/")
            path (clojure.string/replace (s/suffix baseURI "classpath:") filename systemId)]
        (tracef "Loading XML schema resource 'classpath:%s' which was referenced by %s" path filename)
        (reify LSInput
          (^String getBaseURI [this] baseURI)
          (^InputStream getByteStream [this])
          (^boolean getCertifiedText [this] false)
          (^Reader getCharacterStream [this] (-> path io/resource io/input-stream io/reader))
          (^String getEncoding [this])
          (^String getPublicId [this] publicId)
          (^String getStringData [this])
          (^String getSystemId [this] systemId))))))

(defn- stream-source [filename]
  (let [ss (StreamSource. (clojure.lang.RT/resourceAsStream nil filename))]
    ; Fixes resource loading issue
    (.setSystemId ss (str "classpath:" filename))
    ss))

(def- xml-sources
  ["www.w3.org/2001/xml.xsd"
   "www.w3.org/1999/xlink.xsd"])

(def- public-schema-sources
  (conj xml-sources
    "schemas.opengis.net/gml/3.1.1/smil/smil20.xsd"
    "schemas.opengis.net/gml/3.1.1/base/gml.xsd"))

(def- yht-2_1_0
  (conj public-schema-sources
    "krysp/yhteiset-2.1.0.xsd"
    "krysp/rakennusvalvonta-2.1.2.xsd"
    "krysp/YleisenAlueenKaytonLupahakemus.xsd"
    "krysp/poikkeamispaatos_ja_suunnittelutarveratkaisu.xsd"))

(def- yht-2_1_1
  (conj public-schema-sources
    "krysp/yhteiset-2.1.1.xsd"
    "krysp/rakennusvalvonta-2.1.3.xsd"
    "krysp/poikkeamispaatos_ja_suunnittelutarveratkaisu-2.1.3.xsd"))

(def- yht-2_1_2
  (conj public-schema-sources
    "krysp/yhteiset-2.1.2.xsd"
    "krysp/rakennusvalvonta-2.1.4.xsd"
    "krysp/poikkeamispaatos_ja_suunnittelutarveratkaisu-2.1.4.xsd"))

(def- yht-2_1_3
  (conj public-schema-sources
    "krysp/yhteiset-2.1.3.xsd"
    "krysp/rakennusvalvonta-2.1.5.xsd"
    "krysp/poikkeamispaatos_ja_suunnittelutarveratkaisu-2.1.5.xsd"
    "krysp/YleisenAlueenKaytonLupahakemus-2.1.3.xsd"
    "krysp/ymparistoluvat-2.1.2.xsd"
    "krysp/maaAinesluvat-2.1.2.xsd"
    "krysp/vesihuoltolaki-2.1.3.xsd"
    "krysp/ilmoitukset-2.1.2.xsd"))

(def- yht-2_1_5
  (conj public-schema-sources
    "krysp/yhteiset-2.1.5.xsd"
    "krysp/rakennusvalvonta-2.1.5.xsd"
    "krysp/poikkeamispaatos_ja_suunnittelutarveratkaisu-2.2.0.xsd"
    "krysp/YleisenAlueenKaytonLupahakemus-2.2.0.xsd"
    "krysp/ymparistoluvat-2.2.0.xsd"
    "krysp/maaAinesluvat-2.2.0.xsd"
    "krysp/vesihuoltolaki-2.2.0.xsd"
    "krysp/ilmoitukset-2.2.0.xsd"))

(def- yht-2_1_6
  (conj public-schema-sources
    "krysp/yhteiset-2.1.6.xsd"
    "krysp/rakennusvalvonta-2.2.0.xsd"
    "krysp/poikkeamispaatos_ja_suunnittelutarveratkaisu-2.2.1.xsd"
    "krysp/YleisenAlueenKaytonLupahakemus-2.2.1.xsd"
    "krysp/ymparistoluvat-2.2.1.xsd"
    "krysp/maaAinesluvat-2.2.1.xsd"
    "krysp/vesihuoltolaki-2.2.1.xsd"
    "krysp/ilmoitukset-2.2.1.xsd"
    "krysp/osoitteet-2.1.1.xsd"))

(def- rakval-2_1_6
  (conj public-schema-sources
        "krysp/yhteiset-2.1.5.xsd"
        "krysp/rakennusvalvonta-2.1.6.xsd"))

(def- rakval-2_1_8
  (conj public-schema-sources
        "krysp/yhteiset-2.1.5.xsd"
        "krysp/rakennusvalvonta-2.1.8.xsd"))

(def- mkmu-1_0_1
  (conj public-schema-sources
        "krysp/yhteiset-2.1.6.xsd"
        "krysp/maankaytonmuutos-1.0.1.xsd"))

(def- kiito-1_0_2
  (conj public-schema-sources
        "krysp/yhteiset-2.1.6.xsd"
        "krysp/kiinteistotoimitus-1.0.2.xsd"))

(def- asianhallinta_1_1
   (conj xml-sources "asianhallinta/asianhallinta_1.1.xsd"))

(def- asianhallinta_1_2
   (conj xml-sources "asianhallinta/asianhallinta_1.2.xsd"))

(def- asianhallinta_1_3
      (conj xml-sources "asianhallinta/asianhallinta_1.3.xsd"))

(defn- create-validator [schemas]
  (.newValidator (.newSchema schema-factory (into-array (map stream-source schemas)))))

(def- common-validator-2_1_0 (create-validator yht-2_1_0))
(def- common-validator-2_1_1 (create-validator yht-2_1_1))
(def- common-validator-2_1_2 (create-validator yht-2_1_2))
(def- common-validator-2_1_3 (create-validator yht-2_1_3))
(def- common-validator-2_1_5 (create-validator yht-2_1_5))
(def- common-validator-2_1_6 (create-validator yht-2_1_6))

(def- asianhallinta-validator-1_1 (create-validator asianhallinta_1_1))
(def- asianhallinta-validator-1_2 (create-validator asianhallinta_1_2))
(def- asianhallinta-validator-1_3 (create-validator asianhallinta_1_3))

; mapping-common contains the same information.
; Perhaps the permit type -- version -mapping could
; be generated from a single source.

(def- rakval-validators
  {"2.1.2" common-validator-2_1_0
   "2.1.3" common-validator-2_1_1
   "2.1.4" common-validator-2_1_2
   "2.1.5" common-validator-2_1_3
   "2.1.6" (create-validator rakval-2_1_6)
   "2.1.8" (create-validator rakval-2_1_8)
   "2.2.0" common-validator-2_1_6})

(def- ya-validators
  {"2.1.2" common-validator-2_1_0
   "2.1.3" common-validator-2_1_3
   "2.2.0" common-validator-2_1_5
   "2.2.1" common-validator-2_1_6
   "ah-1.1" asianhallinta-validator-1_1
   "ah-1.2" asianhallinta-validator-1_2
   "ah-1.3" asianhallinta-validator-1_3})

(def- poik-validators
  {"2.1.2" common-validator-2_1_0
   "2.1.3" common-validator-2_1_1
   "2.1.4" common-validator-2_1_2
   "2.1.5" common-validator-2_1_3
   "2.2.0" common-validator-2_1_5
   "2.2.1" common-validator-2_1_6
   "ah-1.1" asianhallinta-validator-1_1
   "ah-1.2" asianhallinta-validator-1_2
   "ah-1.3" asianhallinta-validator-1_3})

(def- ymp-validators
  {"2.1.2" common-validator-2_1_3
   "2.2.1" common-validator-2_1_6
   "ah-1.1" asianhallinta-validator-1_1
   "ah-1.2" asianhallinta-validator-1_2
   "ah-1.3" asianhallinta-validator-1_3})

(def- mkmu-validators
  {"2.1.6" common-validator-2_1_6
   "1.0.1" (create-validator mkmu-1_0_1)
   "ah-1.1" asianhallinta-validator-1_1
   "ah-1.2" asianhallinta-validator-1_2
   "ah-1.3" asianhallinta-validator-1_3})

(def- kiito-validators
  {"2.1.6" common-validator-2_1_6
   "1.0.2" (create-validator kiito-1_0_2)
   "ah-1.1" asianhallinta-validator-1_1
   "ah-1.2" asianhallinta-validator-1_2
   "ah-1.3" asianhallinta-validator-1_3})

(def- schema-validators
  {:R   rakval-validators
   :P   poik-validators
   :YA  ya-validators
   :YI  ymp-validators
   :MAL ymp-validators
   :VVVL {"2.1.3" common-validator-2_1_3
          "2.2.1" common-validator-2_1_6
          "ah-1.1" asianhallinta-validator-1_1
          "ah-1.2" asianhallinta-validator-1_2
          "ah-1.3" asianhallinta-validator-1_3}
   :YL  ymp-validators
   :YM {"ah-1.1" asianhallinta-validator-1_1
        "ah-1.2" asianhallinta-validator-1_2
        "ah-1.3" asianhallinta-validator-1_3}
   :MM  mkmu-validators ; maankayton muutos aka kaavat
   :KT  kiito-validators
   :osoitteet {"2.1.1" common-validator-2_1_6}})

(def supported-versions-by-permit-type
  (reduce (fn [m [permit-type validators]] (assoc m permit-type (keys validators))) {} schema-validators))

(def supported-krysp-versions-by-permit-type
  (reduce (fn [m [k versions]] (assoc m k (filter #(Character/isDigit (.charAt % 0)) versions))) {} supported-versions-by-permit-type))

(def supported-asianhallinta-versions-by-permit-type
  (reduce (fn [m [k versions]] (assoc m k (filter #(s/starts-with % "ah") versions))) {} supported-versions-by-permit-type))

(defn validate
  "Throws an exception if the markup is invalid"
  [xml permit-type schema-version]
  (let [xml-source (if (string? xml)
                     (StreamSource. (StringReader. xml))
                     (StreamSource. xml))
        validator  (get-in schema-validators [(keyword permit-type) (name schema-version)])]
    (when-not validator
      (throw (IllegalArgumentException. (str "Unsupported schema version " schema-version " for permit type " permit-type))))

    (try
      (.validate validator xml-source)
      (catch Exception e
        (debugf "Validation error with permit-type %s, schema-version %s: %s" permit-type schema-version (.getMessage e))
        (throw e)))))
