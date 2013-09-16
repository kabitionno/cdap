package com.continuuity.data2.transaction.queue.leveldb;

import com.continuuity.common.queue.QueueName;
import com.continuuity.data.DataSetAccessor;
import com.continuuity.data2.dataset.lib.table.leveldb.LevelDBOcTableService;
import com.continuuity.data2.transaction.queue.QueueAdmin;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.util.Map;

/**
 *
 */
@Singleton
public class LevelDBQueueAdmin implements QueueAdmin {
  private final String tableName;
  private final LevelDBOcTableService service;

  @Inject
  public LevelDBQueueAdmin(DataSetAccessor dataSetAccessor, LevelDBOcTableService service) {
    this.service = service;
    // todo: we have to do that because queues do not follow dataset semantic fully (yet)
    this.tableName = dataSetAccessor.namespace("queue", DataSetAccessor.Namespace.SYSTEM);
  }

  @Override
  public boolean exists(String name) throws Exception {
    throw new UnsupportedOperationException("Exist check is not supported for LevelDB. " +
                                              "You should call create() to ensure table exists.");
  }

  @Override
  public void create(String name) throws Exception {
    service.ensureTableExists(name);
  }

  @Override
  public void truncate(String name) throws Exception {
    service.dropTable(name);
    create(name);
  }

  @Override
  public void drop(String name) throws Exception {
    service.dropTable(name);
  }

  @Override
  public void dropAll() throws Exception {
    // hack: we know that all queues stored in one table
    service.dropTable(tableName);
  }

  @Override
  public void configureInstances(QueueName queueName, long groupId, int instances) {
    // No-op
    // Potentially refactor QueueClientFactory to have better way to handle instances and group info.
  }

  @Override
  public void configureGroups(QueueName queueName, Map<Long, Integer> groupInfo) {
    // No-op
    // Potentially refactor QueueClientFactory to have better way to handle instances and group info.
  }

  public String getTableName() {
    return tableName;
  }
}
