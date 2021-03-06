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
package org.lilyproject.indexer.model.test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.hadoop.hbase.zookeeper.MiniZooKeeperCluster;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.lilyproject.indexer.model.api.IndexDefinition;
import org.lilyproject.indexer.model.api.IndexGeneralState;
import org.lilyproject.indexer.model.api.IndexUpdateException;
import org.lilyproject.indexer.model.api.IndexerModelEvent;
import org.lilyproject.indexer.model.api.IndexerModelEventType;
import org.lilyproject.indexer.model.api.IndexerModelListener;
import org.lilyproject.indexer.model.api.WriteableIndexerModel;
import org.lilyproject.indexer.model.impl.IndexerModelImpl;
import org.lilyproject.util.io.Closer;
import org.lilyproject.util.net.NetUtils;
import org.lilyproject.util.zookeeper.ZkUtil;
import org.lilyproject.util.zookeeper.ZooKeeperItf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IndexerModelTest {
    private static MiniZooKeeperCluster ZK_CLUSTER;
    private static File ZK_DIR;
    private static int ZK_CLIENT_PORT;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        ZK_DIR = new File(System.getProperty("java.io.tmpdir") + File.separator + "lily.zklocktest");
        ZK_CLIENT_PORT = NetUtils.getFreePort();

        ZK_CLUSTER = new MiniZooKeeperCluster();
        ZK_CLUSTER.setDefaultClientPort(ZK_CLIENT_PORT);
        ZK_CLUSTER.startup(ZK_DIR);
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        if (ZK_CLUSTER != null) {
            ZK_CLUSTER.shutdown();
        }
    }

    @Test
    public void testSomeBasics() throws Exception {
        ZooKeeperItf zk1 = ZkUtil.connect("localhost:" + ZK_CLIENT_PORT, 5000);
        ZooKeeperItf zk2 = ZkUtil.connect("localhost:" + ZK_CLIENT_PORT, 5000);
        WriteableIndexerModel model1 = null;
        WriteableIndexerModel model2 = null;
        try {

            TestListener listener = new TestListener();

            model1 = new IndexerModelImpl(zk1);
            model1.registerListener(listener);

            // Create an index
            IndexDefinition index1 = model1.newIndex("index1");
            index1.setConfiguration("<indexer/>".getBytes("UTF-8"));
            assertEquals("<indexer/>", new String(index1.getConfiguration()));
            index1.setSolrCollection("collection1");
            index1.setZkConnectionString("localhost:" + ZK_CLIENT_PORT + "/solr");
            model1.addIndex(index1);

            listener.waitForEvents(1);
            listener.verifyEvents(new IndexerModelEvent(IndexerModelEventType.INDEX_ADDED, "index1"));

            // Verify that a fresh indexer model has the index
            model2 = new IndexerModelImpl(zk2);
            assertEquals(1, model2.getIndexes().size());
            assertTrue(model2.hasIndex("index1"));

            // Update the index
            index1.setGeneralState(IndexGeneralState.DISABLED);
            String lock = model1.lockIndex("index1");
            model1.updateIndex(index1, lock);

            listener.waitForEvents(1);
            listener.verifyEvents(new IndexerModelEvent(IndexerModelEventType.INDEX_UPDATED, "index1"));

            // Do not release the lock, updating through model2 should fail
            index1.setConfiguration("<indexer></indexer>".getBytes("UTF-8"));
            try {
                model2.updateIndex(index1, lock + "foo");
                fail("expected exception");
            } catch (IndexUpdateException e) {
                // verify the exception says something about locks
                assertTrue(e.getMessage().indexOf("lock") != -1);
            }

            model1.unlockIndex(lock);

            model1.deleteIndex("index1");

            listener.waitForEvents(1);
            listener.verifyEvents(new IndexerModelEvent(IndexerModelEventType.INDEX_REMOVED, "index1"));

            // Create some more indexes
            IndexerModelEvent[] expectedEvents = new IndexerModelEvent[9];
            for (int i = 2; i <= 10; i++) {
                String name = "index" + i;
                IndexDefinition index = model1.newIndex(name);
                index.setConfiguration("<indexer/>".getBytes("UTF-8"));
                index.setSolrShards(Collections.singletonMap("shard1", "http://localhost:8983/solr"));
                model1.addIndex(index);
                expectedEvents[i - 2] = new IndexerModelEvent(IndexerModelEventType.INDEX_ADDED, name);
            }

            listener.waitForEvents(9);
            listener.verifyEvents(expectedEvents);

        } finally {
            Closer.close(model1);
            Closer.close(model2);
            Closer.close(zk1);
            Closer.close(zk2);
        }
    }

    private class TestListener implements IndexerModelListener {
        private Set<IndexerModelEvent> events = new HashSet<IndexerModelEvent>();

        @Override
        public void process(IndexerModelEvent event) {
            synchronized (this) {
                events.add(event);
                notifyAll();
            }
        }

        public void waitForEvents(int count) throws InterruptedException {
            long timeout = 1000;
            long now = System.currentTimeMillis();
            synchronized (this) {
                while (events.size() < count && System.currentTimeMillis() - now < timeout) {
                    wait(timeout);
                }
            }
        }

        public void verifyEvents(IndexerModelEvent... expectedEvents) {
            if (events.size() != expectedEvents.length) {
                if (events.size() > 0) {
                    System.out.println("The events are:");
                    for (IndexerModelEvent item : events) {
                        System.out.println(item.getType() + " - " + item.getIndexName());
                    }
                } else {
                    System.out.println("There are no events.");
                }

                assertEquals("Expected number of events", expectedEvents.length, events.size());
            }

            Set<IndexerModelEvent> expectedEventsSet  = new HashSet<IndexerModelEvent>(Arrays.asList(expectedEvents));

            for (IndexerModelEvent event : expectedEvents) {
                if (!events.contains(event)) {
                    fail("Expected event not present among events: " + event);
                }
            }

            for (IndexerModelEvent event : events) {
                if (!expectedEventsSet.contains(event)) {
                    fail("Got an event which is not among the expected events: " + event);
                }
            }

            events.clear();
        }
    }
}
