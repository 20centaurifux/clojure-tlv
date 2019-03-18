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
        (when-let [package (<! c)]
          (recur (tlv/decode decoder' package)))
        (close! c)))
    c))

(defmacro decode-async
  "Builds a decoder from the options found in the opts-map. Evaluates the
  success-body whenever a package is received. Yields the error-body if
  decoding fails.
  
  The success and error forms consists of a parameter list and body. The
  connected channel isn't closed automatically on failure.
  
  Example:

  (let c (chan))

  (decode-async c
                {:sessions-state 1}

                ([t p s]
                 (println (format \"package %d => %s\" s (apply str p)))
	               (inc s))

                ([e]
                 (println \"error => \" e)
                 (close! c)))"
  [c opts success error]
  `(let [d1# (apply tlv/decoder
                    (fn ~(first success)
                      ~(cons 'do (rest success)))
                    (flatten (into [] ~opts)))]
     (go-loop [d2# d1#]
       (if (tlv/valid? d2#)
         (when-let [package# (<! ~c)]
           (recur (tlv/decode d2# package#)))
         (let ~(into (first error) [(:reason 'd2#)])
           ~(cons 'do (rest error)))))))
