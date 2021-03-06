(ns pqlserver.http
  "Custom code for HTTP communication"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async]
            [clojure.java.jdbc :as jdbc])
  (:import [java.io IOException]))

(defn chan-seq!!
  "Create a lazy sequence of channel takes"
  [c]
  (lazy-seq (when-let [v (async/<!! c)]
              (cons v (chan-seq!! c)))))

(defn query
  [pool query]
  (jdbc/with-db-connection [conn {:datasource pool}]
    (jdbc/query conn query)))

(defn query->chan
  "Load a resultset into an async channel, blocking on puts. This is assumed
   to be called outside the main thread.

   Streaming is accomplished by performing a reduce over a jdbc reducible-query
   stream. The reduce is executed purely for side-effects, so its initial value
   is irrelevant. Query cancellation is managed with the kill? channel. Putting
   a value to kill? will abort the query and exit the future cleanly.

   I'm not sure about the performance overhead of doing this on channels, but
   it seems quick enough to me at the moment."
  [pool query result-chan kill?]
  (jdbc/with-db-connection [conn {:datasource pool}]
    (try
      (reduce (fn [_ record]
                (async/alt!!
                  [kill?]
                  ([v _]
                   (log/debugf "Result stream received signal %s; closing" v)
                   (throw (Exception.)))
                  [[result-chan record]]
                  ([_ _])))
              ::init
              (jdbc/reducible-query conn query {:fetch-size 100}))
      ;; Eat the exception; it was just for control flow.
      (catch Exception e)
      (finally
        (async/close! kill?)
        (async/close! result-chan)))))
