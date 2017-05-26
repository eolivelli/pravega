/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.server.logs;

import io.pravega.common.util.ImmutableDate;
import io.pravega.segmentstore.contracts.AttributeUpdate;
import io.pravega.segmentstore.contracts.AttributeUpdateType;
import io.pravega.segmentstore.contracts.Attributes;
import io.pravega.segmentstore.contracts.StreamSegmentInformation;
import io.pravega.segmentstore.server.ContainerMetadata;
import io.pravega.segmentstore.server.UpdateableContainerMetadata;
import io.pravega.segmentstore.server.UpdateableSegmentMetadata;
import io.pravega.segmentstore.server.containers.StreamSegmentContainerMetadata;
import io.pravega.segmentstore.server.logs.operations.MergeTransactionOperation;
import io.pravega.segmentstore.server.logs.operations.Operation;
import io.pravega.segmentstore.server.logs.operations.StreamSegmentAppendOperation;
import io.pravega.segmentstore.server.logs.operations.StreamSegmentMapOperation;
import io.pravega.segmentstore.server.logs.operations.StreamSegmentSealOperation;
import io.pravega.segmentstore.server.logs.operations.TransactionMapOperation;
import io.pravega.segmentstore.storage.LogAddress;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.val;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for OperationMetadataUpdater class.
 */
public class OperationMetadataUpdaterTests {
    private static final int TRANSACTION_COUNT = 100;
    private static final int CONTAINER_ID = 1;
    private static final int MAX_ACTIVE_SEGMENT_COUNT = TRANSACTION_COUNT * 100;
    private static final Supplier<Long> NEXT_ATTRIBUTE_VALUE = System::nanoTime;
    private final Supplier<Integer> nextAppendLength = () -> Math.max(1, (int) System.nanoTime() % 1000);

    /**
     * Tests the basic functionality of the class, when no UpdateTransactions are explicitly created. Operations tested:
     * * StreamSegmentMapOperation
     * * TransactionMapOperation
     * * StreamSegmentAppendOperation
     * * StreamSegmentSealOperation
     * * MergeTransactionOperation
     */
    @Test
    public void testSingleTransaction() throws Exception {
        final int segmentCount = TRANSACTION_COUNT;
        final int transactionsPerSegment = 5;
        final int appendsPerSegment = 10;

        val referenceMetadata = createBlankMetadata();
        val metadata = createBlankMetadata();
        val updater = new OperationMetadataUpdater(metadata);
        val truncationMarkers = new HashMap<Long, LogAddress>();
        for (int i = 0; i < segmentCount; i++) {
            long segmentId = mapSegment(updater, referenceMetadata);
            recordAppends(segmentId, appendsPerSegment, updater, referenceMetadata);

            for (int j = 0; j < transactionsPerSegment; j++) {
                long transactionId = mapTransaction(segmentId, updater, referenceMetadata);
                recordAppends(transactionId, appendsPerSegment, updater, referenceMetadata);
                sealSegment(transactionId, updater, referenceMetadata);
                mergeTransaction(transactionId, updater, referenceMetadata);
            }

            sealSegment(segmentId, updater, referenceMetadata);
            val logAddress = new TestLogAddress(metadata.getOperationSequenceNumber());
            updater.recordTruncationMarker(logAddress.getSequence(), logAddress);
            Assert.assertNull("OperationSequenceNumber did not change.", truncationMarkers.put(logAddress.getSequence(), logAddress));
        }

        val blankMetadata = createBlankMetadata();
        ContainerMetadataUpdateTransactionTests.assertMetadataSame("Before commit", blankMetadata, metadata);
        updater.commit(OperationMetadataUpdater.MAX_TRANSACTION);
        ContainerMetadataUpdateTransactionTests.assertMetadataSame("After commit", referenceMetadata, metadata);
        for (val e : truncationMarkers.entrySet()) {
            val tm = metadata.getClosestTruncationMarker(e.getKey());
            Assert.assertEquals("After commit: Truncation marker not recorded properly.", e.getValue().getSequence(), tm.getSequence());
        }
    }

