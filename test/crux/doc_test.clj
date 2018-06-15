(ns crux.doc-test
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [crux.byte-utils :as bu]
            [crux.db :as db]
            [crux.doc :as doc]
            [crux.index :as idx]
            [crux.tx :as tx]
            [crux.kv-store :as ks]
            [crux.rdf :as rdf]
            [crux.fixtures :as f]
            [taoensso.nippy :as nippy])
  (:import [java.util Date]
           [java.nio ByteBuffer]))

(t/use-fixtures :each f/with-each-kv-store-implementation f/with-kv-store)

(defn load-ntriples-example [resource]
  (with-open [in (io/input-stream (io/resource resource))]
    (->> (rdf/ntriples-seq in)
         (rdf/statements->maps)
         (map #(rdf/use-default-language % :en))
         (#(rdf/maps-by-iri % false)))))

(t/deftest test-can-store-doc
  (let [tx-log (tx/->DocTxLog f/*kv*)
        object-store (doc/->DocObjectStore f/*kv*)
        picasso (-> (load-ntriples-example "crux/Pablo_Picasso.ntriples")
                    :http://dbpedia.org/resource/Pablo_Picasso)
        content-hash (idx/new-id picasso)]
    (t/is (= 47 (count picasso)))
    (t/is (= "Pablo" (:http://xmlns.com/foaf/0.1/givenName picasso)))

    (db/submit-doc tx-log content-hash picasso)
    (t/is (= {content-hash picasso}
             (db/get-objects object-store [content-hash])))

    (t/testing "non existent docs are ignored"
      (t/is (= {content-hash picasso}
               (db/get-objects object-store
                               [content-hash
                                "090622a35d4b579d2fcfebf823821298711d3867"])))
      (t/is (empty? (db/get-objects object-store []))))))

(t/deftest test-can-index-tx-ops
  (let [tx-log (tx/->DocTxLog f/*kv*)
        object-store (doc/->DocObjectStore f/*kv*)
        picasso (-> (load-ntriples-example "crux/Pablo_Picasso.ntriples")
                    :http://dbpedia.org/resource/Pablo_Picasso)
        content-hash (idx/new-id picasso)
        business-time #inst "2018-05-21"
        eid (idx/new-id :http://dbpedia.org/resource/Pablo_Picasso)
        {:keys [transact-time tx-id]}
        @(db/submit-tx tx-log [[:crux.tx/put :http://dbpedia.org/resource/Pablo_Picasso picasso business-time]])
        expected-entities [{:eid eid
                            :content-hash content-hash
                            :bt business-time
                            :tt transact-time
                            :tx-id tx-id}]]

    (with-open [snapshot (ks/new-snapshot f/*kv*)]
      (t/testing "can see entity at transact and business time"
        (t/is (= expected-entities
                 (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] transact-time transact-time)))
        (t/is (= expected-entities
                 (doc/all-entities snapshot transact-time transact-time))))

      (t/testing "can find entity by secondary index"
        (t/testing "single value attribute")
        (t/is (= expected-entities
                 (doc/entities-by-attribute-value-at snapshot :http://xmlns.com/foaf/0.1/givenName "Pablo" "Pablo" transact-time transact-time)))

        (t/testing "find multi valued attribute"
          (t/is (= expected-entities
                   (doc/entities-by-attribute-value-at
                    snapshot
                    :http://purl.org/dc/terms/subject
                    :http://dbpedia.org/resource/Category:Cubist_artists :http://dbpedia.org/resource/Category:Cubist_artists
                    transact-time transact-time))))

        (t/testing "find attribute by range"
          (t/is (= expected-entities
                   (doc/entities-by-attribute-value-at
                    snapshot
                    :http://dbpedia.org/property/imageSize
                    230 230
                    transact-time transact-time)))

          (t/is (= expected-entities
                   (doc/entities-by-attribute-value-at
                    snapshot
                    :http://dbpedia.org/property/imageSize
                    229
                    230
                    transact-time transact-time)))
          (t/is (= expected-entities
                   (doc/entities-by-attribute-value-at
                    snapshot
                    :http://dbpedia.org/property/imageSize
                    229
                    231
                    transact-time transact-time)))
          (t/is (= expected-entities
                   (doc/entities-by-attribute-value-at
                    snapshot
                    :http://dbpedia.org/property/imageSize
                    230
                    231
                    transact-time transact-time)))

          (t/is (empty?
                 (doc/entities-by-attribute-value-at
                  snapshot
                  :http://dbpedia.org/property/imageSize
                  231
                  255
                  transact-time transact-time)))
          (t/is (empty?
                 (doc/entities-by-attribute-value-at
                  snapshot
                  :http://dbpedia.org/property/imageSize
                  1
                  229
                  transact-time transact-time)))
          (t/is (empty?
                 (doc/entities-by-attribute-value-at
                  snapshot
                  :http://dbpedia.org/property/imageSize
                  -255
                  229
                  transact-time transact-time)))))

      (t/testing "cannot see entity before business or transact time"
        (t/is (empty? (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] #inst "2018-05-20" transact-time)))
        (t/is (empty? (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] transact-time #inst "2018-05-20")))

        (t/is (empty? (doc/all-entities snapshot #inst "2018-05-20" transact-time)))
        (t/is (empty? (doc/all-entities snapshot transact-time #inst "2018-05-20"))))

      (t/testing "can see entity after business or transact time"
        (t/is (some? (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] #inst "2018-05-22" transact-time)))
        (t/is (some? (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] transact-time #inst "2018-05-22"))))

      (t/testing "can see entity history"
        (t/is (= [{:eid eid
                   :content-hash content-hash
                   :bt business-time
                   :tt transact-time
                   :tx-id tx-id}]
                 (doc/entity-history snapshot :http://dbpedia.org/resource/Pablo_Picasso)))))

    (t/testing "add new version of entity in the past"
      (let [new-picasso (assoc picasso :foo :bar)
            new-content-hash (idx/new-id new-picasso)
            new-business-time #inst "2018-05-20"
            {new-transact-time :transact-time
             new-tx-id :tx-id}
            @(db/submit-tx tx-log [[:crux.tx/put :http://dbpedia.org/resource/Pablo_Picasso new-picasso new-business-time]])]

        (with-open [snapshot (ks/new-snapshot f/*kv*)]
          (t/is (= [{:eid eid
                     :content-hash new-content-hash
                     :bt new-business-time
                     :tt new-transact-time
                     :tx-id new-tx-id}]
                   (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] new-business-time new-transact-time)))
          (t/is (= [{:eid eid
                     :content-hash new-content-hash
                     :bt new-business-time
                     :tt new-transact-time
                     :tx-id new-tx-id}] (doc/all-entities snapshot new-business-time new-transact-time)))

          (t/is (empty? (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] #inst "2018-05-20" #inst "2018-05-21"))))))

    (t/testing "add new version of entity in the future"
      (let [new-picasso (assoc picasso :baz :boz)
            new-content-hash (idx/new-id new-picasso)
            new-business-time #inst "2018-05-22"
            {new-transact-time :transact-time
             new-tx-id :tx-id}
            @(db/submit-tx tx-log [[:crux.tx/put :http://dbpedia.org/resource/Pablo_Picasso new-picasso new-business-time]])]

        (with-open [snapshot (ks/new-snapshot f/*kv*)]
          (t/is (= [{:eid eid
                     :content-hash new-content-hash
                     :bt new-business-time
                     :tt new-transact-time
                     :tx-id new-tx-id}]
                   (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] new-business-time new-transact-time)))
          (t/is (= [{:eid eid
                     :content-hash content-hash
                     :bt business-time
                     :tt transact-time
                     :tx-id tx-id}]
                   (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] new-business-time transact-time)))
          (t/is (= [{:eid eid
                     :content-hash new-content-hash
                     :bt new-business-time
                     :tt new-transact-time
                     :tx-id new-tx-id}] (doc/all-entities snapshot new-business-time new-transact-time))))

        (t/testing "can correct entity at earlier business time"
          (let [new-picasso (assoc picasso :bar :foo)
                new-content-hash (idx/new-id new-picasso)
                prev-transact-time new-transact-time
                prev-tx-id new-tx-id
                new-business-time #inst "2018-05-22"
                {new-transact-time :transact-time
                 new-tx-id :tx-id}
                @(db/submit-tx tx-log [[:crux.tx/put :http://dbpedia.org/resource/Pablo_Picasso new-picasso new-business-time]])]

            (with-open [snapshot (ks/new-snapshot f/*kv*)]
              (t/is (= [{:eid eid
                         :content-hash new-content-hash
                         :bt new-business-time
                         :tt new-transact-time
                         :tx-id new-tx-id}]
                       (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] new-business-time new-transact-time)))
              (t/is (= [{:eid eid
                         :content-hash new-content-hash
                         :bt new-business-time
                         :tt new-transact-time
                         :tx-id new-tx-id}] (doc/all-entities snapshot new-business-time new-transact-time)))

              (t/is (= prev-tx-id (-> (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] prev-transact-time prev-transact-time)
                                      (first)
                                      :tx-id))))

            (t/testing "compare and set does nothing with wrong content hash"
              (let [old-picasso (assoc picasso :baz :boz)]
                @(db/submit-tx tx-log [[:crux.tx/cas :http://dbpedia.org/resource/Pablo_Picasso old-picasso new-picasso new-business-time]])
                (with-open [snapshot (ks/new-snapshot f/*kv*)]
                  (t/is (= [{:eid eid
                             :content-hash new-content-hash
                             :bt new-business-time
                             :tt new-transact-time
                             :tx-id new-tx-id}]
                           (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] new-business-time new-transact-time))))))

            (t/testing "compare and set updates with correct content hash"
              (let [old-picasso new-picasso
                    new-picasso (assoc old-picasso :baz :boz)
                    new-content-hash (idx/new-id new-picasso)
                    {new-transact-time :transact-time
                     new-tx-id :tx-id}
                    @(db/submit-tx tx-log [[:crux.tx/cas :http://dbpedia.org/resource/Pablo_Picasso old-picasso new-picasso new-business-time]])]
                (with-open [snapshot (ks/new-snapshot f/*kv*)]
                  (t/is (= [{:eid eid
                             :content-hash new-content-hash
                             :bt new-business-time
                             :tt new-transact-time
                             :tx-id new-tx-id}]
                           (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] new-business-time new-transact-time))))))))

        (t/testing "can delete entity"
          (let [new-business-time #inst "2018-05-23"
                {new-transact-time :transact-time
                 new-tx-id :tx-id}
                @(db/submit-tx tx-log [[:crux.tx/delete :http://dbpedia.org/resource/Pablo_Picasso new-business-time]])]
            (with-open [snapshot (ks/new-snapshot f/*kv*)]
              (t/is (empty? (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] new-business-time new-transact-time)))
              (t/testing "first version of entity is still visible in the past"
                (t/is (= tx-id (-> (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] business-time new-transact-time)
                                   (first)
                                   :tx-id)))))))))

    (t/testing "can retrieve history of entity"
      (with-open [snapshot (ks/new-snapshot f/*kv*)]
        (let [picasso-history (doc/entity-history snapshot :http://dbpedia.org/resource/Pablo_Picasso)]
          (t/is (= 6 (count (map :content-hash picasso-history)))))))

    (t/testing "can evict entity"
      (let [new-business-time #inst "2018-05-23"
            {new-transact-time :transact-time
             new-tx-id :tx-id}
            @(db/submit-tx tx-log [[:crux.tx/evict :http://dbpedia.org/resource/Pablo_Picasso new-business-time]])]

        (with-open [snapshot (ks/new-snapshot f/*kv*)]
          (t/is (empty? (doc/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] new-business-time new-transact-time)))

          (t/testing "eviction adds to and keeps tx history"
            (let [picasso-history (doc/entity-history snapshot :http://dbpedia.org/resource/Pablo_Picasso)]
              ;; TODO: this is flaky
              ;; (t/is (= 7 (count (map :content-hash picasso-history))))
              (t/testing "eviction removes docs"
                (t/is (empty? (db/get-objects object-store (keep :content-hash picasso-history)))))))

          (t/testing "eviction removes secondary indexes"
            (t/is (empty? (doc/entities-by-attribute-value-at snapshot :http://xmlns.com/foaf/0.1/givenName "Pablo" "Pablo"
                                                              new-transact-time new-transact-time)))))))))

(t/deftest test-can-perform-unary-leapfrog-join
  (let [tx-log (tx/->DocTxLog f/*kv*)
        tx-ops (vec (concat (for [[relation vs] {:a [0 1 3 4 5 6 7 8 8 9 11 12]
                                                 :b [0 2 6 7 8 9 12 12]
                                                 :c [2 4 5 8 10 12 12]}
                                  [i v] (map-indexed vector vs)
                                  :let [eid (keyword (str (name relation) i "-" v))]]
                              [:crux.tx/put eid {:crux.db/id eid relation v}])))
        {:keys [transact-time tx-id]}
        @(db/submit-tx tx-log tx-ops)]
    (with-open [snapshot (ks/new-snapshot f/*kv*)]
      (t/testing "checking data is loaded before join"
        (t/is (= (idx/new-id :a0-0)
                 (:eid (first (doc/entities-at snapshot [:a0-0] transact-time transact-time)))))
        (t/is (= (count tx-ops) (count (doc/all-entities snapshot transact-time transact-time)))))

      (t/testing "unary leapfrog join"
        (t/is (= [{:a #{(idx/new-id :a7-8)
                        (idx/new-id :a8-8)}
                   :b #{(idx/new-id :b4-8)}
                   :c #{(idx/new-id :c3-8)}}
                  {:a #{(idx/new-id :a11-12)}
                   :b #{(idx/new-id :b6-12)
                        (idx/new-id :b7-12)}
                   :c #{(idx/new-id :c5-12)
                        (idx/new-id :c6-12)}}]
                 (for [[v matches] (doc/unary-leapfrog-join snapshot [:a :b :c] nil nil transact-time transact-time)]
                   (->> (for [[k entities] matches]
                          [k (set (map :eid entities))])
                        (into {})))))))))

;; Q(a, b, c) ← R(a, b), S(b, c), T (a, c).

;; (1, 3, 4)
;; (1, 3, 5)
;; (1, 4, 6)
;; (1, 4, 8)
;; (1, 4, 9)
;; (1, 5, 2)
;; (3, 5, 2)

;; TODO: figure out if this is correct with/without shared attrs?
;; Fix ordering test, commented out below - might be related issue.
(t/deftest test-can-perform-leapfrog-triejoin
  (let [data [{:crux.db/id :r13 :ra 1 :rb 3}
              {:crux.db/id :r14 :ra 1 :rb 4}
              {:crux.db/id :r15 :ra 1 :rb 5}
              {:crux.db/id :r35 :ra 3 :rb 5}
              {:crux.db/id :s34 :sb 3 :sc 4}
              {:crux.db/id :s35 :sb 3 :sc 5}
              {:crux.db/id :s46 :sb 4 :sc 6}
              {:crux.db/id :s48 :sb 4 :sc 8}
              {:crux.db/id :s49 :sb 4 :sc 9}
              {:crux.db/id :s52 :sb 5 :sc 2}
              {:crux.db/id :t14 :ta 1 :tc 4}
              {:crux.db/id :t15 :ta 1 :tc 5}
              {:crux.db/id :t16 :ta 1 :tc 6}
              {:crux.db/id :t18 :ta 1 :tc 8}
              {:crux.db/id :t19 :ta 1 :tc 9}
              {:crux.db/id :t12 :ta 1 :tc 2}
              {:crux.db/id :t32 :ta 3 :tc 2}]]
    (let [tx-log (tx/->DocTxLog f/*kv*)
          tx-ops (vec (concat (for [{:keys [crux.db/id] :as doc} data]
                                [:crux.tx/put id doc])))
          {:keys [transact-time tx-id]}
          @(db/submit-tx tx-log tx-ops)]
      (with-open [snapshot (ks/new-snapshot f/*kv*)]
        (t/testing "checking data is loaded before join"
          (t/is (= (count tx-ops)
                   (count (doc/all-entities snapshot transact-time transact-time)))))

        (t/testing "leapfrog triejoin"
          (let [result (doc/leapfrog-triejoin snapshot
                                              [[:ra :ta [nil nil]]
                                               [:rb :sb [nil nil]]
                                               [:sc :tc [nil nil]]]
                                              [[:ra :rb]
                                               [:sb :sc]
                                               [:ta :tc]]
                                              transact-time
                                              transact-time)]
            (t/testing "order of results"
              (t/is (= (vec (for [[a b c] [[1 3 4]
                                           [1 3 5]
                                           [1 4 6]
                                           [1 4 8]
                                           [1 4 9]
                                           [1 5 2]
                                           [3 5 2]]]
                              [(bu/bytes->hex (idx/value->bytes a))
                               (bu/bytes->hex (idx/value->bytes b))
                               (bu/bytes->hex (idx/value->bytes c))]))
                       (vec (for [[[a b c] _] result]
                              [(bu/bytes->hex a)
                               (bu/bytes->hex b)
                               (bu/bytes->hex c)])))))
            (t/is (= (set (map (comp idx/new-id :crux.db/id) data))
                     (set (for [[v matches] result
                                [k entities] matches
                                {:keys [eid]} entities]
                            eid))))))))))

(t/deftest test-leapfrog-triejoin-prunes-values-based-on-later-joins
  (let [data [;; d365d8e84bb127ed8f4d076f7528641a7ce08049
              {:crux.db/id :r13 :ra 1 :rb 3}
              ;; Unifies with :ta, but not with :sb
              ;; 597d68237e345bbb91eae7751e60a07fb904c8dd
              {:crux.db/id :r14 :ra 1 :rb 4}
              ;; Does not unify with :ta or :sb.
              {:crux.db/id :r25 :ra 2 :rb 5}
               ;; 9434448654674927dbc44b2280d44f92166ac350
              {:crux.db/id :s34 :sb 3 :sc 4}
              ;; Unifies with :rb, but not with :tc
              ;; b824a31f61bf0fc0b498aa038dd9ae5bd08adb64
              {:crux.db/id :s37 :sb 3 :sc 7}
               ;; eed43fbbc28c9b627a8b3e0fba770bab9d7a9465
              {:crux.db/id :t14 :ta 1 :tc 4}
              ;; Unifies with :ra, but not with :sc
              ;; 6c63a4086ad403653314c2ab546aadd54fff897d
              {:crux.db/id :t15 :ta 1 :tc 5}
              ;; Unifies with :sc, but not with :ra
              ;; 41c3f3e9370cc85a4fea723d35b7327d33067c6e
              {:crux.db/id :t34 :ta 3 :tc 4}]]
    (let [tx-log (tx/->DocTxLog f/*kv*)
          tx-ops (vec (concat (for [{:keys [crux.db/id] :as doc} data]
                                [:crux.tx/put id doc])))
          {:keys [transact-time tx-id]}
          @(db/submit-tx tx-log tx-ops)]
      (with-open [snapshot (ks/new-snapshot f/*kv*)]
        (t/is (= #{(idx/new-id :r13)
                   (idx/new-id :s34)
                   (idx/new-id :t14)}
                 (set (for [[v matches] (doc/leapfrog-triejoin snapshot
                                                               [[:ra :ta [nil nil]]
                                                                [:rb :sb [nil nil]]
                                                                [:sc :tc [nil nil]]]
                                                               [[:ra :rb]
                                                                [:sb :sc]
                                                                [:ta :tc]]
                                                               transact-time
                                                               transact-time)
                            [_ entities] matches
                            {:keys [eid]} entities]
                        eid))))))))

(t/deftest test-store-and-retrieve-meta
  (t/is (nil? (doc/read-meta f/*kv* :foo)))
  (doc/store-meta f/*kv* :foo {:bar 2})
  (t/is (= {:bar 2} (doc/read-meta f/*kv* :foo))))
