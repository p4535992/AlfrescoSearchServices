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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.Timer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.alfresco.indexing.config.TrackerBootstrap;
import org.alfresco.indexing.tracker.AclTracker;
import org.alfresco.indexing.tracker.MetadataTracker;
import org.alfresco.indexing.tracker.TrackerRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ConsistencyScanService
{
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final TrackerBootstrap trackerBootstrap;
    private final ConsistencyScanner scanner;
    private final ConsistencyStorageService storage;
    private final ConsistencyProperties properties;
    private final ConsistencyMetrics metrics;
    private final ConcurrentHashMap<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();
    private ExecutorService executor;

    public ConsistencyScanService(TrackerBootstrap trackerBootstrap, ConsistencyScanner scanner,
            ConsistencyStorageService storage, ConsistencyProperties properties, ConsistencyMetrics metrics)
    {
        this.trackerBootstrap = trackerBootstrap;
        this.scanner = scanner;
        this.storage = storage;
        this.properties = properties;
        this.metrics = metrics;
    }

    @PostConstruct
    void initialise()
    {
        int workerCount = Math.max(1, Math.min(properties.getWorkerThreads(), properties.getMaxConcurrentScans()));
        executor = Executors.newFixedThreadPool(workerCount, runnable ->
        {
            Thread thread = new Thread(runnable, "consistency-scan-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown()
    {
        if (executor != null) executor.shutdownNow();
    }

    public synchronized ConsistencyScan create(ConsistencyScanRequest request, String requestedBy)
    {
        if (!properties.isEnabled())
        {
            throw new ConsistencyApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONSISTENCY_DISABLED",
                    "Consistency scans are disabled");
        }
        validate(request);
        long activeScans = storage.listScans().stream()
                .filter(existing -> existing.getStatus() == ConsistencyScanStatus.RUNNING
                        || existing.getStatus() == ConsistencyScanStatus.QUEUED)
                .count();
        if (activeScans >= Math.max(1, properties.getMaxConcurrentScans()))
        {
            throw new ConsistencyApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ACTIVE_SCANS",
                    "The maximum number of concurrent consistency scans has been reached");
        }
        List<String> cores = resolveCores(request);

        ConsistencyScan scan = new ConsistencyScan();
        scan.setScanId("cs-" + ID_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        scan.setRequestedBy(requestedBy);
        scan.setCores(cores);
        scan.setStrategy(request.getStrategy());
        scan.setParameters(request);
        scan.setDryRun(request.isDryRun());
        storage.saveScan(scan);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancellations.put(scan.getScanId(), cancelled);
        try
        {
            Future<?> future = executor.submit(() -> execute(scan, cancelled));
            futures.put(scan.getScanId(), future);
        }
        catch (RejectedExecutionException e)
        {
            cancellations.remove(scan.getScanId());
            throw new ConsistencyApiException(HttpStatus.TOO_MANY_REQUESTS, "SCAN_QUEUE_FULL",
                    "Too many consistency scans are active");
        }
        return scan;
    }

    public ConsistencyScan get(String scanId) { return storage.getScan(scanId); }
    public List<ConsistencyScan> list() { return storage.listScans(); }

    public ConsistencyPage<ConsistencyFinding> findings(String scanId, ConsistencyFindingType type,
            String core, RecommendedAction action, int page, int size)
    {
        return storage.readFindings(scanId, type, core, action, page, size);
    }

    public ConsistencyScan cancel(String scanId)
    {
        ConsistencyScan scan = storage.getScan(scanId);
        if (scan.getStatus() != ConsistencyScanStatus.RUNNING && scan.getStatus() != ConsistencyScanStatus.QUEUED)
        {
            throw new ConsistencyApiException(HttpStatus.CONFLICT, "SCAN_NOT_ACTIVE",
                    "Only queued or running scans can be cancelled");
        }
        cancellations.computeIfAbsent(scanId, ignored -> new AtomicBoolean()).set(true);
        Future<?> future = futures.get(scanId);
        if (future != null) future.cancel(true);
        scan.setStatus(ConsistencyScanStatus.CANCELLED);
        scan.setCompletedAt(Instant.now());
        storage.saveScan(scan);
        return scan;
    }

    public void delete(String scanId) { storage.deleteScan(scanId); }

    private void execute(ConsistencyScan scan, AtomicBoolean cancelled)
    {
        scan.setStatus(ConsistencyScanStatus.RUNNING);
        scan.setStartedAt(Instant.now());
        storage.saveScan(scan);
        Timer.Sample timerSample = metrics.scanStarted();
        try
        {
            scanner.scan(scan, cancelled::get);
            if (cancelled.get())
            {
                scan.setStatus(ConsistencyScanStatus.CANCELLED);
            }
            else
            {
                scan.setStatus(ConsistencyScanStatus.COMPLETED);
                scan.setProgressPercent(100);
            }
        }
        catch (ConsistencyScanner.ScanCancelledException e)
        {
            scan.setStatus(ConsistencyScanStatus.CANCELLED);
        }
        catch (Exception e)
        {
            scan.setStatus(ConsistencyScanStatus.FAILED);
            scan.setErrorMessage(rootMessage(e));
        }
        finally
        {
            scan.setCompletedAt(Instant.now());
            storage.saveScan(scan);
            metrics.scanFinished(scan, timerSample);
            cancellations.remove(scan.getScanId());
            futures.remove(scan.getScanId());
        }
    }

    private void validate(ConsistencyScanRequest request)
    {
        if (request == null)
        {
            throw badRequest("EMPTY_SCAN_REQUEST", "A scan request is required");
        }
        if (request.getStrategy() == null) request.setStrategy(ConsistencyScanStrategy.FULL);
        if (request.getMaxResults() != null && (request.getMaxResults() <= 0
                || request.getMaxResults() > properties.getMaxResults()))
        {
            throw new ConsistencyApiException(HttpStatus.PAYLOAD_TOO_LARGE, "SCAN_LIMIT_EXCEEDED",
                    "maxResults must be between 1 and " + properties.getMaxResults());
        }
        validateRange(request.getFromNodeId(), request.getToNodeId(), "NODE_ID");
        validateRange(request.getFromTxId(), request.getToTxId(), "TRANSACTION_ID");
        validateRange(request.getFromCommitTime(), request.getToCommitTime(), "TRANSACTION_COMMIT_TIME");
        switch (request.getStrategy())
        {
            case AFTS_QUERY -> requireText(request.getQuery(), "query is required for AFTS_QUERY");
            case ANCESTOR ->
            {
                if (request.getAncestorNodeId() == null
                        && (request.getAncestorNodeRef() == null || request.getAncestorNodeRef().isBlank()))
                {
                    throw badRequest("MISSING_ANCESTOR", "ancestorNodeId or ancestorNodeRef is required");
                }
            }
            default -> { }
        }
    }

    private List<String> resolveCores(ConsistencyScanRequest request)
    {
        TrackerRegistry registry = trackerBootstrap.getRegistry();
        if (registry == null)
        {
            throw new ConsistencyApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRACKERS_NOT_READY",
                    "Tracker subsystem is not initialised");
        }
        Set<String> requested = new LinkedHashSet<>(request.getCores());
        if (requested.isEmpty()) requested.addAll(registry.getCoreNames());
        if (requested.isEmpty())
        {
            throw new ConsistencyApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_TRACKED_CORES",
                    "No tracked cores are available");
        }
        for (String core : requested)
        {
            if (!registry.hasTrackersForCore(core))
            {
                throw new ConsistencyApiException(HttpStatus.NOT_FOUND, "CORE_NOT_FOUND",
                        "Tracked core not found: " + core);
            }
        }
        request.setCores(new ArrayList<>(requested));
        return new ArrayList<>(requested);
    }

    private void validateRange(Long from, Long to, String name)
    {
        if (from != null && from < 0) throw badRequest("INVALID_SCAN_RANGE", name + " lower bound cannot be negative");
        if (to != null && to < 0) throw badRequest("INVALID_SCAN_RANGE", name + " upper bound cannot be negative");
        if (from != null && to != null && from > to)
        {
            throw badRequest("INVALID_SCAN_RANGE", name + " lower bound must be less than or equal to upper bound");
        }
    }

    private void requireText(String value, String message)
    {
        if (value == null || value.isBlank()) throw badRequest("MISSING_SCAN_PARAMETER", message);
    }

    private ConsistencyApiException badRequest(String code, String message)
    {
        return new ConsistencyApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private String rootMessage(Throwable throwable)
    {
        Throwable root = throwable;
        while (root.getCause() != null) root = root.getCause();
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
