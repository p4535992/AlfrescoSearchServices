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
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/admin/consistency-scans")
public class ConsistencyController
{
    private final ConsistencyScanService scanService;
    private final ConsistencyRepairService repairService;
    private final ConsistencyStorageService storage;
    private final ObjectMapper objectMapper;

    public ConsistencyController(ConsistencyScanService scanService, ConsistencyRepairService repairService,
            ConsistencyStorageService storage, ObjectMapper objectMapper)
    {
        this.scanService = scanService;
        this.repairService = repairService;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<ConsistencyScan> create(@RequestBody ConsistencyScanRequest request,
            Principal principal, HttpServletRequest httpRequest)
    {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(scanService.create(request, requestedBy(principal, httpRequest)));
    }

    @GetMapping
    public List<ConsistencyScan> list() { return scanService.list(); }

    @GetMapping("/{scanId}")
    public ConsistencyScan get(@PathVariable String scanId) { return scanService.get(scanId); }

    @GetMapping("/{scanId}/findings")
    public ConsistencyPage<ConsistencyFinding> findings(@PathVariable String scanId,
            @RequestParam(value = "type", required = false) ConsistencyFindingType type,
            @RequestParam(value = "core", required = false) String core,
            @RequestParam(value = "recommendedAction", required = false) RecommendedAction action,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "100") int size)
    {
        return scanService.findings(scanId, type, core, action, page, size);
    }

    @PostMapping("/{scanId}/repair")
    public ConsistencyRepairResult repair(@PathVariable String scanId,
            @RequestBody(required = false) ConsistencyRepairRequest request,
            Principal principal, HttpServletRequest httpRequest)
    {
        return repairService.repair(scanId, request, requestedBy(principal, httpRequest));
    }

    @PostMapping("/{scanId}/cancel")
    public ConsistencyScan cancel(@PathVariable String scanId) { return scanService.cancel(scanId); }

    @DeleteMapping("/{scanId}")
    public ResponseEntity<Void> delete(@PathVariable String scanId)
    {
        scanService.delete(scanId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{scanId}/export")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable String scanId,
            @RequestParam(value = "format", defaultValue = "json") String format)
    {
        storage.getScan(scanId);
        Path findings = storage.findingsPath(scanId);
        String normalized = format.toLowerCase();
        if (!normalized.equals("json") && !normalized.equals("csv"))
        {
            throw new ConsistencyApiException(HttpStatus.BAD_REQUEST, "INVALID_EXPORT_FORMAT",
                    "Supported export formats are json and csv");
        }
        MediaType mediaType = normalized.equals("csv") ? MediaType.parseMediaType("text/csv") : MediaType.APPLICATION_JSON;
        String filename = scanId + "." + normalized;
        StreamingResponseBody body = output ->
        {
            if (normalized.equals("csv")) writeCsv(findings, output);
            else writeJson(findings, output);
        };
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    private void writeJson(Path path, java.io.OutputStream output) throws IOException
    {
        output.write('[');
        if (Files.exists(path))
        {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
            {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null)
                {
                    if (!first) output.write(',');
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                    first = false;
                }
            }
        }
        output.write(']');
    }

    private void writeCsv(Path path, java.io.OutputStream output) throws IOException
    {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.println("scanId,core,type,itemId,nodeId,transactionId,aclId,aclTxId,nodeRef,status,message,recommendedAction,detectedAt");
        if (Files.exists(path))
        {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    ConsistencyFinding f = objectMapper.readValue(line, ConsistencyFinding.class);
                    writer.println(String.join(",", csv(f.getScanId()), csv(f.getCore()), csv(f.getType()),
                            csv(f.getItemId()), csv(f.getNodeId()), csv(f.getTransactionId()), csv(f.getAclId()),
                            csv(f.getAclTxId()), csv(f.getNodeRef()), csv(f.getStatus()), csv(f.getMessage()),
                            csv(f.getRecommendedAction()), csv(f.getDetectedAt())));
                }
            }
        }
        writer.flush();
    }

    private String csv(Object value)
    {
        if (value == null) return "";
        return "\"" + value.toString().replace("\"", "\"\"") + "\"";
    }

    private String requestedBy(Principal principal, HttpServletRequest request)
    {
        if (principal != null && principal.getName() != null) return principal.getName();
        String forwarded = request.getHeader("X-Forwarded-User");
        return forwarded == null || forwarded.isBlank() ? "unknown" : forwarded;
    }
}
