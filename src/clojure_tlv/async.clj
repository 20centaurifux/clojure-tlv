(ns clojure-tlv.async
  (:require [clojure-tlv.core :as tlv]
            [clojure.core.async :refer [go-loop <! chan]]))

(defn decoder->chan
  [decoder]
  (let [c (chan)]
    (go-loop [decoder' decoder]
      (when-let [package (<! c)]
        (recur (tlv/decode decoder' package))))
    c))
