/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.snowflake;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.jdbc.JdbcClientPool;
import org.junit.BeforeClass;

@SuppressWarnings("VisibilityModifier")
class SnowTestBase {

  static SnowflakeCatalog snowflakeCatalog;

  static JdbcClientPool clientPool;

  protected SnowTestBase() {}

  @BeforeClass
  public static void beforeAll() {
    snowflakeCatalog = new SnowflakeCatalog();
    TestConfigurations configs = TestConfigurations.getInstance();
    Map<String, String> catalogProps = new HashMap<String, String>(configs.getProperties());
    catalogProps.put(CatalogProperties.URI, configs.getURI());
    snowflakeCatalog.initialize("testCatalog", catalogProps);
    clientPool = new JdbcClientPool(configs.getURI(), configs.getProperties());
  }

  static void createOrReplaceSchema(String schemaName) throws SQLException, InterruptedException {
    clientPool.run(
        conn -> {
          PreparedStatement statement =
              conn.prepareStatement("CREATE OR REPLACE SCHEMA IDENTIFIER(?)");
          statement.setString(1, schemaName);
          return statement.execute();
        });
  }

  static void dropSchemaIfExists(String schemaName) throws SQLException, InterruptedException {
    clientPool.run(
        conn -> {
          PreparedStatement statement =
              conn.prepareStatement("DROP SCHEMA IF EXISTS IDENTIFIER(?)");
          statement.setString(1, schemaName);
          return statement.execute();
        });
  }
}