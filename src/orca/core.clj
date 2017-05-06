(ns orca.core
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.data :refer [diff]]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [org.apache.hadoop.hive.ql.exec.vector
            VectorizedRowBatch ColumnVector DecimalColumnVector DoubleColumnVector LongColumnVector BytesColumnVector TimestampColumnVector
            ListColumnVector StructColumnVector]
           [org.apache.orc OrcFile Reader Writer TypeDescription TypeDescription$Category]
           [org.apache.hadoop.conf Configuration]
           [org.apache.hadoop.fs Path]
           [org.apache.hadoop.hive.serde2.io HiveDecimalWritable]
           [java.nio.charset Charset]
           [java.time Duration Instant LocalDate]
           [java.time.format DateTimeFormatter DateTimeParseException]
           [java.time.temporal ChronoUnit]))


(def ^Charset serialization-charset (Charset/forName "UTF-8"))

(set! *warn-on-reflection* true)

(defn to-path
  [x]
  {:post (instance? Path %)}
  (cond
    (instance? java.net.URL x) (Path. (.toURI ^java.net.URL x))
    (instance? java.io.File x) (Path. (.getPath ^java.io.File x))
    (string? x)                (Path. ^String x)
    (instance? Path x)         x))

(defn ^Reader file-reader
  "Creates an ORC reader for a given file or path."
  [path]
  (OrcFile/createReader (to-path path) (OrcFile/readerOptions (Configuration.))))

(defprotocol ColumnValueReader
  (read-value [col schema idx]))

(defprotocol ColumnValueWriter
  (write-value [col idx v schema opts]))

(defprotocol ByteConversion
  (to-bytes [x]))

(defprotocol RowWriter
  (write-row! [row batch idx schema opts]))

(defprotocol LongConversion
  (to-long [ld]))

(defprotocol InstantConversion
  (to-instant [ld opts]))

(defn decode-column [^ColumnVector col schema nrows]
  (loop [idx 0
         result (transient [])]
    (if (< idx nrows)
      (if (or (.noNulls col) (not (aget (.isNull col) idx)))
        (recur (inc idx) (conj! result (read-value col schema idx)))
        (recur (inc idx) (conj! result nil)))
      (persistent! result))))

(defn read-batch [frame ^VectorizedRowBatch batch ^TypeDescription schema]
  (let [nrows (.size batch)]
    (loop [frame frame
           [[i ^ColumnVector col column-name column-type] & more] (map vector (range) (.cols batch) (map keyword (.getFieldNames schema)) (.getChildren schema))]
      (let [coll  (get frame column-name [])
            frame (assoc frame column-name (into coll (decode-column col column-type nrows)))]
        (if (seq more)
          (recur frame more)
          frame)))))

(defn read-vectors
  "Synchrounously reads column vectors from input."
  [input]
  (let [reader        (file-reader (to-path input))
        schema        (.getSchema reader)
        batch         (.createRowBatch schema)
        record-reader (.rows reader)]
    (loop [frame {}]
      (if (.nextBatch record-reader batch)
        (recur (read-batch frame batch schema))
        frame))))

;; https://cwiki.apache.org/confluence/display/Hive/LanguageManual+Types
(derive ::array    ::compound)
(derive ::map      ::compound)
(derive ::struct   ::compound)
(derive ::union    ::compound)

(derive ::tinyint  ::integral)
(derive ::smallint ::integral)
(derive ::int      ::integral)
(derive ::bigint   ::integral)

