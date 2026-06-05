/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.search;

import java.io.IOException;
import java.util.Set;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;

/**
 * Tests for {@link QueryReadHint} plumbing on {@link IndexSearcher}, {@link Weight}, and {@link
 * ScorerSupplier}. The plumbing itself is opt-in: this test only verifies that:
 *
 * <ul>
 *   <li>defaults are empty,
 *   <li>{@link IndexSearcher#setReadHints(Set)} stores an immutable, defensively-copied snapshot,
 *   <li>hints are reachable through {@link Weight#readHints()} and {@link
 *       ScorerSupplier#readHints()} when a query opts in,
 *   <li>{@link FilterWeight} forwards {@link Weight#readHints()}.
 * </ul>
 */
public class TestQueryReadHint extends LuceneTestCase {

  public void testDefaultsAreEmpty() {
    Weight w =
        new Weight(new MatchAllDocsQuery()) {
          @Override
          public Explanation explain(LeafReaderContext context, int doc) {
            return Explanation.noMatch("");
          }

          @Override
          public ScorerSupplier scorerSupplier(LeafReaderContext context) {
            return null;
          }

          @Override
          public boolean isCacheable(LeafReaderContext ctx) {
            return false;
          }
        };
    assertEquals(Set.of(), w.readHints());

    ScorerSupplier ss =
        new ScorerSupplier() {
          @Override
          public Scorer get(long leadCost) {
            return null;
          }

          @Override
          public long cost() {
            return 0;
          }
        };
    assertEquals(Set.of(), ss.readHints());
  }

  public void testIndexSearcherStoresHintsImmutably() throws IOException {
    try (Directory dir = newDirectory()) {
      try (IndexWriter iw = new IndexWriter(dir, newIndexWriterConfig())) {
        Document doc = new Document();
        doc.add(new StringField("f", "v", Field.Store.NO));
        iw.addDocument(doc);
      }
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        IndexSearcher s = new IndexSearcher(r);
        assertEquals(Set.of(), s.getReadHints());

        s.setReadHints(Set.of(QueryAccessHint.POINT));
        assertEquals(Set.of(QueryAccessHint.POINT), s.getReadHints());

        // returned set must be immutable
        Set<QueryReadHint> snapshot = s.getReadHints();
        expectThrows(UnsupportedOperationException.class, () -> snapshot.add(QueryAccessHint.BULK));

        // mutating an externally-passed set must NOT affect the searcher (defensive copy)
        java.util.HashSet<QueryReadHint> mutable = new java.util.HashSet<>();
        mutable.add(QueryAccessHint.BULK);
        s.setReadHints(mutable);
        mutable.add(QueryAccessHint.POINT);
        assertEquals(Set.of(QueryAccessHint.BULK), s.getReadHints());

        // empty restores default
        s.setReadHints(Set.of());
        assertEquals(Set.of(), s.getReadHints());

        expectThrows(NullPointerException.class, () -> s.setReadHints(null));
      }
    }
  }

  public void testHintsReachableThroughWeightAndScorerSupplier() throws IOException {
    try (Directory dir = newDirectory()) {
      try (IndexWriter iw = new IndexWriter(dir, newIndexWriterConfig())) {
        Document doc = new Document();
        doc.add(new StringField("f", "v", Field.Store.NO));
        iw.addDocument(doc);
      }
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        // Use IndexSearcher directly: newSearcher may wrap with AssertingIndexSearcher
        // which delegates createWeight to its inner searcher, bypassing the hints set
        // on the outer wrapper. The hint-propagation contract being tested here is on
        // the searcher instance that Query#createWeight receives, which is whatever the
        // public IndexSearcher API hands it.
        IndexSearcher s = new IndexSearcher(r);
        // Disable the query cache so we exercise the actual Weight/ScorerSupplier we
        // build, rather than LRUQueryCache's wrapper. The cache wrapper deliberately
        // does not forward read hints because a cached query iterates a heap-resident
        // bitset and never touches the readers the hints are meant to influence.
        s.setQueryCache(null);
        Set<QueryReadHint> hints = Set.of(QueryAccessHint.POINT);
        s.setReadHints(hints);

        Query q = new HintAwareQuery();
        Weight w = s.createWeight(s.rewrite(q), ScoreMode.COMPLETE_NO_SCORES, 1f);
        assertEquals(hints, w.readHints());

        for (LeafReaderContext ctx : r.leaves()) {
          ScorerSupplier ss = w.scorerSupplier(ctx);
          assertNotNull(ss);
          assertEquals(hints, ss.readHints());
        }
      }
    }
  }

  public void testFilterWeightForwardsReadHints() throws IOException {
    Weight inner =
        new Weight(new MatchAllDocsQuery()) {
          @Override
          public Set<QueryReadHint> readHints() {
            return Set.of(QueryAccessHint.BULK);
          }

          @Override
          public Explanation explain(LeafReaderContext context, int doc) {
            return Explanation.noMatch("");
          }

          @Override
          public ScorerSupplier scorerSupplier(LeafReaderContext context) {
            return null;
          }

          @Override
          public boolean isCacheable(LeafReaderContext ctx) {
            return false;
          }
        };
    FilterWeight fw = new FilterWeight(inner) {
          /* inherits everything */
        };
    assertEquals(Set.of(QueryAccessHint.BULK), fw.readHints());
  }

  /**
   * Minimal query that captures the searcher's read hints at weight-creation time and propagates
   * them to its per-leaf ScorerSupplier. This is the canonical pattern queries are expected to
   * follow when they want to honor hints.
   */
  private static final class HintAwareQuery extends Query {
    HintAwareQuery() {}

    @Override
    public Weight createWeight(IndexSearcher s, ScoreMode scoreMode, float boost) {
      final Set<QueryReadHint> captured = s.getReadHints();
      return new Weight(this) {
        @Override
        public Set<QueryReadHint> readHints() {
          return captured;
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) {
          return Explanation.noMatch("");
        }

        @Override
        public ScorerSupplier scorerSupplier(LeafReaderContext context) {
          return new ScorerSupplier() {
            @Override
            public Set<QueryReadHint> readHints() {
              return captured;
            }

            @Override
            public Scorer get(long leadCost) {
              return null;
            }

            @Override
            public long cost() {
              return 0;
            }
          };
        }

        @Override
        public boolean isCacheable(LeafReaderContext ctx) {
          return true;
        }
      };
    }

    @Override
    public void visit(QueryVisitor visitor) {
      visitor.visitLeaf(this);
    }

    @Override
    public String toString(String field) {
      return "HintAwareQuery";
    }

    @Override
    public boolean equals(Object o) {
      return o == this;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }
  }
}
