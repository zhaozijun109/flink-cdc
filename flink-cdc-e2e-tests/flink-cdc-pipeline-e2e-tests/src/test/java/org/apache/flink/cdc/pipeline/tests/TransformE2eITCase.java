/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.cdc.pipeline.tests;

import org.apache.flink.cdc.common.test.utils.TestUtils;
import org.apache.flink.cdc.connectors.mysql.testutils.MySqlContainer;
import org.apache.flink.cdc.connectors.mysql.testutils.MySqlVersion;
import org.apache.flink.cdc.connectors.mysql.testutils.UniqueDatabase;
import org.apache.flink.cdc.pipeline.tests.utils.PipelineTestEnvironment;
import org.apache.flink.cdc.runtime.operators.transform.PostTransformOperator;
import org.apache.flink.cdc.runtime.operators.transform.PreTransformOperator;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/** E2e tests for the {@link PreTransformOperator} and {@link PostTransformOperator}. */
@RunWith(Parameterized.class)
public class TransformE2eITCase extends PipelineTestEnvironment {
    private static final Logger LOG = LoggerFactory.getLogger(TransformE2eITCase.class);

    // ------------------------------------------------------------------------------------------
    // MySQL Variables (we always use MySQL as the data source for easier verifying)
    // ------------------------------------------------------------------------------------------
    protected static final String MYSQL_TEST_USER = "mysqluser";
    protected static final String MYSQL_TEST_PASSWORD = "mysqlpw";
    protected static final String MYSQL_DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";
    protected static final String INTER_CONTAINER_MYSQL_ALIAS = "mysql";

    @ClassRule
    public static final MySqlContainer MYSQL =
            (MySqlContainer)
                    new MySqlContainer(
                                    MySqlVersion.V8_0) // v8 support both ARM and AMD architectures
                            .withConfigurationOverride("docker/mysql/my.cnf")
                            .withSetupSQL("docker/mysql/setup.sql")
                            .withDatabaseName("flink-test")
                            .withUsername("flinkuser")
                            .withPassword("flinkpw")
                            .withNetwork(NETWORK)
                            .withNetworkAliases(INTER_CONTAINER_MYSQL_ALIAS)
                            .withLogConsumer(new Slf4jLogConsumer(LOG));

    protected final UniqueDatabase transformTestDatabase =
            new UniqueDatabase(MYSQL, "transform_test", MYSQL_TEST_USER, MYSQL_TEST_PASSWORD);

    @Before
    public void before() throws Exception {
        super.before();
        transformTestDatabase.createAndInitialize();
    }

    @After
    public void after() {
        super.after();
        transformTestDatabase.dropDatabase();
    }

    @Test
    public void testHeteroSchemaTransform() throws Exception {
        String pipelineJob =
                String.format(
                        "source:\n"
                                + "  type: mysql\n"
                                + "  hostname: %s\n"
                                + "  port: 3306\n"
                                + "  username: %s\n"
                                + "  password: %s\n"
                                + "  tables: %s.\\.*\n"
                                + "  server-id: 5400-5404\n"
                                + "  server-time-zone: UTC\n"
                                + "sink:\n"
                                + "  type: values\n"
                                + "route:\n"
                                + "  - source-table: %s.\\.*\n"
                                + "    sink-table: %s.terminus\n"
                                + "transform:\n"
                                + "  - source-table: %s.TABLEALPHA\n"
                                + "    projection: ID, VERSION\n"
                                + "    filter: ID > 1008\n"
                                + "  - source-table: %s.TABLEBETA\n"
                                + "    projection: ID, VERSION\n"
                                + "pipeline:\n"
                                + "  parallelism: 1",
                        INTER_CONTAINER_MYSQL_ALIAS,
                        MYSQL_TEST_USER,
                        MYSQL_TEST_PASSWORD,
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName());
        Path mysqlCdcJar = TestUtils.getResource("mysql-cdc-pipeline-connector.jar");
        Path valuesCdcJar = TestUtils.getResource("values-cdc-pipeline-connector.jar");
        Path mysqlDriverJar = TestUtils.getResource("mysql-driver.jar");
        submitPipelineJob(pipelineJob, mysqlCdcJar, valuesCdcJar, mysqlDriverJar);
        waitUntilJobRunning(Duration.ofSeconds(30));
        LOG.info("Pipeline job is running");

        waitUntilSpecificEvent(
                String.format(
                        "CreateTableEvent{tableId=%s.terminus, schema=columns={`ID` INT NOT NULL,`VERSION` VARCHAR(17)}, primaryKeys=ID, options=()}",
                        transformTestDatabase.getDatabaseName()),
                60000L);

        validateEvents(
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[1009, 8.1], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[1010, 10], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[1011, 11], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[2011, 11], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[2012, 12], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[2014, 14], op=INSERT, meta=()}");

        LOG.info("Begin incremental reading stage.");
        // generate binlogs
        String mysqlJdbcUrl =
                String.format(
                        "jdbc:mysql://%s:%s/%s",
                        MYSQL.getHost(),
                        MYSQL.getDatabasePort(),
                        transformTestDatabase.getDatabaseName());
        try (Connection conn =
                        DriverManager.getConnection(
                                mysqlJdbcUrl, MYSQL_TEST_USER, MYSQL_TEST_PASSWORD);
                Statement stat = conn.createStatement()) {
            stat.execute("UPDATE TABLEALPHA SET VERSION='100' WHERE id=1009;");
            stat.execute("INSERT INTO TABLEALPHA VALUES (3007, '7', 79, 16, 'IINA');");
            stat.execute("DELETE FROM TABLEBETA WHERE id=2011;");
        } catch (SQLException e) {
            LOG.error("Update table for CDC failed.", e);
            throw e;
        }

        validateEvents(
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[3007, 7], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[1009, 8.1], after=[1009, 100], op=UPDATE, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[2011, 11], after=[], op=DELETE, meta=()}");
    }

