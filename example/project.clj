(defproject kehaar-example "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [democracyworks/kehaar "0.11.1"]]
  :main kehaar-example.core
  :aliases {"streaming-producer" ["run" "-m" "kehaar-example.streaming.producer"]
            "streaming-consumer" ["run" "-m" "kehaar-example.streaming.consumer"]
            "job-instigator" ["run" "-m" "kehaar-example.jobs.instigator"]
            "job-counter" ["run" "-m" "kehaar-example.jobs.counter"]
            "job-threes" ["run" "-m" "kehaar-example.jobs.threes"]})
