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

import java.util.ArrayList;
import java.util.List;

public class ConsistencyScanRequest
{
    private List<String> cores = new ArrayList<>();
    private ConsistencyScanStrategy strategy = ConsistencyScanStrategy.FULL;
    private Long fromNodeId;
    private Long toNodeId;
    private Long fromTxId;
    private Long toTxId;
    private Long fromCommitTime;
    private Long toCommitTime;
    private Long ancestorNodeId;
    private String ancestorNodeRef;
    private String query;
    private Integer maxResults;
    private boolean includeErrorNodes = true;
    private boolean dryRun = true;

    public List<String> getCores() { return cores; }
    public void setCores(List<String> cores) { this.cores = cores == null ? new ArrayList<>() : cores; }
    public ConsistencyScanStrategy getStrategy() { return strategy; }
    public void setStrategy(ConsistencyScanStrategy strategy) { this.strategy = strategy; }
    public Long getFromNodeId() { return fromNodeId; }
    public void setFromNodeId(Long fromNodeId) { this.fromNodeId = fromNodeId; }
    public Long getToNodeId() { return toNodeId; }
    public void setToNodeId(Long toNodeId) { this.toNodeId = toNodeId; }
    public Long getFromTxId() { return fromTxId; }
    public void setFromTxId(Long fromTxId) { this.fromTxId = fromTxId; }
    public Long getToTxId() { return toTxId; }
    public void setToTxId(Long toTxId) { this.toTxId = toTxId; }
    public Long getFromCommitTime() { return fromCommitTime; }
    public void setFromCommitTime(Long fromCommitTime) { this.fromCommitTime = fromCommitTime; }
    public Long getToCommitTime() { return toCommitTime; }
    public void setToCommitTime(Long toCommitTime) { this.toCommitTime = toCommitTime; }
    public Long getAncestorNodeId() { return ancestorNodeId; }
    public void setAncestorNodeId(Long ancestorNodeId) { this.ancestorNodeId = ancestorNodeId; }
    public String getAncestorNodeRef() { return ancestorNodeRef; }
    public void setAncestorNodeRef(String ancestorNodeRef) { this.ancestorNodeRef = ancestorNodeRef; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public Integer getMaxResults() { return maxResults; }
    public void setMaxResults(Integer maxResults) { this.maxResults = maxResults; }
    public boolean isIncludeErrorNodes() { return includeErrorNodes; }
    public void setIncludeErrorNodes(boolean includeErrorNodes) { this.includeErrorNodes = includeErrorNodes; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
}
