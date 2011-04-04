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
package org.lilyproject.indexer.engine.test;

import static org.junit.Assert.assertEquals;
import static org.lilyproject.util.repo.RecordEvent.Type.CREATE;
import static org.lilyproject.util.repo.RecordEvent.Type.DELETE;
import static org.lilyproject.util.repo.RecordEvent.Type.UPDATE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.lilyproject.hbaseindex.IndexManager;
import org.lilyproject.indexer.engine.IndexLocker;
import org.lilyproject.indexer.engine.IndexUpdater;
import org.lilyproject.indexer.engine.IndexUpdaterMetrics;
import org.lilyproject.indexer.engine.Indexer;
import org.lilyproject.indexer.engine.IndexerMetrics;
import org.lilyproject.indexer.engine.SolrServers;
import org.lilyproject.indexer.model.indexerconf.IndexerConf;
import org.lilyproject.indexer.model.indexerconf.IndexerConfBuilder;
import org.lilyproject.linkindex.LinkIndex;
import org.lilyproject.linkindex.LinkIndexUpdater;
import org.lilyproject.repository.api.*;
import org.lilyproject.repotestfw.RepositorySetup;
import org.lilyproject.rowlog.api.RowLogConfigurationManager;
import org.lilyproject.rowlog.api.RowLogException;
import org.lilyproject.rowlog.api.RowLogMessage;
import org.lilyproject.rowlog.api.RowLogMessageListener;
import org.lilyproject.rowlog.api.RowLogMessageListenerMapping;
import org.lilyproject.rowlog.api.RowLogSubscription;
import org.lilyproject.solrtestfw.SolrTestingUtility;
import org.lilyproject.testfw.TestHelper;
import org.lilyproject.util.repo.RecordEvent;
import org.lilyproject.util.repo.VersionTag;

// To run this test from an IDE, set a property solr.war pointing to the SOLR war

public class IndexerTest {
    private final static RepositorySetup repoSetup = new RepositorySetup();
    private static IndexerConf INDEXER_CONF;
    private static SolrTestingUtility SOLR_TEST_UTIL;
    private static Repository repository;
    private static TypeManager typeManager;
    private static IdGenerator idGenerator;
    private static SolrServers solrServers;

    private static FieldType nvTag;
    private static FieldType liveTag;
    private static FieldType previewTag;
    private static FieldType latestTag;
    private static FieldType lastTag;

    private static FieldType nvfield1;
    private static FieldType nvfield2;
    private static FieldType nvLinkField1;
    private static FieldType nvLinkField2;

    private static FieldType vfield1;
    private static FieldType vLinkField1;

    private static FieldType vStringMvField;
    private static FieldType vLongField;
    private static FieldType vBlobField;
    private static FieldType vBlobMvHierField;
    private static FieldType vDateTimeField;
    private static FieldType vDateField;
    private static FieldType vIntHierField;

    private static final String NS = "org.lilyproject.indexer.test";
    private static final String NS2 = "org.lilyproject.indexer.test.2";

    private static Log log = LogFactory.getLog(IndexerTest.class);

    private static MessageVerifier messageVerifier = new MessageVerifier();
    private static RecordType nvRecordType1;
    private static RecordType vRecordType1;
    private static RecordType lastRecordType;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        SOLR_TEST_UTIL = new SolrTestingUtility("org/lilyproject/indexer/engine/test/schema1.xml");

        TestHelper.setupLogging("org.lilyproject.indexer", "org.lilyproject.linkindex",
                "org.lilyproject.rowlog.impl.RowLogImpl");

        SOLR_TEST_UTIL.start();

        repoSetup.setupCore();
        repoSetup.setupRepository(true);
        repoSetup.setupMessageQueue(false, true);

        repository = repoSetup.getRepository();
        typeManager = repoSetup.getTypeManager();
        idGenerator = repository.getIdGenerator();

        IndexManager.createIndexMetaTableIfNotExists(repoSetup.getHadoopConf());
        IndexManager indexManager = new IndexManager(repoSetup.getHadoopConf());

        LinkIndex.createIndexes(indexManager);
        LinkIndex linkIndex = new LinkIndex(indexManager, repository);

        // Field types should exist before the indexer conf is loaded
        setupSchema();

        RowLogConfigurationManager rowLogConfMgr = repoSetup.getRowLogConfManager();
        rowLogConfMgr.addSubscription("WAL", "LinkIndexUpdater", RowLogSubscription.Type.VM, 1);
        rowLogConfMgr.addSubscription("WAL", "MessageVerifier", RowLogSubscription.Type.VM, 2);

        repoSetup.waitForSubscription(repoSetup.getWal(), "LinkIndexUpdater");
        repoSetup.waitForSubscription(repoSetup.getWal(), "MessageVerifier");

        rowLogConfMgr.addSubscription("MQ", "IndexUpdater", RowLogSubscription.Type.VM, 1);

        repoSetup.waitForSubscription(repoSetup.getMq(), "IndexUpdater");

        solrServers = SolrServers.createForOneShard(SOLR_TEST_UTIL.getUri());
        INDEXER_CONF = IndexerConfBuilder.build(IndexerTest.class.getClassLoader().getResourceAsStream(
                "org/lilyproject/indexer/engine/test/indexerconf1.xml"), repository);
        IndexLocker indexLocker = new IndexLocker(repoSetup.getZk());
        Indexer indexer = new Indexer(INDEXER_CONF, repository, solrServers, indexLocker, new IndexerMetrics("test"));

        RowLogMessageListenerMapping.INSTANCE.put("LinkIndexUpdater", new LinkIndexUpdater(repository, linkIndex));
        RowLogMessageListenerMapping.INSTANCE.put("IndexUpdater", new IndexUpdater(indexer, repository, linkIndex,
                indexLocker, repoSetup.getMq(), new IndexUpdaterMetrics("test"), repoSetup.getRowLocker()));
        RowLogMessageListenerMapping.INSTANCE.put("MessageVerifier", messageVerifier);

    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        repoSetup.stop();

