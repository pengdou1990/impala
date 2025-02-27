// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.catalog.iceberg;

import java.util.Map;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionSpecParser;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.hadoop.ConfigProperties;
import org.apache.iceberg.mr.Catalogs;
import org.apache.iceberg.mr.InputFormatConfig;
import org.apache.impala.catalog.FeIcebergTable;
import org.apache.impala.catalog.IcebergTable;
import org.apache.impala.catalog.TableLoadingException;
import org.apache.impala.common.ImpalaRuntimeException;
import org.apache.impala.thrift.TIcebergCatalog;
import org.apache.impala.util.IcebergUtil;

import com.google.common.base.Preconditions;

/**
 * Implementation of IcebergCatalog for tables handled by Iceberg's Catalogs API.
 */
public class IcebergCatalogs implements IcebergCatalog {
  private static IcebergCatalogs instance_;

  public synchronized static IcebergCatalogs getInstance() {
    if (instance_ == null) {
      instance_ = new IcebergCatalogs();
    }
    return instance_;
  }

  private Configuration configuration_;

  private IcebergCatalogs() {
    configuration_ = new HiveConf(IcebergCatalogs.class);
    // We need to set ENGINE_HIVE_ENABLED in order to get Iceberg use the
    // appropriate SerDe and Input/Output format classes.
    configuration_.setBoolean(ConfigProperties.ENGINE_HIVE_ENABLED, true);
  }

  public TIcebergCatalog getUnderlyingCatalogType(String catalogName) {
    String catalogType = configuration_.get(catalogPropertyConfigKey(
        catalogName, CatalogUtil.ICEBERG_CATALOG_TYPE));
    if (catalogType == null ||
        CatalogUtil.ICEBERG_CATALOG_TYPE_HIVE.equalsIgnoreCase(catalogType)) {
      return TIcebergCatalog.HIVE_CATALOG;
    }
    if (CatalogUtil.ICEBERG_CATALOG_TYPE_HADOOP.equalsIgnoreCase(catalogType)) {
      return TIcebergCatalog.HADOOP_CATALOG;
    }
    if (Catalogs.LOCATION.equalsIgnoreCase(catalogType)) {
      return TIcebergCatalog.HADOOP_TABLES;
    }
    return TIcebergCatalog.CATALOGS;
  }

  @Override
  public Table createTable(
      TableIdentifier identifier,
      Schema schema,
      PartitionSpec spec,
      String location,
      Map<String, String> tableProps) throws ImpalaRuntimeException {
    setContextClassLoader();
    String catName = tableProps.get(IcebergTable.ICEBERG_CATALOG);
    Preconditions.checkState(catName != null);
    String catalogType = configuration_.get(catalogPropertyConfigKey(
      catName, CatalogUtil.ICEBERG_CATALOG_TYPE));
    if (catalogType == null) {
      throw new ImpalaRuntimeException(
          String.format("Unknown catalog name: %s", catName));
    }
    Properties properties = createPropsForCatalogs(identifier, location, tableProps);
    properties.setProperty(InputFormatConfig.TABLE_SCHEMA, SchemaParser.toJson(schema));
    properties.setProperty(InputFormatConfig.PARTITION_SPEC,
        PartitionSpecParser.toJson(spec));
    return Catalogs.createTable(configuration_, properties);
  }

  @Override
  public Table loadTable(FeIcebergTable feTable) throws TableLoadingException {
    setContextClassLoader();
    TableIdentifier tableId = IcebergUtil.getIcebergTableIdentifier(feTable);
    return loadTable(tableId, feTable.getLocation(),
        feTable.getMetaStoreTable().getParameters());
  }

  @Override
  public Table loadTable(TableIdentifier tableId, String tableLocation,
      Map<String, String> tableProps) throws TableLoadingException {
    setContextClassLoader();
    Properties properties = createPropsForCatalogs(tableId, tableLocation, tableProps);
    return Catalogs.loadTable(configuration_, properties);
  }

  @Override
  public boolean dropTable(FeIcebergTable feTable, boolean purge) {
    setContextClassLoader();
    if (!purge) return true;
    TableIdentifier tableId = IcebergUtil.getIcebergTableIdentifier(feTable);
    String tableLocation = feTable.getLocation();
    Properties properties = createPropsForCatalogs(tableId, tableLocation,
        feTable.getMetaStoreTable().getParameters());
    return Catalogs.dropTable(configuration_, properties);
  }

  @Override
  public void renameTable(FeIcebergTable feTable, TableIdentifier newTableId) {
    // Iceberg's Catalogs class has no renameTable() method
    throw new UnsupportedOperationException(
        "Cannot rename Iceberg tables that use 'Catalogs'.");
  }

  private Properties createPropsForCatalogs(TableIdentifier tableId, String location,
      Map<String, String> tableProps) {
    Properties properties = new Properties();
    properties.putAll(tableProps);
    if (tableId != null) {
      properties.setProperty(Catalogs.NAME, tableId.toString());
    } else if (location != null) {
      properties.setProperty(Catalogs.LOCATION, location);
    }
    return properties;
  }

  private static String catalogPropertyConfigKey(String catalogName,
      String catalogProperty) {
    return String.format("%s%s.%s", InputFormatConfig.CATALOG_CONFIG_PREFIX,
        catalogName, catalogProperty);
  }

  /**
   * Some of the above methods might be running on native threads as they might be invoked
   * via JNI. In that case the context class loader for those threads are null. 'Catalogs'
   * uses JNDI to load the catalog implementations, e.g. HadoopCatalog or HiveCatalog.
   * JNDI uses the context class loader, but as it is null it falls back to the bootstrap
   * class loader that doesn't have the Iceberg classes on its classpath.
   * To avoid ClassNotFoundException we set the context class loader to the class loader
   * that loaded this class.
   */
  private void setContextClassLoader() {
    if (Thread.currentThread().getContextClassLoader() != null) return;
    Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
  }
}