    /**
     * Tests the ability to successively commit update transactions to the base metadata.
     */
    @Test
    public void testCommit() throws Exception {
        // Commit 1 at a time, then 2, then 3, then 4, etc until we have nothing left to commit.
        // At each step verify that the base metadata has been properly updated.
        val referenceMetadata = createBlankMetadata();
        val metadata = createBlankMetadata();
        val updater = new OperationMetadataUpdater(metadata);
        long lastSegmentId = -1;
        long lastSegmentTxnId = -1;
        long lastCommittedTxnId = -1;
        int txnGroupSize = 1;

        val updateTransactions = new ArrayList<Map.Entry<Long, ContainerMetadata>>();
        while (updateTransactions.size() < TRANSACTION_COUNT) {
            // Create a segment
            long segmentId = mapSegment(updater, referenceMetadata);

            // Make an append (to each segment known so far.)
            for (long sId : referenceMetadata.getAllStreamSegmentIds()) {
                val rsm = referenceMetadata.getStreamSegmentMetadata(sId);
                if (!rsm.isMerged() && !rsm.isSealed()) {
                    recordAppend(sId, this.nextAppendLength.get(), updater, referenceMetadata);
                }
            }

            // Create a SegmentTransaction for the segment created in the previous UpdateTransaction
            long txnId = lastSegmentTxnId;
            if (lastSegmentId >= 0) {
                txnId = mapTransaction(lastSegmentId, updater, referenceMetadata);
            }

            if (lastSegmentTxnId >= 0) {
                // Seal&Merge the transaction created in the previous UpdateTransaction
                sealSegment(lastSegmentTxnId, updater, referenceMetadata);
                mergeTransaction(lastSegmentTxnId, updater, referenceMetadata);
            }

            lastSegmentId = segmentId;
            lastSegmentTxnId = txnId;
            long utId = updater.sealTransaction();
            if (updateTransactions.size() > 0) {
                long prevUtId = updateTransactions.get(updateTransactions.size() - 1).getKey();
                Assert.assertEquals("UpdateTransaction.Id is not sequential and increasing.", prevUtId + 1, utId);
            }

            updateTransactions.add(new AbstractMap.SimpleImmutableEntry<>(utId, clone(referenceMetadata)));
        }

        ContainerMetadata previousMetadata = null;
        for (val t : updateTransactions) {
            val utId = t.getKey();
            val expectedMetadata = t.getValue();

            // Check to see if it's time to commit.
            if (utId - lastCommittedTxnId >= txnGroupSize) {
                if (previousMetadata != null) {
                    // Verify no changes to the metadata prior to commit.
                    ContainerMetadataUpdateTransactionTests.assertMetadataSame("Before commit" + utId, previousMetadata, metadata);
                }

                // Commit and verify.
                updater.commit(utId);
                ContainerMetadataUpdateTransactionTests.assertMetadataSame("After commit" + utId, expectedMetadata, metadata);
                lastCommittedTxnId = utId;
                txnGroupSize++;
                previousMetadata = expectedMetadata;
            }
        }
    }

    /**
     * Tests the ability to rollback update transactions.
     */
    @Test
    public void testRollback() {
        // Create a number of transactions. In each: we create a segment, make an append, a seal and a merge.
        // 2 out of 3 transactions are failed (to verify multi-failure).
        // Commit the rest and verify final metadata is as it should.
    }

    private UpdateableContainerMetadata createBlankMetadata() {
        return new StreamSegmentContainerMetadata(CONTAINER_ID, MAX_ACTIVE_SEGMENT_COUNT);
    }

    private UpdateableContainerMetadata clone(ContainerMetadata base) {
        val metadata = createBlankMetadata();
        for (long segmentId : base.getAllStreamSegmentIds()) {
            val bsm = base.getStreamSegmentMetadata(segmentId);
            UpdateableSegmentMetadata nsm;
            if (bsm.isTransaction()) {
                nsm = metadata.mapStreamSegmentId(bsm.getName(), bsm.getId(), bsm.getParentId());
            } else {
                nsm = metadata.mapStreamSegmentId(bsm.getName(), bsm.getId());
            }

            nsm.setDurableLogLength(bsm.getDurableLogLength());
            nsm.setStorageLength(bsm.getStorageLength());
            nsm.updateAttributes(bsm.getAttributes());
            if (bsm.isSealed()) {
                nsm.markSealed();
            }
            if (bsm.isMerged()) {
                nsm.markMerged();
            }
            if (bsm.isSealedInStorage()) {
                nsm.markSealedInStorage();
            }
        }

        return metadata;
    }

