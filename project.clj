(defproject democracyworks/kehaar "1.0.3-SNAPSHOT"
  :url "https://github.com/democracyworks/kehaar"
  :description "Kehaar passes messages to and from RabbitMQ channels"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0" :scope "provided"]
                 [org.clojure/core.async "0.4.474" :scope "provided"]
                 [com.novemberain/langohr "4.1.0"]
                 [org.apache.commons/commons-collections4 "4.2"]
                 [org.clojure/tools.logging "0.4.1"]]
  :test-selectors {:default (complement :rabbit-mq)
                   :rabbit-mq :rabbit-mq
                   :all (constantly true)}
  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]])
