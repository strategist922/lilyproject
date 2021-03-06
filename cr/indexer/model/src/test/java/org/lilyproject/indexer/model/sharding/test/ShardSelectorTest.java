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
package org.lilyproject.indexer.model.sharding.test;

import org.apache.commons.io.IOUtils;
import org.junit.Test;
import org.lilyproject.indexer.model.sharding.DefaultShardSelectorBuilder;
import org.lilyproject.indexer.model.sharding.JsonShardSelectorBuilder;
import org.lilyproject.indexer.model.sharding.ShardSelector;
import org.lilyproject.indexer.model.sharding.ShardSelectorException;
import org.lilyproject.repository.api.IdGenerator;
import org.lilyproject.repository.api.RecordId;
import org.lilyproject.repository.impl.id.IdGeneratorImpl;

import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.Assert.*;

public class ShardSelectorTest {
    private static final String BASE_PATH = "org/lilyproject/indexer/model/sharding/test/";

    @Test
    public void testRecordIdListMapping() throws Exception {
        byte[] mapping1Data = IOUtils.toByteArray(getClass().getClassLoader().getResourceAsStream(BASE_PATH + "shardingconfig1.json"));
        ShardSelector selector = JsonShardSelectorBuilder.build(mapping1Data);

        IdGenerator idGenerator = new IdGeneratorImpl();

        String shardName = selector.getShard(idGenerator.newRecordId());
        assertNotNull(shardName);
    }

    @Test
    public void testStringFieldListMapping() throws Exception {
        byte[] mappingData = IOUtils.toByteArray(getClass().getClassLoader().getResourceAsStream(BASE_PATH + "shardingconfig2.json"));
        ShardSelector selector = JsonShardSelectorBuilder.build(mappingData);

        IdGenerator idGenerator = new IdGeneratorImpl();
        RecordId recordId = idGenerator.newRecordId(Collections.singletonMap("transport", "car"));

        String shardName = selector.getShard(recordId);
        assertEquals("shard1", shardName);
    }

    @Test
    public void testLongFieldRangeMapping() throws Exception {
        byte[] mappingData = IOUtils.toByteArray(getClass().getClassLoader().getResourceAsStream(BASE_PATH + "shardingconfig3.json"));
        ShardSelector selector = JsonShardSelectorBuilder.build(mappingData);

        IdGenerator idGenerator = new IdGeneratorImpl();
        RecordId recordId = idGenerator.newRecordId(Collections.singletonMap("weight", "400"));

        String shardName = selector.getShard(recordId);
        assertEquals("shard1", shardName);

        recordId = idGenerator.newRecordId(Collections.singletonMap("weight", "1000"));
        shardName = selector.getShard(recordId);
        assertEquals("shard2", shardName);

        recordId = idGenerator.newRecordId(Collections.singletonMap("weight", "1200"));
        shardName = selector.getShard(recordId);
        assertEquals("shard2", shardName);

        recordId = idGenerator.newRecordId(Collections.singletonMap("weight", "341234123"));
        shardName = selector.getShard(recordId);
        assertEquals("shard3", shardName);

        recordId = idGenerator.newRecordId(Collections.singletonMap("weight", "abc"));
        try {
            shardName = selector.getShard(recordId);
            fail("Expected an exception");
        } catch (ShardSelectorException e) {
            // expected
        }
    }

    @Test
    public void testDefaultMapping() throws Exception {
        SortedMap<String, String> shards = new TreeMap<String, String>();
        shards.put("shard1", "http://solr1");
        shards.put("shard2", "http://solr2");
        shards.put("shard3", "http://solr3");

        ShardSelector selector = DefaultShardSelectorBuilder.createDefaultSelector(shards);

        IdGenerator idGenerator = new IdGeneratorImpl();

        boolean shard1Used = false;
        boolean shard2Used = false;
        boolean shard3Used = false;

        for (int i = 0; i < 50; i++) {
            String shardName = selector.getShard(idGenerator.newRecordId());
            assertTrue(shards.containsKey(shardName));

            if (shardName.equals("shard1")) {
                shard1Used = true;
            } else if (shardName.equals("shard2")) {
                shard2Used = true;
            } else if (shardName.equals("shard3")) {
                shard3Used = true;
            }
        }

        assertTrue(shard1Used);
        assertTrue(shard2Used);
        assertTrue(shard3Used);
    }
}
