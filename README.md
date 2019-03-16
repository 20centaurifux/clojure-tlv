# clojure-tlv

**clojure-tlv** is a type-length-value format implementation in Clojure.

## Installation

The library can be installed from Clojars using Leiningen:

[![Clojars Project](http://clojars.org/zcfux/clojure-tlv/latest-version.svg)](https://clojars.org/zcfux/clojure-tlv)

## Package format

A package consists of the following fields:

### Tag

The first byte of a package is called *tag*. The two leftmost bits define the
size of the *length* field.

* 00: the package neither provides a *length* nor a *payload* field
* 01: the length is stored in a single byte
* 10: the length is stored in a word
* 11: the length is stored in a double word

The package type is stored in the other six bits of the *tag*.

### Length

This field indicates the *payload* length. It's stored in big endian format.

### Payload

Variable-sized series of bytes.

## Encoding

A package can be created with the tlv-encode function. It expects a package type
and a sequence.

	(tlv-encode 23 "hello world")

## Decoding

To decode packages **clojure-tlv** provides a pure functional decoder. A
callback function is applied to each found package.

	(defn print-package
	  [t p]
	  (println (format
	             "type: %d, payload: %s"
	             t
	             (apply str p))))

	(-> (tlv-decoder print-package)
	    (tlv-decode (tlv-encode 23 "hello world")))

### Mapping types

Package types can be mapped to keywords by specifying a map.

	(defmulti process-package (fn [t p] t))

	(defmethod process-package :foo
	  [t p]
	  (println "foo => " p))

	(defmethod process-package :bar
	  [t p]
	  (println "bar => " p))

	(-> (tlv-decoder process-package :type-map {23 :foo 42 :bar})
	    (tlv-decode (tlv-encode 23 "foo"))
	    (tlv-decode (tlv-encode 42 "bar")))

### Session state

Decoders can have a session state. Set the initial value by providing the
"session-state" keyword.

If the session state is defined it's passed to the decoder's callback function
as third argument and set to the return value.

	(assert (zero? (-> (tlv-decoder (fn [t p s] (inc s)) :session-state -1)
                           (tlv-decode (tlv-encode 5 "hello world"))
                           :session-state)))

### Message size limit

A payload size limit can be set when defining a decoder. If a message exceeds the
specified limit the decoder becomes invalid. Any new data will be ignored.

	(let [decoder (-> (tlv-decoder (fn [t p s] (inc s)) :session-state 0 :max-size 1)
	                  (tlv-decode (tlv-encode 1 "a"))
	                  (tlv-decode (tlv-encode 1 "bc")))]
	  (assert (= (:session-state decoder) 1))
	  (assert (failed? decoder)))
