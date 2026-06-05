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

/**
 * Hint describing the access pattern of a query, intended to be consulted by readers when wiring up
 * per-query iteration.
 *
 * <p>This intentionally mirrors {@link org.apache.lucene.store.DataAccessHint} in spirit: it
 * describes <em>what is likely to happen</em>, not what mechanism should be used to satisfy it.
 * Readers may consult this hint to choose between, e.g., aggressive prefetch vs. cache-friendly
 * traversal, but they are not required to.
 *
 * @lucene.experimental
 */
public enum QueryAccessHint implements QueryReadHint {
  /**
   * The query expects to perform a small number of accesses and is latency-sensitive. Readers may
   * favor strategies that reduce per-access latency, such as prefetching the blocks a single
   * traversal is about to touch. Consumed by {@link
   * org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsReader}, which prefetches each batch of
   * neighbour vectors before scoring them.
   */
  POINT,

  /**
   * The query expects to perform many accesses and is throughput-oriented (for example a
   * deep-paging or analytics-style traversal). Defined as the throughput-oriented counterpart to
   * {@link #POINT} so readers have a vocabulary for both ends of the spectrum; no built-in reader
   * consumes it yet, so today it is a no-op.
   */
  BULK
}