        if (SOLR_TEST_UTIL != null)
            SOLR_TEST_UTIL.stop();
    }

    private static void setupSchema() throws Exception {
        ValueType stringValueType = typeManager.getValueType("STRING", false, false);
        ValueType stringMvValueType = typeManager.getValueType("STRING", true, false);

        ValueType longValueType = typeManager.getValueType("LONG", false, false);

        ValueType linkValueType = typeManager.getValueType("LINK", false, false);

        ValueType blobValueType = typeManager.getValueType("BLOB", false, false);
        ValueType blobMvHierValueType = typeManager.getValueType("BLOB", true, true);

        ValueType dateTimeValueType = typeManager.getValueType("DATETIME", false, false);
        ValueType dateValueType = typeManager.getValueType("DATE", false, false);

        ValueType intHierValueType = typeManager.getValueType("INTEGER", false, true);

        //
        // Version tag fields
        //

        lastTag = typeManager.getFieldTypeByName(VersionTag.LAST);

        QName nvTagName = new QName(VersionTag.NAMESPACE, "nonversioned");
        nvTag = typeManager.newFieldType(longValueType, nvTagName, Scope.NON_VERSIONED);
        nvTag = typeManager.createFieldType(nvTag);

        QName liveTagName = new QName(VersionTag.NAMESPACE, "live");
        liveTag = typeManager.newFieldType(longValueType, liveTagName, Scope.NON_VERSIONED);
        liveTag = typeManager.createFieldType(liveTag);

        QName previewTagName = new QName(VersionTag.NAMESPACE, "preview");
        previewTag = typeManager.newFieldType(longValueType, previewTagName, Scope.NON_VERSIONED);
        previewTag = typeManager.createFieldType(previewTag);

        // Note: tag 'last' was renamed to 'latest' because there is now built-in behaviour for the tag named 'last'
        QName lastTagName = new QName(VersionTag.NAMESPACE, "latest");
        latestTag = typeManager.newFieldType(longValueType, lastTagName, Scope.NON_VERSIONED);
        latestTag = typeManager.createFieldType(latestTag);

        //
        // Schema types for the nonversioned test
        //

        QName field1Name = new QName(NS, "nv_field1");
        nvfield1 = typeManager.newFieldType(stringValueType, field1Name, Scope.NON_VERSIONED);
        nvfield1 = typeManager.createFieldType(nvfield1);

        QName field2Name = new QName(NS, "nv_field2");
        nvfield2 = typeManager.newFieldType(stringValueType, field2Name, Scope.NON_VERSIONED);
        nvfield2 = typeManager.createFieldType(nvfield2);

        QName linkField1Name = new QName(NS, "nv_linkfield1");
        nvLinkField1 = typeManager.newFieldType(linkValueType, linkField1Name, Scope.NON_VERSIONED);
        nvLinkField1 = typeManager.createFieldType(nvLinkField1);

        QName linkField2Name = new QName(NS, "nv_linkfield2");
        nvLinkField2 = typeManager.newFieldType(linkValueType, linkField2Name, Scope.NON_VERSIONED);
        nvLinkField2 = typeManager.createFieldType(nvLinkField2);

        nvRecordType1 = typeManager.newRecordType(new QName(NS, "NVRecordType1"));
        nvRecordType1.addFieldTypeEntry(nvfield1.getId(), false);
        nvRecordType1.addFieldTypeEntry(liveTag.getId(), false);
        nvRecordType1.addFieldTypeEntry(latestTag.getId(), false);
        nvRecordType1.addFieldTypeEntry(previewTag.getId(), false);
        nvRecordType1.addFieldTypeEntry(nvLinkField1.getId(), false);
        nvRecordType1.addFieldTypeEntry(nvLinkField2.getId(), false);
        nvRecordType1 = typeManager.createRecordType(nvRecordType1);

        //
        // Schema types for the versioned test
        //
        QName vfield1Name = new QName(NS2, "v_field1");
        vfield1 = typeManager.newFieldType(stringValueType, vfield1Name, Scope.VERSIONED);
        vfield1 = typeManager.createFieldType(vfield1);

        QName vlinkField1Name = new QName(NS2, "v_linkfield1");
        vLinkField1 = typeManager.newFieldType(linkValueType, vlinkField1Name, Scope.VERSIONED);
        vLinkField1 = typeManager.createFieldType(vLinkField1);

        QName vStringMvFieldName = new QName(NS2, "v_string_mv_field");
        vStringMvField = typeManager.newFieldType(stringMvValueType, vStringMvFieldName, Scope.VERSIONED);
        vStringMvField = typeManager.createFieldType(vStringMvField);

        QName vLongFieldName = new QName(NS2, "v_long_field");
        vLongField = typeManager.newFieldType(longValueType, vLongFieldName, Scope.VERSIONED);
        vLongField = typeManager.createFieldType(vLongField);

        QName vBlobFieldName = new QName(NS2, "v_blob_field");
        vBlobField = typeManager.newFieldType(blobValueType, vBlobFieldName, Scope.VERSIONED);
        vBlobField = typeManager.createFieldType(vBlobField);

        QName vBlobMvHierFieldName = new QName(NS2, "v_blob_mv_hier_field");
        vBlobMvHierField = typeManager.newFieldType(blobMvHierValueType, vBlobMvHierFieldName, Scope.VERSIONED);
        vBlobMvHierField = typeManager.createFieldType(vBlobMvHierField);

        QName vDateTimeFieldName = new QName(NS2, "v_datetime_field");
        vDateTimeField = typeManager.newFieldType(dateTimeValueType, vDateTimeFieldName, Scope.VERSIONED);
        vDateTimeField = typeManager.createFieldType(vDateTimeField);

        QName vDateFieldName = new QName(NS2, "v_date_field");
        vDateField = typeManager.newFieldType(dateValueType, vDateFieldName, Scope.VERSIONED);
        vDateField = typeManager.createFieldType(vDateField);

        QName vIntHierFieldName = new QName(NS2, "v_int_hier_field");
        vIntHierField = typeManager.newFieldType(intHierValueType, vIntHierFieldName, Scope.VERSIONED);
        vIntHierField = typeManager.createFieldType(vIntHierField);

        vRecordType1 = typeManager.newRecordType(new QName(NS2, "VRecordType1"));
        vRecordType1.addFieldTypeEntry(vfield1.getId(), false);
        vRecordType1.addFieldTypeEntry(liveTag.getId(), false);
        vRecordType1.addFieldTypeEntry(latestTag.getId(), false);
        vRecordType1.addFieldTypeEntry(previewTag.getId(), false);
        vRecordType1.addFieldTypeEntry(vLinkField1.getId(), false);
        vRecordType1.addFieldTypeEntry(nvLinkField2.getId(), false);
        vRecordType1.addFieldTypeEntry(vStringMvField.getId(), false);
        vRecordType1.addFieldTypeEntry(vLongField.getId(), false);
        vRecordType1.addFieldTypeEntry(vBlobField.getId(), false);
        vRecordType1.addFieldTypeEntry(vBlobMvHierField.getId(), false);
        vRecordType1.addFieldTypeEntry(vDateTimeField.getId(), false);
        vRecordType1.addFieldTypeEntry(vDateField.getId(), false);
        vRecordType1.addFieldTypeEntry(vIntHierField.getId(), false);
        vRecordType1 = typeManager.createRecordType(vRecordType1);

        //
        // Schema types for testing last tag
        //
        lastRecordType = typeManager.newRecordType(new QName(NS2, "LastRecordType"));
        lastRecordType.addFieldTypeEntry(vfield1.getId(), false);
        lastRecordType.addFieldTypeEntry(nvfield1.getId(), false);
        lastRecordType = typeManager.createRecordType(lastRecordType);
    }

    @Test
    public void testIndexerNonVersioned() throws Exception {
        messageVerifier.init();

        //
        // Basic create-update-delete
        //
        {
            // Create a record
            log.debug("Begin test NV1");
            Record record = repository.newRecord();
            record.setRecordType(nvRecordType1.getName());
            record.setField(nvfield1.getName(), "apple");
            record.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, record.getId(), nvfield1.getId(), nvTag.getId());
            record = repository.create(record);

            commitIndex();
            verifyResultCount("nv_field1:apple", 1);

            // Update the record
            log.debug("Begin test NV2");
            record.setField(nvfield1.getName(), "pear");
            expectEvent(UPDATE, record.getId(), nvfield1.getId());
            repository.update(record);

            commitIndex();
            verifyResultCount("nv_field1:pear", 1);
            verifyResultCount("nv_field1:apple", 0);

            // Do as if field2 changed, while field2 is not present in the document.
            // Such situations can occur if the record is modified before earlier events are processed.
            log.debug("Begin test NV3");
            // TODO send event directly to the Indexer
            // sendEvent(EVENT_RECORD_UPDATED, record.getId(), nvfield2.getId());

            verifyResultCount("nv_field1:pear", 1);
            verifyResultCount("nv_field1:apple", 0);

            // Add a vtag field pointing to a version. For versionless records, this should have no effect
            log.debug("Begin test NV4");
            record.setField(liveTag.getName(), new Long(1));
            expectEvent(UPDATE, record.getId(), liveTag.getId());
            repository.update(record);

            commitIndex();
            verifyResultCount("nv_field1:pear", 1);
            verifyResultCount("nv_field1:apple", 0);

            // Delete the record
            log.debug("Begin test NV5");
            expectEvent(DELETE, record.getId());
            repository.delete(record.getId());

            commitIndex();
            verifyResultCount("nv_field1:pear", 0);
        }

        //
        // Deref
        //
        {
            log.debug("Begin test NV6");
            Record record1 = repository.newRecord();
            record1.setRecordType(nvRecordType1.getName());
            record1.setField(nvfield1.getName(), "pear");
            record1.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, record1.getId(), nvfield1.getId(), nvTag.getId());
            record1 = repository.create(record1);

            Record record2 = repository.newRecord();
            record2.setRecordType(nvRecordType1.getName());
            record2.setField(nvLinkField1.getName(), new Link(record1.getId()));
            record2.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, record2.getId(), nvLinkField1.getId(), nvTag.getId());
            record2 = repository.create(record2);

            commitIndex();
            verifyResultCount("nv_deref1:pear", 1);
        }


        //
        // Variant deref
        //
        {
            log.debug("Begin test NV7");
            Record masterRecord = repository.newRecord();
            masterRecord.setRecordType(nvRecordType1.getName());
            masterRecord.setField(nvfield1.getName(), "yellow");
            masterRecord.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, masterRecord.getId(), nvfield1.getId(), nvTag.getId());
            masterRecord = repository.create(masterRecord);

            RecordId var1Id = idGenerator.newRecordId(masterRecord.getId(), Collections.singletonMap("lang", "en"));
            Record var1Record = repository.newRecord(var1Id);
            var1Record.setRecordType(nvRecordType1.getName());
            var1Record.setField(nvfield1.getName(), "green");
            var1Record.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, var1Id, nvfield1.getId(), nvTag.getId());
            repository.create(var1Record);

            Map<String, String> varProps = new HashMap<String, String>();
            varProps.put("lang", "en");
            varProps.put("branch", "dev");
            RecordId var2Id = idGenerator.newRecordId(masterRecord.getId(), varProps);
            Record var2Record = repository.newRecord(var2Id);
            var2Record.setRecordType(nvRecordType1.getName());
            var2Record.setField(nvfield2.getName(), "blue");
            var2Record.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, var2Id, nvfield2.getId(), nvTag.getId());
            repository.create(var2Record);

            commitIndex();
            verifyResultCount("nv_deref2:yellow", 1);
            verifyResultCount("nv_deref3:yellow", 2);
            verifyResultCount("nv_deref4:green", 1);
            verifyResultCount("nv_deref3:green", 0);
        }

        //
        // Update denormalized data
        //
        {
            log.debug("Begin test NV8");
            Record record1 = repository.newRecord(idGenerator.newRecordId("boe"));
            record1.setRecordType(nvRecordType1.getName());
            record1.setField(nvfield1.getName(), "cumcumber");
            record1.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, record1.getId(), nvfield1.getId(), nvTag.getId());
            record1 = repository.create(record1);

            // Create a record which will contain denormalized data through linking
            Record record2 = repository.newRecord();
            record2.setRecordType(nvRecordType1.getName());
            record2.setField(nvLinkField1.getName(), new Link(record1.getId()));
            record2.setField(nvfield1.getName(), "mushroom");
            record2.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, record2.getId(), nvLinkField1.getId(), nvfield1.getId(), nvTag.getId());
            record2 = repository.create(record2);

            // Create a record which will contain denormalized data through master-dereferencing
            RecordId record3Id = idGenerator.newRecordId(record1.getId(), Collections.singletonMap("lang", "en"));
            Record record3 = repository.newRecord(record3Id);
            record3.setRecordType(nvRecordType1.getName());
            record3.setField(nvfield1.getName(), "eggplant");
            record3.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, record3.getId(), nvfield1.getId(), nvTag.getId());
            record3 = repository.create(record3);

            // Create a record which will contain denormalized data through variant-dereferencing
            Map<String, String> varprops = new HashMap<String, String>();
            varprops.put("lang", "en");
            varprops.put("branch", "dev");
            RecordId record4Id = idGenerator.newRecordId(record1.getId(), varprops);
            Record record4 = repository.newRecord(record4Id);
            record4.setRecordType(nvRecordType1.getName());
            record4.setField(nvfield1.getName(), "broccoli");
            record4.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, record4.getId(), nvfield1.getId(), nvTag.getId());
            record4 = repository.create(record4);

            commitIndex();
            verifyResultCount("nv_deref1:cumcumber", 1);
            verifyResultCount("nv_deref2:cumcumber", 1);
            verifyResultCount("nv_deref3:cumcumber", 2);

            // Update record1, check if index of the others is updated
            log.debug("Begin test NV9");
            record1.setField(nvfield1.getName(), "tomato");
            expectEvent(UPDATE, record1.getId(), nvfield1.getId());
            record1 = repository.update(record1);

            commitIndex();
            verifyResultCount("nv_deref1:tomato", 1);
            verifyResultCount("nv_deref2:tomato", 1);
            verifyResultCount("nv_deref3:tomato", 2);
            verifyResultCount("nv_deref1:cumcumber", 0);
            verifyResultCount("nv_deref2:cumcumber", 0);
            verifyResultCount("nv_deref3:cumcumber", 0);
            verifyResultCount("nv_deref4:eggplant", 1);

            // Update record3, index for record4 should be updated
            log.debug("Begin test NV10");
            record3.setField(nvfield1.getName(), "courgette");
            expectEvent(UPDATE, record3.getId(), nvfield1.getId());
            repository.update(record3);

            commitIndex();
            verifyResultCount("nv_deref4:courgette", 1);
            verifyResultCount("nv_deref4:eggplant", 0);

            // Delete record 3: index for record 4 should be updated
            log.debug("Begin test NV11");
            verifyResultCount("@@id:" + ClientUtils.escapeQueryChars(record3.getId().toString()), 1);
            expectEvent(DELETE, record3.getId());
            repository.delete(record3.getId());

            commitIndex();
            verifyResultCount("nv_deref4:courgette", 0);
            verifyResultCount("nv_deref3:tomato", 1);
            verifyResultCount("@@id:" + ClientUtils.escapeQueryChars(record3.getId().toString()), 0);

            // Delete record 4
            log.debug("Begin test NV12");
            expectEvent(DELETE, record4.getId());
            repository.delete(record4.getId());

            commitIndex();
            verifyResultCount("nv_deref3:tomato", 0);
            verifyResultCount("nv_field1:broccoli", 0);
            verifyResultCount("@@id:" + ClientUtils.escapeQueryChars(record4.getId().toString()), 0);

            // Delete record 1: index of record 2 should be updated
            log.debug("Begin test NV13");
            expectEvent(DELETE, record1.getId());
            repository.delete(record1.getId());

            commitIndex();
            verifyResultCount("nv_deref1:tomato", 0);
            verifyResultCount("nv_field1:mushroom", 1);
        }

        assertEquals("All received messages are correct.", 0, messageVerifier.getFailures());
    }

    @Test
    public void testIndexerWithVersioning() throws Exception {
        messageVerifier.init();

        //
        // Basic create-update-delete
        //
        {
            log.debug("Begin test V1");
            // Create a record
            Record record = repository.newRecord();
            record.setRecordType(vRecordType1.getName());
            record.setField(vfield1.getName(), "apple");
            record.setField(liveTag.getName(), new Long(1));
            expectEvent(CREATE, record.getId(), 1L, null, vfield1.getId(), liveTag.getId());
            record = repository.create(record);

            commitIndex();
            verifyResultCount("v_field1:apple", 1);

            // Update the record, this will create a new version, but we leave the live version tag pointing to version 1
            log.debug("Begin test V2");
            record.setField(vfield1.getName(), "pear");
            expectEvent(UPDATE, record.getId(), 2L, null, vfield1.getId());
            repository.update(record);

            commitIndex();
            verifyResultCount("v_field1:pear", 0);
            verifyResultCount("v_field1:apple", 1);

            // Now move the live version tag to point to version 2
            log.debug("Begin test V3");
            record.setField(liveTag.getName(), new Long(2));
            expectEvent(UPDATE, record.getId(), liveTag.getId());
            record = repository.update(record);

            commitIndex();
            verifyResultCount("v_field1:pear", 1);
            verifyResultCount("v_field1:apple", 0);

            // Now remove the live version tag
            log.debug("Begin test V4");
            record.delete(liveTag.getName(), true);
            expectEvent(UPDATE, record.getId(), liveTag.getId());
            record = repository.update(record);

            commitIndex();
            verifyResultCount("v_field1:pear", 0);

            // Now test with multiple version tags
            log.debug("Begin test V5");
            record.setField(liveTag.getName(), new Long(1));
            record.setField(previewTag.getName(), new Long(2));
            record.setField(latestTag.getName(), new Long(2));
            expectEvent(UPDATE, record.getId(), liveTag.getId(), previewTag.getId(), latestTag.getId());
            record = repository.update(record);

            commitIndex();
            verifyResultCount("v_field1:apple", 1);
            verifyResultCount("v_field1:pear", 2);

            verifyResultCount("+v_field1:pear +@@vtag:" + qesc(previewTag.getId().toString()), 1);
            verifyResultCount("+v_field1:pear +@@vtag:" + qesc(latestTag.getId().toString()), 1);
            verifyResultCount("+v_field1:pear +@@vtag:" + qesc(liveTag.getId().toString()), 0);
            verifyResultCount("+v_field1:apple +@@vtag:" + qesc(liveTag.getId().toString()), 1);
        }

        //
        // Deref
        //
        {
            // Create 4 records for the 4 kinds of dereferenced fields
            log.debug("Begin test V6");
            Record record1 = repository.newRecord();
            record1.setRecordType(vRecordType1.getName());
            record1.setField(vfield1.getName(), "fig");
            record1.setField(liveTag.getName(), Long.valueOf(1));
            expectEvent(CREATE, record1.getId(), 1L, null, vfield1.getId(), liveTag.getId());
            record1 = repository.create(record1);

            Record record2 = repository.newRecord();
            record2.setRecordType(vRecordType1.getName());
            record2.setField(vLinkField1.getName(), new Link(record1.getId()));
            record2.setField(liveTag.getName(), Long.valueOf(1));
            expectEvent(CREATE, record2.getId(), 1L, null, vLinkField1.getId(), liveTag.getId());
            record2 = repository.create(record2);

            commitIndex();
            verifyResultCount("v_deref1:fig", 1);

            log.debug("Begin test V6.1");
            RecordId record3Id = idGenerator.newRecordId(record1.getId(), Collections.singletonMap("lang", "en"));
            Record record3 = repository.newRecord(record3Id);
            record3.setRecordType(vRecordType1.getName());
            record3.setField(vfield1.getName(), "banana");
            record3.setField(liveTag.getName(), Long.valueOf(1));
            expectEvent(CREATE, record3.getId(), 1L, null, vfield1.getId(), liveTag.getId());
            record3 = repository.create(record3);

            commitIndex();
            verifyResultCount("v_deref3:fig", 1);

            log.debug("Begin test V6.2");
            Map<String, String> varprops = new HashMap<String, String>();
            varprops.put("lang", "en");
            varprops.put("branch", "dev");
            RecordId record4Id = idGenerator.newRecordId(record1.getId(), varprops);
            Record record4 = repository.newRecord(record4Id);
            record4.setRecordType(vRecordType1.getName());
            record4.setField(vfield1.getName(), "coconut");
            record4.setField(liveTag.getName(), Long.valueOf(1));
            expectEvent(CREATE, record4.getId(), 1L, null, vfield1.getId(), liveTag.getId());
            record4 = repository.create(record4);

            commitIndex();
            verifyResultCount("v_deref3:fig", 2);
            verifyResultCount("v_deref2:fig", 1);
            verifyResultCount("v_deref4:banana", 1);

            // remove the live tag from record1
            log.debug("Begin test V7");
            record1.delete(liveTag.getName(), true);
            expectEvent(UPDATE, record1.getId(), liveTag.getId());
            record1 = repository.update(record1);

            commitIndex();
            verifyResultCount("v_deref1:fig", 0);

            // and add the live tag again record1
            log.debug("Begin test V8");
            record1.setField(liveTag.getName(), Long.valueOf(1));
            expectEvent(UPDATE, record1.getId(), liveTag.getId());
            record1 = repository.update(record1);

            commitIndex();
            verifyResultCount("v_deref1:fig", 1);

            // Make second version of record1, assign both versions different tags, and assign these tags also
            // to version1 of record2.
            log.debug("Begin test V9");
            record1.setField(vfield1.getName(), "strawberries");
            record1.setField(previewTag.getName(), Long.valueOf(2));
            expectEvent(UPDATE, record1.getId(), 2L, null, vfield1.getId(), previewTag.getId());
            record1 = repository.update(record1);

            record2.setField(previewTag.getName(), Long.valueOf(1));
            expectEvent(UPDATE, record2.getId(), previewTag.getId());
            record2 = repository.update(record2);

            commitIndex();
            verifyResultCount("+v_deref1:strawberries +@@vtag:" + qesc(previewTag.getId().toString()), 1);
            verifyResultCount("+v_deref1:strawberries +@@vtag:" + qesc(liveTag.getId().toString()), 0);
            verifyResultCount("+v_deref1:strawberries", 1);
            verifyResultCount("+v_deref1:fig +@@vtag:" + qesc(liveTag.getId().toString()), 1);
            verifyResultCount("+v_deref1:fig +@@vtag:" + qesc(previewTag.getId().toString()), 0);
            verifyResultCount("+v_deref1:fig", 1);

            // Now do something similar with a 3th version, but first update record2 and then record1
            log.debug("Begin test V10");
            record2.setField(latestTag.getName(), Long.valueOf(1));
            expectEvent(UPDATE, record2.getId(), latestTag.getId());
            record2 = repository.update(record2);

            record1.setField(vfield1.getName(), "kiwi");
            record1.setField(latestTag.getName(), Long.valueOf(3));
            expectEvent(UPDATE, record1.getId(), 3L, null, vfield1.getId(), latestTag.getId());
            record1 = repository.update(record1);

            commitIndex();
            verifyResultCount("+v_deref1:kiwi +@@vtag:" + qesc(latestTag.getId().toString()), 1);
            verifyResultCount("+v_deref1:strawberries +@@vtag:" + qesc(previewTag.getId().toString()), 1);
            verifyResultCount("+v_deref1:fig +@@vtag:" + qesc(liveTag.getId().toString()), 1);
            verifyResultCount("+v_deref1:kiwi +@@vtag:" + qesc(liveTag.getId().toString()), 0);
            verifyResultCount("+v_field1:kiwi +@@vtag:" + qesc(latestTag.getId().toString()), 1);
            verifyResultCount("+v_field1:fig +@@vtag:" + qesc(liveTag.getId().toString()), 1);

            // Perform updates to record3 and check if denorm'ed data in record4 follows
            log.debug("Begin test V11");
            record3.delete(vfield1.getName(), true);
            expectEvent(UPDATE, record3.getId(), 2L, null, vfield1.getId());
            record3 = repository.update(record3);

            commitIndex();
            verifyResultCount("v_deref4:banana", 1); // live tag still points to version 1!

            log.debug("Begin test V12");
            repository.read(record3Id, Long.valueOf(2)); // check version 2 really exists
            record3.setField(liveTag.getName(), Long.valueOf(2));
            expectEvent(UPDATE, record3.getId(), liveTag.getId());
            repository.update(record3);

            commitIndex();
            verifyResultCount("v_deref4:banana", 0);
            verifyResultCount("v_field1:coconut", 1);

            // Delete master
            log.debug("Begin test V13");
            expectEvent(DELETE, record1.getId());
            repository.delete(record1.getId());

            commitIndex();
            verifyResultCount("v_deref1:fig", 0);
            verifyResultCount("v_deref2:fig", 0);
            verifyResultCount("v_deref3:fig", 0);
        }

        //
        // Test that when using vtag pointing to version '0', versioned content is not accessible
        //
        {
            // Plain (without deref)
            log.debug("Begin test V14");
            Record record1 = repository.newRecord();
            record1.setRecordType(vRecordType1.getName());
            record1.setField(nvfield1.getName(), "rollerblades");
            record1.setField(vfield1.getName(), "bicycle");
            record1.setField(liveTag.getName(), 1L);
            record1.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, record1.getId(), 1L, null, nvfield1.getId(), vfield1.getId(), liveTag.getId(),
                    nvTag.getId());
            record1 = repository.create(record1);

            commitIndex();
            verifyResultCount("+@@vtag:" + qesc(nvTag.getId().toString()) + " +nv_field1:rollerblades", 1);
            verifyResultCount("+@@vtag:" + qesc(nvTag.getId().toString()) + " +v_field1:bicycle", 0);
            verifyResultCount("+@@vtag:" + qesc(liveTag.getId().toString()) + " +nv_field1:rollerblades", 1);
            verifyResultCount("+@@vtag:" + qesc(liveTag.getId().toString()) + " +v_field1:bicycle", 1);

            // With deref
            log.debug("Begin test V15");
            Record record2 = repository.newRecord();
            record2.setRecordType(vRecordType1.getName());
            record2.setField(nvLinkField2.getName(), new Link(record1.getId()));
            record2.setField(nvTag.getName(), 0L);
            record2.setField(liveTag.getName(), 0L);
            expectEvent(CREATE, record2.getId(), nvLinkField2.getId(), nvTag.getId(), liveTag.getId());
            record2 = repository.create(record2);

            commitIndex();
            verifyResultCount("+@@vtag:" + qesc(nvTag.getId().toString()) + " +nv_v_deref:bicycle", 0);
            verifyResultCount("+@@vtag:" + qesc(liveTag.getId().toString()) + " +nv_v_deref:bicycle", 1);
        }

        //
        // Test deref from a versionless record via a versioned field to a non-versioned field.
        // From the moment a versioned field is in the deref chain, when the vtag points to version 0,
        // the deref should evaluate to null.
        //
        {
            log.debug("Begin test V18");
            Record record1 = repository.newRecord();
            record1.setRecordType(vRecordType1.getName());
            record1.setField(nvfield1.getName(), "Brussels");
            record1.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, record1.getId(), (Long)null, null, nvfield1.getId(), nvTag.getId());
            record1 = repository.create(record1);

            Record record2 = repository.newRecord();
            record2.setRecordType(vRecordType1.getName());
            record2.setField(vLinkField1.getName(), new Link(record1.getId()));
            record2.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, record2.getId(), 1L, null, vLinkField1.getId(), nvTag.getId());
            record2 = repository.create(record2);

            Record record3 = repository.newRecord();
            record3.setRecordType(vRecordType1.getName());
            record3.setField(nvLinkField2.getName(), new Link(record2.getId()));
            record3.setField(nvTag.getName(), 0L);
            expectEvent(CREATE, record3.getId(), (Long)null, null, nvLinkField2.getId(), nvTag.getId());
            record3 = repository.create(record3);

            commitIndex();
            verifyResultCount("+@@vtag:" + qesc(nvTag.getId().toString()) + " +nv_v_nv_deref:Brussels", 0);

            // Give the records a live tag
            log.debug("Begin test V19");
            record1.setField(liveTag.getName(), 0L);
            expectEvent(UPDATE, record1.getId(), liveTag.getId());
            record1 = repository.update(record1);

            record2.setField(liveTag.getName(), 1L);
            expectEvent(UPDATE, record2.getId(), liveTag.getId());
            record2 = repository.update(record2);

            record3.setField(liveTag.getName(), 0L);
            expectEvent(UPDATE, record3.getId(), liveTag.getId());
            record3 = repository.update(record3);

            commitIndex();
            verifyResultCount("+@@vtag:" + qesc(liveTag.getId().toString()) + " +nv_v_nv_deref:Brussels", 1);
        }

        //
        // Test many-to-one dereferencing (= deref where there's actually more than one record pointing to another
        // record)
        // (Besides correctness, this test was also added to check/evaluate the processing time)
        //
        {
            log.debug("Begin test V19.1");

            final int COUNT = 5;

            Record record1 = repository.newRecord();
            record1.setRecordType(vRecordType1.getName());
            record1.setField(vfield1.getName(), "hyponiem");
            record1.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record1.getId(), 1L, null, vfield1.getId(), liveTag.getId());
            record1 = repository.create(record1);

            // Create multiple records
            for (int i = 0; i < COUNT; i++) {
                Record record2 = repository.newRecord();
                record2.setRecordType(vRecordType1.getName());
                record2.setField(vLinkField1.getName(), new Link(record1.getId()));
                record2.setField(liveTag.getName(), 1L);
                expectEvent(CREATE, record2.getId(), 1L, null, vLinkField1.getId(), liveTag.getId());
                record2 = repository.create(record2);
            }

            commitIndex();
            verifyResultCount("v_deref1:hyponiem", COUNT);

            record1.setField(vfield1.getName(), "hyperoniem");
            record1.setField(liveTag.getName(), 2L);
            expectEvent(UPDATE, record1.getId(), 2L, null, vfield1.getId(), liveTag.getId());
            record1 = repository.update(record1);
            commitIndex();
            verifyResultCount("v_deref1:hyperoniem", COUNT);
        }

        //
        // Multi-value field tests
        //
        {
            // Test multi-value field
            log.debug("Begin test V30");
            Record record1 = repository.newRecord();
            record1.setRecordType(vRecordType1.getName());
            record1.setField(vStringMvField.getName(), Arrays.asList("Dog", "Cat"));
            record1.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record1.getId(), 1L, null, vStringMvField.getId(), liveTag.getId());
            record1 = repository.create(record1);

            commitIndex();
            verifyResultCount("v_string_mv:Dog", 1);
            verifyResultCount("v_string_mv:Cat", 1);
            verifyResultCount("v_string_mv:(Dog Cat)", 1);
            verifyResultCount("v_string_mv:(\"Dog Cat\")", 0);

            // Test multiple single-valued fields indexed into one MV field
            // TODO

            // Test single-value field turned into multivalue by formatter
            // TODO

            // Test multi-valued deref to single-valued field
            // TODO
        }

        //
        // Long type tests
        //
        {
            log.debug("Begin test V40");
            Record record1 = repository.newRecord();
            record1.setRecordType(vRecordType1.getName());
            record1.setField(vLongField.getName(), 123L);
            record1.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record1.getId(), 1L, null, vLongField.getId(), liveTag.getId());
            record1 = repository.create(record1);

            commitIndex();
            verifyResultCount("v_long:123", 1);
            verifyResultCount("v_long:[100 TO 150]", 1);
        }

        //
        // Datetime type test
        //
        {
            log.debug("Begin test V50");
            Record record1 = repository.newRecord();
            record1.setRecordType(vRecordType1.getName());
            record1.setField(vDateTimeField.getName(), new DateTime(2010, 10, 14, 15, 30, 12, 756, DateTimeZone.UTC));
            record1.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record1.getId(), 1L, null, vDateTimeField.getId(), liveTag.getId());
            record1 = repository.create(record1);

            commitIndex();
            verifyResultCount("v_datetime:\"2010-10-14T15:30:12.756Z\"", 1);
            verifyResultCount("v_datetime:\"2010-10-14T15:30:12Z\"", 0);

            // Test without milliseconds
            log.debug("Begin test V51");
            Record record2 = repository.newRecord();
            record2.setRecordType(vRecordType1.getName());
            record2.setField(vDateTimeField.getName(), new DateTime(2010, 10, 14, 15, 30, 12, 000, DateTimeZone.UTC));
            record2.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record2.getId(), 1L, null, vDateTimeField.getId(), liveTag.getId());
            record2 = repository.create(record2);

            commitIndex();
            verifyResultCount("v_datetime:\"2010-10-14T15:30:12Z\"", 1);
            verifyResultCount("v_datetime:\"2010-10-14T15:30:12.000Z\"", 1);
            verifyResultCount("v_datetime:\"2010-10-14T15:30:12.000Z/SECOND\"", 1);
        }

        //
        // Date type test
        //
        {
            log.debug("Begin test V60");
            Record record1 = repository.newRecord();
            record1.setRecordType(vRecordType1.getName());
            record1.setField(vDateField.getName(), new LocalDate(2020, 1, 30));
            record1.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record1.getId(), 1L, null, vDateField.getId(), liveTag.getId());
            record1 = repository.create(record1);

            commitIndex();
            verifyResultCount("v_date:\"2020-01-30T00:00:00Z/DAY\"", 1);
            verifyResultCount("v_date:\"2020-01-30T00:00:00.000Z\"", 1);
            verifyResultCount("v_date:\"2020-01-30T00:00:00Z\"", 1);
            verifyResultCount("v_date:\"2020-01-30T00:00:01Z\"", 0);

            verifyResultCount("v_date:[2020-01-29T00:00:00Z/DAY TO 2020-01-31T00:00:00Z/DAY]", 1);

            log.debug("Begin test V61");
            Record record2 = repository.newRecord();
            record2.setRecordType(vRecordType1.getName());
            record2.setField(vDateField.getName(), new LocalDate(2020, 1, 30));
            record2.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record2.getId(), 1L, null, vDateField.getId(), liveTag.getId());
            record2 = repository.create(record2);

            commitIndex();
            verifyResultCount("v_date:\"2020-01-30T00:00:00Z/DAY\"", 2);
        }

        //
        // Blob tests
        //
        {
            log.debug("Begin test V70");
            Blob blob1 = createBlob("org/lilyproject/indexer/engine/test/blob1_msword.doc", "application/msword", "blob1_msword.doc");
            Blob blob1dup = createBlob("org/lilyproject/indexer/engine/test/blob1_msword.doc", "application/msword", "blob1_msword.doc");
            Blob blob2 = createBlob("org/lilyproject/indexer/engine/test/blob2.pdf", "application/pdf", "blob2.pdf");
            Blob blob3 = createBlob("org/lilyproject/indexer/engine/test/blob3_oowriter.odt", "application/vnd.oasis.opendocument.text", "blob3_oowriter.odt");
            Blob blob4 = createBlob("org/lilyproject/indexer/engine/test/blob4_excel.xls", "application/excel", "blob4_excel.xls");

            // Single-valued blob field
            Record record1 = repository.newRecord();
            record1.setRecordType(vRecordType1.getName());
            record1.setField(vBlobField.getName(), blob1);
            record1.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record1.getId(), 1L, null, vBlobField.getId(), liveTag.getId());
            record1 = repository.create(record1);

            commitIndex();
            verifyResultCount("v_blob:sollicitudin", 1);
            verifyResultCount("v_blob:\"Sed pretium pretium lorem\"", 1);
            verifyResultCount("v_blob:lily", 0);

            // Multi-value and hierarchical blob field
            log.debug("Begin test V71");
            HierarchyPath path1 = new HierarchyPath(blob1dup, blob2);
            HierarchyPath path2 = new HierarchyPath(blob3, blob4);
            List<HierarchyPath> blobs = Arrays.asList(path1, path2);

            Record record2 = repository.newRecord();
            record2.setRecordType(vRecordType1.getName());
            record2.setField(vBlobMvHierField.getName(), blobs);
            record2.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record2.getId(), 1L, null, vBlobMvHierField.getId(), liveTag.getId());
            record2 = repository.create(record2);

            commitIndex();
            verifyResultCount("v_blob:blob1", 2);
            verifyResultCount("v_blob:blob2", 1);
            verifyResultCount("v_blob:blob3", 1);
            verifyResultCount("+v_blob:blob4 +v_blob:\"Netherfield Park\"", 1);
        }

        //
        // Test field with explicitly configured formatter
        //
        {
            log.debug("Begin test V80");
            Record record1 = repository.newRecord();
            record1.setRecordType(vRecordType1.getName());
            record1.setField(vDateTimeField.getName(), new DateTime(2058, 10, 14, 15, 30, 12, 756, DateTimeZone.UTC));
            record1.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record1.getId(), 1L, null, vDateTimeField.getId(), liveTag.getId());
            record1 = repository.create(record1);

            commitIndex();
            verifyResultCount("year:2058", 1);
        }

        //
        // Test field with auto-selected formatter
        //
        {
            log.debug("Begin test V90");
            Record record1 = repository.newRecord();
            record1.setRecordType(vRecordType1.getName());
            record1.setField(vIntHierField.getName(), new HierarchyPath(8, 16, 24, 32));
            record1.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record1.getId(), 1L, null, vIntHierField.getId(), liveTag.getId());
            record1 = repository.create(record1);

            commitIndex();
            verifyResultCount("inthierarchy:\"8_16_24_32\"", 1);
        }

        //
        // Test inheritance of variant properties for link fields
        //
        {
            log.debug("Begin test V100");
            Map<String, String> varProps = new HashMap<String, String>();
            varProps.put("lang", "nl");
            varProps.put("user", "ali");

            RecordId record1Id = repository.getIdGenerator().newRecordId(varProps);
            Record record1 = repository.newRecord(record1Id);
            record1.setRecordType(vRecordType1.getName());
            record1.setField(vfield1.getName(), "venus");
            record1.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record1.getId(), 1L, null, vfield1.getId(), liveTag.getId());
            record1 = repository.create(record1);

            RecordId record2Id = repository.getIdGenerator().newRecordId(varProps);
            Record record2 = repository.newRecord(record2Id);
            record2.setRecordType(vRecordType1.getName());
            // Notice we make the link to the record without variant properties
            record2.setField(vLinkField1.getName(), new Link(record1.getId().getMaster()));
            record2.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record2.getId(), 1L, null, vLinkField1.getId(), liveTag.getId());
            record2 = repository.create(record2);

            commitIndex();
            verifyResultCount("v_deref1:venus", 1);

            log.debug("Begin test V101");
            record1.setField(vfield1.getName(), "mars");
            record1.setField(liveTag.getName(), 2L);
            expectEvent(UPDATE, record1.getId(), 2L, null, vfield1.getId(), liveTag.getId());
            record1 = repository.update(record1);

            commitIndex();
            verifyResultCount("v_deref1:mars", 1);
        }

        // Test that the index is updated when a version is created, in absence of changes to the vtag fields.
        // This would fail if the 'versionCreated' is not in the record event.
        {
            log.debug("Begin test V120");
            Record record1 = repository.newRecord();
            record1.setRecordType(vRecordType1.getName());
            record1.setField(liveTag.getName(), 1L);
            expectEvent(CREATE, record1.getId(), liveTag.getId());
            record1 = repository.create(record1);

            record1.setField(vfield1.getName(), "stool");
            expectEvent(UPDATE, record1.getId(), 1L, null, vfield1.getId());
            record1 = repository.update(record1);

            commitIndex();
            verifyResultCount("v_field1:stool", 1);
        }

        // Test that the index is updated when a version is updated, in absence of changes to the vtag fields.
        // This would fail if the 'versionCreated' is not in the record event.
        {
            log.debug("Begin test V130");
            Record record1 = repository.newRecord();
            record1.setRecordType(vRecordType1.getName());
            record1.setField(liveTag.getName(), 2L);
            record1.setField(vfield1.getName(), "wall");
            expectEvent(CREATE, record1.getId(), 1L, null, vfield1.getId(), liveTag.getId());
            record1 = repository.create(record1);

            record1.setField(vfield1.getName(), "floor");
            expectEvent(UPDATE, record1.getId(), 2L, null, vfield1.getId());
            record1 = repository.update(record1);

            commitIndex();
            verifyResultCount("v_field1:floor", 1);
        }

        //
        // Test the automatic vtag 'last', which is a virtual vtag which always points to the last version
        // of any record, without having to add it to the record or record type.
        //
        {
            log.debug("Begin test V140");
            Record record1 = repository.newRecord();
            record1.setRecordType(lastRecordType.getName());
            record1.setField(nvfield1.getName(), "north");
            expectEvent(CREATE, record1.getId(), nvfield1.getId());
            record1 = repository.create(record1);

            commitIndex();
            verifyResultCount("+@@vtag:" + qesc(lastTag.getId().toString()) + " +nv_field1:north", 1);

            record1.setField(vfield1.getName(), "south");
            expectEvent(UPDATE, record1.getId(), 1L, null, vfield1.getId());
            record1 = repository.update(record1);

            commitIndex();
            verifyResultCount("+@@vtag:" + qesc(lastTag.getId().toString()) + " +nv_field1:north", 1);
            verifyResultCount("+@@vtag:" + qesc(lastTag.getId().toString()) + " +v_field1:south", 1);
        }

        assertEquals("All received messages are correct.", 0, messageVerifier.getFailures());
    }

    private Blob createBlob(String resource, String mediaType, String fileName) throws Exception {
        byte[] mswordblob = readResource(resource);

        Blob blob = new Blob(mediaType, (long)mswordblob.length, fileName);
        OutputStream os = repository.getOutputStream(blob);
        try {
            os.write(mswordblob);
        } finally {
            os.close();
        }

        return blob;
    }

    private byte[] readResource(String path) throws IOException {
        InputStream mswordblob = getClass().getClassLoader().getResourceAsStream(path);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = mswordblob.read(buffer)) != -1){
            bos.write(buffer, 0, read);
        }

        return bos.toByteArray();
    }

    private static String qesc(String input) {
        return ClientUtils.escapeQueryChars(input);
    }

    private void commitIndex() throws Exception {
        repoSetup.processMQ();
        solrServers.commit(true, true);
    }

    private void verifyResultCount(String query, int count) throws SolrServerException {
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.set("q", query);
        solrQuery.set("rows", 5000);
        QueryResponse response = solrServers.query(solrQuery);
        if (count != response.getResults().size()) {
            System.out.println("The query result contains a wrong number of documents, here is the result:");
            for (int i = 0; i < response.getResults().size(); i++) {
                SolrDocument result = response.getResults().get(i);
                System.out.println(result.getFirstValue("@@key"));
            }
        }
        assertEquals(count, response.getResults().getNumFound());
    }

    private void expectEvent(RecordEvent.Type type, RecordId recordId, SchemaId... updatedFields) {
        expectEvent(type, recordId, null, null, updatedFields);
    }

    private void expectEvent(RecordEvent.Type type, RecordId recordId, Long versionCreated, Long versionUpdated,
            SchemaId... updatedFields) {

        RecordEvent event = new RecordEvent();

        event.setType(type);

        for (SchemaId updatedField : updatedFields) {
            event.addUpdatedField(updatedField);
        }

        if (versionCreated != null)
            event.setVersionCreated(versionCreated);

        if (versionUpdated != null)
            event.setVersionUpdated(versionUpdated);

        messageVerifier.setExpectedEvent(recordId, event);
    }

    private static class MessageVerifier implements RowLogMessageListener {
        private RecordId expectedId;
        private RecordEvent expectedEvent;
        private int failures = 0;

        public int getFailures() {
            return failures;
        }

        public void init() {
            this.expectedId = null;
            this.expectedEvent = null;
            this.failures = 0;
        }

        public void setExpectedEvent(RecordId recordId, RecordEvent recordEvent) {
            this.expectedId = recordId;
            this.expectedEvent = recordEvent;
        }

        public boolean processMessage(RowLogMessage message) {
            // In case of failures we print out "load" messages, the main junit thread is expected to
            // test that the failures variable is 0.
            
            RecordId recordId = repository.getIdGenerator().fromBytes(message.getRowKey());
            try {
                RecordEvent event = new RecordEvent(message.getPayload(), idGenerator);
                if (expectedEvent == null) {
                    failures++;
                    printSomethingLoad();
                    System.err.println("Did not expect a message, but got:");
                    System.err.println(recordId);
                    System.err.println(event.toJson());
                } else {
                    if (!event.equals(expectedEvent) ||
                            !(recordId.equals(expectedId) || (expectedId == null && expectedEvent.getType() == CREATE))) {
                        failures++;
                        printSomethingLoad();
                        System.err.println("Expected message:");
                        System.err.println(expectedId);
                        System.err.println(expectedEvent.toJson());
                        System.err.println("Received message:");
                        System.err.println(recordId);
                        System.err.println(event.toJson());
                    } else {
                        log.debug("Received message ok.");
                    }
                }
            } catch (RowLogException e) {
                failures++;
                e.printStackTrace();
            } catch (IOException e) {
                failures++;
                e.printStackTrace();
            } finally {
                expectedId = null;
                expectedEvent = null;
            }
            return true;
        }

        private void printSomethingLoad() {
            for (int i = 0; i < 10; i++) {
                System.err.println("!!");
            }
        }
    }
}
