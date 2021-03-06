/*
 * Copyright 2010 Outerthought bvba
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
package org.lilyproject.indexer.worker;

import static org.lilyproject.indexer.model.api.IndexerModelEventType.INDEX_ADDED;
import static org.lilyproject.indexer.model.api.IndexerModelEventType.INDEX_REMOVED;
import static org.lilyproject.indexer.model.api.IndexerModelEventType.INDEX_UPDATED;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import com.ngdata.sep.impl.SepConsumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.zookeeper.KeeperException;
import org.lilyproject.indexer.derefmap.DerefMap;
import org.lilyproject.indexer.derefmap.DerefMapHbaseImpl;
import org.lilyproject.indexer.engine.ClassicSolrShardManager;
import org.lilyproject.indexer.engine.CloudSolrShardManager;
import org.lilyproject.indexer.engine.IndexLocker;
import org.lilyproject.indexer.engine.IndexUpdater;
import org.lilyproject.indexer.engine.IndexUpdaterMetrics;
import org.lilyproject.indexer.engine.Indexer;
import org.lilyproject.indexer.engine.IndexerMetrics;
import org.lilyproject.indexer.engine.IndexerRegistry;
import org.lilyproject.indexer.engine.SolrClientConfig;
import org.lilyproject.indexer.engine.SolrShardManager;
import org.lilyproject.indexer.model.api.IndexDefinition;
import org.lilyproject.indexer.model.api.IndexNotFoundException;
import org.lilyproject.indexer.model.api.IndexUpdateState;
import org.lilyproject.indexer.model.api.IndexerModel;
import org.lilyproject.indexer.model.api.IndexerModelEvent;
import org.lilyproject.indexer.model.api.IndexerModelListener;
import org.lilyproject.indexer.model.indexerconf.IndexerConf;
import org.lilyproject.indexer.model.indexerconf.IndexerConfBuilder;
import org.lilyproject.indexer.model.sharding.DefaultShardSelectorBuilder;
import org.lilyproject.indexer.model.sharding.JsonShardSelectorBuilder;
import org.lilyproject.indexer.model.sharding.ShardSelector;
import org.lilyproject.repository.api.RepositoryManager;
import org.lilyproject.sep.LilyEventPublisherManager;
import org.lilyproject.sep.LilyPayloadExtractor;
import org.lilyproject.sep.ZooKeeperItfAdapter;
import org.lilyproject.util.Logs;
import org.lilyproject.util.ObjectUtils;
import org.lilyproject.util.hbase.HBaseTableFactory;
import org.lilyproject.util.io.Closer;
import org.lilyproject.util.zookeeper.ZooKeeperItf;

/**
 * IndexerWorker is responsible for the incremental indexing updating, thus for starting
 * index updaters on each Lily node for each index that is configured for updating (according
 * to its {@link IndexUpdateState}).
 *
 * <p>IndexerWorker does not shut down the index updaters when the ZooKeeper connection is
 * lost. This is in the assumption that if the ZK connection would be lost for a longer
 * period of time, the Lily node will shut down, and that it should not cause harm that
 * the index updaters continue to run according to a possibly outdated configuration, since
 * after all one can not expect these things to change momentarily.
 */
public class IndexerWorker {
    private final IndexerModel indexerModel;

    private final RepositoryManager repositoryManager;

    private final Configuration hbaseConf;

    private final ZooKeeperItf zk;

    private final SolrClientConfig solrClientConfig;

    private final IndexerWorkerSettings settings;

    private final String hostName;

    private final IndexerModelListener listener = new MyListener();

    private final Map<String, IndexUpdaterHandle> indexUpdaters = new HashMap<String, IndexUpdaterHandle>();

    private final Object indexUpdatersLock = new Object();

    private final BlockingQueue<IndexerModelEvent> eventQueue = new LinkedBlockingQueue<IndexerModelEvent>();

    private EventWorker eventWorker;

    private Thread eventWorkerThread;

    private HttpClient httpClient;

    private ThreadSafeClientConnManager connectionManager;

    private final IndexerRegistry indexerRegistry;

    private HBaseTableFactory tableFactory;

    private final Log log = LogFactory.getLog(getClass());

    public IndexerWorker(IndexerModel indexerModel, RepositoryManager repositoryManager, ZooKeeperItf zk, Configuration hbaseConf,
                         SolrClientConfig solrClientConfig, String hostName, IndexerWorkerSettings settings,
                         IndexerRegistry indexerRegistry, HBaseTableFactory tableFactory)
            throws IOException, org.lilyproject.hbaseindex.IndexNotFoundException, InterruptedException {
        this.indexerModel = indexerModel;
        this.repositoryManager = repositoryManager;
        this.hbaseConf = hbaseConf;
        this.zk = zk;
        this.settings = settings;
        this.solrClientConfig = solrClientConfig;
        this.hostName = hostName;
        this.indexerRegistry = indexerRegistry;
        this.tableFactory = tableFactory;
    }

