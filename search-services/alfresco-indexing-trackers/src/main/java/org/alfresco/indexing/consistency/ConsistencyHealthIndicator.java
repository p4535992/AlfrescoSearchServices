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

import java.util.List;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class ConsistencyHealthIndicator implements HealthIndicator
{
    private final ConsistencyStorageService storage;
    private final ConsistencyProperties properties;

    public ConsistencyHealthIndicator(ConsistencyStorageService storage, ConsistencyProperties properties)
    {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public Health health()
    {
        List<ConsistencyScan> scans = storage.listScans();
        long active = scans.stream().filter(scan -> scan.getStatus() == ConsistencyScanStatus.RUNNING
                || scan.getStatus() == ConsistencyScanStatus.QUEUED).count();
        Health.Builder builder = Health.up()
                .withDetail("enabled", properties.isEnabled())
                .withDetail("activeScans", active)
                .withDetail("storedScans", scans.size())
                .withDetail("storagePath", storage.getRoot().toString());
        if (!scans.isEmpty())
        {
            builder.withDetail("lastScanId", scans.get(0).getScanId())
                    .withDetail("lastScanStatus", scans.get(0).getStatus());
        }
        return builder.build();
    }
}
