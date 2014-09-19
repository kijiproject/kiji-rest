/**
 * (c) Copyright 2013 WibiData, Inc.
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

package org.kiji.rest;

import java.util.Collection;

import org.kiji.schema.Kiji;
import org.kiji.schema.KijiSchemaTable;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiTableReader;

/**
 * Interface for Kiji clients that are utilized by KijiREST resources.
 */
public interface KijiClient {
  /**
   * Gets a Kiji object for the specified instance. Caller should not release the Kiji instance.
   *
   * @param instance of the Kiji to request.
   * @return Kiji object
   * @throws javax.ws.rs.WebApplicationException if there is an error getting the instance OR
   *         if the instance requested is unavailable for handling via REST.
   */
  Kiji getKiji(String instance);

  /**
   * Returns the names of the instances currently being served.
   *
   * @return the names of currently served instances.
   */
  Collection<String> getInstances();

  /**
   * Gets a Kiji table. Caller does not have to release the table as it will be released
   * when the KijiClient is closed.
   *
   * @param instance in which this table resides
   * @param table name of the requested table
   * @return KijiTable object
   * @throws javax.ws.rs.WebApplicationException if there is an error.
   */
  KijiTable getKijiTable(String instance, String table);

  /**
   * Returns the Kiji schema table for the given instance. Caller should not close the schema
   * table.
   *
   * @param instance is the instance for which the schema table should be retrieved.
   * @return the schema table for the specified instance.
   */
  KijiSchemaTable getKijiSchemaTable(String instance);

  /**
   * Gets a FreshKijiTableReader. Caller should not close the fresh table reader.
   *
   * @param instance in which this table reader resides
   * @param table name of the table to read
   * @return FreshKijiTableReader object
   * @throws javax.ws.rs.WebApplicationException if there is an error.
   */
  KijiTableReader getKijiTableReader(String instance, String table);

  /**
   * Removes the table from the various table reader caches. This can happen as a response to a
   * user request, or because a table is no longer valid.
   *
   * @param instance is the name of the instance.
   * @param table is the name of the table to remove from the cache.
   */
  void invalidateTable(String instance, String table);

  /**
   * Closes an open Kiji instance and corresponding tables. This can happen as a response to a
   * user request, or because an instance is no longer valid.
   *
   * @param instance name of the Kiji instance.
   */
  void invalidateInstance(String instance);
}
