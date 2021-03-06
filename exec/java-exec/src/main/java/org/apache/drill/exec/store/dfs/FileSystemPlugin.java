/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.dfs;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.hydromatic.optiq.SchemaPlus;

import org.apache.drill.common.JSONOptions;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.logical.FormatPluginConfig;
import org.apache.drill.common.logical.StoragePluginConfig;
import org.apache.drill.exec.physical.base.AbstractGroupScan;
import org.apache.drill.exec.rpc.user.UserSession;
import org.apache.drill.exec.server.DrillbitContext;
import org.apache.drill.exec.store.AbstractStoragePlugin;
import org.apache.drill.exec.store.ClassPathFileSystem;
import org.apache.drill.exec.store.LocalSyncableFileSystem;
import org.apache.drill.exec.store.StoragePluginOptimizerRule;
import org.apache.drill.exec.store.dfs.shim.DrillFileSystem;
import org.apache.drill.exec.store.dfs.shim.FileSystemCreator;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * A Storage engine associated with a Hadoop FileSystem Implementation. Examples include HDFS, MapRFS, QuantacastFileSystem,
 * LocalFileSystem, as well Apache Drill specific CachedFileSystem, ClassPathFileSystem and LocalSyncableFileSystem.
 * Tables are file names, directories and path patterns. This storage engine delegates to FSFormatEngines but shares
 * references to the FileSystem configuration and path management.
 */
public class FileSystemPlugin extends AbstractStoragePlugin{
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FileSystemPlugin.class);

  private final FileSystemSchemaFactory schemaFactory;
  private Map<String, FormatPlugin> formatPluginsByName;
  private Map<FormatPluginConfig, FormatPlugin> formatPluginsByConfig;
  private FileSystemConfig config;
  private DrillbitContext context;
  private final DrillFileSystem fs;

  public FileSystemPlugin(FileSystemConfig config, DrillbitContext context, String name) throws ExecutionSetupException{
    try {
      this.config = config;
      this.context = context;

      Configuration fsConf = new Configuration();
      fsConf.set(FileSystem.FS_DEFAULT_NAME_KEY, config.connection);
      fsConf.set("fs.classpath.impl", ClassPathFileSystem.class.getName());
      fsConf.set("fs.drill-local.impl", LocalSyncableFileSystem.class.getName());
      fs = FileSystemCreator.getFileSystem(context.getConfig(), fsConf);
      formatPluginsByName = FormatCreator.getFormatPlugins(context, fs, config);
      List<FormatMatcher> matchers = Lists.newArrayList();
      formatPluginsByConfig = Maps.newHashMap();
      for (FormatPlugin p : formatPluginsByName.values()) {
        matchers.add(p.getMatcher());
        formatPluginsByConfig.put(p.getConfig(), p);
      }

      boolean noWorkspace = config.workspaces == null || config.workspaces.isEmpty();
      List<WorkspaceSchemaFactory> factories = Lists.newArrayList();
      if (!noWorkspace) {
        for (Map.Entry<String, WorkspaceConfig> space : config.workspaces.entrySet()) {
          factories.add(new WorkspaceSchemaFactory(context.getConfig(), context.getPersistentStoreProvider(), this, space.getKey(), name, fs, space.getValue(), matchers));
        }
      }

      // if the "default" workspace is not given add one.
      if (noWorkspace || !config.workspaces.containsKey("default")) {
        factories.add(new WorkspaceSchemaFactory(context.getConfig(), context.getPersistentStoreProvider(), this, "default", name, fs, WorkspaceConfig.DEFAULT, matchers));
      }

      this.schemaFactory = new FileSystemSchemaFactory(name, factories);
    } catch (IOException e) {
      throw new ExecutionSetupException("Failure setting up file system plugin.", e);
    }
  }

  @Override
  public boolean supportsRead() {
    return true;
  }

  @Override
  public StoragePluginConfig getConfig() {
    return config;
  }

  @Override
  public AbstractGroupScan getPhysicalScan(JSONOptions selection, List<SchemaPath> columns) throws IOException {
    FormatSelection formatSelection = selection.getWith(context.getConfig(), FormatSelection.class);
    FormatPlugin plugin;
    if (formatSelection.getFormat() instanceof NamedFormatPluginConfig) {
      plugin = formatPluginsByName.get( ((NamedFormatPluginConfig) formatSelection.getFormat()).name);
    } else {
      plugin = formatPluginsByConfig.get(formatSelection.getFormat());
    }
    if (plugin == null) {
      throw new IOException(String.format("Failure getting requested format plugin named '%s'.  It was not one of the format plugins registered.", formatSelection.getFormat()));
    }
    return plugin.getGroupScan(formatSelection.getSelection(), columns);
  }

  @Override
  public void registerSchemas(UserSession session, SchemaPlus parent) {
    schemaFactory.registerSchemas(session, parent);
  }

  public FormatPlugin getFormatPlugin(String name) {
    return formatPluginsByName.get(name);
  }

  public FormatPlugin getFormatPlugin(FormatPluginConfig config) {
    if (config instanceof NamedFormatPluginConfig) {
      return formatPluginsByName.get(((NamedFormatPluginConfig) config).name);
    } else {
      return formatPluginsByConfig.get(config);
    }
  }

  @Override
  public Set<StoragePluginOptimizerRule> getOptimizerRules() {
    Builder<StoragePluginOptimizerRule> setBuilder = ImmutableSet.builder();
    for(FormatPlugin plugin : this.formatPluginsByName.values()){
      Set<StoragePluginOptimizerRule> rules = plugin.getOptimizerRules();
      if(rules != null && rules.size() > 0){
        setBuilder.addAll(rules);
      }
    }
    return setBuilder.build();
  }


}
