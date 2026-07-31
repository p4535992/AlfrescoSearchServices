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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsistencyStorageServiceTest
{
    @TempDir
    Path tempDir;

    @Test
    void persistsAndPagesFindings() throws Exception
    {
        ConsistencyProperties properties = new ConsistencyProperties();
        properties.setStoragePath(tempDir.toString());
        properties.setResultPageSizeMax(10);
        ConsistencyStorageService storage = new ConsistencyStorageService(
                new ObjectMapper().findAndRegisterModules(), properties);
        storage.initialise();

        ConsistencyScan scan = new ConsistencyScan();
        scan.setScanId("scan-1");
        scan.setCores(List.of("alfresco"));
        scan.setStrategy(ConsistencyScanStrategy.NODE_ID);
        storage.saveScan(scan);

        for (long id = 1; id <= 3; id++)
        {
            ConsistencyFinding finding = new ConsistencyFinding();
            finding.setScanId(scan.getScanId());
            finding.setCore("alfresco");
            finding.setType(ConsistencyFindingType.MISSING_NODE);
            finding.setItemId(id);
            finding.setNodeId(id);
            finding.setRecommendedAction(RecommendedAction.REINDEX);
            storage.appendFinding(finding);
        }

        ConsistencyPage<ConsistencyFinding> page = storage.readFindings(
                "scan-1", ConsistencyFindingType.MISSING_NODE, null, null, 1, 2);
        assertEquals(3, page.getTotalElements());
        assertEquals(1, page.getContent().size());
        assertEquals(3L, page.getContent().get(0).getNodeId());
    }

    @Test
    void refusesToDeleteActiveScan() throws Exception
    {
        ConsistencyProperties properties = new ConsistencyProperties();
        properties.setStoragePath(tempDir.toString());
        ConsistencyStorageService storage = new ConsistencyStorageService(
                new ObjectMapper().findAndRegisterModules(), properties);
        storage.initialise();
        ConsistencyScan scan = new ConsistencyScan();
        scan.setScanId("active");
        scan.setStatus(ConsistencyScanStatus.RUNNING);
        storage.saveScan(scan);
        assertThrows(ConsistencyApiException.class, () -> storage.deleteScan("active"));
        assertFalse(storage.listScans().isEmpty());
    }
}
