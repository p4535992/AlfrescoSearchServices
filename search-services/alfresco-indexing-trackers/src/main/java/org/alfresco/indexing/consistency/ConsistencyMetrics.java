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

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class ConsistencyMetrics
{
    private final MeterRegistry registry;
    private final AtomicInteger activeScans = new AtomicInteger();

    public ConsistencyMetrics(MeterRegistry registry)
    {
        this.registry = registry;
        Gauge.builder("consistency_scan_active", activeScans, AtomicInteger::get)
                .description("Number of active consistency scans")
                .register(registry);
    }

    public Timer.Sample scanStarted()
    {
        activeScans.incrementAndGet();
        Counter.builder("consistency_scan_total").register(registry).increment();
        return Timer.start(registry);
    }

    public void scanFinished(ConsistencyScan scan, Timer.Sample sample)
    {
        activeScans.updateAndGet(current -> Math.max(0, current - 1));
        sample.stop(Timer.builder("consistency_scan_duration_seconds")
                .tag("strategy", scan.getStrategy() == null ? "unknown" : scan.getStrategy().name())
                .register(registry));
        Counter.builder("consistency_scan_processed_items_total")
                .tag("strategy", scan.getStrategy() == null ? "unknown" : scan.getStrategy().name())
                .register(registry).increment(scan.getProcessedItems());
        if (scan.getStatus() == ConsistencyScanStatus.FAILED)
        {
            Counter.builder("consistency_scan_failed_total").register(registry).increment();
        }
    }

    public void finding(ConsistencyFinding finding)
    {
        Counter.builder("consistency_scan_findings_total")
                .tag("core", finding.getCore() == null ? "unknown" : finding.getCore())
                .tag("findingType", finding.getType() == null ? "unknown" : finding.getType().name())
                .register(registry).increment();
    }

    public void repairs(ConsistencyRepairResult result)
    {
        for (Map.Entry<RecommendedAction, Long> entry : result.getScheduled().entrySet())
        {
            Counter.builder("consistency_repair_scheduled_total")
                    .tag("action", entry.getKey().name())
                    .tag("dryRun", Boolean.toString(result.isDryRun()))
                    .register(registry).increment(entry.getValue());
        }
    }
}
