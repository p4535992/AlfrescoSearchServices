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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ConsistencyScan
{
    private String scanId;
    private ConsistencyScanStatus status = ConsistencyScanStatus.QUEUED;
    private Instant createdAt = Instant.now();
    private Instant startedAt;
    private Instant completedAt;
    private String requestedBy;
    private List<String> cores = new ArrayList<>();
    private ConsistencyScanStrategy strategy;
    private ConsistencyScanRequest parameters;
    private boolean dryRun;
    private long processedItems;
    private Long totalItems;
    private int progressPercent;
    private Map<ConsistencyFindingType, Long> resultCounts = new EnumMap<>(ConsistencyFindingType.class);
    private String errorMessage;

    public String getScanId() { return scanId; }
    public void setScanId(String scanId) { this.scanId = scanId; }
    public ConsistencyScanStatus getStatus() { return status; }
    public void setStatus(ConsistencyScanStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public List<String> getCores() { return cores; }
    public void setCores(List<String> cores) { this.cores = cores; }
    public ConsistencyScanStrategy getStrategy() { return strategy; }
    public void setStrategy(ConsistencyScanStrategy strategy) { this.strategy = strategy; }
    public ConsistencyScanRequest getParameters() { return parameters; }
    public void setParameters(ConsistencyScanRequest parameters) { this.parameters = parameters; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public long getProcessedItems() { return processedItems; }
    public void setProcessedItems(long processedItems) { this.processedItems = processedItems; }
    public Long getTotalItems() { return totalItems; }
    public void setTotalItems(Long totalItems) { this.totalItems = totalItems; }
    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int progressPercent) { this.progressPercent = progressPercent; }
    public Map<ConsistencyFindingType, Long> getResultCounts() { return resultCounts; }
    public void setResultCounts(Map<ConsistencyFindingType, Long> resultCounts) { this.resultCounts = resultCounts; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public void incrementProcessed() { processedItems++; }
    public void incrementFinding(ConsistencyFindingType type) { resultCounts.merge(type, 1L, Long::sum); }
}
