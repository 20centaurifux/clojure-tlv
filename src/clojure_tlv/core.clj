(ns clojure-tlv.core)

(defn tlv-decoder
  "Creates the initial TLV decoder state. f is applied to found TLV packages."
  [f & {:keys [type-map session-state] :or {type-map {}}}]
  {:state :tag
   :callback f
   :type-map type-map
   :session-state session-state})

(defmulti ^:private tlv-decode-step :state)

(defn tlv-decode
  "Decodes bytes & updates the decoder state."
  [decoder bytes]
  (cond-> decoder
    (not-empty bytes) (tlv-decode-step bytes)))

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

;; Binds the specified vars to the corresponding values of m.
;; => (with-keys {:a 1 :b 2} [a b] (+ a b))
(defmacro with-keys
  [m vars & body]
  `(let [~vars (map ~m (map keyword '~vars))] ~@body))

(defn- reset-tlv-decoder
  [decoder]
  (-> decoder
      (select-keys [:callback :type-map :session-state])
      (assoc :state :tag)))

;; Runs the associated callback function & updates session state.
(defn- callback
  [decoder]
  (with-keys
    decoder
    [type-map type payload session-state callback]
    (->> (cond-> [(get type-map type type) payload]
           (not (nil? session-state)) (conj session-state))
         (apply callback))))

(defmethod tlv-decode-step :tag
  [decoder bytes]
  (let [[t l] (parse-tag (first bytes))]
    (-> (if (zero? l)
          (->> (assoc decoder :type t :payload [])
               callback
               (assoc decoder :session-state)
               reset-tlv-decoder)
          (assoc decoder
                 :state :header
                 :header []
                 :type t
                 :required l))
        (tlv-decode (rest bytes)))))

;; Maximum number of bytes which can be read from the source without affecting the decoder state.
(defn- readable-bytes
  [decoder bytes]
  (with-keys
    decoder
    [required state]
    (min (count bytes) (- required (count (state decoder))))))

(defn- reading-completed?
  [decoder]
  (with-keys
    decoder
    [state required]
    (= (count (state decoder)) required)))

;; Copies as much bytes as possible from the source without affecting the decoder state.
(defn- read-next-bytes
  [decoder bytes]
  (let [readable (readable-bytes decoder bytes)]
    (-> decoder
        (update (:state decoder) concat (take readable bytes))
        (tlv-decode-step (drop readable bytes)))))

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
 
(defmethod tlv-decode-step :header
  [decoder bytes]
  (cond-> decoder
    (reading-completed? decoder) (assoc
                                   :state :payload
                                   :required (bytes->num (:header decoder))
                                   :payload [])
    (not-empty bytes) (read-next-bytes bytes)))

(defmethod tlv-decode-step :payload
  [decoder bytes]
  (if (reading-completed? decoder)
    (-> decoder
        (assoc :session-state (callback decoder))
        reset-tlv-decoder
        (tlv-decode bytes))
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

(defn tlv-header
  "Returns a byte sequence containing package tag & length."
  [t l]
  (cons (tag t l) (num->bytes l)))

(defn tlv-encode
  "Prepends package header to a byte sequence."
  [t payload]
  (concat (tlv-header t (count payload)) payload))
