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

	(tlv-decode
	  (tlv-decoder print-package)
	  (tlv-encode 23 "hello world"))

### Mapping types

Package types can be mapped to keywords by specifying a map.

	(defmulti process-package (fn [a b] a))

	(defmethod process-package :foo
	  [t p]
	  (println "foo => " p))

	(defmethod process-package :bar
	  [t p]
	  (println "bar => " p))

	(def decoder (tlv-decoder
	               process-package
	               :type-map {23 :foo 42 :bar}))

	(->>
	  (mapcat
	    (partial apply tlv-encode)
	    (partition 2 [23 "foo" 42 "bar" 23 "f00" 23 "fo0" 42 "baz"]))
	  (tlv-decode decoder))

### Session state

Decoders can have a session state. You can set the initial value by providing
the "session-state" keyword.

	(tlv-decoder
	  process-package
	  :session-state {:authenticated? false})

If the session state is defined it's passed to the decoder's callback function
as third argument and set to the return value.

	(defn count-packages
	  [t p s]
	  (inc s))

	(tlv-decoder count-packages :session-state 0)

	(:session-state (tlv-decode
	                  (tlv-decoder count-packages :session-state 0)
	                  (tlv-encode 5 "hello world")))
