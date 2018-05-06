(ns clojure-tlv.core-test
  (:require [clojure.test :refer :all]
            [clojure-tlv.core :refer :all])
  (:import (java.util Arrays)
           (java.security MessageDigest)))

(defn- header-length
  [l]
  (cond
    (> l 0xFFFF) 4
    (> l 0x00FF) 2
    (> l 0x0000) 1
    :else 0))

(deftest tag
  (testing "Building & parsing tags."
    (doseq [[t l] (for [t (range 64) l (range 0 (dec (Math/pow 2 32)) 2000000)] (vector t l))]
      (let [[t' l'] (#'clojure-tlv.core/parse-tag (#'clojure-tlv.core/tag t l))]
        (is (= t t'))
        (is (= l' (header-length l)))))))

(deftest encoding
  (testing "Data encoding."
    (with-open [rdr (clojure.java.io/reader "test-data/bible.txt")]
      (doseq [line (line-seq rdr)]
        (let [bytes (.getBytes line)
              type (rand-int 64)
              pkg (tlv-encode type bytes)
              tag (#'clojure-tlv.core/parse-tag (first pkg))
              offset (inc (second tag))]
          (is (= (first tag) type))
          (is (= (- (count pkg) offset) (count bytes)))
          (is (Arrays/equals (byte-array (drop offset pkg)) bytes)))))))

(defn- update-digest
  [t p s]
  (.update s (byte-array (cons t p))) s)

(deftest decoding
  (testing "Data decoding"
    (let [digest (MessageDigest/getInstance "sha1")
          digest' (MessageDigest/getInstance "sha1")]
      (with-open [rdr (clojure.java.io/reader "test-data/bible.txt")]
        (loop [lines (line-seq rdr)
               decoder (tlv-decoder update-digest :session-state digest')
               type (rand-int 64)]
          (when-let [line (first lines)]
            (.update digest (byte-array (cons type (.getBytes line))))
            (recur (rest lines)
                   (tlv-decode decoder (tlv-encode type (.getBytes line)))
                   (rand-int 64)))))
      (is (Arrays/equals (.digest digest) (.digest digest'))))))

(deftest partial-decode
  (testing "Partial data decoding."
    (loop [bytes (byte-array [65 3 1 2 3 66 3 4 5 6 67 3 7 8 9])
           decoder (tlv-decoder #(apply + (cons %3 %2)) :session-state 0)]
      (if-let [b (first bytes)]
        (recur (rest bytes)
               (tlv-decode decoder [b]))
        (is (:session-state decoder) 45)))))

(defn- decrement-key
  [t p s]
  (if (keyword? t)
    (update s t dec)
    s))

(deftest map-types
  (testing "Map types."
    (loop [bytes (range 10)
           decoder (tlv-decoder decrement-key :type-map {1 :a 2 :b 3 :c} :session-state {:a 2 :b 3 :c 4 :d 5})]
      (if-let [b (first bytes)]
        (recur (rest bytes)
               (tlv-decode decoder [b]))
        (is (= (apply + (vals (:session-state decoder))) 11))))))

(deftest sessionless-decode
  (testing "Sessionless data decoding."
    (let [counter (atom 0)]
      (loop [bytes (range 64)
             decoder (tlv-decoder (fn [_ _] (swap! counter inc) nil))]
        (if-let [b (first bytes)]
          (recur (rest bytes)
                 (tlv-decode decoder [b]))
          (= @counter 64))))))
