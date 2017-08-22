package com.thinkbiganalytics.hive.service;

/*-
 * #%L
 * thinkbig-thrift-proxy-core
 * %%
 * Copyright (C) 2017 ThinkBig Analytics
 * %%
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
 * #L%
 */

import com.thinkbiganalytics.discovery.schema.Field;
import com.thinkbiganalytics.discovery.schema.QueryResult;
import com.thinkbiganalytics.discovery.schema.TableSchema;
import com.thinkbiganalytics.hive.util.HiveUtils;
import com.thinkbiganalytics.kerberos.KerberosTicketConfiguration;
import com.thinkbiganalytics.schema.DBSchemaParser;
import com.thinkbiganalytics.schema.QueryRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.sql.DataSource;

/**
 */
public class HiveService {

    private static final Logger log = LoggerFactory.getLogger(HiveService.class);

    @Inject
    @Qualifier("hiveJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    @Inject
    @Qualifier("kerberosHiveConfiguration")
    private KerberosTicketConfiguration kerberosHiveConfiguration;

    private DBSchemaParser schemaParser = null;

    public DataSource getDataSource() {
        return jdbcTemplate.getDataSource();
    }

    public DBSchemaParser getDBSchemaParser() {
        if (schemaParser == null) {
            schemaParser = new DBSchemaParser(getDataSource(), kerberosHiveConfiguration);
        }
        return schemaParser;
    }

    public List<String> getSchemaNames() {
        return getDBSchemaParser().listSchemas();
    }

    public List<String> getTables(String schema) {
        List<String> tables = getDBSchemaParser().listTables(schema, null);
        return tables;
    }

    /**
     * returns a list of schemanName.TableName
     */
    private List<String> getAllTables() {
        List<String> allTables = new ArrayList<>();
        List<String> schemas = getSchemaNames();
        if (schemas != null) {
            for (String schema : schemas) {
                List<String> tables = getTables(schema);
                if (tables != null) {
                    for (String table : tables) {
                        allTables.add(schema + "." + table);
                    }
                }
            }
        }
        return allTables;
    }


    /**
     * returns a list of all the scheam.tablename for a given schema
     */
    public List<String> getTablesForImpersonatedUser(String schema) {
        QueryResult tables = query("show tables in " + schema);
        return tables.getRows().stream().flatMap(row -> row.entrySet().stream()).map(e -> schema + "." + e.getValue().toString()).collect(Collectors.toList());
    }


    /**
     * returns a list of all the schema.tablename
     */
    public List<String> getAllTablesForImpersonatedUser() {
        long start = System.currentTimeMillis();
        List<String> allTables = new ArrayList<>();
        QueryResult result = query("show databases");
        List<Object> databases = result.getRows().stream().flatMap(row -> row.entrySet().stream()).map(e -> e.getValue()).collect(Collectors.toList());
        databases.stream().forEach(database -> allTables.addAll(getTablesForImpersonatedUser(database.toString())));
        log.debug("time to get all tables " + (System.currentTimeMillis() - start) + " ms");
        return allTables;
    }

    public boolean testConnection() throws SQLException {
        return ((RefreshableDataSource) this.getDataSource()).testConnection();

    }

    /**
     * returns a list of populated TableSchema objects
     */
    public List<TableSchema> getAllTableSchemas() {
        List<TableSchema> allTables = new ArrayList<>();
        List<String> schemas = getSchemaNames();
        if (schemas != null) {
            for (String schema : schemas) {
                List<String> tables = getTables(schema);
                if (tables != null) {
                    for (String table : tables) {
                        allTables.add(getTableSchema(schema, table));
                    }
                }
            }
        }
        return allTables;
    }

    /**
     * Describes the given Table
     */
    public TableSchema getTableSchema(String schema, String table) {
        return getDBSchemaParser().describeTable(schema, table);
    }


    public List<? extends Field> getFields(String schema, String table) {
        TableSchema tableSchema = getTableSchema(schema, table);
        if (tableSchema != null) {
            return tableSchema.getFields();
        }
        return null;
    }


    public QueryResult browse(String schema, String table, String where, Integer limit) throws DataAccessException {

        if (where == null) {
            where = "";
        }
        String query = "SELECT * from " + HiveUtils.quoteIdentifier(schema, table) + " " + where + " LIMIT " + limit;
        return browse(query);
    }


    public QueryResult browse(String query) throws DataAccessException {
        return query(query);

    }

    public QueryResult query(String query) throws DataAccessException {
        //  Setting in order to query complex formats like parquet
        jdbcTemplate.execute("set hive.optimize.index.filter=false");
        return new QueryRunner(jdbcTemplate).query(query);
    }

    /**
     * Executes a single SQL update operation (such as insert, update, or delete).
     *
     * @param query the SQL to execute
     * @return the number of rows affected
     * @throws DataAccessException if there is any problem
     */
    public int update(@Nonnull final String query) {
        return jdbcTemplate.update(query);
    }
}
