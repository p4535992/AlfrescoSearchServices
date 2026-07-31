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

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

public class ConsistencyRepairResult
{
    private String repairId;
    private String scanId;
    private Instant requestedAt = Instant.now();
    private String requestedBy;
    private boolean dryRun;
    private Map<RecommendedAction, Long> scheduled = new EnumMap<>(RecommendedAction.class);
    private Map<RecommendedAction, Long> skipped = new EnumMap<>(RecommendedAction.class);
    private Map<RecommendedAction, Long> failed = new EnumMap<>(RecommendedAction.class);

    public String getRepairId() { return repairId; }
    public void setRepairId(String repairId) { this.repairId = repairId; }
    public String getScanId() { return scanId; }
    public void setScanId(String scanId) { this.scanId = scanId; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public Map<RecommendedAction, Long> getScheduled() { return scheduled; }
    public Map<RecommendedAction, Long> getSkipped() { return skipped; }
    public Map<RecommendedAction, Long> getFailed() { return failed; }
    public void scheduled(RecommendedAction action) { scheduled.merge(action, 1L, Long::sum); }
    public void skipped(RecommendedAction action) { skipped.merge(action, 1L, Long::sum); }
    public void failed(RecommendedAction action) { failed.merge(action, 1L, Long::sum); }
}
