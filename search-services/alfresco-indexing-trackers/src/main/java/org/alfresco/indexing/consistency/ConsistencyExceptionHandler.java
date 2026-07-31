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
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ConsistencyController.class)
public class ConsistencyExceptionHandler
{
    @ExceptionHandler(ConsistencyApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ConsistencyApiException exception, HttpServletRequest request)
    {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("timestamp", Instant.now());
        error.put("status", exception.getStatus().value());
        error.put("error", exception.getStatus().getReasonPhrase());
        error.put("code", exception.getCode());
        error.put("message", exception.getMessage());
        error.put("path", request.getRequestURI());
        return ResponseEntity.status(exception.getStatus()).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException exception,
            HttpServletRequest request)
    {
        return handle(new ConsistencyApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "INVALID_SCAN_REQUEST", exception.getMessage()), request);
    }
}
