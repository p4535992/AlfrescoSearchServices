/*-
 * #%L
 * Alfresco Indexing Trackers
 * %%
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
 * %%
 * This file is part of the Pristy software, developed by Jeci SARL.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.indexing.consistency;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.server.InformationServer;
import org.alfresco.indexing.tracker.AclTracker;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.alfresco.solr.AclReport;
import org.alfresco.solr.NodeReport;
import org.alfresco.solr.adapters.IOpenBitSet;
import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.AclChangeSets;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.Node.SolrApiNodeStatus;
import org.alfresco.solr.client.Transaction;
import org.alfresco.solr.client.TenantDbId;
import org.alfresco.solr.client.Transactions;
import org.alfresco.solr.tracker.IndexHealthReport;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConsistencyScanner
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ConsistencyScanner.class);
    private static final String FIELD_DBID = "DBID";
    private static final String FIELD_DOC_TYPE = "DOC_TYPE";
    private static final String FIELD_TXID = "TXID";
    private static final String DOC_TYPE_NODE = "Node";

    private final TrackerBootstrap trackerBootstrap;
    private final SolrClient solrClient;
    private final ConsistencyStorageService storage;
    private final ConsistencyProperties properties;
    private final ConsistencyMetrics metrics;

    public ConsistencyScanner(TrackerBootstrap trackerBootstrap, SolrClient solrClient,
            ConsistencyStorageService storage, ConsistencyProperties properties, ConsistencyMetrics metrics)
    {
        this.trackerBootstrap = trackerBootstrap;
        this.solrClient = solrClient;
        this.storage = storage;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void scan(ConsistencyScan scan, BooleanSupplier cancelled)
    {
        ConsistencyScanRequest request = scan.getParameters();
        scan.setTotalItems((long) effectiveLimit(request));
        TrackerRegistry registry = trackerBootstrap.getRegistry();
        if (registry == null)
        {
            throw new IllegalStateException("Tracker subsystem is not initialised");
        }

        for (String core : scan.getCores())
        {
            checkCancelled(cancelled);
            MetadataTracker metadataTracker = registry.getTrackerForCore(core, MetadataTracker.class);
            AclTracker aclTracker = registry.getTrackerForCore(core, AclTracker.class);
            InformationServer informationServer = trackerBootstrap.getInformationServer(core);
            if (metadataTracker == null || informationServer == null)
            {
                throw new IllegalArgumentException("Core is not tracked: " + core);
            }

            switch (request.getStrategy())
            {
                case NODE_ID -> scanNodeRange(scan, core, metadataTracker, aclTracker, request, cancelled);
                case TRANSACTION_ID -> scanTransactions(scan, core, metadataTracker, aclTracker,
                        informationServer, request.getFromTxId(), request.getToTxId(), null, null, cancelled);
                case TRANSACTION_COMMIT_TIME -> scanTransactions(scan, core, metadataTracker, aclTracker,
                        informationServer, null, null, request.getFromCommitTime(), request.getToCommitTime(), cancelled);
                case FULL ->
                {
                    scanTransactions(scan, core, metadataTracker, aclTracker, informationServer,
                            0L, null, null, null, cancelled);
                    if (aclTracker != null)
                    {
                        scanAclTransactions(scan, core, aclTracker, informationServer, null, null, cancelled);
                    }
                    scanIndexForOrphanNodes(scan, core, metadataTracker, "DOC_TYPE:Node", cancelled);
                    if (aclTracker != null)
                    {
                        scanIndexForOrphanAcls(scan, core, aclTracker, cancelled);
                    }
                }
                case AFTS_QUERY -> scanIndexedAftsScope(scan, core, metadataTracker, aclTracker,
                        requireText(request.getQuery(), "query is required for AFTS_QUERY"), cancelled);
                case ANCESTOR ->
                {
                    String ancestorRef = resolveAncestorRef(metadataTracker, request);
                    scanIndexedAftsScope(scan, core, metadataTracker, aclTracker,
                            "ANCESTOR:\"" + ancestorRef + "\"", cancelled);
                }
            }

            if (request.isIncludeErrorNodes())
            {
                appendErrorNodes(scan, core, informationServer, cancelled);
            }
        }
    }

    private void scanNodeRange(ConsistencyScan scan, String core, MetadataTracker metadataTracker,
            AclTracker aclTracker, ConsistencyScanRequest request, BooleanSupplier cancelled)
    {
        long from = request.getFromNodeId() == null ? 0 : request.getFromNodeId();
        Long to = request.getToNodeId();
        int limit = effectiveLimit(request);
        long current = from;
        int processed = 0;
        Set<Long> checkedAcls = new HashSet<>();

        while (processed < limit && (to == null || current <= to))
        {
            checkCancelled(cancelled);
            int batch = Math.min(properties.getBatchSize(), limit - processed);
            List<Node> nodes = metadataTracker.getRepositoryNodes(current, to, batch);
            if (nodes.isEmpty()) break;
            long last = current;
            for (Node node : nodes)
            {
                checkCancelled(cancelled);
                processRepositoryNode(scan, core, metadataTracker, aclTracker, node, checkedAcls);
                last = Math.max(last, node.getId());
                processed++;
                if (processed >= limit) break;
            }
            if (last < current) break;
            current = last + 1;
            storage.saveScan(scan);
        }

        String query = FIELD_DOC_TYPE + ":" + DOC_TYPE_NODE + " AND " + FIELD_DBID + ":[" + from + " TO "
                + (to == null ? "*" : to) + "]";
        scanIndexForOrphanNodes(scan, core, metadataTracker, query, cancelled);
    }

    private void scanTransactions(ConsistencyScan scan, String core, MetadataTracker metadataTracker,
            AclTracker aclTracker, InformationServer informationServer, Long fromTx, Long toTx,
            Long fromTime, Long toTime, BooleanSupplier cancelled)
    {
        long minRequestTx = fromTx == null ? 0L : fromTx;
        Long cursorTx = minRequestTx;
        int limit = effectiveLimit(scan.getParameters());
        int processedNodes = 0;
        Long minSeenTx = null;
        long maxSeenTx = 0;
        IOpenBitSet txIds = informationServer.getOpenBitSetInstance();
        Set<Long> checkedAcls = new HashSet<>();

        while (processedNodes < limit)
        {
            checkCancelled(cancelled);
            Transactions transactions = metadataTracker.getRepositoryTransactions(fromTime, cursorTx,
                    toTime, toTx, properties.getBatchSize());
            List<Transaction> batchTransactions = transactions.getTransactions();
            if (batchTransactions.isEmpty()) break;

            List<Long> ids = new ArrayList<>(batchTransactions.size());
            long lastTx = cursorTx == null ? 0L : cursorTx;
            for (Transaction transaction : batchTransactions)
            {
                long txId = transaction.getId();
                if (toTx != null && txId > toTx) break;
                txIds.set(txId);
                ids.add(txId);
                minSeenTx = minSeenTx == null ? txId : Math.min(minSeenTx, txId);
                maxSeenTx = Math.max(maxSeenTx, txId);
                lastTx = Math.max(lastTx, txId);
            }
            if (ids.isEmpty()) break;

            List<Node> nodes = metadataTracker.getRepositoryNodesForTransactions(ids, Integer.MAX_VALUE);
            for (Node node : nodes)
            {
                checkCancelled(cancelled);
                if (processedNodes >= limit) break;
                processRepositoryNode(scan, core, metadataTracker, aclTracker, node, checkedAcls);
                processedNodes++;
            }

            if (lastTx < cursorTx) break;
            cursorTx = lastTx + 1;
            storage.saveScan(scan);
            if (batchTransactions.size() < properties.getBatchSize()) break;
        }

        if (minSeenTx != null)
        {
            try
            {
                IndexHealthReport report = informationServer.reportIndexTransactions(minSeenTx, txIds, maxSeenTx);
                appendTransactionFindings(scan, core, report);
            }
            catch (IOException e)
            {
                throw new IllegalStateException("Unable to compare transaction documents for core " + core, e);
            }
        }
    }

    private void scanAclTransactions(ConsistencyScan scan, String core, AclTracker aclTracker,
            InformationServer informationServer, Long fromTime, Long toTime, BooleanSupplier cancelled)
    {
        long cursor = 0L;
        Long minSeen = null;
        long maxSeen = 0L;
        IOpenBitSet ids = informationServer.getOpenBitSetInstance();
        Set<Long> checkedAcls = new HashSet<>();

        while (true)
        {
            checkCancelled(cancelled);
            AclChangeSets changeSets = aclTracker.getRepositoryAclChangeSets(fromTime, cursor, toTime,
                    null, properties.getBatchSize());
            if (changeSets.getAclChangeSets().isEmpty()) break;
            long last = cursor;
            for (AclChangeSet changeSet : changeSets.getAclChangeSets())
            {
                long id = changeSet.getId();
                ids.set(id);
                minSeen = minSeen == null ? id : Math.min(minSeen, id);
                maxSeen = Math.max(maxSeen, id);
                last = Math.max(last, id);
                for (Long aclId : aclTracker.getAclsForDbAclTransaction(id))
                {
                    checkAcl(scan, core, aclTracker, aclId, checkedAcls);
                }
            }
            cursor = last + 1;
            storage.saveScan(scan);
            if (changeSets.getAclChangeSets().size() < properties.getBatchSize()) break;
        }

        if (minSeen != null)
        {
            IndexHealthReport report = informationServer.reportAclTransactionsInIndex(minSeen, ids, maxSeen);
            appendAclTransactionFindings(scan, core, report);
        }
    }

    private void processRepositoryNode(ConsistencyScan scan, String core, MetadataTracker metadataTracker,
            AclTracker aclTracker, Node node, Set<Long> checkedAcls)
    {
        NodeReport report = metadataTracker.checkNode(node);
        long count = report.getIndexedNodeDocCount() == null ? 0 : report.getIndexedNodeDocCount();
        SolrApiNodeStatus status = node.getStatus();

        if (status == SolrApiNodeStatus.UPDATED)
        {
            if (count == 0)
            {
                addFinding(scan, finding(scan, core, ConsistencyFindingType.MISSING_NODE, node.getId(),
                        node.getTxnId(), node.getAclId(), node.getNodeRef(), RecommendedAction.REINDEX,
                        "Repository node is missing from the index"));
            }
            else if (count > 1)
            {
                addFinding(scan, finding(scan, core, ConsistencyFindingType.DUPLICATED_NODE, node.getId(),
                        node.getTxnId(), node.getAclId(), node.getNodeRef(), RecommendedAction.REINDEX,
                        "Repository node has " + count + " index documents"));
            }
        }
        else if (status == SolrApiNodeStatus.DELETED && count > 0)
        {
            addFinding(scan, finding(scan, core, ConsistencyFindingType.ORPHAN_NODE, node.getId(),
                    node.getTxnId(), node.getAclId(), node.getNodeRef(), RecommendedAction.PURGE,
                    "Deleted repository node is still present in the index"));
        }
        else if (status == SolrApiNodeStatus.UNKNOWN)
        {
            addFinding(scan, finding(scan, core, ConsistencyFindingType.UNINDEXABLE_NODE, node.getId(),
                    node.getTxnId(), node.getAclId(), node.getNodeRef(), RecommendedAction.MANUAL_REVIEW,
                    "Repository returned an unknown node status"));
        }

        incrementProcessed(scan);
        if (aclTracker != null && node.getAclId() > 0)
        {
            checkAcl(scan, core, aclTracker, node.getAclId(), checkedAcls);
        }
    }

    private void checkAcl(ConsistencyScan scan, String core, AclTracker aclTracker, long aclId, Set<Long> checked)
    {
        if (!checked.add(aclId)) return;
        AclReport report = aclTracker.checkAcl(aclId);
        long count = report.getIndexedAclDocCount() == null ? 0 : report.getIndexedAclDocCount();
        if (report.isExistsInDb() && count == 0)
        {
            ConsistencyFinding finding = baseFinding(scan, core, ConsistencyFindingType.MISSING_ACL,
                    aclId, RecommendedAction.REINDEX, "Repository ACL is missing from the index");
            finding.setAclId(aclId);
            addFinding(scan, finding);
        }
        else if (report.isExistsInDb() && count > 1)
        {
            ConsistencyFinding finding = baseFinding(scan, core, ConsistencyFindingType.DUPLICATED_ACL,
                    aclId, RecommendedAction.REINDEX, "Repository ACL has " + count + " index documents");
            finding.setAclId(aclId);
            addFinding(scan, finding);
        }
        else if (!report.isExistsInDb() && count > 0)
        {
            ConsistencyFinding finding = baseFinding(scan, core, ConsistencyFindingType.ORPHAN_ACL,
                    aclId, RecommendedAction.PURGE, "Index ACL is absent from the repository");
            finding.setAclId(aclId);
            addFinding(scan, finding);
        }
    }

    private void appendTransactionFindings(ConsistencyScan scan, String core, IndexHealthReport report)
    {
        appendBitSet(scan, core, report.getMissingTxFromIndex(), ConsistencyFindingType.MISSING_TRANSACTION,
                RecommendedAction.REINDEX, "Repository transaction is missing from the index", false);
        appendBitSet(scan, core, report.getDuplicatedTxInIndex(), ConsistencyFindingType.DUPLICATED_TRANSACTION,
                RecommendedAction.REINDEX, "Transaction has duplicate index documents", false);
        appendBitSet(scan, core, report.getTxInIndexButNotInDb(), ConsistencyFindingType.ORPHAN_TRANSACTION,
                RecommendedAction.PURGE, "Index transaction is absent from the repository", false);
    }

    private void appendAclTransactionFindings(ConsistencyScan scan, String core, IndexHealthReport report)
    {
        appendBitSet(scan, core, report.getMissingAclTxFromIndex(), ConsistencyFindingType.MISSING_ACL_TRANSACTION,
                RecommendedAction.REINDEX, "Repository ACL transaction is missing from the index", true);
        appendBitSet(scan, core, report.getDuplicatedAclTxInIndex(), ConsistencyFindingType.DUPLICATED_ACL_TRANSACTION,
                RecommendedAction.REINDEX, "ACL transaction has duplicate index documents", true);
        appendBitSet(scan, core, report.getAclTxInIndexButNotInDb(), ConsistencyFindingType.ORPHAN_ACL_TRANSACTION,
                RecommendedAction.PURGE, "Index ACL transaction is absent from the repository", true);
    }

    private void appendBitSet(ConsistencyScan scan, String core, IOpenBitSet ids, ConsistencyFindingType type,
            RecommendedAction action, String message, boolean aclTx)
    {
        if (ids == null) return;
        for (long id = ids.nextSetBit(0); id >= 0; id = ids.nextSetBit(id + 1))
        {
            ConsistencyFinding finding = baseFinding(scan, core, type, id, action, message);
            if (aclTx) finding.setAclTxId(id); else finding.setTransactionId(id);
            addFinding(scan, finding);
            if (id == Long.MAX_VALUE) break;
        }
    }

    private void appendErrorNodes(ConsistencyScan scan, String core, InformationServer informationServer,
            BooleanSupplier cancelled)
    {
        try
        {
            Set<Long> errorNodeIds = new HashSet<>(informationServer.getErrorDocIds());
            for (TenantDbId errorDoc : informationServer.getDocsWithIndexingError())
            {
                if (errorDoc != null && errorDoc.dbId != null)
                {
                    errorNodeIds.add(errorDoc.dbId);
                }
            }
            for (Long nodeId : errorNodeIds)
            {
                checkCancelled(cancelled);
                ConsistencyFinding finding = baseFinding(scan, core, ConsistencyFindingType.ERROR_NODE,
                        nodeId, RecommendedAction.RETRY, "Node is recorded as an indexing error");
                finding.setNodeId(nodeId);
                addFinding(scan, finding);
            }
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Unable to read error nodes for core " + core, e);
        }
    }

    private void scanIndexedAftsScope(ConsistencyScan scan, String core, MetadataTracker metadataTracker,
            AclTracker aclTracker, String aftsQuery, BooleanSupplier cancelled)
    {
        LOGGER.info("Consistency scan {} uses index-visible AFTS scope on core {}: {}", scan.getScanId(), core, aftsQuery);
        scanSolrNodes(scan, core, metadataTracker, aclTracker, aftsQuery, "/afts", true, cancelled);
    }

    private void scanIndexForOrphanNodes(ConsistencyScan scan, String core, MetadataTracker metadataTracker,
            String query, BooleanSupplier cancelled)
    {
        scanSolrNodes(scan, core, metadataTracker, null, query, "/select", false, cancelled);
    }

    private void scanSolrNodes(ConsistencyScan scan, String core, MetadataTracker metadataTracker,
            AclTracker aclTracker, String queryString, String handler, boolean classifyVisibleNodes,
            BooleanSupplier cancelled)
    {
        int start = 0;
        int limit = effectiveLimit(scan.getParameters());
        Set<Long> checkedAcls = new HashSet<>();
        Set<Long> checkedNodes = new HashSet<>();
        while (start < limit)
        {
            checkCancelled(cancelled);
            SolrQuery query = new SolrQuery(queryString);
            query.setRequestHandler(handler);
            query.setStart(start);
            query.setRows(Math.min(properties.getBatchSize(), limit - start));
            query.setFields("id", FIELD_DBID, FIELD_TXID, "ACLID", FIELD_DOC_TYPE);
            try
            {
                QueryResponse response = solrClient.query(core, query);
                SolrDocumentList documents = response.getResults();
                if (documents == null || documents.isEmpty()) break;
                for (SolrDocument document : documents)
                {
                    Object dbidValue = document.getFieldValue(FIELD_DBID);
                    if (dbidValue == null) continue;
                    long dbid = asLong(dbidValue);
                    if (!checkedNodes.add(dbid)) continue;
                    NodeReport report = metadataTracker.checkNode(dbid);
                    long count = report.getIndexedNodeDocCount() == null ? 0 : report.getIndexedNodeDocCount();
                    boolean processedByRepositoryCheck = false;
                    if (report.getDbTx() != null && report.getDbTx() == -1L && count > 0)
                    {
                        ConsistencyFinding finding = baseFinding(scan, core, ConsistencyFindingType.ORPHAN_NODE,
                                dbid, RecommendedAction.PURGE, "Index node is absent from the repository");
                        finding.setNodeId(dbid);
                        addFinding(scan, finding);
                    }
                    else if (classifyVisibleNodes && report.getDbTx() != null && report.getDbTx() >= 0)
                    {
                        Node node = new Node();
                        node.setId(dbid);
                        node.setTxnId(report.getDbTx());
                        node.setStatus(report.getDbNodeStatus());
                        Object aclValue = document.getFieldValue("ACLID");
                        if (aclValue != null) node.setAclId(asLong(aclValue));
                        processRepositoryNode(scan, core, metadataTracker, aclTracker, node, checkedAcls);
                        processedByRepositoryCheck = true;
                    }
                    if (!processedByRepositoryCheck)
                    {
                        incrementProcessed(scan);
                    }
                }
                start += documents.size();
                storage.saveScan(scan);
                if (documents.size() < query.getRows()) break;
            }
            catch (SolrServerException | IOException e)
            {
                throw new IllegalStateException("Unable to query Solr consistency scope for core " + core, e);
            }
        }
    }

    private void scanIndexForOrphanAcls(ConsistencyScan scan, String core, AclTracker aclTracker,
            BooleanSupplier cancelled)
    {
        int start = 0;
        int limit = effectiveLimit(scan.getParameters());
        Set<Long> checkedAcls = new HashSet<>();
        while (start < limit)
        {
            checkCancelled(cancelled);
            SolrQuery query = new SolrQuery(FIELD_DOC_TYPE + ":Acl");
            query.setStart(start);
            query.setRows(Math.min(properties.getBatchSize(), limit - start));
            query.setFields("ACLID", FIELD_DOC_TYPE);
            try
            {
                QueryResponse response = solrClient.query(core, query);
                SolrDocumentList documents = response.getResults();
                if (documents == null || documents.isEmpty()) break;
                for (SolrDocument document : documents)
                {
                    Object aclValue = document.getFieldValue("ACLID");
                    if (aclValue == null) continue;
                    long aclId = asLong(aclValue);
                    if (!checkedAcls.add(aclId)) continue;
                    AclReport report = aclTracker.checkAcl(aclId);
                    long count = report.getIndexedAclDocCount() == null ? 0 : report.getIndexedAclDocCount();
                    if (!report.isExistsInDb() && count > 0)
                    {
                        ConsistencyFinding finding = baseFinding(scan, core, ConsistencyFindingType.ORPHAN_ACL,
                                aclId, RecommendedAction.PURGE, "Index ACL is absent from the repository");
                        finding.setAclId(aclId);
                        addFinding(scan, finding);
                    }
                    else if (report.isExistsInDb() && count > 1)
                    {
                        ConsistencyFinding finding = baseFinding(scan, core, ConsistencyFindingType.DUPLICATED_ACL,
                                aclId, RecommendedAction.REINDEX, "Repository ACL has " + count + " index documents");
                        finding.setAclId(aclId);
                        addFinding(scan, finding);
                    }
                    incrementProcessed(scan);
                }
                start += documents.size();
                storage.saveScan(scan);
                if (documents.size() < query.getRows()) break;
            }
            catch (SolrServerException | IOException e)
            {
                throw new IllegalStateException("Unable to scan ACL documents for core " + core, e);
            }
        }
    }

    private String resolveAncestorRef(MetadataTracker metadataTracker, ConsistencyScanRequest request)
    {
        if (request.getAncestorNodeRef() != null && !request.getAncestorNodeRef().isBlank())
        {
            return request.getAncestorNodeRef();
        }
        if (request.getAncestorNodeId() == null)
        {
            throw new IllegalArgumentException("ancestorNodeId or ancestorNodeRef is required for ANCESTOR");
        }
        List<Node> nodes = metadataTracker.getRepositoryNodes(request.getAncestorNodeId(), request.getAncestorNodeId(), 1);
        if (nodes.isEmpty() || nodes.get(0).getNodeRef() == null)
        {
            throw new IllegalArgumentException("Ancestor node was not found in the repository");
        }
        return nodes.get(0).getNodeRef();
    }

    private void incrementProcessed(ConsistencyScan scan)
    {
        scan.incrementProcessed();
        Long total = scan.getTotalItems();
        if (total != null && total > 0)
        {
            int progress = (int) Math.min(99, (scan.getProcessedItems() * 100L) / total);
            scan.setProgressPercent(progress);
        }
    }

    private void addFinding(ConsistencyScan scan, ConsistencyFinding finding)
    {
        long current = scan.getResultCounts().values().stream().mapToLong(Long::longValue).sum();
        if (current >= properties.getMaxResults()) return;
        storage.appendFinding(finding);
        scan.incrementFinding(finding.getType());
        metrics.finding(finding);
    }

    private ConsistencyFinding finding(ConsistencyScan scan, String core, ConsistencyFindingType type,
            long nodeId, long txId, long aclId, String nodeRef, RecommendedAction action, String message)
    {
        ConsistencyFinding finding = baseFinding(scan, core, type, nodeId, action, message);
        finding.setNodeId(nodeId);
        finding.setTransactionId(txId);
        finding.setAclId(aclId);
        finding.setNodeRef(nodeRef);
        return finding;
    }

    private ConsistencyFinding baseFinding(ConsistencyScan scan, String core, ConsistencyFindingType type,
            long itemId, RecommendedAction action, String message)
    {
        ConsistencyFinding finding = new ConsistencyFinding();
        finding.setScanId(scan.getScanId());
        finding.setCore(core);
        finding.setType(type);
        finding.setItemId(itemId);
        finding.setRecommendedAction(action);
        finding.setStatus("DETECTED");
        finding.setMessage(message);
        return finding;
    }

    private int effectiveLimit(ConsistencyScanRequest request)
    {
        int requested = request.getMaxResults() == null ? properties.getMaxResults() : request.getMaxResults();
        return Math.max(1, Math.min(requested, properties.getMaxResults()));
    }

    private String requireText(String value, String message)
    {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }

    private long asLong(Object value)
    {
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private void checkCancelled(BooleanSupplier cancelled)
    {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
        {
            throw new ScanCancelledException();
        }
    }

    public static class ScanCancelledException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
    }
}
