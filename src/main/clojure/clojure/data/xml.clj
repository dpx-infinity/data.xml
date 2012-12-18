;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:doc "Functions to parse XML into lazy sequences and lazy trees and
  emit these as text."
  :author "Chris Houser"}
  clojure.data.xml
  (:require [clojure.string :as str])
  (:import (javax.xml.stream XMLInputFactory
                             XMLStreamReader
                             XMLStreamWriter
                             XMLStreamConstants)
           (java.nio.charset Charset)
           (java.io Reader)))

(def ^{:dynamic true
       :doc "Defines whether commentaries should be read from input XML"}
  *enable-comments* false)

(defmacro with-comments-enabled
  [& body]
  `(binding [*enable-comments* true]
     ~@body))

; Represents a parse event.
; type is one of :start-element, :end-element, :characters or :comment
(defrecord Event [type name attrs str])

(defn event [type name & [attrs str]]
  (Event. type name attrs str))

(defn qualified-name [event-name]
  (if (instance? clojure.lang.Named event-name)
   [(namespace event-name) (name event-name)]
   (let [name-parts (str/split event-name #"/" 2)]
     (if (= 2 (count name-parts))
       name-parts
       [nil (first name-parts)]))))

(defn write-attributes [attrs ^javax.xml.stream.XMLStreamWriter writer]
  (doseq [[k v] attrs]
    (let [[attr-ns attr-name] (qualified-name k)]
      (if attr-ns
        (.writeAttribute writer attr-ns attr-name (str v))
        (.writeAttribute writer attr-name (str v))))))

; Represents a node of an XML tree
(defrecord Element [tag attrs content])
(defrecord CData [content])
(defrecord Comment [content])

(defn emit-start-tag [event ^javax.xml.stream.XMLStreamWriter writer]
  (let [[nspace qname] (qualified-name (:name event))]
    (.writeStartElement writer "" qname (or nspace ""))
    (write-attributes (:attrs event) writer)))

(defn emit-event [event ^javax.xml.stream.XMLStreamWriter writer]
  (case (:type event)
    :start-element (emit-start-tag event writer)
    :end-element (.writeEndElement writer)
    :chars (.writeCharacters writer (:str event))
    :cdata (.writeCData writer (:str event))
    :comment (.writeComment writer (:str event))))

(defprotocol EventGeneration
  "Protocol for generating new events based on element type"
  (gen-event [item]
    "Function to generate an event for e.")
  (next-events [item next-items]
    "Returns the next set of events that should occur after e.  next-events are the
     events that should be generated after this one is complete."))

(extend-protocol EventGeneration
  Element
  (gen-event [element]
    (Event. :start-element (:tag element) (:attrs element) nil))
  (next-events [element next-items]
    (cons (:content element)
          (cons (Event. :end-element (:tag element) nil nil) next-items)))
  Event
  (gen-event [event] event)
  (next-events [_ next-items]
    next-items)

  clojure.lang.Sequential
  (gen-event [coll]
    (gen-event (first coll)))
  (next-events [coll next-items]
    (if-let [r (seq (rest coll))]
      (cons (next-events (first coll) r) next-items)
      (next-events (first coll) next-items)))
  
  String
  (gen-event [s]
    (Event. :chars nil nil s))
  (next-events [_ next-items]
    next-items)
  
  CData
  (gen-event [cdata]
    (Event. :cdata nil nil (:content cdata)))
  (next-events [_ next-items]
    next-items)
  
  Comment
  (gen-event [comment]
    (Event. :comment nil nil (:content comment)))
  (next-events [_ next-items]
    next-items)
  
  nil
  (gen-event [_]
    (Event. :chars nil nil ""))
  (next-events [_ next-items]
    next-items))

(defn flatten-elements [elements]
  (when (seq elements)
    (lazy-seq
     (let [e (first elements)]
       (cons (gen-event e)
             (flatten-elements (next-events e (rest elements))))))))

(defn element [tag & [attrs & content]]
  (Element. tag (or attrs {}) (remove nil? content)))

(defn cdata [content]
  (CData. content))

(defn xml-comment [content]
  (Comment. content))

;=== Parse-related functions ===
(defn seq-tree
  "Takes a seq of events that logically represents
  a tree by each event being one of: enter-sub-tree event,
  exit-sub-tree event, or node event.

  Returns a lazy sequence whose first element is a sequence of
  sub-trees and whose remaining elements are events that are not
  siblings or descendants of the initial event.

  The given exit? function must return true for any exit-sub-tree
  event.  parent must be a function of two arguments: the first is an
  event, the second a sequence of nodes or subtrees that are children
  of the event.  parent must return nil or false if the event is not
  an enter-sub-tree event.  Any other return value will become
  a sub-tree of the output tree and should normally contain in some
  way the children passed as the second arg.  The node function is
  called with a single event arg on every event that is neither parent
  nor exit, and its return value will become a node of the output tree.

  (seq-tree #(when (= %1 :<) (vector %2)) #{:>} str
            [1 2 :< 3 :< 4 :> :> 5 :> 6])
  ;=> ((\"1\" \"2\" [(\"3\" [(\"4\")])] \"5\") 6)"
 [parent exit? node coll]
  (lazy-seq
    (when-let [[event] (seq coll)]
      (let [more (rest coll)]
        (if (exit? event)
          (cons nil more)
          (let [tree (seq-tree parent exit? node more)]
            (if-let [p (parent event (lazy-seq (first tree)))]
              (let [subtree (seq-tree parent exit? node (lazy-seq (rest tree)))]
                (cons (cons p (lazy-seq (first subtree)))
                      (lazy-seq (rest subtree))))
              (cons (cons (node event) (lazy-seq (first tree)))
                    (lazy-seq (rest tree))))))))))

(defn event-tree
  "Returns a lazy tree of Element objects for the given seq of Event
  objects. See source-seq and parse."
  [events]
  (ffirst
   (seq-tree
    (fn [^Event event contents]
      (when (= :start-element (.type event))
        (Element. (.name event) (.attrs event) contents)))
    (fn [^Event event] (= :end-element (.type event)))
    (if [*enable-comments*]
      (fn [^Event event]
        (case (.type event)
          :comment (Comment. (.str event))
          :characters (.str event)))
      (fn [^Event event] (.str event)))
    events)))

(defprotocol AsElements
  (as-elements [expr] "Return a seq of elements represented by an expression."))

(extend-protocol AsElements
  clojure.lang.IPersistentVector
  (as-elements [v]
    (let [[tag & [attrs & after-attrs :as content]] v
          [attrs content] (if (map? attrs)
                            [(into {} (for [[k v] attrs]
                                        [k (str v)]))
                             after-attrs]
                            [{} content])]
      [(Element. tag attrs (mapcat as-elements content))]))

  clojure.lang.ISeq
  (as-elements [s]
    (mapcat as-elements s))

  clojure.lang.Keyword
  (as-elements [k]
    [(Element. k {} ())])

  java.lang.String
  (as-elements [s]
    [s])

  nil
  (as-elements [_] nil)

  java.lang.Object
  (as-elements [o]
    [(str o)]))

(defn sexps-as-fragment
  "Convert a compact prxml/hiccup-style data structure into the more formal
   tag/attrs/content format. A seq of elements will be returned, which may
   not be suitable for immediate use as there is no root element. See also
   sexp-as-element.

   The format is [:tag-name attr-map? content*]. Each vector opens a new tag;
   seqs do not open new tags, and are just used for inserting groups of elements
   into the parent tag. A bare keyword not in a vector creates an empty element.

   To provide XML conversion for your own data types, extend the AsElements
   protocol to them."
  ([] nil)
  ([sexp] (as-elements sexp))
  ([sexp & sexps] (mapcat as-elements (cons sexp sexps))))

(defn sexp-as-element
  "Convert a single sexp into an Element"
  [sexp]
  (let [[root & more] (sexps-as-fragment sexp)]
    (when more
      (throw
       (IllegalArgumentException.
        "Cannot have multiple root elements; try creating a fragment instead")))
    root))


(defn- attr-prefix [^XMLStreamReader sreader index]
  (let [p (.getAttributePrefix sreader index)]
    (when-not (str/blank? p)
      p)))

(defn- attr-hash [^XMLStreamReader sreader] (into {}
    (for [i (range (.getAttributeCount sreader))]
      [(keyword (attr-prefix sreader i) (.getAttributeLocalName sreader i))
       (.getAttributeValue sreader i)])))

; Note, sreader is mutable and mutated here in pull-seq, but it's
; protected by a lazy-seq so it's thread-safe.
(defn- pull-seq
  "Creates a seq of events.  The XMLStreamConstants/SPACE clause below doesn't seem to 
   be triggered by the JDK StAX parser, but is by others.  Leaving in to be more complete."
  [^XMLStreamReader sreader]
  (lazy-seq
   (loop []
     (condp == (.next sreader)
       XMLStreamConstants/START_ELEMENT
       (cons (event :start-element
                    (keyword (.getLocalName sreader))
                    (attr-hash sreader) nil)
             (pull-seq sreader)) 
       XMLStreamConstants/END_ELEMENT
       (cons (event :end-element
                    (keyword (.getLocalName sreader)) nil nil)
             (pull-seq sreader))
       XMLStreamConstants/CHARACTERS
       (if-let [text (and (not (.isWhiteSpace sreader))
                          (.getText sreader))]
         (cons (event :characters nil nil text)
               (pull-seq sreader))
         (recur))
       XMLStreamConstants/COMMENT
       (if *enable-comments*
         (if-let [text (and (not (.isWhiteSpace sreader)) (.getText sreader))]
           (cons (event :comment nil nil text) (pull-seq sreader))
           (recur))
         (recur))
       XMLStreamConstants/END_DOCUMENT
       nil
       (recur);; Consume and ignore spaces, processing instructions etc
       ))))

(def ^{:private true} xml-input-factory-props
  {:allocator javax.xml.stream.XMLInputFactory/ALLOCATOR
   :coalescing javax.xml.stream.XMLInputFactory/IS_COALESCING
   :namespace-aware javax.xml.stream.XMLInputFactory/IS_NAMESPACE_AWARE
   :replacing-entity-references javax.xml.stream.XMLInputFactory/IS_REPLACING_ENTITY_REFERENCES
   :supporting-external-entities javax.xml.stream.XMLInputFactory/IS_SUPPORTING_EXTERNAL_ENTITIES
   :validating javax.xml.stream.XMLInputFactory/IS_VALIDATING
   :reporter javax.xml.stream.XMLInputFactory/REPORTER
   :resolver javax.xml.stream.XMLInputFactory/RESOLVER
   :support-dtd javax.xml.stream.XMLInputFactory/SUPPORT_DTD})

(defn- new-xml-input-factory [props]
  (let [fac (javax.xml.stream.XMLInputFactory/newInstance)]
    (doseq [[k v] props
            :let [prop (xml-input-factory-props k)]]
      (.setProperty fac prop v))
    fac))

(defn source-seq
  "Parses the XML InputSource source using a pull-parser. Returns
   a lazy sequence of Event records.  Accepts key pairs
   with XMLInputFactory options, see http://docs.oracle.com/javase/6/docs/api/javax/xml/stream/XMLInputFactory.html
   and xml-input-factory-props for more information. Defaults coalescing true."
  [s & {:as props}]
  (let [fac (new-xml-input-factory (merge {:coalescing true} props))
        ;; Reflection on following line cannot be eliminated via a
        ;; type hint, because s is advertised by fn parse to be an
        ;; InputStream or Reader, and there are different
        ;; createXMLStreamReader signatures for each of those types.
        sreader (.createXMLStreamReader fac s)]
    (pull-seq sreader)))

(defn parse
  "Parses the source, which can be an
   InputStream or Reader, and returns a lazy tree of Element records. Accepts key pairs
   with XMLInputFactory options, see http://docs.oracle.com/javase/6/docs/api/javax/xml/stream/XMLInputFactory.html
   and xml-input-factory-props for more information. Defaults coalescing true."
  [source & props]
  (event-tree (apply source-seq source props)))

(defn parse-str
  "Parses the passed in string to Clojure data structures.  Accepts key pairs
   with XMLInputFactory options, see http://docs.oracle.com/javase/6/docs/api/javax/xml/stream/XMLInputFactory.html
   and xml-input-factory-props for more information. Defaults coalescing true."
  [s & props]
  (let [sr (java.io.StringReader. s)]
    (apply parse sr props)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; XML Emitting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn check-stream-encoding [^java.io.OutputStreamWriter stream xml-encoding]
  (when (not= (Charset/forName xml-encoding) (Charset/forName (.getEncoding stream)))
    (throw (Exception. (str "Output encoding of stream (" xml-encoding
                            ") doesn't match declaration ("
                            (.getEncoding stream) ")")))))

(defn emit
  "Prints the given Element tree as XML text to stream.
   Options:
    :encoding <str>          Character encoding to use"
  [e ^java.io.Writer stream & {:as opts}]
  (let [^javax.xml.stream.XMLStreamWriter writer (-> (javax.xml.stream.XMLOutputFactory/newInstance)
                                                     (.createXMLStreamWriter stream))]

    (when (instance? java.io.OutputStreamWriter stream)
      (check-stream-encoding stream (or (:encoding opts) "UTF-8")))
    
    (.writeStartDocument writer (or (:encoding opts) "UTF-8") "1.0")
    (doseq [event (flatten-elements [e])]
      (emit-event event writer))
    (.writeEndDocument writer)
    stream))

(defn emit-str
  "Emits the Element to String and returns it"
  [e]
  (let [^java.io.StringWriter sw (java.io.StringWriter.)]
    (emit e sw)
    (.toString sw)))

(defmacro wrapped-with
  [what & body]
  `(do
     (~(symbol (str "before-" what)) ~'this)
     ~@body
     (~(symbol (str "after-" what)) ~'this)))

(defmacro deftype-with-delegation
  "Adds delegation feature to deftype. Equivalent to (deftype), but it is also
  possible to use (delegate-to target method*) form inside it, which will
  expand to method definitions which will call themselves on target with all
  corresponding arguments. Example:

  (deftype-with-delegation NewType
    [val a b]
    SomeInterface
    (delegate-to val
      method1 [x y]
      method2 [z])
    (method3 [this u] (println u)))

  expands to

  (deftype NewType
    [val a b]
    SomeInterface
    (method1 [_ x y] (. val method1 x y))
    (method2 [_ z] (. val method2 z))
    (method3 [this u] (println u)))

  With this macro it is possible to delegate multiple methods of some
  interface/protocol to another object. Useful for creating wrappers, e.g. for
  java.io.OutputStream or java.xml.stream.XMLStreamWriter.

  Side-effect of this macro is that it is not possible to extend protocols which contain 'delegate-to' method
  with it in other way than delegating."
  [& args]
  `(deftype
     ~@(mapcat
         (fn [arg]
           (or (when (coll? arg)
                 (when-let [[name & more] arg]
                   (when (= name 'delegate-to)
                     (let [[target & pairs] more]
                       (for [[name args] (partition 2 pairs)]
                         `(~name [~'_ ~@args] (. ~target ~name ~@args)))))))
             [arg]))
         args)))

;; The following implementation of indenting XMLStreamWriter is heavily inspired
;; by stax-utils IndentingXMLStreamWriter

(defprotocol Indenter
  (before-element [this])
  (after-element [this])
  (before-text [this])
  (after-text [this]))

(deftype-with-delegation IndentingXMLStreamWriter
  [^{:tag XMLStreamWriter} writer
   ^{:tag int :unsynchronized-mutable true} indent-size
   ; This one should be private in fact
   ^{:tag int :unsynchronized-mutable true} indent-level]
  XMLStreamWriter
  (delegate-to writer
    close []
    flush []
    getPrefix [uri]
    setPrefix [prefix uri]
    setDefaultNamespace [uri]
    setNamespaceContext [namespace-context]
    getNamespaceContext []
    getProperty [name]
    writeEntityRef [name]
    writeNamespace [prefix namespace-uri]
    writeDefaultNamespace [namespace-uri]
    writeAttribute [local-name value]
    writeAttribute [namespace-uri local-name value]
    writeAttribute [prefix namespace-uri local-name value])
  (writeStartElement [this local-name])
  (writeStartElement [this namespace-uri local-name])
  (writeStartElement [this prefix namespace-uri local-name])
  (writeEmptyElement [this local-name])
  (writeEmptyElement [this namespace-uri local-name])
  (writeEmptyElement [this prefix namespace-uri local-name])
  (writeEndElement [this])
  (writeComment [this data])
  (writeProcessingInstruction [this target])
  (writeProcessingInstruction [this target data])
  (writeCData [this data])
  (writeCharacters [this text start length])
  (writeCharacters [this text])
  (writeDTD [this dtd])
  (writeStartDocument [this])
  (writeStartDocument [this version])
  (writeStartDocument [this encoding version])
  (writeEndDocument [this]))

(defn ^javax.xml.transform.Transformer indenting-transformer []
  (doto (-> (javax.xml.transform.TransformerFactory/newInstance) .newTransformer)
    (.setOutputProperty (javax.xml.transform.OutputKeys/INDENT) "yes")
    (.setOutputProperty (javax.xml.transform.OutputKeys/METHOD) "xml")
    (.setOutputProperty "{http://xml.apache.org/xslt}indent-amount" "2")))

(defn indent
  "Emits the XML and indents the result.  WARNING: this is slow
   it will emit the XML and read it in again to indent it.  Intended for 
   debugging/testing only."
  [e ^java.io.Writer stream & {:as opts}]
  (let [sw (java.io.StringWriter.)
        _ (apply emit e sw (apply concat opts))
        source (-> sw .toString java.io.StringReader. javax.xml.transform.stream.StreamSource.)
        result (javax.xml.transform.stream.StreamResult. stream)]
    (.transform (indenting-transformer) source result)))

(defn indent-str
  "Emits the XML and indents the result.  Writes the results to a String and returns it"
  [e]
  (let [^java.io.StringWriter sw (java.io.StringWriter.)]
    (indent e sw)
    (.toString sw)))

