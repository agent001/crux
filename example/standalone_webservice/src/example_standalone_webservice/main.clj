(ns example-standalone-webservice.main
  (:require [crux.api :as api]
            [crux.io :as crux-io]
            [clojure.instant :as instant]
            [clojure.tools.logging :as log]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [yada.yada :refer [handler listener]]
            [hiccup2.core :refer [html]]
            [yada.resource :refer [resource]]
            [yada.resources.classpath-resource]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [example-standalone-webservice.backup :as backup])
  (:import [crux.api IndexVersionOutOfSyncException]
           java.io.Closeable
           [java.util Date UUID]
           java.text.SimpleDateFormat))

(defn- format-date [^Date d]
  (when d
    (.format ^SimpleDateFormat (.get ^ThreadLocal @#'instant/thread-local-utc-date-format) d)))

(defn index-handler
  [ctx {:keys [crux]}]
  (fn [ctx]
    (let [{:strs [vt tt]} (get-in ctx [:parameters :query])
          vt (if (seq vt)
               (instant/read-instant-date vt)
               (Date.))
          tt (when tt
               (instant/read-instant-date tt))
          tx-log (with-open [tx-log-cxt (api/new-tx-log-context crux)]
                   (vec (api/tx-log crux tx-log-cxt nil true)))]
      (str
       "<!DOCTYPE html>"
       (html
        [:html
         [:head
          [:meta {:charset "utf-8"}]
          [:link {:rel "stylesheet" :type "text/css" :href "/static/styles/normalize.css"}]
          [:link {:rel "stylesheet" :type "text/css" :href "/static/styles/main.css"}]
          [:title "Message Board"]]
         [:body
          [:header
           [:h2 [:a {:href "/"} "Message Board"]]]
          [:div.timetravel
           [:form
            {:action "/" :method "GET"}
            [:fieldset
             [:label {:for "valid-time"} "Valid time:"]
             [:input {:type "datetime-local" :value (str/replace (format-date vt) #"-00:00$" "") :name "vt"}]
             [:label {:for "tx-time"} "Transaction time:"]
             [:select
              {:id "tx-time" :name "tt"}
              (for [{:keys [crux.tx/tx-time]} (reverse tx-log)]
                [:option {:value (format-date tx-time)
                          :selected (= tt tx-time)} (format-date tx-time)])]]
            [:input {:type "submit" :value "Go"}]]]

          [:div.comments
           [:h3 "Comments"]
           [:ul
            (for [[message name created id edited]
                  (->> (api/q (api/db crux vt tt)
                              '{:find [m n c e ed]
                                :where [[e :message-post/message m]
                                        [e :message-post/name n]
                                        [e :message-post/created c]
                                        [e :message-post/edited ed]]})
                       (sort-by #(nth % 2)))]
              [:li
               [:dl
                [:dt "From:"] [:dd name]
                [:dt "Created:"] [:dd [:a {:href (str "/?vt=" (format-date created))} (format-date created)]]
                (when (not= created edited)
                  [:dt "Edited:"])
                (when (not= created edited)
                  [:dd [:a {:href (str "/?vt=" (format-date edited))} (format-date edited)]])]
               [:form.edit-comment {:action (str "/comment/" id) :method "POST"}
                [:fieldset
                 [:input {:type "text" :name "created" :value (format-date created) :hidden true}]
                 [:input {:type "text" :name "name" :value name :hidden true}]
                 [:textarea {:id (str "edit-message-" id) :name "message"} message]]
                [:input {:type "submit" :name "_action" :value "Edit"}]
                [:input {:type "submit" :name "_action" :value "Delete"}]
                [:input {:type "submit" :name "_action" :value "Delete History"}]
                [:input {:type "submit" :name "_action" :value "Evict"}]]])]]

          [:div.add-comment-box
           [:h3 "Add new comment"]
           [:form {:action "/comment" :method "POST"}
            [:fieldset
             [:label {:for "name"} "Name:"]
             [:input {:type "text" :id "name" :name "name"}]
             [:label {:for "message"} "Message:"]
             [:textarea {:cols "40" :rows "10" :id "message" :name "message"}]]
            [:input {:type "submit" :value "Submit"}]]]

          [:div.transaction-history
           [:h4 "Transaction History:"
            [:small "(Earliest first.)"]]
           [:table
            [:thead
             [:th (str :crux.tx/tx-id)]
             [:th (str :crux.tx/tx-time)]
             [:th (str :crux.tx/tx-ops)]]
            [:tbody
             (for [{:crux.tx/keys [tx-id tx-time tx-ops]} tx-log]
               [:tr
                [:td [:a {:href (str "/?tt=" (format-date tx-time))} tx-id]]
                [:td (format-date tx-time)]
                [:td (with-out-str
                       (pp/pprint tx-ops))]])]]]
          [:div.status
           [:h4 "Status:"]
           [:pre (with-out-str
                   (pp/pprint (api/status crux)))]]
          [:footer
           "© 2019 "
           [:a {:href "https://juxt.pro"} "JUXT"]]]])))))

(defn redirect-with-time [ctx valid-time transaction-time]
  (assoc (:response ctx)
         :status 302
         :headers {"location" (str "/?vt=" (format-date valid-time) "&tt=" (format-date transaction-time))}))

(defn post-comment-handler
  [ctx {:keys [crux]}]
  (let [{:keys [name message]} (get-in ctx [:parameters :form])]
    (let [id (UUID/randomUUID)
          now (Date.)
          {:keys [crux.tx/tx-time]}
          (api/submit-tx
           crux
           [[:crux.tx/put
             id
             {:crux.db/id id
              :message-post/created now
              :message-post/edited now
              :message-post/name name
              :message-post/message message}
             now]])]
      (redirect-with-time ctx now tx-time))))

(defn edit-comment-handler
  [ctx {:keys [crux]}]
  (let [{:keys [name message created _action]} (get-in ctx [:parameters :form])
        id (get-in ctx [:parameters :path :id])
        now (Date.)
        {:keys [crux.tx/tx-time]}
        (case (str/lower-case _action)
          "delete"
          (api/submit-tx
           crux
           [[:crux.tx/delete
             id
             now]])
          "delete history"
          (api/submit-tx
           crux
           [[:crux.tx/delete
             id
             (instant/read-instant-date created)
             now]])
          "evict"
          (api/submit-tx
           crux
           [[:crux.tx/evict
             id
             (instant/read-instant-date created)
             now]])
          "edit"
          (api/submit-tx
           crux
           [[:crux.tx/put
             id
             {:crux.db/id id
              :message-post/created (instant/read-instant-date created)
              :message-post/edited now
              :message-post/name name
              :message-post/message message}
             now]]))]
    (redirect-with-time ctx now tx-time)))

(defn application-resource
  [system]
  ["/"
   [[""
     (resource
      {:methods
       {:get {:produces "text/html"
              :response #(index-handler % system)}}})]
    ["comment"
     (resource
      {:methods
       {:post {:consumes "application/x-www-form-urlencoded"
               :parameters {:form {:name String
                                   :message String}}
               :produces "text/html"
               :response #(post-comment-handler % system)}}})]

    [["comment/" :id]
     (resource
      {:methods
       {:post {:consumes "application/x-www-form-urlencoded"
               :parameters {:form {:created String
                                   :name String
                                   :message String
                                   :_action String}}
               :produces "text/html"
               :response #(edit-comment-handler % system)}}})]
    ["static"
     (yada.resources.classpath-resource/new-classpath-resource "static")]]])

(def index-dir "data/db-dir-1")
(def log-dir "data/eventlog-1")

(def crux-options
  {:kv-backend "crux.kv.rocksdb.RocksKv"
   :bootstrap-servers "kafka-cluster-kafka-brokers.crux.svc.cluster.local:9092"
   :event-log-dir log-dir
   :db-dir index-dir
   :server-port 8080})

(def backup-options
  {:backend :shell
   :backup-dir "data/backup"
   :shell/backup-script "bin/backup.sh"
   :shell/restore-script "bin/restore.sh"})

(defn run-system [{:keys [server-port] :as options} with-system-fn]
  (with-open [crux-system (case (System/getenv "CRUX_MODE")
                            "LOCAL_NODE" (api/start-local-node options)
                            (api/start-standalone-system options))
              http-server
              (let [l (listener
                       (application-resource {:crux crux-system})
                       {:port server-port})]
                (log/info "started webserver on port:" server-port)
                (reify Closeable
                  (close [_]
                    ((:close l)))))]
    (with-system-fn crux-system)))

(defn -main []
  (try
    (backup/check-and-restore crux-options backup-options)
    (run-system
     crux-options
     (fn [crux-system]
       (while (not (.isInterrupted (Thread/currentThread)))
         (Thread/sleep (* 1000 60 60 1)) ;; every hour
         (backup/backup-current-version crux-system crux-options backup-options))))
    (catch IndexVersionOutOfSyncException e
      (crux-io/delete-dir index-dir)
      (-main))
    (catch Exception e
      (log/error e "what happened" (ex-data e)))))

(comment
  (def s (future
           (run-system
            crux-options
            (fn [_]
              (Thread/sleep Long/MAX_VALUE)))))
  (future-cancel s))