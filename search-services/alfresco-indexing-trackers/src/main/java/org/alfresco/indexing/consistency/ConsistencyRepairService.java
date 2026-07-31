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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.tracker.AclTracker;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ConsistencyRepairService
{
    private final TrackerBootstrap trackerBootstrap;
    private final ConsistencyStorageService storage;
    private final ConsistencyProperties properties;
    private final ConsistencyMetrics metrics;

    public ConsistencyRepairService(TrackerBootstrap trackerBootstrap, ConsistencyStorageService storage,
            ConsistencyProperties properties, ConsistencyMetrics metrics)
    {
        this.trackerBootstrap = trackerBootstrap;
        this.storage = storage;
        this.properties = properties;
        this.metrics = metrics;
    }

    public ConsistencyRepairResult repair(String scanId, ConsistencyRepairRequest request, String requestedBy)
    {
        ConsistencyScan scan = storage.getScan(scanId);
        if (scan.getStatus() != ConsistencyScanStatus.COMPLETED)
        {
            throw new ConsistencyApiException(HttpStatus.CONFLICT, "SCAN_NOT_COMPLETED",
                    "Repairs can only be scheduled from a completed scan");
        }
        if (request == null) request = new ConsistencyRepairRequest();

        ConsistencyRepairResult result = new ConsistencyRepairResult();
        result.setRepairId("cr-" + UUID.randomUUID());
        result.setScanId(scanId);
        result.setRequestedBy(requestedBy);
        result.setDryRun(request.isDryRun());

        Set<String> scheduledKeys = new HashSet<>();
        int page = 0;
        int size = Math.min(1000, properties.getResultPageSizeMax());
        while (true)
        {
            ConsistencyPage<ConsistencyFinding> findings = storage.readFindings(scanId, null, null, null, page, size);
            for (ConsistencyFinding finding : findings.getContent())
            {
                if (!request.getTypes().isEmpty() && !request.getTypes().contains(finding.getType())) continue;
                schedule(finding, request, result, scheduledKeys);
            }
            page++;
            if (page >= findings.getTotalPages()) break;
        }
        storage.saveRepair(result);
        metrics.repairs(result);
        return result;
    }

    private void schedule(ConsistencyFinding finding, ConsistencyRepairRequest request,
            ConsistencyRepairResult result, Set<String> scheduledKeys)
    {
        RecommendedAction action = finding.getRecommendedAction();
        if (action == RecommendedAction.NONE || action == RecommendedAction.MANUAL_REVIEW)
        {
            result.skipped(action);
            return;
        }
        if (action == RecommendedAction.PURGE && !request.isPurgeOrphans())
        {
            result.skipped(action);
            return;
        }
        String key = finding.getCore() + ':' + repairTarget(finding);
        if (!scheduledKeys.add(key)) return;
        if (request.isDryRun())
        {
            result.scheduled(action);
            return;
        }

        TrackerRegistry registry = trackerBootstrap.getRegistry();
        MetadataTracker metadata = registry.getTrackerForCore(finding.getCore(), MetadataTracker.class);
        AclTracker acl = registry.getTrackerForCore(finding.getCore(), AclTracker.class);
        try
        {
            switch (finding.getType())
            {
                case MISSING_NODE, DUPLICATED_NODE -> metadata.addNodeToReindex(requireId(finding.getNodeId(), finding));
                case MISSING_TRANSACTION, DUPLICATED_TRANSACTION -> metadata.addTransactionToReindex(requireId(finding.getTransactionId(), finding));
                case MISSING_ACL, DUPLICATED_ACL -> acl.addAclToReindex(requireId(finding.getAclId(), finding));
                case MISSING_ACL_TRANSACTION, DUPLICATED_ACL_TRANSACTION -> acl.addAclChangeSetToReindex(requireId(finding.getAclTxId(), finding));
                case ERROR_NODE -> metadata.addNodeToReindex(requireId(finding.getNodeId(), finding));
                case ORPHAN_NODE -> metadata.addNodeToPurge(requireId(finding.getNodeId(), finding));
                case ORPHAN_TRANSACTION -> metadata.addTransactionToPurge(requireId(finding.getTransactionId(), finding));
                case ORPHAN_ACL -> acl.addAclToPurge(requireId(finding.getAclId(), finding));
                case ORPHAN_ACL_TRANSACTION -> acl.addAclChangeSetToPurge(requireId(finding.getAclTxId(), finding));
                case UNINDEXABLE_NODE -> { result.skipped(RecommendedAction.MANUAL_REVIEW); return; }
            }
            result.scheduled(action);
        }
        catch (Exception e)
        {
            result.failed(action);
        }
    }

    private String repairTarget(ConsistencyFinding finding)
    {
        if (finding.getNodeId() != null) return "node:" + finding.getNodeId();
        if (finding.getTransactionId() != null) return "tx:" + finding.getTransactionId();
        if (finding.getAclId() != null) return "acl:" + finding.getAclId();
        if (finding.getAclTxId() != null) return "acltx:" + finding.getAclTxId();
        return "item:" + finding.getItemId();
    }

    private long requireId(Long value, ConsistencyFinding finding)
    {
        if (value == null)
        {
            throw new IllegalArgumentException("Finding has no repair identifier: " + finding.getType());
        }
        return value;
    }
}
