(ns kehaar.power-test
  (:require [kehaar.power :refer :all]
            [clojure.test :refer :all]
            [clojure.core.async :as async]))

(deftest basic-defn-service-handler-call
  (testing "the basic, minimum def-service-handler"
    (defn-service-handler testing1 "some-queue.name"
      "A test handler"
      [message]
      nil)))

(deftest defn-service-handler-can-be-called-like-function
  (testing "you can call a def-service-handler like a function"
    (defn-service-handler like-fn "random-queue"
      "Just a basic tester."
      [message]
      message)

    (is (= 1 (like-fn 1)))
    (is (= 2 (like-fn 2))))
  (testing "you can use a destructuring form as argument"
    (defn-service-handler like-fn "random-queue"
      "Just a basic tester."
      [{:keys [hello]}]
      hello)

    (is (= 1 (like-fn {:hello 1})))
    (is (= 2 (like-fn {:hello 2})))))

(deftest defonce-outgoing-events-chan-basic
  (testing "the basic defonce-outgoing-events-chan"
    (defonce-outgoing-events-chan test-events "test.events"
      (async/chan))))

(deftest defonce-outgoing-events-chan-only-once
  (testing "make sure calling it twice does not redefine"
    (defonce-outgoing-events-chan test-events "test.events"
      (async/chan))
    (let [c1 test-events]
      (defonce-outgoing-events-chan test-events "test.events"
        (async/chan))
      (let [c2 test-events]
        (is (identical? c1 c2))))))

(deftest defn-external-service-basic
  (testing "the basic defonce-external-service call"
    (defn-external-service some-service "some-queue"
      "something I can call")))

(deftest defn-incoming-event-handler-basic
  (testing "the basic def-incoming-event-handler definition"
    (defn-incoming-event-handler user-create "user.create" "power-test.queue"
      "Respond to a user-create event."
      [message]
      (println "Got event:" (pr-str message)))))
