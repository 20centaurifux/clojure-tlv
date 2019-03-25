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

A package can be created with the encode function. It expects a package type
and a sequence.

	=> (require '[clojure-tlv.core :as tlv])

	=> (tlv/encode 23 "hello world")
	(87 11 104 101 108 108 111 32 119 111 114 108 100)

## Decoding

To decode packages **clojure-tlv** provides a pure functional decoder. A
callback function is applied to each found package.

	=> (defn ->string
	     [p]
	     (String. (byte-array p)))

	=> (defn print-package
	     [t p]
	     (println (format
	                "type: %d, payload: %s"
	                t
	                (->string p))))

	=> (-> (tlv/decoder print-package)
	       (tlv/decode (tlv/encode 23 "hello world")))

### Mapping types to keywords

Package types can be mapped to keywords automatically.

	=> (defmulti process-package (fn [t p] t))

	=> (defmethod process-package :foo
	     [t p]
	     (println "I'm a foo => " (->string p)))

	=> (defmethod process-package :bar
	    [t p]
	    (println "I'm a bar => " (->string p)))

	=> (-> (tlv/decoder process-package :type-map {23 :foo
	                                               42 :bar})
	       (tlv/decode (tlv/encode 23 "foo"))
	       (tlv/decode (tlv/encode 42 "bar")))

### Session state

It's also possible to implement a stateful decoder by setting an initial session state.
The state is passed to the decoder's callback function as third argument and set to the
return value.

	=> (assert (zero? (-> (tlv/decoder (fn [t p s]
	                                     (inc s))
	                                   :session-state -1)
	                      (tlv/decode (tlv/encode 5 "hello world"))
	                      :session-state)))

### Message size limit

A payload size limit can be set when defining a decoder. If a message exceeds the
specified limit the decoder becomes invalid. Any new data will be ignored.

	=> (let [decoder (-> (tlv/decoder (fn [t p s]
	                                    (inc s))
	                                  :session-state -1
	                                  :max-size 1)
	                     (tlv/decode (tlv/encode 1 "a"))
	                     (tlv/decode (tlv/encode 1 "bc")))]
	     (assert (zero? (:session-state decoder)))
	     (assert (tlv/failed? decoder))
	     (assert (not (tlv/valid? decoder))))

### Nested packages

**clojure-tlv** offers a function to unpack nested packages.

	=> (defn unpack-container
	     [t p]
	     (when-let [children (tlv/unpack p)]
	       (println (clojure.string/join " "
	                                     (map #(->string (second %))
	                                          children)))))

	=> (-> (tlv/decoder unpack-container)
	       (tlv/decode (tlv/encode 1 (concat (tlv/encode 2 "klaatu")
	                                         (tlv/encode 2 "barada")
	                                         (tlv/encode 2 "nikto")))))

### Async support

**clojure-tlv** provides a simple [core.async](https://github.com/clojure/core.async) wrapper.

	=> (require '[clojure-tlv.async :as async]
	            '[clojure.core.async :refer [>!! chan close!]])

	=> (def c (-> (tlv/decoder print-package)
	              async/decoder->chan))

	=> (>!! c (concat (tlv/encode 1 "foo")
	                  (tlv/encode 2 "bar")))

Alternatively you can use the convenient decode-async macro.

	=> (def c (chan))

	=> (async/decode-async c
	                       ; decoder options
	                       {:session-state 1
	                        :max-size 256}

	                       ; package received
	                       ([t p s]
	                        (println (format "package %d => %s"
	                                         s
	                                         (String. (byte-array p))))
	                        (inc s))

	                       ; error (channel is not closed automatically)
	                       ([e]
	                        (println "error => " e)
	                        (close! c)))

	=> (>!! c (tlv/encode 1 "foobar"))
