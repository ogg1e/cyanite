(ns so.grep.cyanite.http
  (:require [aleph.http            :as http]
            [ring.util.codec       :as codec]
            [so.grep.cyanite.store :as store]
            [cheshire.core         :as json]
            [lamina.core           :refer [enqueue]]
            [clojure.string        :refer [lower-case]]
            [clojure.tools.logging :refer [info error debug]]))

(def routes [[:paths   #"^/paths.*"]
             [:metrics #"^/metrics.*"]])

(defn now
  []
  (quot (System/currentTimeMillis) 1000))

(defn keywordized
  "Yield a map where string keys are keywordized"
  [params]
  (dissoc
   (->> (map (juxt (comp keyword lower-case key) val) params)
        (reduce merge {}))
   nil))

(defn find-best-rollup
  [from rollups]
  (let [within (fn [{:keys [rollup period] :as rollup-def}]
                 (and (>= (Long/parseLong from) (- (now) (* rollup period)))
                      rollup-def))]
    (some within (sort-by :rollup rollups))))

(defn assoc-params
  [{:keys [query-string] :as request}]
  (or
   (when-let [params (and (seq query-string)
                          (codec/form-decode query-string))]
     (assoc request
       :params (keywordized
                (cond (map? params) params
                      (string? params) {params nil}
                      :else {}))))
   (assoc request :params {})))

(defn match-route
  [{:keys [uri path-info] :as request} [action re]]
  (when (re-matches re (or path-info uri))
    action))

(defn assoc-route
  [request]
  (assoc request :action (some (partial match-route request) routes)))

(defmulti process :action)

(defmethod process :paths
  [{{:keys [query]} :params :as request}]
  (store/find-paths true query))

(defmethod process :metrics
  [{{:keys [from to path agg]} :params :keys [store rollups] :as request}]
  (debug "fetching paths: " path)
  (if-let [{:keys [rollup period]} (find-best-rollup from rollups)]
    (let [to    (if to (Long/parseLong to) (now))
          from  (Long/parseLong from)
          paths (mapcat (partial store/find-paths false)
                        (if (sequential? path) path [path]))]
      (store/fetch store (or agg "mean") paths rollup period from to))
    {:step nil :from nil :to nil :data []}))

(defmethod process :default
  [_]
  {:body {:error "unknown action" :status 404}})

(defn wrap-process
  [request rollups chan store]
  (debug "got request: " request)
  (enqueue
   chan
   (try
     (let [resp-body (process (assoc request
                                :store store
                                :rollups rollups))
           def-resp  {:status 200 :headers {"Content-Type" "application/json"}}
           resp      (assoc def-resp :body resp-body)]
       (update-in resp [:body] json/generate-string))
     (catch Exception e
       (error e "could not process request")
       {:status 500
        :headers {"Content-Type" "application/json"}
        :body (json/generate-string
               {:error (.getMessage e)
                :data  (ex-data e)})}))))

(defn start
  [{:keys [http store carbon] :as config}]
  (let [handler (fn [chan request]
                  (-> request
                      (assoc-params)
                      (assoc-route)
                      (wrap-process (:rollups carbon) chan store)))]
    (http/start-http-server handler http))
  nil)
