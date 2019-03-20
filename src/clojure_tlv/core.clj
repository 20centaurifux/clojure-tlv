(ns clojure-tlv.core)

(defn- options->map
  [options]
  {:pre [(even? (count options))]}
  (apply hash-map options))

(defn decoder
  "Creates the initial TLV decoder state. f is applied to found TLV packages."
  [f & options]
  (merge {:state :tag :callback f}
         (select-keys
          (options->map options)
          [:session-state
           :type-map
           :max-size])))

(defn failed?
  "true if the decoder state is invalid."
  [decoder]
  (= (:state decoder) :failed))

(def valid?
  ^{:doc "true if the decoder state is valid"}
  (comp not failed?))

(defmulti ^:private decode-step :state)

(defn decode
  "Decodes bytes & updates the decoder state."
  [decoder bytes]
  (cond-> decoder
    (not-empty bytes) (decode-step bytes)))

(defmethod decode-step :failed
  [decoder bytes]
  decoder)

;; Binds the specified vars to the corresponding values of m.
;; => (with-keys {:a 1 :b 2} [a b] (+ a b))
(defmacro ^:private with-keys
  [m vars & body]
  `(let [~vars (map ~m (map keyword '~vars))] ~@body))

(defn- has-session?
  [decoder]
  (contains? decoder :session-state))

;; Runs the associated callback function & updates session state.
(defn- apply-callback
  [decoder]
  (with-keys decoder
    [type-map type payload session-state callback]
    (->> (cond-> [(get type-map type type) payload]
           (has-session? decoder) (conj session-state))
         (apply callback))))

(defn- reset-decoder
  [decoder]
  (-> (select-keys decoder [:callback :type-map :session-state :max-size])
      (assoc :state :tag)))

(defn- tag-without-payload
  [decoder t]
  (let [result (apply-callback (assoc decoder :type t :payload []))]
    (-> (cond-> decoder
          (has-session? decoder) (assoc :session-state result))
        reset-decoder)))

(defn- tag-with-payload
  [decoder t l]
  (assoc decoder
         :state :header
         :header []
         :type t
         :required l))

(def ^:private tag->type (partial bit-and 0x3F))

;; The header length is stored in the first two bits. To get the number of
;; bytes it can be divided by 48:
;; f(0)    = 0
;; f(0x40) = 1
;; f(0x80) = 2
;; f(0xc0) = 4
;; f(n)    = (n / 64) + (n / 192) = 4n / 192 = n / 48
(defn- tag->length
  [tag]
  (-> tag
      (bit-and 0xC0)
      (quot 48)))

(def ^:private parse-tag (juxt tag->type tag->length))

(defmethod decode-step :tag
  [decoder bytes]
  (let [[t l] (parse-tag (first bytes))]
    (-> (if (zero? l)
          (tag-without-payload decoder t)
          (tag-with-payload decoder t l))
        (decode (rest bytes)))))

;; Maximum number of bytes which can be read from the source without affecting the decoder state.
(defn- readable-bytes
  [decoder bytes]
  (with-keys decoder
    [required state]
    (min (count bytes) (- required (count (state decoder))))))

;; Copies as much bytes as possible from the source without affecting the decoder state.
(defn- read-next-bytes
  [decoder bytes]
  (let [readable (readable-bytes decoder bytes)]
    (-> (update decoder (:state decoder) concat (take readable bytes))
        (decode-step (drop readable bytes)))))

(defn- bytes->num
  [bytes]
  (if (empty? bytes)
    0
    (reduce
     bit-or
     (map-indexed
      #(bit-shift-left
        (bit-and %2 0x0FF)
        (* 8 (- (dec (count bytes)) %1)))
      bytes))))

(defn- payload-exceeds-limit?
  [decoder payload-size]
  (boolean
   (when-let [max-size (:max-size decoder)]
     (> payload-size max-size))))

(defn- reading-completed?
  [decoder]
  (with-keys decoder
    [state required]
    (= (count (state decoder)) required)))

(def ^:private bytes-left? (comp not reading-completed?))

(defn- start-payload
  [decoder]
  (let [size (bytes->num (:header decoder))]
    (->> (cond
           (bytes-left? decoder) {}
           (payload-exceeds-limit? decoder size) {:state :failed :reason "Payload size exceeds maximum."}
           :else {:state :payload :required size :payload []})
         (merge decoder))))

(defmethod decode-step :header
  [decoder bytes]
  (let [decoder' (start-payload decoder)]
    (cond-> decoder'
      (valid? decoder') (read-next-bytes bytes))))

(defmethod decode-step :payload
  [decoder bytes]
  (if (reading-completed? decoder)
    (let [result (apply-callback decoder)]
      (-> (cond-> decoder
            (has-session? decoder) (assoc :session-state result))
          reset-decoder
          (decode bytes)))
    (cond-> decoder
      (not-empty bytes) (read-next-bytes bytes))))

(defn- required-header-size
  [l]
  (cond
    (> l 0xFFFF) :dword
    (> l 0x00FF) :word
    (> l 0x0000) :byte
    :else :zero))

(defn- tag
  [t l]
  (case (required-header-size l)
    :dword (bit-or t 0XC0)
    :word (bit-or t 0x80)
    :byte (bit-or t 0x40)
    :zero t))

(defn- num->bytes
  [l]
  (case (required-header-size l)
    :zero []
    :byte [l]
    :word [(bit-shift-right (bit-and l 0xFF00) 8)
           (bit-and 0xFF l)]
    :dword [(bit-shift-right (bit-and l 0xFF000000) 24)
            (bit-shift-right (bit-and l 0x00FF0000) 16)
            (bit-shift-right (bit-and l 0x0000FF00) 8)
            (bit-and l 0x000000FF)]))

(defn header
  "Returns a byte sequence containing package tag & length."
  [t l]
  (cons (tag t l)
        (num->bytes l)))

(defn encode
  "Prepends package header to a byte sequence."
  [t payload]
  (concat (header t (count payload))
          (map byte payload)))
