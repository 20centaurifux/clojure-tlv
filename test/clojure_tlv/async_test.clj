(ns clojure-tlv.async-test
  (:require [clojure.test :refer :all]
            [clojure-tlv.core :refer [decoder encode]]
            [clojure-tlv.async :refer [decoder->chan]]
            [clojure.core.async :refer [>!!]]))

(deftest channel
  (testing "Decoder channel."
    (let [counter (atom 0)
          c (-> (decoder (fn [_ p] (swap! counter inc)))
                decoder->chan)]
      (>!! c (encode 0 "foo"))
      (>!! c (encode 1 "bar"))
      (>!! c (encode 2 "baz"))
      (Thread/sleep 500) ; wait for decoder
      (is (= @counter 3)))))
