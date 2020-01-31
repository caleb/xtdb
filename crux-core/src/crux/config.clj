(ns crux.config
  (:require [clojure.spec.alpha :as s]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import java.util.Properties
           (java.time Duration)))

(def property-types
  {::boolean [boolean? (fn [x]
                         (or (and (string? x) (Boolean/parseBoolean x)) x))]
   ::int [int? (fn [x]
                 (or (and (string? x) (Long/parseLong x)) x))]
   ::nat-int [nat-int? (fn [x]
                         (or (and (string? x) (Long/parseLong x)) x))]
   ::string [string? identity]
   ::module [(fn [m] (s/valid? :crux.topology/module m))
             (fn [m] (s/conform :crux.topology/module m))]
   ::duration [#(instance? Duration %)
               (fn [d]
                 (cond
                   (instance? Duration d) d
                   (nat-int? d) (Duration/ofMillis d)
                   (string? d) (Duration/parse d)))]})

(s/def ::type
  (s/and (s/conformer (fn [x] (or (property-types x) x)))
         (fn [x] (and (vector? x) (-> x first fn?) (some-> x second fn?)))))

(s/def ::doc string?)
(s/def ::default any?)
(s/def ::required? boolean?)

(defn load-properties [f]
  (with-open [rdr (io/reader f)]
    (let [props (Properties.)]
      (.load props rdr)
      (into {}
            (for [[k v] props]
              [(keyword k) v])))))

(defn load-edn [f]
  (with-open [rdr (io/reader f)]
    (into {}
          (for [[k v] (edn/read-string (slurp rdr))]
            [(keyword k) v]))))
