(defproject democracyworks/kehaar "0.2.1-SNAPSHOT"
  :description "Kehaar passes messages to and from RabbitMQ channels"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-beta3"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [com.novemberain/langohr "3.2.0"]]
  :test-selectors {:default (complement :rabbit-mq)
                   :rabbit-mq :rabbit-mq
                   :all (constantly true)})
