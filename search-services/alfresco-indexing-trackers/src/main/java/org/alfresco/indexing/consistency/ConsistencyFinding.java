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

public class ConsistencyFinding
{
    private String scanId;
    private String core;
    private ConsistencyFindingType type;
    private Long itemId;
    private Long nodeId;
    private Long transactionId;
    private Long aclId;
    private Long aclTxId;
    private String nodeRef;
    private String status;
    private String message;
    private RecommendedAction recommendedAction = RecommendedAction.NONE;
    private Instant detectedAt = Instant.now();

    public String getScanId() { return scanId; }
    public void setScanId(String scanId) { this.scanId = scanId; }
    public String getCore() { return core; }
    public void setCore(String core) { this.core = core; }
    public ConsistencyFindingType getType() { return type; }
    public void setType(ConsistencyFindingType type) { this.type = type; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }
    public Long getAclId() { return aclId; }
    public void setAclId(Long aclId) { this.aclId = aclId; }
    public Long getAclTxId() { return aclTxId; }
    public void setAclTxId(Long aclTxId) { this.aclTxId = aclTxId; }
    public String getNodeRef() { return nodeRef; }
    public void setNodeRef(String nodeRef) { this.nodeRef = nodeRef; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public RecommendedAction getRecommendedAction() { return recommendedAction; }
    public void setRecommendedAction(RecommendedAction recommendedAction) { this.recommendedAction = recommendedAction; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
}