    @PostConstruct
    public void init() {
        connectionManager = new ThreadSafeClientConnManager();
        connectionManager.setDefaultMaxPerRoute(settings.getSolrMaxConnectionsPerHost());
        connectionManager.setMaxTotal(settings.getSolrMaxTotalConnections());
        httpClient = new DefaultHttpClient(connectionManager);

        eventWorker = new EventWorker();
        eventWorkerThread = new Thread(eventWorker, "IndexerWorkerEventWorker");
        eventWorkerThread.start();

        synchronized (indexUpdatersLock) {
            Collection<IndexDefinition> indexes = indexerModel.getIndexes(listener);

            for (IndexDefinition index : indexes) {
                if (shouldRunIndexUpdater(index)) {
                    addIndexUpdater(index);
                }
            }
        }
    }

    @PreDestroy
    public void stop() {
        eventWorker.stop();
        eventWorkerThread.interrupt();
        try {
            Logs.logThreadJoin(eventWorkerThread);
            eventWorkerThread.join();
        } catch (InterruptedException e) {
            log.info("Interrupted while joining eventWorkerThread.");
        }

        for (IndexUpdaterHandle handle : indexUpdaters.values()) {
            try {
                handle.stop();
            } catch (InterruptedException e) {
                // Continue the stop procedure
            }
        }

        connectionManager.shutdown();
    }