    @Test
    public void testMultipleTransformRule() throws Exception {
        String pipelineJob =
                String.format(
                        "source:\n"
                                + "  type: mysql\n"
                                + "  hostname: %s\n"
                                + "  port: 3306\n"
                                + "  username: %s\n"
                                + "  password: %s\n"
                                + "  tables: %s.\\.*\n"
                                + "  server-id: 5400-5404\n"
                                + "  server-time-zone: UTC\n"
                                + "\n"
                                + "sink:\n"
                                + "  type: values\n"
                                + "route:\n"
                                + "transform:\n"
                                + "  - source-table: %s.\\.*\n"
                                + "    projection: ID, VERSION, 'Type-A' AS CATEGORY\n"
                                + "    filter: ID > 1008\n"
                                + "  - source-table: %s.\\.*\n"
                                + "    projection: ID, VERSION, 'Type-B' AS CATEGORY\n"
                                + "    filter: ID <= 1008\n"
                                + "\n"
                                + "pipeline:\n"
                                + "  parallelism: 1",
                        INTER_CONTAINER_MYSQL_ALIAS,
                        MYSQL_TEST_USER,
                        MYSQL_TEST_PASSWORD,
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName());
        Path mysqlCdcJar = TestUtils.getResource("mysql-cdc-pipeline-connector.jar");
        Path valuesCdcJar = TestUtils.getResource("values-cdc-pipeline-connector.jar");
        Path mysqlDriverJar = TestUtils.getResource("mysql-driver.jar");
        submitPipelineJob(pipelineJob, mysqlCdcJar, valuesCdcJar, mysqlDriverJar);
        waitUntilJobRunning(Duration.ofSeconds(30));
        LOG.info("Pipeline job is running");

        waitUntilSpecificEvent(
                String.format(
                        "CreateTableEvent{tableId=%s.TABLEALPHA, schema=columns={`ID` INT NOT NULL,`VERSION` VARCHAR(17),`CATEGORY` STRING}, primaryKeys=ID, options=()}",
                        transformTestDatabase.getDatabaseName()),
                60000L);

        waitUntilSpecificEvent(
                String.format(
                        "CreateTableEvent{tableId=%s.TABLEBETA, schema=columns={`ID` INT NOT NULL,`VERSION` VARCHAR(17),`CATEGORY` STRING}, primaryKeys=ID, options=()}",
                        transformTestDatabase.getDatabaseName()),
                60000L);

        validateEvents(
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1008, 8, Type-B], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1009, 8.1, Type-A], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1010, 10, Type-A], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1011, 11, Type-A], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2011, 11, Type-A], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2012, 12, Type-A], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2013, 13, Type-A], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2014, 14, Type-A], op=INSERT, meta=()}");

        LOG.info("Begin incremental reading stage.");
        // generate binlogs
        String mysqlJdbcUrl =
                String.format(
                        "jdbc:mysql://%s:%s/%s",
                        MYSQL.getHost(),
                        MYSQL.getDatabasePort(),
                        transformTestDatabase.getDatabaseName());
        try (Connection conn =
                        DriverManager.getConnection(
                                mysqlJdbcUrl, MYSQL_TEST_USER, MYSQL_TEST_PASSWORD);
                Statement stat = conn.createStatement()) {
            stat.execute("UPDATE TABLEALPHA SET VERSION='100' WHERE id=1009;");
            stat.execute("INSERT INTO TABLEALPHA VALUES (3007, '7', 79, 16, 'IINA');");
            stat.execute("DELETE FROM TABLEBETA WHERE id=2011;");
        } catch (SQLException e) {
            LOG.error("Update table for CDC failed.", e);
            throw e;
        }

        validateEvents(
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[1009, 8.1, Type-A], after=[1009, 100, Type-A], op=UPDATE, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[3007, 7, Type-A], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[2011, 11, Type-A], after=[], op=DELETE, meta=()}");
    }

    @Test
    public void testAssortedSchemaTransform() throws Exception {
        String pipelineJob =
                String.format(
                        "source:\n"
                                + "  type: mysql\n"
                                + "  hostname: %s\n"
                                + "  port: 3306\n"
                                + "  username: %s\n"
                                + "  password: %s\n"
                                + "  tables: %s.\\.*\n"
                                + "  server-id: 5400-5404\n"
                                + "  server-time-zone: UTC\n"
                                + "sink:\n"
                                + "  type: values\n"
                                + "route:\n"
                                + "  - source-table: %s.\\.*\n"
                                + "    sink-table: %s.terminus\n"
                                + "transform:\n"
                                + "  - source-table: %s.TABLEALPHA\n"
                                + "    projection: ID, CONCAT('v', VERSION) AS VERSION, LOWER(NAMEALPHA) AS NAME\n"
                                + "    filter: AGEALPHA < 19\n"
                                + "  - source-table: %s.TABLEBETA\n"
                                + "    projection: ID, CONCAT('v', VERSION) AS VERSION, LOWER(NAMEBETA) AS NAME\n"
                                + "pipeline:\n"
                                + "  parallelism: 1",
                        INTER_CONTAINER_MYSQL_ALIAS,
                        MYSQL_TEST_USER,
                        MYSQL_TEST_PASSWORD,
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName());
        Path mysqlCdcJar = TestUtils.getResource("mysql-cdc-pipeline-connector.jar");
        Path valuesCdcJar = TestUtils.getResource("values-cdc-pipeline-connector.jar");
        Path mysqlDriverJar = TestUtils.getResource("mysql-driver.jar");
        submitPipelineJob(pipelineJob, mysqlCdcJar, valuesCdcJar, mysqlDriverJar);
        waitUntilJobRunning(Duration.ofSeconds(30));
        LOG.info("Pipeline job is running");

        waitUntilSpecificEvent(
                String.format(
                        "CreateTableEvent{tableId=%s.terminus, schema=columns={`ID` INT NOT NULL,`VERSION` STRING,`NAME` STRING}, primaryKeys=ID, options=()}",
                        transformTestDatabase.getDatabaseName()),
                60000L);

        validateEvents(
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[1008, v8, alice], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[1009, v8.1, bob], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[2011, v11, eva], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[2012, v12, fred], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[2013, v13, gus], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[2014, v14, henry], op=INSERT, meta=()}");

        LOG.info("Begin incremental reading stage.");
        // generate binlogs
        String mysqlJdbcUrl =
                String.format(
                        "jdbc:mysql://%s:%s/%s",
                        MYSQL.getHost(),
                        MYSQL.getDatabasePort(),
                        transformTestDatabase.getDatabaseName());
        try (Connection conn =
                        DriverManager.getConnection(
                                mysqlJdbcUrl, MYSQL_TEST_USER, MYSQL_TEST_PASSWORD);
                Statement stat = conn.createStatement()) {
            stat.execute("UPDATE TABLEALPHA SET VERSION='100' WHERE id=1009;");
            stat.execute("INSERT INTO TABLEALPHA VALUES (3007, '7', 79, 16, 'IINA');");
            stat.execute("DELETE FROM TABLEBETA WHERE id=2011;");
        } catch (SQLException e) {
            LOG.error("Update table for CDC failed.", e);
            throw e;
        }

        validateEvents(
                "DataChangeEvent{tableId=%s.terminus, before=[1009, v8.1, bob], after=[1009, v100, bob], op=UPDATE, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[], after=[3007, v7, iina], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.terminus, before=[2011, v11, eva], after=[], op=DELETE, meta=()}");
    }

    @Test
    public void testWildcardSchemaTransform() throws Exception {
        String pipelineJob =
                String.format(
                        "source:\n"
                                + "  type: mysql\n"
                                + "  hostname: %s\n"
                                + "  port: 3306\n"
                                + "  username: %s\n"
                                + "  password: %s\n"
                                + "  tables: %s.\\.*\n"
                                + "  server-id: 5400-5404\n"
                                + "  server-time-zone: UTC\n"
                                + "sink:\n"
                                + "  type: values\n"
                                + "transform:\n"
                                + "  - source-table: %s.TABLEALPHA\n"
                                + "    projection: \\*, CONCAT('v', VERSION) AS VERSION, LOWER(NAMEALPHA) AS NAME\n"
                                + "    filter: AGEALPHA < 19\n"
                                + "  - source-table: %s.TABLEBETA\n"
                                + "    projection: \\*, CONCAT('v', VERSION) AS VERSION, LOWER(NAMEBETA) AS NAME\n"
                                + "pipeline:\n"
                                + "  parallelism: 1",
                        INTER_CONTAINER_MYSQL_ALIAS,
                        MYSQL_TEST_USER,
                        MYSQL_TEST_PASSWORD,
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName());
        Path mysqlCdcJar = TestUtils.getResource("mysql-cdc-pipeline-connector.jar");
        Path valuesCdcJar = TestUtils.getResource("values-cdc-pipeline-connector.jar");
        Path mysqlDriverJar = TestUtils.getResource("mysql-driver.jar");
        submitPipelineJob(pipelineJob, mysqlCdcJar, valuesCdcJar, mysqlDriverJar);
        waitUntilJobRunning(Duration.ofSeconds(30));
        LOG.info("Pipeline job is running");

        waitUntilSpecificEvent(
                String.format(
                        "CreateTableEvent{tableId=%s.TABLEALPHA, schema=columns={`ID` INT NOT NULL,`VERSION` STRING,`PRICEALPHA` INT,`AGEALPHA` INT,`NAMEALPHA` VARCHAR(128),`NAME` STRING}, primaryKeys=ID, options=()}",
                        transformTestDatabase.getDatabaseName()),
                60000L);

        waitUntilSpecificEvent(
                String.format(
                        "CreateTableEvent{tableId=%s.TABLEBETA, schema=columns={`ID` INT NOT NULL,`VERSION` STRING,`CODENAMESBETA` VARCHAR(17),`AGEBETA` INT,`NAMEBETA` VARCHAR(128),`NAME` STRING}, primaryKeys=ID, options=()}",
                        transformTestDatabase.getDatabaseName()),
                60000L);

        validateEvents(
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1008, v8, 199, 17, Alice, alice], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1009, v8.1, 0, 18, Bob, bob], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2011, v11, Big Sur, 21, Eva, eva], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2012, v12, Monterey, 22, Fred, fred], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2013, v13, Ventura, 23, Gus, gus], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2014, v14, Sonoma, 24, Henry, henry], op=INSERT, meta=()}");

        LOG.info("Begin incremental reading stage.");
        // generate binlogs
        String mysqlJdbcUrl =
                String.format(
                        "jdbc:mysql://%s:%s/%s",
                        MYSQL.getHost(),
                        MYSQL.getDatabasePort(),
                        transformTestDatabase.getDatabaseName());
        try (Connection conn =
                        DriverManager.getConnection(
                                mysqlJdbcUrl, MYSQL_TEST_USER, MYSQL_TEST_PASSWORD);
                Statement stat = conn.createStatement()) {
            stat.execute("UPDATE TABLEALPHA SET VERSION='100' WHERE id=1009;");
            stat.execute("INSERT INTO TABLEALPHA VALUES (3007, '7', 79, 16, 'IINA');");
            stat.execute("DELETE FROM TABLEBETA WHERE id=2011;");
        } catch (SQLException e) {
            LOG.error("Update table for CDC failed.", e);
            throw e;
        }

        validateEvents(
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[1009, v8.1, 0, 18, Bob, bob], after=[1009, v100, 0, 18, Bob, bob], op=UPDATE, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[3007, v7, 79, 16, IINA, iina], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[2011, v11, Big Sur, 21, Eva, eva], after=[], op=DELETE, meta=()}");
    }

    @Test
    public void testWildcardWithMetadataColumnTransform() throws Exception {
        String pipelineJob =
                String.format(
                        "source:\n"
                                + "  type: mysql\n"
                                + "  hostname: %s\n"
                                + "  port: 3306\n"
                                + "  username: %s\n"
                                + "  password: %s\n"
                                + "  tables: %s.\\.*\n"
                                + "  server-id: 5400-5404\n"
                                + "  server-time-zone: UTC\n"
                                + "sink:\n"
                                + "  type: values\n"
                                + "transform:\n"
                                + "  - source-table: %s.TABLEALPHA\n"
                                + "    projection: \\*, __namespace_name__ || '.' || __schema_name__ || '.' || __table_name__ AS identifier_name, __data_event_type__ AS type\n"
                                + "  - source-table: %s.TABLEBETA\n"
                                + "    projection: \\*, __namespace_name__ || '.' || __schema_name__ || '.' || __table_name__ AS identifier_name, __data_event_type__ AS type\n"
                                + "pipeline:\n"
                                + "  parallelism: 1",
                        INTER_CONTAINER_MYSQL_ALIAS,
                        MYSQL_TEST_USER,
                        MYSQL_TEST_PASSWORD,
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName());
        Path mysqlCdcJar = TestUtils.getResource("mysql-cdc-pipeline-connector.jar");
        Path valuesCdcJar = TestUtils.getResource("values-cdc-pipeline-connector.jar");
        Path mysqlDriverJar = TestUtils.getResource("mysql-driver.jar");
        submitPipelineJob(pipelineJob, mysqlCdcJar, valuesCdcJar, mysqlDriverJar);
        waitUntilJobRunning(Duration.ofSeconds(30));
        LOG.info("Pipeline job is running");

        waitUntilSpecificEvent(
                String.format(
                        "CreateTableEvent{tableId=%s.TABLEALPHA, schema=columns={`ID` INT NOT NULL,`VERSION` VARCHAR(17),`PRICEALPHA` INT,`AGEALPHA` INT,`NAMEALPHA` VARCHAR(128),`identifier_name` STRING,`type` STRING}, primaryKeys=ID, options=()}",
                        transformTestDatabase.getDatabaseName()),
                60000L);

        waitUntilSpecificEvent(
                String.format(
                        "CreateTableEvent{tableId=%s.TABLEBETA, schema=columns={`ID` INT NOT NULL,`VERSION` VARCHAR(17),`CODENAMESBETA` VARCHAR(17),`AGEBETA` INT,`NAMEBETA` VARCHAR(128),`identifier_name` STRING,`type` STRING}, primaryKeys=ID, options=()}",
                        transformTestDatabase.getDatabaseName()),
                60000L);

        validateEvents(
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1008, 8, 199, 17, Alice, null.%s.TABLEALPHA, +I], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1010, 10, 99, 19, Carol, null.%s.TABLEALPHA, +I], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1009, 8.1, 0, 18, Bob, null.%s.TABLEALPHA, +I], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1011, 11, 59, 20, Dave, null.%s.TABLEALPHA, +I], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2012, 12, Monterey, 22, Fred, null.%s.TABLEBETA, +I], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2011, 11, Big Sur, 21, Eva, null.%s.TABLEBETA, +I], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2014, 14, Sonoma, 24, Henry, null.%s.TABLEBETA, +I], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2013, 13, Ventura, 23, Gus, null.%s.TABLEBETA, +I], op=INSERT, meta=()}");

        // generate binlogs
        String mysqlJdbcUrl =
                String.format(
                        "jdbc:mysql://%s:%s/%s",
                        MYSQL.getHost(),
                        MYSQL.getDatabasePort(),
                        transformTestDatabase.getDatabaseName());
        insertBinlogEvents(mysqlJdbcUrl);

        validateEvents(
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[1009, 8.1, 0, 18, Bob, null.%s.TABLEALPHA, -U], after=[1009, 100, 0, 18, Bob, null.%s.TABLEALPHA, +U], op=UPDATE, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[3007, 7, 79, 16, IINA, null.%s.TABLEALPHA, +I], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[2011, 11, Big Sur, 21, Eva, null.%s.TABLEBETA, -D], after=[], op=DELETE, meta=()}");
    }

    private static void insertBinlogEvents(String mysqlJdbcUrl) throws SQLException {
        try (Connection conn =
                        DriverManager.getConnection(
                                mysqlJdbcUrl, MYSQL_TEST_USER, MYSQL_TEST_PASSWORD);
                Statement stat = conn.createStatement()) {
            stat.execute("UPDATE TABLEALPHA SET VERSION='100' WHERE id=1009;");
            stat.execute("INSERT INTO TABLEALPHA VALUES (3007, '7', 79, 16, 'IINA');");
            stat.execute("DELETE FROM TABLEBETA WHERE id=2011;");
        } catch (SQLException e) {
            LOG.error("Update table for CDC failed.", e);
            throw e;
        }
    }

    @Test
    public void testMultipleHittingTable() throws Exception {
        String pipelineJob =
                String.format(
                        "source:\n"
                                + "  type: mysql\n"
                                + "  hostname: %s\n"
                                + "  port: 3306\n"
                                + "  username: %s\n"
                                + "  password: %s\n"
                                + "  tables: %s.\\.*\n"
                                + "  server-id: 5400-5404\n"
                                + "  server-time-zone: UTC\n"
                                + "sink:\n"
                                + "  type: values\n"
                                + "transform:\n"
                                + "  - source-table: %s.TABLE\\.*\n"
                                + "    projection: \\*, ID + 1000 as UID, VERSION AS NEWVERSION\n"
                                + "pipeline:\n"
                                + "  parallelism: 1",
                        INTER_CONTAINER_MYSQL_ALIAS,
                        MYSQL_TEST_USER,
                        MYSQL_TEST_PASSWORD,
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName());
        Path mysqlCdcJar = TestUtils.getResource("mysql-cdc-pipeline-connector.jar");
        Path valuesCdcJar = TestUtils.getResource("values-cdc-pipeline-connector.jar");
        Path mysqlDriverJar = TestUtils.getResource("mysql-driver.jar");
        submitPipelineJob(pipelineJob, mysqlCdcJar, valuesCdcJar, mysqlDriverJar);
        waitUntilJobRunning(Duration.ofSeconds(30));
        LOG.info("Pipeline job is running");

        waitUntilSpecificEvent(
                String.format(
                        "CreateTableEvent{tableId=%s.TABLEALPHA, schema=columns={`ID` INT NOT NULL,`VERSION` VARCHAR(17),`PRICEALPHA` INT,`AGEALPHA` INT,`NAMEALPHA` VARCHAR(128),`UID` INT,`NEWVERSION` STRING}, primaryKeys=ID, options=()}",
                        transformTestDatabase.getDatabaseName()),
                60000L);

        waitUntilSpecificEvent(
                String.format(
                        "CreateTableEvent{tableId=%s.TABLEBETA, schema=columns={`ID` INT NOT NULL,`VERSION` VARCHAR(17),`CODENAMESBETA` VARCHAR(17),`AGEBETA` INT,`NAMEBETA` VARCHAR(128),`UID` INT,`NEWVERSION` STRING}, primaryKeys=ID, options=()}",
                        transformTestDatabase.getDatabaseName()),
                60000L);

        validateEvents(
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1008, 8, 199, 17, Alice, 2008, 8], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1009, 8.1, 0, 18, Bob, 2009, 8.1], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1010, 10, 99, 19, Carol, 2010, 10], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1011, 11, 59, 20, Dave, 2011, 11], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2011, 11, Big Sur, 21, Eva, 3011, 11], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2012, 12, Monterey, 22, Fred, 3012, 12], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2013, 13, Ventura, 23, Gus, 3013, 13], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2014, 14, Sonoma, 24, Henry, 3014, 14], op=INSERT, meta=()}");

        LOG.info("Begin incremental reading stage.");
        // generate binlogs
        String mysqlJdbcUrl =
                String.format(
                        "jdbc:mysql://%s:%s/%s",
                        MYSQL.getHost(),
                        MYSQL.getDatabasePort(),
                        transformTestDatabase.getDatabaseName());
        try (Connection conn =
                        DriverManager.getConnection(
                                mysqlJdbcUrl, MYSQL_TEST_USER, MYSQL_TEST_PASSWORD);
                Statement stat = conn.createStatement()) {
            stat.execute("UPDATE TABLEALPHA SET VERSION='100' WHERE id=1009;");
            stat.execute("INSERT INTO TABLEALPHA VALUES (3007, '7', 79, 25, 'IINA');");
            stat.execute("DELETE FROM TABLEBETA WHERE id=2011;");
        } catch (SQLException e) {
            LOG.error("Update table for CDC failed.", e);
            throw e;
        }

        validateEvents(
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[1009, 8.1, 0, 18, Bob, 2009, 8.1], after=[1009, 100, 0, 18, Bob, 2009, 100], op=UPDATE, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[3007, 7, 79, 25, IINA, 4007, 7], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[2011, 11, Big Sur, 21, Eva, 3011, 11], after=[], op=DELETE, meta=()}");
    }

    @Test
    public void testTransformWithCast() throws Exception {
        String pipelineJob =
                String.format(
                        "source:\n"
                                + "  type: mysql\n"
                                + "  hostname: %s\n"
                                + "  port: 3306\n"
                                + "  username: %s\n"
                                + "  password: %s\n"
                                + "  tables: %s.\\.*\n"
                                + "  server-id: 5400-5404\n"
                                + "  server-time-zone: UTC\n"
                                + "sink:\n"
                                + "  type: values\n"
                                + "transform:\n"
                                + "  - source-table: %s.TABLEALPHA\n"
                                + "    projection: ID, CAST(VERSION AS DOUBLE) + 100 AS VERSION, CAST(AGEALPHA AS VARCHAR) || ' - ' || NAMEALPHA AS IDENTIFIER\n"
                                + "    filter: AGEALPHA < 19\n"
                                + "  - source-table: %s.TABLEBETA\n"
                                + "    projection: ID, CAST(VERSION AS DOUBLE) + 100 AS VERSION, CAST(AGEBETA AS VARCHAR) || ' - ' || NAMEBETA AS IDENTIFIER\n"
                                + "pipeline:\n"
                                + "  parallelism: 1",
                        INTER_CONTAINER_MYSQL_ALIAS,
                        MYSQL_TEST_USER,
                        MYSQL_TEST_PASSWORD,
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName());
        Path mysqlCdcJar = TestUtils.getResource("mysql-cdc-pipeline-connector.jar");
        Path valuesCdcJar = TestUtils.getResource("values-cdc-pipeline-connector.jar");
        Path mysqlDriverJar = TestUtils.getResource("mysql-driver.jar");
        submitPipelineJob(pipelineJob, mysqlCdcJar, valuesCdcJar, mysqlDriverJar);
        waitUntilJobRunning(Duration.ofSeconds(30));
        LOG.info("Pipeline job is running");

        waitUntilSpecificEvent(
                String.format(
                        "CreateTableEvent{tableId=%s.TABLEALPHA, schema=columns={`ID` INT NOT NULL,`VERSION` DOUBLE,`IDENTIFIER` STRING}, primaryKeys=ID, options=()}",
                        transformTestDatabase.getDatabaseName()),
                60000L);
        waitUntilSpecificEvent(
                String.format(
                        "CreateTableEvent{tableId=%s.TABLEBETA, schema=columns={`ID` INT NOT NULL,`VERSION` DOUBLE,`IDENTIFIER` STRING}, primaryKeys=ID, options=()}",
                        transformTestDatabase.getDatabaseName()),
                60000L);

        validateEvents(
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1008, 108.0, 17 - Alice], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[1009, 108.1, 18 - Bob], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2011, 111.0, 21 - Eva], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2012, 112.0, 22 - Fred], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2013, 113.0, 23 - Gus], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[], after=[2014, 114.0, 24 - Henry], op=INSERT, meta=()}");

        LOG.info("Begin incremental reading stage.");
        // generate binlogs
        String mysqlJdbcUrl =
                String.format(
                        "jdbc:mysql://%s:%s/%s",
                        MYSQL.getHost(),
                        MYSQL.getDatabasePort(),
                        transformTestDatabase.getDatabaseName());
        try (Connection conn =
                        DriverManager.getConnection(
                                mysqlJdbcUrl, MYSQL_TEST_USER, MYSQL_TEST_PASSWORD);
                Statement stat = conn.createStatement()) {
            stat.execute("UPDATE TABLEALPHA SET VERSION='100' WHERE id=1009;");
            stat.execute("INSERT INTO TABLEALPHA VALUES (3007, '7', 79, 16, 'IINA');");
            stat.execute("DELETE FROM TABLEBETA WHERE id=2011;");
        } catch (SQLException e) {
            LOG.error("Update table for CDC failed.", e);
            throw e;
        }

        validateEvents(
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[1009, 108.1, 18 - Bob], after=[1009, 200.0, 18 - Bob], op=UPDATE, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEALPHA, before=[], after=[3007, 107.0, 16 - IINA], op=INSERT, meta=()}",
                "DataChangeEvent{tableId=%s.TABLEBETA, before=[2011, 111.0, 21 - Eva], after=[], op=DELETE, meta=()}");

        String stdout = taskManagerConsumer.toUtf8String();
        System.out.println(stdout);
    }

    private void validateEvents(String... expectedEvents) throws Exception {
        for (String event : expectedEvents) {
            waitUntilSpecificEvent(
                    String.format(
                            event,
                            transformTestDatabase.getDatabaseName(),
                            transformTestDatabase.getDatabaseName(),
                            transformTestDatabase.getDatabaseName()),
                    20000L);
        }
    }

    @Test
    public void testTemporalFunctions() throws Exception {
        String pipelineJob =
                String.format(
                        "source:\n"
                                + "  type: mysql\n"
                                + "  hostname: %s\n"
                                + "  port: 3306\n"
                                + "  username: %s\n"
                                + "  password: %s\n"
                                + "  tables: %s.\\.*\n"
                                + "  server-id: 5400-5404\n"
                                + "  server-time-zone: UTC\n"
                                + "\n"
                                + "sink:\n"
                                + "  type: values\n"
                                + "transform:\n"
                                + "  - source-table: %s.\\.*\n"
                                + "    projection: ID, LOCALTIME as lcl_t, CURRENT_TIME as cur_t, CAST(CURRENT_TIMESTAMP AS TIMESTAMP) as cur_ts, CAST(NOW() AS TIMESTAMP) as now_ts, LOCALTIMESTAMP as lcl_ts, CURRENT_DATE as cur_dt\n"
                                + "\n"
                                + "pipeline:\n"
                                + "  parallelism: 1\n"
                                + "  local-time-zone: America/Los_Angeles",
                        INTER_CONTAINER_MYSQL_ALIAS,
                        MYSQL_TEST_USER,
                        MYSQL_TEST_PASSWORD,
                        transformTestDatabase.getDatabaseName(),
                        transformTestDatabase.getDatabaseName());
        Path mysqlCdcJar = TestUtils.getResource("mysql-cdc-pipeline-connector.jar");
        Path valuesCdcJar = TestUtils.getResource("values-cdc-pipeline-connector.jar");
        Path mysqlDriverJar = TestUtils.getResource("mysql-driver.jar");
        submitPipelineJob(pipelineJob, mysqlCdcJar, valuesCdcJar, mysqlDriverJar);
        waitUntilJobRunning(Duration.ofSeconds(30));
        LOG.info("Pipeline job is running");

        waitForTemporaryRecords(8, 60000L);
    }

    private void validateResult(List<String> expectedEvents) throws Exception {
        for (String event : expectedEvents) {
            waitUntilSpecificEvent(event, 6000L);
        }
    }

    private void waitUntilSpecificEvent(String event, long timeout) throws Exception {
        boolean result = false;
        long endTimeout = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < endTimeout) {
            String stdout = taskManagerConsumer.toUtf8String();
            if (stdout.contains(event)) {
                result = true;
                break;
            }
            Thread.sleep(1000);
        }
        if (!result) {
            throw new TimeoutException(
                    "failed to get specific event: "
                            + event
                            + " from stdout: "
                            + taskManagerConsumer.toUtf8String());
        }
    }

    private int validateTemporaryRecords() {
        int validRecordCount = 0;
        for (String line : taskManagerConsumer.toUtf8String().split("\n")) {
            if (extractDataLines(line)) {
                validRecordCount++;
            }
        }
        return validRecordCount;
    }

    private void waitForTemporaryRecords(int expectedRecords, long timeout) throws Exception {
        boolean result = false;
        long endTimeout = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < endTimeout) {
            if (validateTemporaryRecords() >= expectedRecords) {
                result = true;
                break;
            }
            Thread.sleep(1000);
        }
        if (!result) {
            throw new TimeoutException(
                    "failed to get enough temporary records: "
                            + expectedRecords
                            + " from stdout: "
                            + taskManagerConsumer.toUtf8String());
        }
    }

    boolean extractDataLines(String line) {
        if (!line.startsWith("DataChangeEvent{")) {
            return false;
        }
        Stream.of("before", "after")
                .forEach(
                        tag -> {
                            String[] arr = line.split(tag + "=\\[", 2);
                            String dataRecord = arr[arr.length - 1].split("]", 2)[0];
                            if (!dataRecord.isEmpty()) {
                                verifyDataRecord(dataRecord);
                            }
                        });
        return true;
    }

    void verifyDataRecord(String recordLine) {
        LOG.info("Verifying data line {}", recordLine);
        List<String> tokens = Arrays.asList(recordLine.split(", "));
        Assert.assertTrue(tokens.size() >= 6);

        tokens = tokens.subList(tokens.size() - 6, tokens.size());

        String localTime = tokens.get(0);
        String currentTime = tokens.get(1);
        Assert.assertEquals(localTime, currentTime);

        String currentTimestamp = tokens.get(2);
        String nowTimestamp = tokens.get(3);
        String localTimestamp = tokens.get(4);
        Assert.assertEquals(currentTimestamp, nowTimestamp);
        Assert.assertEquals(currentTimestamp, localTimestamp);

        // If timestamp millisecond part is .000, it will be truncated to yyyy-MM-dd'T'HH:mm:ss
        // format. Manually append this for the following checks.
        if (currentTimestamp.length() == 19) {
            currentTimestamp += ".000";
        }

        Instant instant =
                LocalDateTime.parse(
                                currentTimestamp,
                                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"))
                        .toInstant(ZoneOffset.UTC);

        long milliSecondsInOneDay = 24 * 60 * 60 * 1000;

        Assert.assertEquals(
                instant.toEpochMilli() % milliSecondsInOneDay, Long.parseLong(localTime));

        String currentDate = tokens.get(5);

        Assert.assertEquals(
                instant.toEpochMilli() / milliSecondsInOneDay, Long.parseLong(currentDate));
    }
}
