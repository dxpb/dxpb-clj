(defproject dxpb-clj "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.1.587"]
                 [juxt/crux-core "20.05-1.8.4-alpha"]
                 [juxt/crux-rocksdb "20.05-1.8.4-alpha"]
                 [yogthos/config "1.1.7"]
                 [compojure "1.6.1"]
                 [ring/ring-core "1.8.1"]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-jetty-adapter "1.8.1"]
                 [hiccup "1.0.5"]
                 ]
  :main ^:skip-aot dxpb-clj.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[ring/ring-devel "1.8.1"]]}}
  :ring {:handler dxpb-clj.core/rosie}
  :plugins [[lein-ring "0.12.5"]])
