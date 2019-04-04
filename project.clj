(defproject zcfux/clojure-tlv "0.1.0-SNAPSHOT"
  :description "A TLV implementation in clojure."
  :url "https://github.com/20centaurifux/clojure-tlv"
  :license {:name "GPLv3"
            :url "https://www.gnu.org/licenses/gpl-3.0.txt"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.async "0.4.490"]]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :plugins [[lein-cljfmt "0.6.0"]])
