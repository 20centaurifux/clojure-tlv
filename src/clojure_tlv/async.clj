(ns clojure-tlv.async
  (:require [clojure-tlv.core :as tlv]
            [clojure.core.async :refer [go-loop <! chan close!]]))

(defn decoder->chan
  "Creates an asynchronous channel and connects it to decoder. The
  channel is closed automatically on failure."
  [decoder]
  (let [c (chan)]
    (go-loop [decoder' decoder]
      (if (tlv/valid? decoder')
        (if-let [package (<! c)]
          (recur (tlv/decode decoder' package)))
        (close! c)))
    c))

(defmacro decode-async
  "Builds a decoder from the options found in the opts-map. Evaluates success
  when a package is decoded. Type, length (and session) of the decoded package
  are bound to vars. If the decoder fails the fail-form is evaluated. The error
  reason is bound to e. The channel isn't closed automatically on failure."
  [c opts vars success [e] fail]
  `(let [decoder# (apply tlv/decoder
                         (fn ~vars (~@success))
                         (flatten (into [] ~opts)))]
     (go-loop [decoder'# decoder#]
       (if (tlv/valid? decoder'#)
         (when-let [package# (<! ~c)]
           (recur (tlv/decode decoder'# package#)))
         (let [~e (:reason decoder'#)]
           (~@fail))))))
