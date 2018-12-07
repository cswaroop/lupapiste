(ns lupapalvelu.invoices
  "A common interface for accessing invoices and related data"
  (:require [lupapalvelu.mongo :as mongo]
            [monger.operators :refer [$in]]
            [schema.core :as sc]
            [sade.core :refer [ok fail] :as sade]
            [sade.schemas :as ssc]
            [taoensso.timbre :refer [trace tracef debug debugf info infof
                                     warn warnf error errorf fatal fatalf]]
            [lupapalvelu.money-schema :refer [MoneyResponse]]
            [lupapalvelu.money :refer [sum-with-discounts ->currency ->MoneyResponse discounted-value multiply-amount]]
            [lupapalvelu.user :as user]
            [lupapalvelu.domain :refer [get-application-no-access-checking]]
            [lupapalvelu.application-schema :refer [Operation]]
            [lupapiste-invoice-commons.states :refer [state-change-direction move-to-state]]
            [lupapalvelu.invoices.transfer-batch :refer [add-invoice-to-transfer-batch]]
            [lupapalvelu.invoices.schemas :refer [User
                                                  PriceCatalogue
                                                  DiscountPercent
                                                  InvoiceRowType
                                                  InvoiceRowUnit
                                                  InvoiceRow
                                                  InvoiceOperation
                                                  Invoice
                                                  InvoiceId
                                                  InvoiceInsertRequest
                                                  CatalogueRow
                                                  PriceCatalogue
                                                  ->invoice-user]]))
(def state-actions {:add-to-transfer-batch add-invoice-to-transfer-batch})
(defn fetch-invoice [invoice-id]
  (mongo/by-id :invoices invoice-id))

(defn validate-insert-invoice-request [{{invoice-data :invoice} :data :as command}]
  (debug ">> validate-insert-invoice request data: " invoice-data)
  (when (sc/check InvoiceInsertRequest invoice-data)
    (fail :error.invalid-invoice)))

(defn validate-invoice [invoice]
  (debug ">> validate-invoice: " invoice)
  (sc/validate Invoice invoice))


(defn sum-single-row [row]
  (let [sum-by-units (multiply-amount (:units row) (:price-per-unit row))
        discount-percent (:discount-percent row)]
    {:without-discount (->MoneyResponse sum-by-units)
     :with-discount (-> sum-by-units
                        (discounted-value discount-percent)
                        ->MoneyResponse)}))

(defn- merge-invoice-rows [rows]
  (map (fn [row]
         {:row-total
          (multiply-amount (:units row) (:price-per-unit row))
          :discount-percent
          (:discount-percent row)}) rows))

(defn sum-invoice [invoice]
  (let [rows (flatten (map :invoice-rows (:operations invoice)))
        merged-invoice-rows (merge-invoice-rows rows)]
    (assoc invoice :sum (sum-with-discounts :row-total :discount-percent merged-invoice-rows))))

(defn- enrich-sums-invoice-row [row]
  (assoc row :sums (sum-single-row row)))

(defn- enrich-rows-in-operations [invoice]
  (let [operations-from-invoice (:operations invoice)
        enriched-operations (map (fn [operation]
                                   (let [invoice-rows (:invoice-rows operation)]
                                     (assoc operation :invoice-rows
                                            (vec (map (fn [row]
                                                        (enrich-sums-invoice-row row))
                                                      invoice-rows))))) operations-from-invoice)
        p (info "eriched operations " enriched-operations)]
    (assoc invoice :operations (vec enriched-operations))))


(defn enrich-invoice-sums-before-save [invoice]
  (-> invoice
      (enrich-rows-in-operations)
      (sum-invoice)))

(defn create-invoice!
  [invoice]
  (debug ">> create-invoice! invoice-request: " invoice)
  (let [id (mongo/create-id)
        invoice-with-id (assoc invoice :id id)]
    (debug ">> invoice-with-id: " invoice-with-id)
    (->> invoice-with-id
         enrich-invoice-sums-before-save
         validate-invoice
         (mongo/insert :invoices))
    id))

(defn update-invoice!
  [{:keys [id] :as invoice}]
  (let [current-invoice (mongo/by-id "invoices" id)
        new-invoice (merge current-invoice (select-keys invoice [:operations :state]))
        state-change-direction (state-change-direction (:state current-invoice) (:state new-invoice) :backend)
        state-change-response (if (= state-change-direction (or :next :previous))
                                (move-to-state [:state] current-invoice (:state new-invoice) state-change-direction :backend)
                                {:actions []})
        actions (:actions state-change-response)
        update-result (->> new-invoice
                           enrich-invoice-sums-before-save
                           validate-invoice
                           (mongo/update-by-id "invoices" id))]
    (if (not-empty actions)
      (doseq [action actions]
        (if-let [action-fn (action state-actions)]
          (action-fn new-invoice (:created-by new-invoice)))))
    update-result))

(defn fetch-by-application-id [application-id]
  (mongo/select "invoices" {:application-id application-id}))

(sc/defn ^:always-validate  get-operations-from-application :- [Operation]
  "Returns a vector (primaryOperation being first) of operations from application, by combining primary and secondary operations to one seq"
  [application]
  (let [primary-operation (:primaryOperation application)
        secondary-operations (:secondaryOperations application)]
    (concat [primary-operation] secondary-operations)))

(defn fetch-application-operations [application-id]
  (let [application (get-application-no-access-checking application-id)]
    (get-operations-from-application application)))

(defn fetch-invoices-for-organizations [organization-ids]
  (mongo/select :invoices {:organization-id {$in organization-ids}}))

(defn get-user-orgs-having-role [user role]
  (->> (:orgAuthz user)
       (filter (fn [[org-id roles]]
                 (roles (keyword role))))
       (map (comp name first))))

(defn get-doc [doc-id docs]
  (some (fn [{:keys [id] :as doc}]
          (if (= id doc-id)
            doc))
        docs))

(defn enrich-org-data [user-orgs {:keys [organization-id] :as invoice}]
  (let [organization (get-doc organization-id user-orgs)
        localized-names (:name organization)]
    (assoc-in invoice [:enriched-data :organization :name] localized-names)))

(defn fetch-application-data [application-ids projection]
  (mongo/select :applications {:_id {$in application-ids}} projection))

(defn enrich-application-data [applications {:keys [application-id] :as invoice}]
  (let [application (get-doc application-id applications)
        address (:address application)]
    (assoc-in invoice [:enriched-data :application :address] address)))
