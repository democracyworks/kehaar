(defproject democracyworks/kehaar "0.11.4-SNAPSHOT"
  :url "https://github.com/democracyworks/kehaar"
  :description "Kehaar passes messages to and from RabbitMQ channels"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.3.443"]
                 [com.novemberain/langohr "4.1.0"]
                 [org.apache.commons/commons-collections4 "4.1"]
                 [org.clojure/tools.logging "0.3.1"]]
  :test-selectors {:default (complement :rabbit-mq)
                   :rabbit-mq :rabbit-mq
                   :all (constantly true)}
  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]])
