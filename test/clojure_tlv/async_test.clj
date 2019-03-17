(ns clojure-tlv.async-test
  (:require [clojure.test :refer :all]
            [clojure-tlv.core :refer [decoder encode]]
            [clojure-tlv.async :refer [decoder->chan decode-async]]
            [clojure.core.async :refer [chan >!! put!]]))

(deftest channel
  (testing "Decoder channel."
    (let [counter (atom 0)
          c (-> (decoder (fn [_ p] (swap! counter inc)))
                decoder->chan)]
      (doseq [p ["foo" "bar" "baz"]]
        (>!! c (encode 0 p)))
      (Thread/sleep 500)
      (is (= @counter 3)))))

(deftest failed-channel
  (testing "Failed decoder channel."
    (let [counter (atom -1)
          c (-> (decoder (fn [_ p]
                           (swap! counter inc)) :max-size 1) decoder->chan)]
      (doseq [p ["a" "bc" "d"]]
        (put! c (encode 0 p)))
      (Thread/sleep 500)
      (is (zero? @counter)))))

(deftest decode-async-macro
  (testing "Decode asynchronously."
    (let [n (atom 0)
          c (chan)]
      (decode-async c
                    {:max-size 1 :session-state 1}
                    [t p s] (do
                              (swap! n (partial + s))
                              (inc s))
                    [e] (swap! n (partial * 2)))
      (doseq [p ["a" "b" "c" "de" "f"]]
        (put! c (encode 0 p)))
      (Thread/sleep 500)
      (is (= 12 @n)))))
