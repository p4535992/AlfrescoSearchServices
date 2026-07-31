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

import java.util.LinkedHashSet;
import java.util.Set;

public class ConsistencyRepairRequest
{
    private Set<ConsistencyFindingType> types = new LinkedHashSet<>();
    private boolean dryRun = true;
    private boolean purgeOrphans;

    public Set<ConsistencyFindingType> getTypes() { return types; }
    public void setTypes(Set<ConsistencyFindingType> types) { this.types = types == null ? new LinkedHashSet<>() : types; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public boolean isPurgeOrphans() { return purgeOrphans; }
    public void setPurgeOrphans(boolean purgeOrphans) { this.purgeOrphans = purgeOrphans; }
}
