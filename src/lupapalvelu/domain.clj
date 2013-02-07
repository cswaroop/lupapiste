(ns lupapalvelu.domain)

(defn role-in-application [{roles :roles} user-id]
  (some (fn [[role {id :id}]] (when (= id user-id) role)) roles))

(defn has-role? [application user-id]
  (not (nil? (role-in-application application user-id))))

(defn has-auth? [{auth :auth} user-id]
  (or (some (partial = user-id) (map :id auth)) false))

(defn get-document-by-id
  "returns first document from application with the document-id"
  [{documents :documents} document-id]
  (first (filter #(= document-id (:id %)) documents)))

(defn get-document-by-name
  "returns first document from application by name"
  [{documents :documents} name]
  (first (filter #(= name (get-in % [:schema :info :name])) documents)))

(defn invited? [{invites :invites} email]
  (or (some #(= email (-> % :user :username)) invites) false))