    private void mergeTransaction(long transactionId, OperationMetadataUpdater updater, UpdateableContainerMetadata referenceMetadata)
            throws Exception {
        long parentSegmentId = updater.getStreamSegmentMetadata(transactionId).getParentId();
        val op = new MergeTransactionOperation(parentSegmentId, transactionId);
        process(op, updater);
        if (referenceMetadata != null) {
            referenceMetadata.getStreamSegmentMetadata(transactionId).markMerged();
            val rsm = referenceMetadata.getStreamSegmentMetadata(parentSegmentId);
            rsm.setDurableLogLength(rsm.getDurableLogLength() + op.getLength());
        }
    }

    private void sealSegment(long segmentId, OperationMetadataUpdater updater, UpdateableContainerMetadata referenceMetadata)
            throws Exception {
        val op = new StreamSegmentSealOperation(segmentId);
        process(op, updater);
        if (referenceMetadata != null) {
            referenceMetadata.getStreamSegmentMetadata(segmentId).markSealed();
        }
    }

    private void recordAppends(long segmentId, int count, OperationMetadataUpdater updater, UpdateableContainerMetadata referenceMetadata)
            throws Exception {
        for (int i = 0; i < count; i++) {
            recordAppend(segmentId, this.nextAppendLength.get(), updater, referenceMetadata);
        }
    }

    private void recordAppend(long segmentId, int length, OperationMetadataUpdater updater, UpdateableContainerMetadata referenceMetadata)
            throws Exception {
        byte[] data = new byte[length];
        val attributeUpdates = Arrays.asList(
                new AttributeUpdate(Attributes.CREATION_TIME, AttributeUpdateType.Replace, NEXT_ATTRIBUTE_VALUE.get()),
                new AttributeUpdate(Attributes.EVENT_COUNT, AttributeUpdateType.Accumulate, NEXT_ATTRIBUTE_VALUE.get()));
        val op = new StreamSegmentAppendOperation(segmentId, data, attributeUpdates);
        process(op, updater);
        if (referenceMetadata != null) {
            val rsm = referenceMetadata.getStreamSegmentMetadata(segmentId);
            rsm.setDurableLogLength(rsm.getDurableLogLength() + length);
            val attributes = new HashMap<UUID, Long>();
            op.getAttributeUpdates().forEach(au -> attributes.put(au.getAttributeId(), au.getValue()));
            rsm.updateAttributes(attributes);
        }
    }

    private long mapSegment(OperationMetadataUpdater updater, UpdateableContainerMetadata referenceMetadata) throws Exception {
        String segmentName = "Segment_" + updater.nextOperationSequenceNumber();

        val mapOp = new StreamSegmentMapOperation(
                new StreamSegmentInformation(segmentName, 0, false, false, null, new ImmutableDate()));
        process(mapOp, updater);
        if (referenceMetadata != null) {
            val rsm = referenceMetadata.mapStreamSegmentId(segmentName, mapOp.getStreamSegmentId());
            rsm.setDurableLogLength(0);
            rsm.setStorageLength(0);
        }

        return mapOp.getStreamSegmentId();
    }

    private long mapTransaction(long parentSegmentId, OperationMetadataUpdater updater, UpdateableContainerMetadata referenceMetadata) throws Exception {
        String segmentName = "Transaction_" + updater.nextOperationSequenceNumber();

        val mapOp = new TransactionMapOperation(parentSegmentId,
                new StreamSegmentInformation(segmentName, 0, false, false, null, new ImmutableDate()));
        process(mapOp, updater);
        if (referenceMetadata != null) {
            val rsm = referenceMetadata.mapStreamSegmentId(segmentName, mapOp.getStreamSegmentId(), parentSegmentId);
            rsm.setDurableLogLength(0);
            rsm.setStorageLength(0);
        }

        return mapOp.getStreamSegmentId();
    }

    private void process(Operation op, OperationMetadataUpdater updater) throws Exception {
        updater.preProcessOperation(op);
        op.setSequenceNumber(updater.nextOperationSequenceNumber());
        updater.acceptOperation(op);
    }

    private static class TestLogAddress extends LogAddress {
        public TestLogAddress(long sequence) {
            super(sequence);
        }
    }
}