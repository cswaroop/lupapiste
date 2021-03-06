(ns lupapalvelu.ui.pate.service
  (:require [lupapalvelu.ui.common :as common]
            [lupapalvelu.ui.hub :as hub]
            [lupapalvelu.ui.pate.state :as state]
            [sade.shared-util :as util]))

(defn fetch-template-list []
  (common/query "verdict-templates"
                #(reset! state/template-list (:verdict-templates %))
                :org-id @state/org-id))

(defn- list-update-response [callback]
  (fn [response]
    (fetch-template-list)
    (callback response)
    (hub/send "pate::verdict-templates-changed")))

(defn update-changes-and-errors
  "Returns a success callback function that updates the state according
  to the command response (e.g., changes, removals and errors). container*
  is a 'top-level' atom that contains the info property (including
  modified and filled?)."
  [container* {:keys [state path]}]
  (fn [{:keys [modified changes errors removals filled]}]
    (swap! state (fn [state]
                   (let [state (as-> state $
                                 (reduce (fn [acc [k v]]
                                           (assoc-in acc (map keyword k) v))
                                         $
                                         changes)
                                 (reduce (fn [acc k]
                                           (let [[x & _
                                                  :as path] (map keyword k)
                                                 pruned     (util/dissoc-in acc path)]
                                             ;; Make sure that the
                                             ;; top-level map still
                                             ;; exists
                                             (cond-> pruned
                                               (-> pruned x nil?)
                                               (assoc x {}))))
                                         $
                                         removals))]
                     (reduce (fn [acc [k v]]
                               (assoc-in acc
                                         (cons :_errors
                                               (map keyword k))
                                         v))
                             (util/dissoc-in state
                                             (cons :_errors
                                                   (map keyword path)))
                             errors))))
    (swap! container* (fn [container]
                        (cond-> container
                          modified            (assoc-in [:info :modified] modified)
                          (not (nil? filled)) (assoc-in [:info :filled?] filled))))))

(defn fetch-categories [callback]
  (common/query "verdict-template-categories"
                #(do
                   (reset! state/categories (:categories %))
                   (callback @state/categories))
                :org-id @state/org-id))

(defn publish-template [template-id callback]
  (common/command {:command "publish-verdict-template"
                   :success (list-update-response callback)}
                  :template-id template-id
                  :org-id @state/org-id))

(defn save-draft-value [template-id path value callback]
  (common/command {:command "save-verdict-template-draft-value"
                   :success callback}
                  :template-id template-id
                  :path path
                  :value value
                  :org-id @state/org-id))

(defn fetch-template [template-id callback]
  (common/query :verdict-template
                callback
                :template-id template-id
                :org-id @state/org-id))

(defn fetch-updated-template [template-id callback]
  (common/command {:command  :update-and-open-verdict-template
                   :success callback}
                  :template-id template-id
                  :org-id @state/org-id))

(defn new-template [category callback]
  (common/command {:command "new-verdict-template"
                   :success (list-update-response callback)}
                  :category category
                  :lang (common/get-current-language)
                  :org-id @state/org-id))

(defn set-template-name [template-id name callback]
  (common/command {:command "set-verdict-template-name"
                   :success (list-update-response callback)}
                  :template-id template-id
                  :name name
                  :org-id @state/org-id))

(defn toggle-delete-template [template-id delete callback]
  (common/command {:command "toggle-delete-verdict-template"
                   :success (list-update-response callback)}
                  :template-id template-id
                  :delete delete
                  :org-id @state/org-id))

(defn copy-template [template-id callback]
  (common/command {:command "copy-verdict-template"
                   :success (list-update-response callback)}
                  :template-id template-id
                  :org-id @state/org-id))

(defn settings [category callback]
  (common/query "verdict-template-settings"
                callback
                :category category
                :org-id @state/org-id))

(defn save-settings-value [category path value callback]
  (common/command {:command "save-verdict-template-settings-value"
                   :success callback}
                  :category category
                  :path path
                  :value value
                  :org-id @state/org-id))

;; Phrases

(defn fetch-organization-phrases []
  (common/query "organization-phrases"
                #(reset! state/phrases (get % :phrases []))
                :org-id @state/org-id))

(defn fetch-application-phrases [app-id]
  (common/query "application-phrases"
                #(reset! state/phrases (get % :phrases []))
                :id app-id))

(defn fetch-custom-application-phrases [app-id]
  (common/query "custom-application-phrase-categories"
                #(reset! state/custom-phrase-categories (get % :custom-categories []))
                :id app-id))

(defn fetch-custom-organization-phrases []
  (common/query "custom-organization-phrase-categories"
                #(reset! state/custom-phrase-categories (get % :custom-categories []))
                :org-id @state/org-id))

(defn upsert-phrase [phrase-map callback]
  (apply common/command
         "upsert-phrase"
         (fn []
           (concat (fetch-organization-phrases)
                   (fetch-custom-organization-phrases))
           (callback))
         (flatten (into [:org-id @state/org-id] phrase-map))))

