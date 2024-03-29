(defproject dxpb-clj "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.1.587"]
                 [pro.juxt.crux/crux-core "1.18.1"]
                 [pro.juxt.crux/crux-rocksdb "1.18.1"]
                 [yogthos/config "1.1.7"]
                 [compojure "1.6.1"]
                 [ring/ring-core "1.8.1"]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-jetty-adapter "1.8.1"]
                 [hiccup "1.0.5"]
                 [org.clojure/data.json "1.0.0"]
                 [http-kit "2.5.1"]
                 [org.tobereplaced/nio.file "0.4.0"]]
  :main ^:skip-aot dxpb-clj.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[ring/ring-devel "1.8.1"]]
                   :plugins [[lein-ring "0.12.5"]
                             [lein-kibit "0.1.8"]]}}
  :ring {:handler dxpb-clj.core/rosie})
