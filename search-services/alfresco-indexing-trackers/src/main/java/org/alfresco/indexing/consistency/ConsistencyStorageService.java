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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ConsistencyStorageService
{
    private final ObjectMapper objectMapper;
    private final ConsistencyProperties properties;
    private final Map<String, ConsistencyScan> scans = new ConcurrentHashMap<>();
    private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();
    private Path root;

    public ConsistencyStorageService(ObjectMapper objectMapper, ConsistencyProperties properties)
    {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    void initialise() throws IOException
    {
        root = Path.of(expandSystemProperties(properties.getStoragePath())).toAbsolutePath().normalize();
        Files.createDirectories(root);
        loadScans();
        cleanupRetention();
    }

    public Path getRoot() { return root; }

    public void saveScan(ConsistencyScan scan)
    {
        scans.put(scan.getScanId(), scan);
        Path target = scanPath(scan.getScanId());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        synchronized (lock(scan.getScanId()))
        {
            try
            {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), scan);
                try
                {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                catch (java.nio.file.AtomicMoveNotSupportedException e)
                {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            catch (IOException e)
            {
                throw new IllegalStateException("Unable to persist consistency scan " + scan.getScanId(), e);
            }
        }
    }

    public ConsistencyScan getScan(String scanId)
    {
        ConsistencyScan scan = scans.get(scanId);
        if (scan == null)
        {
            throw new ConsistencyApiException(HttpStatus.NOT_FOUND, "SCAN_NOT_FOUND",
                    "Consistency scan not found: " + scanId);
        }
        return scan;
    }

    public List<ConsistencyScan> listScans()
    {
        return scans.values().stream()
                .sorted(Comparator.comparing(ConsistencyScan::getCreatedAt).reversed())
                .toList();
    }

    public void appendFinding(ConsistencyFinding finding)
    {
        synchronized (lock(finding.getScanId()))
        {
            try (BufferedWriter writer = Files.newBufferedWriter(findingsPath(finding.getScanId()),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND))
            {
                writer.write(objectMapper.writeValueAsString(finding));
                writer.newLine();
            }
            catch (IOException e)
            {
                throw new IllegalStateException("Unable to persist consistency finding", e);
            }
        }
    }

    public ConsistencyPage<ConsistencyFinding> readFindings(String scanId, ConsistencyFindingType type,
            String core, RecommendedAction action, int page, int size)
    {
        getScan(scanId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, properties.getResultPageSizeMax()));
        long offset = (long) safePage * safeSize;
        long matched = 0;
        List<ConsistencyFinding> content = new ArrayList<>(safeSize);
        Path path = findingsPath(scanId);
        if (!Files.exists(path))
        {
            return new ConsistencyPage<>(content, safePage, safeSize, 0);
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                ConsistencyFinding finding = objectMapper.readValue(line, ConsistencyFinding.class);
                if (!matches(finding, type, core, action)) continue;
                if (matched >= offset && content.size() < safeSize) content.add(finding);
                matched++;
            }
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Unable to read consistency findings", e);
        }
        return new ConsistencyPage<>(content, safePage, safeSize, matched);
    }

    public List<ConsistencyFinding> readAllFindings(String scanId)
    {
        getScan(scanId);
        List<ConsistencyFinding> result = new ArrayList<>();
        Path path = findingsPath(scanId);
        if (!Files.exists(path)) return result;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                result.add(objectMapper.readValue(line, ConsistencyFinding.class));
            }
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Unable to read consistency findings", e);
        }
        return result;
    }

    public Path findingsPath(String scanId) { return root.resolve(scanId + ".findings.jsonl"); }

    public void saveRepair(ConsistencyRepairResult repair)
    {
        Path target = root.resolve(repair.getRepairId() + ".repair.json");
        try
        {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), repair);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Unable to persist consistency repair audit", e);
        }
    }

    public void deleteScan(String scanId)
    {
        ConsistencyScan scan = getScan(scanId);
        if (scan.getStatus() == ConsistencyScanStatus.RUNNING || scan.getStatus() == ConsistencyScanStatus.QUEUED)
        {
            throw new ConsistencyApiException(HttpStatus.CONFLICT, "SCAN_ACTIVE",
                    "An active consistency scan cannot be deleted");
        }
        scans.remove(scanId);
        try
        {
            Files.deleteIfExists(scanPath(scanId));
            Files.deleteIfExists(findingsPath(scanId));
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Unable to delete consistency scan", e);
        }
    }

    private boolean matches(ConsistencyFinding finding, ConsistencyFindingType type, String core, RecommendedAction action)
    {
        return (type == null || type == finding.getType())
                && (core == null || core.isBlank() || core.equals(finding.getCore()))
                && (action == null || action == finding.getRecommendedAction());
    }

    private void loadScans() throws IOException
    {
        try (var stream = Files.list(root))
        {
            for (Path path : stream.filter(p -> p.getFileName().toString().endsWith(".scan.json")).toList())
            {
                try
                {
                    ConsistencyScan scan = objectMapper.readValue(path.toFile(), ConsistencyScan.class);
                    if (scan.getStatus() == ConsistencyScanStatus.RUNNING || scan.getStatus() == ConsistencyScanStatus.QUEUED)
                    {
                        scan.setStatus(ConsistencyScanStatus.FAILED);
                        scan.setCompletedAt(Instant.now());
                        scan.setErrorMessage("Tracker service restarted while the scan was active");
                    }
                    scans.put(scan.getScanId(), scan);
                    saveScan(scan);
                }
                catch (RuntimeException | IOException ignored)
                {
                    // A corrupt scan file must not prevent tracker startup.
                }
            }
        }
    }

    private void cleanupRetention()
    {
        if (properties.getRetentionDays() <= 0) return;
        Instant cutoff = Instant.now().minus(properties.getRetentionDays(), ChronoUnit.DAYS);
        for (ConsistencyScan scan : new ArrayList<>(scans.values()))
        {
            if (scan.getCreatedAt() != null && scan.getCreatedAt().isBefore(cutoff)
                    && scan.getStatus() != ConsistencyScanStatus.RUNNING
                    && scan.getStatus() != ConsistencyScanStatus.QUEUED)
            {
                deleteScan(scan.getScanId());
            }
        }
    }

    private Path scanPath(String scanId) { return root.resolve(scanId + ".scan.json"); }
    private Object lock(String scanId) { return fileLocks.computeIfAbsent(scanId, ignored -> new Object()); }

    private String expandSystemProperties(String value)
    {
        String result = value;
        int start;
        while ((start = result.indexOf("${")) >= 0)
        {
            int end = result.indexOf('}', start);
            if (end < 0) break;
            String key = result.substring(start + 2, end);
            String replacement = System.getProperty(key, "");
            result = result.substring(0, start) + replacement + result.substring(end + 1);
        }
        return result;
    }
}