(defn delete-phrase [phrase-id callback]
  (common/command {:command "delete-phrase"
                   :success (fn []
                              (fetch-organization-phrases)
                              (callback))}
                  :phrase-id phrase-id
                  :org-id @state/org-id))

(defn save-phrase-category [category]
  (common/command {:command "save-phrase-category"
                   :success (fn []
                             (fetch-custom-organization-phrases))}
                  :category category
                  :org-id @state/org-id))

(defn delete-phrase-category [category]
  (common/command {:command "delete-phrase-category"
                   :success (fn [_]
                             (fetch-custom-organization-phrases))}
                  :category category
                  :org-id @state/org-id))

;; Application verdict templates

(defn fetch-application-verdict-templates [app-id]
  (common/query "application-verdict-templates"
                #(reset! state/template-list (:templates %))
                :id app-id))

;; Verdicts

(defn fetch-verdict-list [app-id]
  (common/query "pate-verdicts"
                #(reset! state/verdict-list (:verdicts %))
                :id app-id))

(defn new-verdict-draft
  ([app-id template-id callback]
    (new-verdict-draft app-id template-id callback nil))
  ([app-id template-id callback replacement-id]
    (common/command {:command "new-pate-verdict-draft"
                     :success callback}
                    :id app-id
                    :template-id template-id
                    :replacement-id replacement-id)))

(defn copy-verdict-draft
  [app-id callback replacement-id]
   (common/command {:command "copy-pate-verdict-draft"
                    :success callback}
                   :id app-id
                   :replacement-id replacement-id))

(defn new-legacy-verdict-draft [app-id callback]
  (common/command {:command "new-legacy-verdict-draft"
                   :success callback}
                  :id app-id))

(defn open-verdict [app-id verdict-id callback]
  (common/query "pate-verdict"
                callback
                :id app-id
                :verdict-id verdict-id))

(defn fetch-appeals [app-id verdict-id]
  (when (state/auth? :appeals)
    (common/query :appeals
                  #(->> %
                        :data
                        ((keyword verdict-id))
                        (map (fn [{:keys [giver appellant] :as a}]
                               (assoc a :author (or giver appellant))))
                        (reset! state/appeals))
                  :id app-id)))

(declare batch-job)

(defn upsert-appeal [app-id verdict-id params wait?* callback]
  (apply (partial common/command
                  {:command               :upsert-pate-appeal
                   :show-saved-indicator? true
                   :waiting?              wait?*
                   :success               (fn [res]
                                            (reset! wait?* true)
                                            (batch-job (fn [{:keys [pending]}]
                                                         (when (empty? pending)
                                                           (fetch-appeals app-id verdict-id)
                                                           (callback)
                                                           (reset! wait?* false)))
                                                       res))}
                  :id app-id
                  :verdict-id verdict-id)
         (->> (util/filter-map-by-val identity params)
              (mapcat identity))))

(defn delete-appeal [app-id verdict-id appeal-id]
  (common/command {:command               :delete-pate-appeal
                   :show-saved-indicator? true
                   :success               #(fetch-appeals app-id verdict-id)}
                  :id app-id
                  :verdict-id verdict-id
                  :appeal-id appeal-id))

(defn open-published-verdict [app-id verdict-id callback]
  (common/query "published-pate-verdict"
                callback
                :id app-id
                :verdict-id verdict-id)
  (fetch-appeals app-id verdict-id))

(defn delete-verdict [app-id {:keys [id published legacy? category]}]
  (let [backing-system? (util/=as-kw category :backing-system)]
    (common/command {:command (cond
                                legacy?         :delete-legacy-verdict
                                backing-system? :delete-verdict
                                :else           :delete-pate-verdict)
                     :success #(do (fetch-verdict-list app-id)
                                   (if published
                                     (js/repository.load app-id)
                                     (js/lupapisteApp.services.attachmentsService.queryAll))
                                   (state/refresh-application-auth-model app-id))}
                   :id app-id
                   :verdict-id id)))

(defn check-for-verdict [app-id waiting?* callback]
  (common/command {:command :check-for-verdict
                   :waiting? waiting?*
                   :success (fn [response]
                              (fetch-verdict-list app-id)
                              (js/repository.load app-id)
                              (state/refresh-application-auth-model app-id)
                              (state/refresh-verdict-auths app-id)
                              (js/lupapisteApp.services.attachmentsService.queryAll)
                              (callback response))}
                  :id app-id))

(defn edit-verdict [app-id verdict-id path value callback]
  (common/command {:command "edit-pate-verdict"
                   :success (fn [response]
                              (state/refresh-verdict-auths app-id
                                                           {:verdict-id verdict-id})
                              (callback response))}
                  :id app-id
                  :verdict-id verdict-id
                  :path path
                  :value value))