;; allows implicity conversion as documented in https://cwiki.apache.org/confluence/display/Hive/LanguageManual+Types#LanguageManualTypes-AllowedImplicitConversions
(def implicit-conversions
  {::tinyint   #{::smallint ::int ::bigint ::float ::double ::decimal ::string ::varchar}
   ::smallint  #{::int ::bigint ::float ::double ::decimal ::string ::varchar}
   ::int       #{::bigint ::float ::double ::decimal ::string ::varchar}
   ::bigint    #{::float ::double ::decimal ::string ::varchar}
   ::float     #{::double ::decimal ::string ::varchar}
   ::double    #{::decimal ::string ::varchar}
   ::decimal   #{::string ::varchar}
   ::string    #{::double ::decimal ::varchar}
   ::varchar   #{::double ::decimal ::string}
   ::timestamp #{::string ::varchar}
   ::date      #{::string ::varchar}})

(defprotocol TypeInference
  (data-type [v])
  (data-props [v]))

(extend-protocol TypeInference
  (Class/forName "[C")
  (data-type [v] ::char)
  (data-props [v]))

(extend-protocol TypeInference
  ;; array      ListColumnVector
  java.util.List
  (data-type [v]
    (when (seq v)
      ::array))
  (data-props [v])

  ;; binary     BytesColumnVector

  ;; bigint     LongColumnVector
  java.math.BigInteger
  (data-type [v] ::bigint)
  (data-props [v])

  ;; boolean    LongColumnVector
  java.lang.Boolean
  (data-type [v] ::boolean)
  (data-props [v])

  ;; char       BytesColumnVector
  java.lang.Character
  (data-type [v] ::char)
  (data-props [v] {:length 1})

  ;; date       LongColumnVector
  java.time.LocalDate
  (data-type [v] ::date)
  (data-props [v])

  ;; org.joda.time.LocalDate
  ;; (data-type [v] ::date)
  ;; (data-props [v])

  ;; decimal    DecimalColumnVector
  java.math.BigDecimal
  (data-type [v] ::decimal)
  (data-props [v] {:scale (.scale v) :precision (.precision v)})

  ;; float      DoubleColumnVector
  java.lang.Float
  (data-type [v] ::float)
  (data-props [v])

  ;; double     DoubleColumnVector
  java.lang.Double
  (data-type [v] ::double)
  (data-props [v])

  ;; int        LongColumnVector
  ;; long       LongColumnVector
  ;; smallint   LongColumnVector
  ;; tinyint    LongColumnVector
  java.lang.Number
  (data-type [v]
    (let [x (long v)]
      (cond
        (>= x Byte/MIN_VALUE)    (cond
                                   (<= x Byte/MAX_VALUE)    ::tinyint
                                   (<= x Short/MAX_VALUE)   ::smallint
                                   (<= x Integer/MAX_VALUE) ::int
                                   :else ::bigint)
        (>= x Short/MIN_VALUE)   ::smallint
        (>= x Integer/MIN_VALUE) ::int
        :else                    ::bigint)))
  (data-props [v])

  ;; map        MapColumnVector
  java.util.Map
  (data-type [v] ::struct)
  (data-props [v])

  ;; struct     StructColumnVector

  ;; timestamp  TimestampColumnVector
  java.time.Instant
  (data-type [v] ::timestamp)
  (data-props [v])

  ;; uniontype  UnionColumnVector

  ;; string     BytesColumnVector
  ;; varchar    BytesCoumnVector
  java.lang.String
  (data-type [v] ::string)
  (data-props [v])

  clojure.lang.Named
  (data-type [v] (data-type (name v)))
  (data-props [v] (data-props (name v)))

  nil
  (data-type [v])
  (data-props [v]))

(defn stats [coll]
  (let [nrows (count coll)
        coll  (remove nil? coll)]
    {:sum   (reduce + coll)
     :min   (apply min coll)
     :max   (apply max coll)
     :count nrows}))

(defmulti infer-typedef
  (fn [x opts]
    (data-type x)))

(defn typedef
  ([x] (typedef x {}))
  ([x opts] (infer-typedef x opts)))

(defmethod infer-typedef :default [x opts]
  (if-let [props (data-props x)]
    [(data-type x) props]
    (data-type x)))

(defmethod infer-typedef ::decimal [x {:keys [min-decimal-scale min-decimal-precision] :as opts}]
  [(data-type x)
   (cond-> (data-props x)
     (integer? min-decimal-scale) (update :scale max min-decimal-scale)
     (integer? min-decimal-precision) (update :precision max min-decimal-precision))])

(defmethod infer-typedef ::map [x opts]
  [::map
   (reduce-kv
    (fn [kmap k v]
      (if-let [dt (data-type v)]
        (assoc kmap k (infer-typedef v))
        kmap))
    {}
    x)])

(defmethod infer-typedef ::struct [x opts]
  [::struct
   (reduce-kv
    (fn [kmap k v]
      (if-let [dt (data-type v)]
        (assoc kmap k (infer-typedef v opts))
        kmap))
    {}
    x)])

(defmethod infer-typedef ::array [x opts]
  (let [child-types (set (map #(infer-typedef % opts) (remove nil? x)))
        n-types     (count child-types)
        tdef        [::array]]
    (cond
      (zero? n-types) tdef
      (= n-types 1)   (conj tdef (first child-types))
      :else           (conj tdef child-types))))

(defn try-decimal [^String s {:keys [coerce-decimal-strings?] :as opts}]
  (when coerce-decimal-strings?
    (try
      (BigDecimal. s)
      (catch NumberFormatException ex))))

(defn try-date [^String s {:keys [coerce-date-strings?] :as opts}]
  (when coerce-date-strings?
    (try
      (LocalDate/parse s DateTimeFormatter/ISO_DATE)
      (catch DateTimeParseException ex))))

(defn try-timestamp [^String s {:keys [coerce-timestamp-strings?] :as opts}]
  (when coerce-timestamp-strings?
    (try
      (Instant/parse s)
      (catch DateTimeParseException ex))))

(defmethod infer-typedef ::string [x {:keys [coerce-decimal-strings? coerce-date-strings?] :as opts}]
  (or (some-> x (try-date opts) (infer-typedef opts))
      (some-> x (try-timestamp opts) (infer-typedef opts))
      (some-> x (try-decimal opts) (infer-typedef opts))
      ::string))

(defn typedef->schema
  "Creates an ORC TypeDescription"
  ([td] (typedef->schema td {}))
  ([td {:keys []}]
   (let [[dtype opts] (if (vector? td) td [td])]
     (case dtype
       ::boolean   (TypeDescription/createBoolean)
       ::tinyint   (TypeDescription/createByte)
       ::smallint  (TypeDescription/createShort)
       ::int       (TypeDescription/createInt)
       ::bigint    (TypeDescription/createLong)
       ::float     (TypeDescription/createFloat)
       ::double    (TypeDescription/createDouble)
       ::string    (TypeDescription/createString)
       ::date      (TypeDescription/createDate)
       ::timestamp (TypeDescription/createTimestamp)
       ::binary    (TypeDescription/createBinary)
       ::decimal   (let [{:keys [scale precision]} opts]
                     (cond-> (TypeDescription/createDecimal)
                       (number? scale) (.withScale scale)
                       (number? precision) (.withPrecision precision)))
       ::varchar   (TypeDescription/createVarchar)
       ::char      (TypeDescription/createChar )
       ::array     (TypeDescription/createList (typedef->schema opts))
       ::map       (let [key-types (set (map typedef (keys opts)))
                         ktype     (if (> (count key-types) 1)
                                     (typedef->schema [::union key-types])
                                     (typedef->schema (first key-types)))
                         val-types (set (vals opts))
                         vtype     (if (> (count val-types) 1)
                                     (typedef->schema [::union val-types])
                                     (typedef->schema (first val-types)))]
                     (TypeDescription/createMap ktype vtype))
       ::struct    (let [struct (TypeDescription/createStruct)]
                     (doseq [[k v] opts]
                       (.addField struct (name k) (typedef->schema v)))
                     struct)
       ::union     (let [utype (TypeDescription/createUnion)]
                     (doseq [child opts]
                       (.addUnionChild utype (typedef->schema child)))
                     utype)))))

(defn coerce [x y]
  (or (-> implicit-conversions x y)
      (-> implicit-conversions y x)))

(defn type-of [x]
  (if (vector? x)
    (first x)
    x))

(defn dispatch-merge [x y]
  (let [x-type (type-of x)
        y-type (type-of y)]
    (cond
      (= x y) ::match
      (= x-type y-type ::array) ::array
      (= x-type y-type ::decimal) ::decimal
      (= x-type y-type ::struct) ::struct
      (and (isa? x-type ::integral) (isa? y-type ::integral)) ::integral
      (and (not (vector? x)) (not (vector? y)) (boolean (coerce x y))) ::coercible
      :else (set [(type-of x) (type-of y)]))))

(defmulti combine-typedef dispatch-merge)
(defmulti simplify-typedef type-of)

(defmethod combine-typedef :default [x y]
  (throw (ex-info "unable to combine-typedef" {:x x :y y})))

(defmethod simplify-typedef :default [x] x)

(defn merge-typedef
  ([x] x)
  ([x y] (combine-typedef x y))
  ([x y & more]
   (reduce merge-typedef (merge-typedef x y) more)))

(defmethod combine-typedef ::integral [x y]
  (coerce x y))

(defmethod combine-typedef ::decimal [[_ x] [_ y]]
  [::decimal (merge-with max x y)])

(defmethod combine-typedef ::coercible [x y]
  (coerce x y))

(defmethod combine-typedef ::match [x _]
  x)

(defmethod combine-typedef ::array [[_ x] [_ y]]
  [::array (merge-typedef x y)])

(defmethod simplify-typedef ::array [x]
  (let [[_ x-params] x]
    (if (set? x-params)
      [::array (reduce merge-typedef (map simplify-typedef x-params))]
      x)))

(defmethod combine-typedef ::struct [[_ x] [_ y]]
  [::struct (reduce-kv (fn [m field field-type]
                         (assoc m field (merge-typedef (get x field field-type) field-type)))
                       x
                       y)])

(defmethod simplify-typedef ::struct [[_ x]]
  (let [reduce-fn (fn [params k v]
                    (if-let [new-val (simplify-typedef v)]
                      (assoc params k new-val)
                      params))
        params (reduce-kv reduce-fn {} x)]
    (when-not (empty? params)
      [::struct params])))

(defmethod combine-typedef #{::decimal ::string} [x y]
  ::string)

(defn rows->typedef
  "Infers a typedef from rows."
  [rows options]
  (->> rows
       (map #(typedef % options))
       (map simplify-typedef)
       (reduce merge-typedef)))

(defn set-null! [^ColumnVector col ^long idx]
  (set! (.noNulls col) false)
  (aset-boolean (.isNull col) idx true))

(extend-protocol ByteConversion
  java.lang.String
  (to-bytes [s] (.getBytes s serialization-charset)))

(extend-protocol InstantConversion
  Instant
  (to-instant [x _] x)

  String
  (to-instant [x opts] (Instant/parse x)))

(extend-protocol LongConversion
  Number
  (to-long [x] (long x))

  LocalDate
  (to-long [x] (.toEpochDay x))

  Boolean
  (to-long [b] (case b true 1 false 0)))

(extend-type DecimalColumnVector
  ColumnValueReader
  (read-value [arr schema idx]
    (let [^HiveDecimalWritable d (aget (.vector arr) idx)]
      (.bigDecimalValue (.getHiveDecimal d)))))

(extend-type LongColumnVector
  ColumnValueReader
  (read-value [arr ^TypeDescription schema idx]
    (condp = (.getCategory schema)
      TypeDescription$Category/DATE (LocalDate/ofEpochDay (aget (.vector arr) idx))
      (aget (.vector arr) idx)))

  ColumnValueWriter
  (write-value [col idx v _ opts]
    (aset-long (.vector col) idx (to-long v))))

(extend-type DoubleColumnVector
  ColumnValueReader
  (read-value [arr ^TypeDescription schema idx]
    (aget (.vector arr) idx))

  ColumnValueWriter
  (write-value [col idx v _ opts]
    (aset-double (.vector col) idx (double v))))

(extend-type BytesColumnVector
  ColumnValueReader
  (read-value [arr schema idx]
    (when-let [ba (aget (.vector arr) idx)]
      (String. ^"[B" ba (aget (.start arr) idx) (aget (.length arr) idx) serialization-charset)))

  ColumnValueWriter
  (write-value [col idx v _ opts]
    (.setVal col idx (to-bytes v))))

(extend-type TimestampColumnVector
  ColumnValueReader
  (read-value [arr schema idx]
    (.plusNanos (Instant/ofEpochMilli (aget (.time arr) idx)) (.getNanos arr idx)))

  ColumnValueWriter
  (write-value [col idx v schema opts]
    (.set col idx (java.sql.Timestamp/from ^Instant (to-instant v opts)))))

(extend-type ListColumnVector
  ColumnValueReader
  (read-value [col ^TypeDescription schema idx]
    (let [offset       (aget (.offsets col) idx)
          len          (aget (.lengths col) idx)
          child-col    (.child col)
          child-schema (first (.getChildren schema))]
      (mapv #(read-value child-col child-schema %) (range offset (+ offset len)))))

  ColumnValueWriter
  (write-value [col idx v ^TypeDescription schema opts]
    (let [child-col    (.child col)
          child-count  (.childCount col)
          child-schema (first (.getChildren schema))
          elems        (count v)
          _            (aset-long (.offsets col) idx child-count)
          _            (.ensureSize child-col (+ child-count elems) true)]
      (doseq [elem v
              :let [child-offset (.childCount col)]]
        (write-value (.child col) child-offset elem child-schema opts)
        (set! (.childCount col) (inc child-offset)))
      (aset-long (.lengths col) idx elems))))

(extend-type StructColumnVector
  ColumnValueReader
  (read-value [col ^TypeDescription schema idx]
    (reduce
     (fn [m [^ColumnVector field-col field-name ^TypeDescription field-type]]
       (let [v (read-value field-col field-type idx)]
         (if (nil? v)
           m
           (assoc m (keyword field-name) v))))
     {}
     (map vector (.fields col) (.getFieldNames schema) (.getChildren schema))))

  ColumnValueWriter
  (write-value [col idx v ^TypeDescription schema opts]
    (if (nil? v)
      (set-null! col idx)
      (doseq [[^ColumnVector field-col field-name ^TypeDescription field-type] (map vector (.fields col) (.getFieldNames schema) (.getChildren schema))
              :let [field-value (get v (keyword field-name))]]
        (if (nil? field-value)
          (set-null! field-col idx)
          (write-value field-col idx field-value field-type opts))))))

(extend-protocol RowWriter
  clojure.lang.IPersistentMap
  (write-row! [row ^VectorizedRowBatch batch idx ^TypeDescription schema opts]
    (doseq [[^ColumnVector col field child] (map vector (.cols batch) (.getFieldNames schema) (.getChildren schema))]
      (let [val (get row (keyword field))]
        (if (nil? val)
          (set-null! col idx)
          (write-value col idx val child opts)))))

  clojure.lang.Sequential
  (write-row! [row ^VectorizedRowBatch batch idx ^TypeDescription schema opts]
    (doseq [[^ColumnVector col v child] (map vector (.cols batch) row (.getChildren schema))]
      (if (nil? v)
        (set-null! col idx)
        (write-value col idx v child opts)))))

(defn write-rows
  "Write row-seq into an ORC file at path.

  Options:

  :overwrite?  - overwrites path if a file exists."
  [path row-seq schema & {:keys [overwrite?] :or {overwrite? false} :as opts}]
  (try
    (when overwrite?
      (.delete (io/file path)))
    (let [conf    (Configuration.)
          schema  (TypeDescription/fromString schema)
          options (.setSchema (OrcFile/writerOptions conf) schema)
          writer  (OrcFile/createWriter (to-path path) options)
          batch   (.createRowBatch schema)]
      (try
        (doseq [row-batch (partition-all 1024 row-seq)
                :let [batch-size (count row-batch)
                      _          (.ensureSize batch batch-size)]]
          (doseq [row row-batch
                  :let [idx (.size batch)]]
            (set! (.size batch) (inc idx))
            (write-row! row batch idx schema opts))
          (.addRowBatch writer batch)
          (.reset batch))
        (finally
          (.close writer))))
    (catch Exception ex
      (throw ex))))

(defn tmp-path []
  (let [tmp (java.io.File/createTempFile "test" (str (rand-int (Integer/MAX_VALUE))))
        path (.getPath tmp)]
    (.delete tmp)
    path))

(defn frame->vecs [frame]
  (apply map vector (vals frame)))

(defn frame->maps [frame]
  (map zipmap (repeat (keys frame)) (frame->vecs frame)))
