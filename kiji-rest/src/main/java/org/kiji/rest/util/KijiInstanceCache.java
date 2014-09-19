/**
 * (c) Copyright 2014 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.rest.util;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.schema.HBaseEntityId;
import org.kiji.schema.Kiji;
import org.kiji.schema.KijiDataRequest;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiTableReader;
import org.kiji.schema.KijiURI;

/**
 * A cache object containing all Kiji, KijiTable, and KijiTableReader objects for a Kiji
 * instance. Handles the creation and lifecycle of instances.
 */
public class KijiInstanceCache {

  private static final Logger LOG = LoggerFactory.getLogger(KijiInstanceCache.class);

  private static final long TEN_MINUTES = 10 * 60 * 1000;

  /** Determines whether new values can be loaded into the contained caches. */
  private volatile boolean mIsOpen = true;

  private final Kiji mKiji;

  private final LoadingCache<String, KijiTable> mTables =
      CacheBuilder.newBuilder()
          // Expire table if it has not been used in 10 minutes
          // TODO (REST-133): Make this value configurable
          .expireAfterAccess(10, TimeUnit.MINUTES)
          .removalListener(
              new RemovalListener<String, KijiTable>() {
                @Override
                public void onRemoval(RemovalNotification<String, KijiTable> notification) {
                  try {
                    notification.getValue().release(); // strong cache; should not be null
                  } catch (IOException e) {
                    LOG.warn("Unable to release KijiTable {} with name {}.",
                        notification.getValue(), notification.getValue());
                  }
                }
              }
          )
          .build(
              new CacheLoader<String, KijiTable>() {
                @Override
                public KijiTable load(String table) throws IOException {
                  Preconditions.checkState(mIsOpen, "Cannot open KijiTable in closed cache.");
                  return mKiji.openTable(table);
                }
              }
          );

  private final LoadingCache<String, KijiTableReader> mReaders =
      CacheBuilder.newBuilder()
          // Expire reader if it has not been used in 10 minutes
          // TODO (REST-133): Make this value configurable
          .expireAfterAccess(10, TimeUnit.MINUTES)
          .removalListener(
              new RemovalListener<String, KijiTableReader>() {
                @Override
                public void onRemoval(
                    RemovalNotification<String,
                    KijiTableReader> notification
                ) {
                  try {
                    notification.getValue().close(); // strong cache; should not be null
                  } catch (IOException e) {
                    LOG.warn("Unable to close KijiTableReader {} on table {}.",
                        notification.getValue(), notification.getValue());
                  }
                }
              }
          )
          .build(
              new CacheLoader<String, KijiTableReader>() {
                @Override
                public KijiTableReader load(String table) throws IOException {
                  try {
                    Preconditions.checkState(mIsOpen,
                        "Cannot open KijiTableReader in closed cache.");
                    return mTables.get(table).openTableReader();
                  } catch (ExecutionException e) {
                    // Unwrap (if possible) and rethrow. Will be caught by #getKijiTableReader.
                    if (e.getCause() instanceof IOException) {
                      throw (IOException) e.getCause();
                    } else {
                      throw new IOException(e.getCause());
                    }
                  }
                }
              }
          );

  /**
   *
   * Create a new KijiInstanceCache which caches the instance at the provided URI.
   *
   * @param uri of instance to cache access to.
   * @throws IOException if error while opening kiji.
   */
  public KijiInstanceCache(KijiURI uri) throws IOException {
    mKiji = Kiji.Factory.open(uri);
  }

  /**
   * Returns the Kiji instance held by this cache.  This Kiji instance should *NOT* be released.
   *
   * @return a Kiji instance.
   */
  public Kiji getKiji() {
    return mKiji;
  }

  /**
   * Returns the KijiTable instance for the table name held by this cache.  This KijiTable instance
   * should *NOT* be released.
   *
   * @param table name.
   * @return the KijiTable instance
   * @throws java.util.concurrent.ExecutionException if the table cannot be created.
   */
  public KijiTable getKijiTable(String table) throws ExecutionException {
    return mTables.get(table);
  }

  /**
   * Returns the KijiTableReader instance for the table held by this cache.  This
   * KijiTableReader instance should *NOT* be closed.
   *
   * @param table name.
   * @return a KijiTableReader for the table.
   * @throws ExecutionException if a KijiTableReader cannot be created for the table.
   */
  public KijiTableReader getKijiTableReader(String table) throws ExecutionException {
    return mReaders.get(table);
  }

  /**
   * Invalidates cached KijiTable and KijiTableReader instances for a table.
   *
   * @param table name to be invalidated.
   */
  public void invalidateTable(String table) {
    mTables.invalidate(table);
    mReaders.invalidate(table);
  }

  /**
   * Stop creating resources to cache, and cleanup any existing resources.
   *
   * @throws IOException if error while closing instance.
   */
  public void stop() throws IOException {
    mIsOpen = false; // Stop caches from loading more entries
    mReaders.invalidateAll();
    mReaders.cleanUp();
    mTables.invalidateAll();
    mTables.cleanUp();
    mKiji.release();
  }

  /**
   * Checks the health of this KijiInstanceCache, and all the stateful objects it contains.
   *
   * @return a list health issues.  Will be empty if the cache is healthy.
   */
  public List<String> checkHealth() {
    ImmutableList.Builder<String> issues = ImmutableList.builder();
    if (mIsOpen) {

      // Check that the Kiji instance is healthy
      try {
        mKiji.getMetaTable();
      } catch (IllegalStateException e) {
        issues.add(String.format("Kiji instance %s is in illegal state.", mKiji));
      } catch (IOException e) {
        issues.add(String.format("Kiji instance %s cannot open meta table.", mKiji));
      }

      // Check that the KijiTable instances are healthy
      for (KijiTable table : mTables.asMap().values()) {
        try {
          table.getWriterFactory();
        } catch (IllegalStateException e) {
          issues.add(String.format("KijiTable instance %s is in illegal state.", table));
        } catch (IOException e) {
          issues.add(String.format("KijiTable instance %s cannot open reader factory.", table));
        }
      }

      // Check that the KijiTableReader instances are healthy
      for (KijiTableReader reader : mReaders.asMap().values()) {
        try {
          reader.get(HBaseEntityId.fromHBaseRowKey(new byte[0]), KijiDataRequest.empty());
        } catch (IllegalStateException e) {
          issues.add(String.format("KijiTableReader instance %s is in illegal state.",
              reader));
        } catch (IOException e) {
          issues.add(String.format("KijiTableReader instance %s cannot get data.",
              reader));
        }
      }
    } else {
      issues.add(String.format("KijiInstanceCache for kiji instance %s is not open.",
          mKiji.getURI().getInstance()));
    }
    return issues.build();
  }
}
