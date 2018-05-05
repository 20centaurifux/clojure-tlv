(ns clojure-tlv.core)

(defn tlv-decoder
  "Creates the initial TLV decoder state. f is applied to payload."
  [f & {:keys [type-map session-state] :or {type-map {}}}]
  {:state :tag
   :callback f
   :type-map type-map
   :session-state session-state})

(defmulti ^:private tlv-decode-step :state)

(defn tlv-decode
  "Decodes bytes."
  [decoder bytes]
  (cond-> decoder
    (not-empty bytes) (tlv-decode-step bytes)))

;; Gets type information from a tag.
(def ^:private tag->type (partial bit-and 0x3F))

;; Gets header length from a tag.
(defn- tag->length
  [tag]
  (-> tag
      (bit-and 0xC0)
      (quot 48)))

;; Returns type and header length from a tag.
(def ^:private parse-tag (juxt tag->type tag->length))

;; Merges m with a map created from the given keys & vals.
(defn- assoc-vectors
  [m keys vals]
  (into m (zipmap keys vals)))

(defmethod tlv-decode-step :tag
  [decoder bytes]
  (-> decoder
      (assoc :state :header
             :header [])
      (assoc-vectors [:type :required] (parse-tag (first bytes)))
      (tlv-decode (rest bytes))))

;; Binds the specified vars to the corresponding values of m.
;; Example: (with-keys {:a 1 :b 2} [a b] (+ a b))
(defmacro with-keys
  [m vars & body]
  `(let [~vars (map ~m (map keyword '~vars))] ~@body))

;; Maximum number of bytes which can be read from the source without affecting the decoder state.
(defn- readable-bytes
  [decoder bytes]
  (with-keys
    decoder
    [required state]
    (min (count bytes) (- required (count (state decoder))))))

;; Checks if the decoder state can be changed.
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

;; Converts a byte array to an integer.
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

(defmethod tlv-decode-step :payload
  [decoder bytes]
  (if (reading-completed? decoder)
    (-> decoder
        (assoc :session-state (callback decoder))
        reset-tlv-decoder
        (tlv-decode bytes))
    (cond-> decoder
      (not-empty bytes) (read-next-bytes bytes))))

;; Calculates the number of required header bytes.
(defn- header-length
  [l]
  (cond
    (> l 0xFFFF) :dword
    (> l 0x00FF) :word
    (> l 0x0000) :byte
    :else :zero))

(defn- tag
  [t l]
  (case (header-length l)
    :dword (bit-or t 0XC0)
    :word (bit-or t 0x80)
    :byte (bit-or t 0x40)
    :zero t))

;; Converts a number to a byte array.
(defn- num->bytes
  [l]
  (case (header-length l)
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
  "Builds & prepends a package header to a byte sequence."
  [t payload]
  (concat (tlv-header t (count payload)) payload))
