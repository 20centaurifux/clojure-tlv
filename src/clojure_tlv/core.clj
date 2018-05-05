(ns clojure-tlv.core)

(defn tlv-decoder
  "Creates the initial tlv decoder state. f is applied to payload."
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

;; Binds the specified vars to the corresponding values from m.
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

;; Converts a byte array to an integer (big endian).
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
