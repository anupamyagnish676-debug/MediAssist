package com.med.assistant.controller;

import com.med.assistant.service.AuditLogService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST API for viewing and downloading the user activity log file.
 * The log file resides purely on disk (./logs/user_actions.log) and is NOT stored in the database.
 */
@RestController
@RequestMapping("/api/v1/logs")
@CrossOrigin(origins = "*")
public class UserActionLogApiController {

    private final AuditLogService auditLogService;

    public UserActionLogApiController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * Preview recent user action logs.
     */
    @GetMapping("/user-actions")
    public ResponseEntity<Map<String, Object>> getRecentUserActions(@RequestParam(defaultValue = "200") int limit) {
        List<String> lines = auditLogService.getRecentLogs(limit);
        File file = auditLogService.getLogFile();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "fileName", file.getName(),
                "fileAbsolutePath", file.getAbsolutePath(),
                "fileSizeBytes", auditLogService.getLogFileSize(),
                "lineCount", lines.size(),
                "logs", lines
        ));
    }

    /**
     * Download the physical user_actions.log file directly.
     */
    @GetMapping(value = "/user-actions/download", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Resource> downloadLogFile() throws IOException {
        File file = auditLogService.getLogFile();
        Resource resource = new FileSystemResource(file);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"user_actions.log\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(resource);
    }

    /**
     * Clear the log file.
     */
    @DeleteMapping("/user-actions/clear")
    public ResponseEntity<Map<String, Object>> clearLogFile() {
        boolean ok = auditLogService.clearLogs();
        return ResponseEntity.ok(Map.of(
                "success", ok,
                "message", ok ? "User action log file cleared successfully" : "Failed to clear log file"
        ));
    }
}
