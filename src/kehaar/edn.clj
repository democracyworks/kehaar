(ns kehaar.edn
  (:require
   [clojure.edn :as edn]
   [clojure.walk :as walk])
  (:import (java.util.regex Pattern))
  (:refer-clojure :exclude [pr-str read-string]))

(defn sanitize
  "Replaces regexes in value `v` with their string representation so that `v`
  can be EDN encoded.

  Maybe it won't be needed forever:
  https://github.com/clojure/clojure/blob/c6756a8bab137128c8119add29a25b0a88509900/src/jvm/clojure/lang/EdnReader.java#L53"
  [v]
  (walk/postwalk
   (fn [f]
     (if (instance? Pattern f)
       (.toString f)
       f))
   v))

(defn pr-str
  "A safer replacement for clojure.core/pr-str that replaces
  java.util.regex.Pattern instances with their string representations. See
  `kehaar.edn/sanitize` for why this is necessary."
  [v]
  (clojure.core/pr-str (sanitize v)))

(defn read-string
  "A safer replacement for clojure.edn/read-string that doesn't throw
  exceptions on unknown tags. Instead it just reads in their values as is."
  [s]
  (edn/read-string {:default (fn [_ v] v)} s))