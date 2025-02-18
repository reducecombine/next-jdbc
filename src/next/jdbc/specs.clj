;; copyright (c) 2019-2021 Sean Corfield, all rights reserved

(ns next.jdbc.specs
  "Specs for the core API of next.jdbc.

  The functions from `next.jdbc`, `next.jdbc.sql`, and `next.jdbc.prepare`
  have specs here.

  Just `:args` are spec'd. These specs are intended to aid development
  with `next.jdbc` by catching simple errors in calling the library.
  The `connectable` argument is currently just `any?` but both
  `get-datasource` and `get-connection` have stricter specs.

  In addition, there is an `instrument` function that provides a simple
  way to instrument all of the `next.jdbc` functions, and `unstrument`
  to undo that."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.protocols :as p]
            [next.jdbc.sql :as sql])
  (:import (java.sql Connection PreparedStatement Statement)
           (javax.sql DataSource)))

(set! *warn-on-reflection* true)

(s/def ::dbtype string?)
(s/def ::dbname string?)
(s/def ::dbname-separator string?)
(s/def ::classname string?)
(s/def ::user string?)
(s/def ::password string?)
(s/def ::host (s/or :name string?
                    :none #{:none}))
(s/def ::host-prefix string?)
(s/def ::port pos-int?)
(s/def ::db-spec-map (s/keys :req-un [::dbtype ::dbname]
                             :opt-un [::classname
                                      ::user ::password
                                      ::host ::port
                                      ::dbname-separator
                                      ::host-prefix]))
(defn jdbc-url-format?
  "JDBC URLs must begin with `jdbc:` followed by the `dbtype` and
  a second colon. Note: `clojure.java.jdbc` incorrectly allowed
  `jdbc:` to be omitted at the beginning of a JDBC URL."
  [url]
  (re-find #"^jdbc:[^:]+:" url))
(s/def ::jdbcUrl (s/and string? jdbc-url-format?))
(comment
  (s/explain-data ::jdbcUrl "jdbc:somedb://some-host/dbname")
  (s/explain-data ::jdbcUrl "somedb://some-host/dbname"))
(s/def ::jdbc-url-map (s/keys :req-un [::jdbcUrl]))

(s/def ::connection #(instance? Connection %))
(s/def ::datasource #(instance? DataSource %))
(s/def ::prepared-statement #(instance? PreparedStatement %))
(s/def ::statement #(instance? Statement %))

(s/def ::db-spec (s/or :db-spec  ::db-spec-map
                       :jdbc-url ::jdbc-url-map
                       :string   ::jdbcUrl
                       :ds       ::datasource))
(s/def ::db-spec-or-jdbc (s/or :db-spec  ::db-spec-map
                               :jdbc-url ::jdbc-url-map))
(s/def ::proto-connectable (s/or :db-spec     ::db-spec
                                 :connectable #(satisfies? p/Connectable %)
                                 :sourceable  #(satisfies? p/Sourceable %)))

(s/def ::connectable any?)
(s/def ::key-map (s/map-of keyword? any?))
(s/def ::example-map (s/map-of keyword? any? :min-count 1))

;; can be a simple column name (keyword) or a pair of something and as alias
;; and that something can be a simple column name (keyword) or an arbitrary
;; expression (string) where we assume you know what you're doing
(s/def ::column-spec (s/or :column keyword?
                           :alias  (s/and vector?
                                          (s/cat :expr   (s/or :col keyword?
                                                               :str string?)
                                                 :column keyword?))))
(s/def ::columns (s/coll-of ::column-spec :kind vector?))

(s/def ::order-by-col (s/or :col keyword?
                            :dir (s/cat :col keyword?
                                        :dir #{:asc :desc})))
(s/def ::order-by (s/coll-of ::order-by-col :kind vector? :min-count 1))
(s/def ::opts-map (s/and (s/map-of keyword? any?)
                         (s/keys :opt-un [::columns ::order-by])))

(s/def ::transactable any?)

(s/def ::sql-params (s/and vector?
                           (s/cat :sql string?
                                  :params (s/* any?))))
(s/def ::params (s/coll-of any? :kind sequential?))

(s/def ::batch-size pos-int?)
(s/def ::large boolean?)
(s/def ::batch-opts (s/keys :opt-un [::batch-size ::large]))

(s/fdef jdbc/get-datasource
        :args (s/cat :spec ::proto-connectable))

(s/fdef jdbc/get-connection
        :args (s/cat :spec ::proto-connectable
                     :opts (s/? ::opts-map)))

(s/fdef jdbc/prepare
        :args (s/cat :connection ::connection
                     :sql-params ::sql-params
                     :opts (s/? ::opts-map)))

(s/fdef jdbc/plan
        :args (s/alt :prepared (s/cat :stmt ::statement)
                     :sql (s/cat :connectable ::connectable
                                 :sql-params (s/nilable ::sql-params)
                                 :opts (s/? ::opts-map))))

(s/fdef jdbc/execute!
        :args (s/alt :prepared (s/cat :stmt ::statement)
                     :sql (s/cat :connectable ::connectable
                                 :sql-params (s/nilable ::sql-params)
                                 :opts (s/? ::opts-map))))

(s/fdef jdbc/execute-one!
        :args (s/alt :prepared (s/cat :stmt ::statement)
                     :sql (s/cat :connectable ::connectable
                                 :sql-params (s/nilable ::sql-params)
                                 :opts (s/? ::opts-map))))

(s/fdef jdbc/execute-batch!
        :args (s/alt :prepared (s/cat :ps ::prepared-statement
                                      :param-groups (s/coll-of ::params :kind sequential?)
                                      :opts (s/? ::batch-opts))
                     :sql (s/cat :connectable ::connectable
                                 :sql string?
                                 :param-groups (s/coll-of ::params :kind sequential?)
                                 :opts ::batch-opts)))

(s/fdef jdbc/transact
        :args (s/cat :transactable ::transactable
                     :f fn?
                     :opts (s/? ::opts-map)))

(s/fdef jdbc/with-options
        :args (s/cat :connectable ::connectable
                     :opts ::opts-map))

(s/fdef jdbc/with-transaction
        :args (s/cat :binding (s/and vector?
                                     (s/cat :sym simple-symbol?
                                            :transactable ::transactable
                                            :opts (s/? ::opts-map)))
                     :body (s/* any?)))

(s/fdef connection/->pool
        :args (s/cat :clazz #(instance? Class %)
                     :db-spec ::db-spec-or-jdbc))

(s/fdef connection/component
        :args (s/cat :clazz #(instance? Class %)
                     :db-spec ::db-spec-or-jdbc
                     :close-fn (s/? fn?)))

(s/fdef prepare/set-parameters
        :args (s/cat :ps ::prepared-statement
                     :params ::params))

(s/fdef prepare/statement
        :args (s/cat :connection ::connection
                     :opts (s/? ::opts-map)))

(s/fdef sql/insert!
        :args (s/cat :connectable ::connectable
                     :table keyword?
                     :key-map ::key-map
                     :opts (s/? ::opts-map)))

(s/fdef sql/insert-multi!
        :args
        (s/or
          :with-rows-and-columns
          (s/and (s/cat :connectable ::connectable
                        :table keyword?
                        :cols (s/coll-of keyword?
                                         :kind sequential?
                                         :min-count 1)
                        :rows (s/coll-of (s/coll-of any? :kind sequential?)
                                         :kind sequential?)
                        :opts (s/? ::opts-map))
                 #(apply = (count (:cols %))
                         (map count (:rows %))))
          :with-hash-maps
          (s/cat :connectable ::connectable
                 :table keyword?
                 :hash-maps (s/coll-of map?
                                       :kind sequential?
                                       :min-count 1)
                 :opts (s/? ::opts-map))))

(s/fdef sql/query
        :args (s/cat :connectable ::connectable
                     :sql-params ::sql-params
                     :opts (s/? ::opts-map)))

(s/fdef sql/find-by-keys
        :args (s/cat :connectable ::connectable
                     :table keyword?
                     :key-map (s/or :example ::example-map
                                    :where ::sql-params
                                    :all #{:all})
                     :opts (s/? ::opts-map)))

(s/fdef sql/get-by-id
        :args (s/alt :with-id (s/cat :connectable ::connectable
                                     :table keyword?
                                     :pk any?
                                     :opts (s/? ::opts-map))
                     :pk-name (s/cat :connectable ::connectable
                                     :table keyword?
                                     :pk any?
                                     :pk-name keyword?
                                     :opts ::opts-map)))

(s/fdef sql/update!
        :args (s/cat :connectable ::connectable
                     :table keyword?
                     :key-map ::key-map
                     :where-params (s/or :example ::example-map
                                         :where ::sql-params)
                     :opts (s/? ::opts-map)))

(s/fdef sql/delete!
        :args (s/cat :connectable ::connectable
                     :table keyword?
                     :where-params (s/or :example ::example-map
                                         :where ::sql-params)
                     :opts (s/? ::opts-map)))

(def ^:private fns-with-specs
  [`jdbc/get-datasource
   `jdbc/get-connection
   `jdbc/prepare
   `jdbc/plan
   `jdbc/execute!
   `jdbc/execute-one!
   `jdbc/execute-batch!
   `jdbc/transact
   `jdbc/with-transaction
   `jdbc/with-options
   `connection/->pool
   `connection/component
   `prepare/set-parameters
   `prepare/statement
   `sql/insert!
   `sql/insert-multi!
   `sql/query
   `sql/find-by-keys
   `sql/get-by-id
   `sql/update!
   `sql/delete!])

(defn instrument []
  (st/instrument fns-with-specs))

(defn unstrument []
  (st/unstrument fns-with-specs))
