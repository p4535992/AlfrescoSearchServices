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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.alfresco.indexing.config.TrackerBootstrap;
import org.junit.jupiter.api.Test;

class ConsistencyRepairServiceTest
{
    @Test
    void dryRunCountsRepairWithoutSchedulingTrackerWork()
    {
        TrackerBootstrap bootstrap = mock(TrackerBootstrap.class);
        ConsistencyStorageService storage = mock(ConsistencyStorageService.class);
        ConsistencyProperties properties = new ConsistencyProperties();
        ConsistencyScan scan = new ConsistencyScan();
        scan.setScanId("scan-1");
        scan.setStatus(ConsistencyScanStatus.COMPLETED);
        ConsistencyFinding finding = new ConsistencyFinding();
        finding.setScanId("scan-1");
        finding.setCore("alfresco");
        finding.setType(ConsistencyFindingType.MISSING_NODE);
        finding.setNodeId(42L);
        finding.setItemId(42L);
        finding.setRecommendedAction(RecommendedAction.REINDEX);
        when(storage.getScan("scan-1")).thenReturn(scan);
        when(storage.readFindings("scan-1", null, null, null, 0, 1000))
                .thenReturn(new ConsistencyPage<>(List.of(finding), 0, 1000, 1));

        ConsistencyRepairRequest request = new ConsistencyRepairRequest();
        request.setDryRun(true);
        ConsistencyMetrics metrics = mock(ConsistencyMetrics.class);
        ConsistencyRepairResult result = new ConsistencyRepairService(bootstrap, storage, properties, metrics)
                .repair("scan-1", request, "tester");

        assertEquals(1L, result.getScheduled().get(RecommendedAction.REINDEX));
    }
    @Test
    void dryRunDeduplicatesSameNodeAcrossFindingTypes()
    {
        TrackerBootstrap bootstrap = mock(TrackerBootstrap.class);
        ConsistencyStorageService storage = mock(ConsistencyStorageService.class);
        ConsistencyProperties properties = new ConsistencyProperties();
        ConsistencyScan scan = completedScan("scan-2");
        ConsistencyFinding missing = nodeFinding("scan-2", ConsistencyFindingType.MISSING_NODE,
                RecommendedAction.REINDEX, 42L);
        ConsistencyFinding error = nodeFinding("scan-2", ConsistencyFindingType.ERROR_NODE,
                RecommendedAction.RETRY, 42L);
        when(storage.getScan("scan-2")).thenReturn(scan);
        when(storage.readFindings("scan-2", null, null, null, 0, 1000))
                .thenReturn(new ConsistencyPage<>(List.of(missing, error), 0, 1000, 2));

        ConsistencyRepairRequest request = new ConsistencyRepairRequest();
        request.setDryRun(true);
        ConsistencyRepairResult result = new ConsistencyRepairService(bootstrap, storage, properties,
                mock(ConsistencyMetrics.class)).repair("scan-2", request, "tester");

        assertEquals(1L, result.getScheduled().get(RecommendedAction.REINDEX));
        assertEquals(null, result.getScheduled().get(RecommendedAction.RETRY));
    }

    @Test
    void orphanPurgeIsSkippedUnlessExplicitlyEnabled()
    {
        TrackerBootstrap bootstrap = mock(TrackerBootstrap.class);
        ConsistencyStorageService storage = mock(ConsistencyStorageService.class);
        ConsistencyProperties properties = new ConsistencyProperties();
        ConsistencyScan scan = completedScan("scan-3");
        ConsistencyFinding orphan = nodeFinding("scan-3", ConsistencyFindingType.ORPHAN_NODE,
                RecommendedAction.PURGE, 99L);
        when(storage.getScan("scan-3")).thenReturn(scan);
        when(storage.readFindings("scan-3", null, null, null, 0, 1000))
                .thenReturn(new ConsistencyPage<>(List.of(orphan), 0, 1000, 1));

        ConsistencyRepairResult result = new ConsistencyRepairService(bootstrap, storage, properties,
                mock(ConsistencyMetrics.class)).repair("scan-3", new ConsistencyRepairRequest(), "tester");

        assertEquals(1L, result.getSkipped().get(RecommendedAction.PURGE));
    }

    private ConsistencyScan completedScan(String id)
    {
        ConsistencyScan scan = new ConsistencyScan();
        scan.setScanId(id);
        scan.setStatus(ConsistencyScanStatus.COMPLETED);
        return scan;
    }

    private ConsistencyFinding nodeFinding(String scanId, ConsistencyFindingType type,
            RecommendedAction action, long nodeId)
    {
        ConsistencyFinding finding = new ConsistencyFinding();
        finding.setScanId(scanId);
        finding.setCore("alfresco");
        finding.setType(type);
        finding.setNodeId(nodeId);
        finding.setItemId(nodeId);
        finding.setRecommendedAction(action);
        return finding;
    }

}
