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

package org.apache.lucene.search.knn;

import java.io.IOException;
import java.util.Set;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.search.QueryReadHint;
import org.apache.lucene.search.TopKnnCollector;

/** TopKnnCollectorManager responsible for creating {@link TopKnnCollector} instances. */
public class TopKnnCollectorManager implements KnnCollectorManager {

  // the number of docs to collect
  private final int k;
  // Per-query read hints carried from the IndexSearcher, surfaced to codec readers via
  // KnnCollector#readHints(). Empty (the default) means no hint and no extra allocation.
  private final Set<QueryReadHint> readHints;

  public TopKnnCollectorManager(int k, IndexSearcher indexSearcher) {
    this.k = k;
    this.readHints = indexSearcher == null ? Set.of() : indexSearcher.getReadHints();
  }

  /**
   * Return a new {@link TopKnnCollector} instance.
   *
   * @param visitedLimit the maximum number of nodes that the search is allowed to visit
   * @param context the leaf reader context
   */
  @Override
  public KnnCollector newCollector(
      int visitedLimit, KnnSearchStrategy searchStrategy, LeafReaderContext context)
      throws IOException {
    return withReadHints(new TopKnnCollector(k, visitedLimit, searchStrategy));
  }

  @Override
  public KnnCollector newOptimisticCollector(
      int visitedLimit, KnnSearchStrategy searchStrategy, LeafReaderContext context, int k) {
    return withReadHints(new TopKnnCollector(k, visitedLimit, searchStrategy));
  }

  @Override
  public boolean isOptimistic() {
    return true;
  }

  /**
   * Surface the searcher's read hints on the produced collector so codec readers (e.g. {@link
   * org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsReader}) can consult {@link
   * KnnCollector#readHints()}. This is what connects {@link IndexSearcher#setReadHints} to the
   * vector reader. When there are no hints the collector is returned unwrapped, so the default path
   * allocates nothing extra.
   */
  private KnnCollector withReadHints(KnnCollector collector) {
    if (readHints.isEmpty()) {
      return collector;
    }
    return new KnnCollector.Decorator(collector) {
      @Override
      public Set<QueryReadHint> readHints() {
        return readHints;
      }
    };
  }
}