    private void addIndexUpdater(IndexDefinition index) {
        IndexUpdaterHandle handle = null;
        try {
            IndexerConf indexerConf = IndexerConfBuilder.build(new ByteArrayInputStream(index.getConfiguration()),
                    repositoryManager);

            final SolrShardManager solrShardMgr = getSolrShardManager(index);

            IndexLocker indexLocker = new IndexLocker(zk, settings.getEnableLocking());
            IndexerMetrics indexerMetrics = new IndexerMetrics(index.getName());

            // Create a deref map in case the indexer configuration contains deref fields and the index definition says
            // we should maintain a deref map.
            DerefMap derefMap = index.isEnableDerefMap() && indexerConf.containsDerefExpressions() ?
                    DerefMapHbaseImpl.create(index.getName(), hbaseConf, tableFactory,
                            repositoryManager.getIdGenerator()) : null;

            // create and register the indexer
            Indexer indexer = new Indexer(index.getName(), indexerConf,
                    repositoryManager, solrShardMgr, indexLocker, indexerMetrics,
                    derefMap);
            indexerRegistry.register(indexer);

            IndexUpdaterMetrics updaterMetrics = new IndexUpdaterMetrics(index.getName());
            LilyEventPublisherManager eventPublisherManager = new LilyEventPublisherManager(tableFactory);
            IndexUpdater indexUpdater = new IndexUpdater(indexer,
                            repositoryManager, indexLocker, updaterMetrics, derefMap,
                            eventPublisherManager, index.getQueueSubscriptionId());

            SepConsumer sepConsumer = new SepConsumer(index.getQueueSubscriptionId(),
                    index.getSubscriptionTimestamp(), indexUpdater, settings.getListenersPerIndex(), hostName,
                    new ZooKeeperItfAdapter(zk), hbaseConf, new LilyPayloadExtractor());
            handle = new IndexUpdaterHandle(index, sepConsumer, solrShardMgr, indexerMetrics, updaterMetrics);
            handle.start();

            indexUpdaters.put(index.getName(), handle);

            log.info("Started index updater for index " + index.getName());
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            log.error("Problem starting index updater for index " + index.getName(), t);

            if (handle != null) {
                // stop any listeners that might have been started
                try {
                    handle.stop();
                } catch (Throwable t2) {
                    if (t instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.error("Problem stopping listeners for failed-to-start index updater for index '" +
                            index.getName() + "'", t2);
                }
            }
        }
    }

    private SolrShardManager getSolrShardManager(IndexDefinition index) throws Exception {
        if (index.getSolrShards().isEmpty()) {
            return new CloudSolrShardManager(index.getName(), index.getZkConnectionString(), index.getSolrCollection(),
                    true);
        } else {
            ShardSelector shardSelector;
            if (index.getShardingConfiguration() == null) {
                shardSelector = DefaultShardSelectorBuilder.createDefaultSelector(index.getSolrShards());
            } else {
                shardSelector = JsonShardSelectorBuilder.build(index.getShardingConfiguration());
            }

            checkShardUsage(index.getName(), index.getSolrShards().keySet(), shardSelector.getShards());

            return new ClassicSolrShardManager(index.getName(), index.getSolrShards(), shardSelector, httpClient,
                    solrClientConfig, true);
        }
    }

    private void checkShardUsage(String indexName, Set<String> definedShards, Set<String> selectorShards) {
        for (String shard : definedShards) {
            if (!selectorShards.contains(shard)) {
                log.warn("A shard is not used by the shard selector. Index: " + indexName + ", shard: " + shard);
            }
        }
    }

    private void updateIndexUpdater(IndexDefinition index) {
        IndexUpdaterHandle handle = indexUpdaters.get(index.getName());

        if (handle.indexDef.getZkDataVersion() >= index.getZkDataVersion()) {
            return;
        }

        boolean relevantChanges = !Arrays.equals(handle.indexDef.getConfiguration(), index.getConfiguration()) ||
                !handle.indexDef.getSolrShards().equals(index.getSolrShards()) ||
                !ObjectUtils.safeEquals(handle.indexDef.getShardingConfiguration(), index.getShardingConfiguration()) ||
                handle.indexDef.isEnableDerefMap() != index.isEnableDerefMap();

        if (!relevantChanges) {
            return;
        }

        if (removeIndexUpdater(index.getName())) {
            addIndexUpdater(index);
        }
    }

    private boolean removeIndexUpdater(String indexName) {
        indexerRegistry.unregister(indexName);

        IndexUpdaterHandle handle = indexUpdaters.get(indexName);

        if (handle == null) {
            return true;
        }

        try {
            handle.stop();
            indexUpdaters.remove(indexName);
            log.info("Stopped indexer updater for index " + indexName);
            return true;
        } catch (Throwable t) {
            log.fatal("Failed to stop an IndexUpdater that should be stopped.", t);
            return false;
        }
    }

    private class MyListener implements IndexerModelListener {
        @Override
        public void process(IndexerModelEvent event) {
            try {
                // Because the actions we take in response to events might take some time, we
                // let the events process by another thread, so that other watchers do not
                // have to wait too long.
                eventQueue.put(event);
            } catch (InterruptedException e) {
                log.info("IndexerWorker.IndexerModelListener interrupted.");
            }
        }
    }

    private boolean shouldRunIndexUpdater(IndexDefinition index) {
        return index.getUpdateState() == IndexUpdateState.SUBSCRIBE_AND_LISTEN &&
                index.getQueueSubscriptionId() != null &&
                !index.getGeneralState().isDeleteState();
    }

    private class IndexUpdaterHandle {
        private final IndexDefinition indexDef;
        private final SepConsumer sepConsumer;
        private final SolrShardManager solrShardMgr;
        private final IndexerMetrics indexerMetrics;
        private final IndexUpdaterMetrics updaterMetrics;

        public IndexUpdaterHandle(IndexDefinition indexDef, SepConsumer sepEventSlave,
                                  SolrShardManager solrShardMgr, IndexerMetrics indexerMetrics,
                                  IndexUpdaterMetrics updaterMetrics) {
            this.indexDef = indexDef;
            this.sepConsumer = sepEventSlave;
            this.solrShardMgr = solrShardMgr;
            this.indexerMetrics = indexerMetrics;
            this.updaterMetrics = updaterMetrics;
        }

        public void start() throws InterruptedException, KeeperException, IOException {
            sepConsumer.start();
        }

        public void stop() throws InterruptedException {
            Closer.close(sepConsumer);
            Closer.close(solrShardMgr);
            Closer.close(indexerMetrics);
            Closer.close(updaterMetrics);
        }
    }

    private class EventWorker implements Runnable {
        private volatile boolean stop = false;

        public void stop() {
            stop = true;
        }

        @Override
        public void run() {
            while (!stop) { // We need the stop flag because some code (HBase client code) eats interrupted flags
                if (Thread.interrupted()) {
                    return;
                }

                try {
                    int queueSize = eventQueue.size();
                    if (queueSize >= 10) {
                        log.warn("EventWorker queue getting large, size = " + queueSize);
                    }

                    IndexerModelEvent event = eventQueue.take();
                    if (event.getType() == INDEX_ADDED || event.getType() == INDEX_UPDATED) {
                        try {
                            IndexDefinition index = indexerModel.getIndex(event.getIndexName());
                            if (shouldRunIndexUpdater(index)) {
                                if (indexUpdaters.containsKey(index.getName())) {
                                    updateIndexUpdater(index);
                                } else {
                                    addIndexUpdater(index);
                                }
                            } else {
                                removeIndexUpdater(index.getName());
                            }
                        } catch (IndexNotFoundException e) {
                            removeIndexUpdater(event.getIndexName());
                        } catch (Throwable t) {
                            log.error("Error in IndexerWorker's IndexerModelListener.", t);
                        }
                    } else if (event.getType() == INDEX_REMOVED) {
                        removeIndexUpdater(event.getIndexName());
                    }
                } catch (InterruptedException e) {
                    log.info("IndexerWorker.EventWorker interrupted.");
                    return;
                } catch (Throwable t) {
                    log.error("Error processing indexer model event in IndexerWorker.", t);
                }
            }
        }
    }
}