(defn publish-and-reopen-verdict
  ([app-id {verdict-id :id legacy? :legacy?} proposal? callback]
   (common/command {:command (cond
                               legacy? :publish-legacy-verdict
                               proposal? :publish-verdict-proposal
                               :else :publish-pate-verdict)
                    :success (fn []
                               (fetch-verdict-list app-id)
                               (if proposal?
                                 (open-verdict app-id verdict-id callback)
                                 (do
                                   (state/refresh-verdict-auths app-id {:verdict-id verdict-id})
                                   (open-published-verdict app-id verdict-id callback)
                                   (js/repository.load app-id))))}
                   :id app-id
                   :verdict-id verdict-id))
  ([app-id info callback]
    (publish-and-reopen-verdict app-id info false callback)))

(defn sign-contract [app-id verdict-id password category error-callback]
  (let [command (if (util/=as-kw :allu-contract category)
                  :sign-allu-contract
                  :sign-pate-contract)]
    (common/command {:command command
                     :success (fn []
                                (state/refresh-verdict-auths app-id
                                                             {:verdict-id verdict-id})
                                (fetch-verdict-list app-id)
                                (js/repository.load app-id))
                     :error error-callback}
                    :id app-id
                    :verdict-id verdict-id
                    :password password)))

(declare refresh-attachments)

(defn generate-pdf
  [app-id verdict-id waiting?*]
  (let [refresh-auths #(state/refresh-verdict-auths app-id
                                                    {:verdict-id verdict-id})]
    (common/command {:command :generate-pate-pdf
                    :waiting? waiting?*
                    :success (fn [{:keys [attachment-id]}]
                               (when attachment-id
                                 (refresh-auths)
                                 (refresh-attachments)))
                     :error (fn [_] (refresh-auths))}
                   :id app-id
                   :verdict-id verdict-id)))

;; Attachments

(defn delete-file [file-id]
  (common/command {:command :remove-uploaded-file}
                  :attachmentId file-id))

(defn bind-attachments
  "If draft? is true, then :bind-draft-attachments command is called."
  ([app-id filedatas callback draft?]
   (common/command {:command (if draft?
                               :bind-draft-attachments
                               :bind-attachments)
                    :success callback}
                   :id app-id
                   :filedatas filedatas))
  ([app-id filedatas callback]
   (bind-attachments app-id callback false)))

(defn bind-attachments-job [job-id version callback]
  (common/query :bind-attachments-job
                callback
                :jobId job-id
                :version version))

;; Signature request

(defn fetch-application-parties [app-id verdict-id callback]
  (common/query "pate-parties"
                callback
                :id app-id
                :verdict-id verdict-id))

(defn send-signature-request [app-id verdict-id signer-id]
  (common/command {:command :send-signature-request
                   :success #(fetch-verdict-list app-id)}
                  :id app-id
                  :verdict-id verdict-id
                  :signer-id signer-id))

(defn- batch-job [status-fn {:keys [job]}]
  (status-fn (when job
               (reduce (fn [acc {:keys [fileId status]}]
                         (let [k (case (keyword status)
                                   :done :done
                                   :error :error
                                   :pending)]
                           (update acc k #(cons fileId %))))
                       {:pending []
                        :error   []
                        :done    []}
                       (some-> job :value vals))))
  (when (util/=as-kw (:status job) :running)
    (bind-attachments-job (:id job)
                          (:version job)
                          (partial batch-job status-fn))))

(defn canonize-filedatas
  "Frontend filedatas to backend format."
  [filedatas]
  (map (fn [{:keys [file-id type] :as filedata}]
         (let [[type-group type-id] (util/split-kw-path type)]
           (merge (select-keys filedata [:target :contents :source])
                  {:fileId file-id
                   :type   {:type-group type-group
                            :type-id    type-id}})))
       filedatas))

(defn bind-attachments-batch
  "Convenience function that combines the binding and querying of the
  results. Arguments:

  app-id: Application id

  filedatas: List of maps with :file-id (string), :type (kw-path). Any
  additional keys (e.g., :contents, :target) are copied as is.

  status-fn: Will be called with updated status - [file-id]
  map. Status can be :pending, :done or :error. The callback will not
  be called after every file has terminal status (:done or :error). In
  other words, when the :pending list is empty. Nil (no job) argument
  denotes error (e.g., timeout).

  draft?: If true bind-draft-attachments command is used."
  ([app-id filedatas status-fn draft?]
   (bind-attachments app-id
                     (canonize-filedatas filedatas)
                     (partial batch-job status-fn)
                     draft?))
  ([app-id filedatas status-fn]
   (bind-attachments-batch app-id filedatas status-fn nil)))

;; Co-operation with the AttachmentsService

(defn attachments []
  (js->clj (js/lupapisteApp.services.attachmentsService.rawAttachments)
           :keywordize-keys true))

(defn refresh-attachments []
  (js/lupapisteApp.services.attachmentsService.queryAll)
  (js/lupapisteApp.services.attachmentsService.refreshAuthModels))
