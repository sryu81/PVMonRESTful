package com.example.epics.controller;

import com.example.epics.service.EPICSService;
import com.example.epics.model.PVData;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/epics")
@CrossOrigin(origins = "*") // Configure as needed
public class EPICSController {
    
    private static final Logger logger = LoggerFactory.getLogger(EPICSController.class);

    @Autowired
    private EPICSService epicsService;
    
    // Store both legacy PVUpdate and new PVData
    private final ConcurrentHashMap<String, List<EPICSService.PVUpdate>> pvUpdates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<PVData>> pvDataHistory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PVData> currentPVData = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        logger.info("=== EPICSController @PostConstruct called ===");
        epicsService.setController(this);
        logger.info("Controller set in EPICSService");
    }

    // ========== SUBSCRIPTION ENDPOINTS ==========
    @PostMapping("/subscribe/{pvName}")
    public ResponseEntity<Map<String, Object>> subscribeToPV(@PathVariable String pvName) {
        logger.info("=== REST API: Subscribe called for PV: {} ===", pvName);
        
        try {
            epicsService.subscribeToPV(pvName);
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Subscribed to " + pvName + " (field data loading in background)");
            response.put("pvName", pvName);
            response.put("timestamp", System.currentTimeMillis());
            response.put("note", "Main PV subscribed immediately, additional field data will be available shortly");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", e.getMessage());
            response.put("pvName", pvName);
            return ResponseEntity.status(500).body(response);
        }
    }
    
    @DeleteMapping("/unsubscribe/{pvName}")
    public ResponseEntity<Map<String, Object>> unsubscribeFromPV(@PathVariable String pvName) {
        logger.info("=== REST API: Unsubscribe called for PV: {} ===", pvName);
        
        try {
            epicsService.unsubscribeFromPV(pvName);
            
            // Clean up stored data
            pvUpdates.remove(pvName);
            pvDataHistory.remove(pvName);
            currentPVData.remove(pvName);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Unsubscribed from " + pvName);
            response.put("pvName", pvName);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

        // ========== PV DATA ENDPOINTS (New Enhanced API) ==========
    @GetMapping("/pv/{pvName}")
    public ResponseEntity<Map<String, Object>> getCurrentPVData(@PathVariable String pvName) {
        logger.debug("=== REST API: Get current PV data with metadata for: {} ===", pvName);
        
        PVData pvData = currentPVData.get(pvName);
        if (pvData == null) {
            // Try to get from service cache
            pvData = epicsService.getPVData(pvName);
            if (pvData != null) {
                currentPVData.put(pvName, pvData);
            }
        }
        
        if (pvData != null) {
            Map<String, Object> response = new HashMap<>();
            
            // Core PV data
            response.put("pvName", pvData.getPvName());
            response.put("value", pvData.getValue());
            response.put("dataType", pvData.getDataType());
            response.put("timestamp", pvData.getTimestamp());
            response.put("lastUpdate", pvData.getLastUpdate());
            
            // Connection and status
            response.put("connectionStatus", pvData.getConnectionStatus());
            response.put("alarmStatus", pvData.getAlarmStatus());
            response.put("alarmSeverity", pvData.getAlarmSeverity());
            
            // Metadata
            response.put("units", pvData.getUnits());
            response.put("precision", pvData.getPrecision());
            response.put("description", pvData.getDescription());
            
            // Limits (these will be null if not available)
            response.put("displayLimits", pvData.getDisplayLimits());
            response.put("alarmLimits", pvData.getAlarmLimits());
            response.put("controlLimits", pvData.getControlLimits());
            
            // Additional computed fields for frontend convenience
            response.put("isConnected", pvData.getConnectionStatus() == PVData.ConnectionStatus.CONNECTED);
            response.put("hasAlarm", pvData.getAlarmSeverity() != null && 
                                    pvData.getAlarmSeverity() != PVData.AlarmSeverity.NO_ALARM);
            response.put("isWritable", pvData.getControlLimits() != null);
            
            // Formatted value for display (if numeric and has precision)
            if (pvData.getValue() instanceof Number && pvData.getPrecision() != null) {
                Number numValue = (Number) pvData.getValue();
                String format = "%." + pvData.getPrecision() + "f";
                response.put("formattedValue", String.format(format, numValue.doubleValue()));
            } else {
                response.put("formattedValue", pvData.getValue() != null ? pvData.getValue().toString() : null);
            }
            
            // Value with units for display
            String valueWithUnits = response.get("formattedValue") != null ? 
                response.get("formattedValue").toString() : "";
            if (pvData.getUnits() != null && !pvData.getUnits().trim().isEmpty()) {
                valueWithUnits += " " + pvData.getUnits();
            }
            response.put("displayValue", valueWithUnits);
            
            return ResponseEntity.ok(response);
        }
        
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/pv/{pvName}/history")
    public ResponseEntity<List<PVData>> getPVDataHistory(
            @PathVariable String pvName,
            @RequestParam(defaultValue = "10") int limit) {
        logger.debug("=== REST API: Get PV data history for: {} (limit: {}) ===", pvName, limit);
        
        List<PVData> history = pvDataHistory.get(pvName);
        if (history != null && !history.isEmpty()) {
            int fromIndex = Math.max(0, history.size() - limit);
            return ResponseEntity.ok(new ArrayList<>(history.subList(fromIndex, history.size())));
        }
        return ResponseEntity.ok(new ArrayList<>());
    }

    @PostMapping("/pv/{pvName}/set")
    public ResponseEntity<Map<String, Object>> setPVValue(
            @PathVariable String pvName,
            @RequestBody Map<String, Object> request) {
        logger.info("=== REST API: Set PV value for: {} ===", pvName);
        
        try {
            Object value = request.get("value");
            if (value == null) {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "error");
                response.put("message", "Value is required");
                return ResponseEntity.badRequest().body(response);
            }
            
            epicsService.setPVValue(pvName, value);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "PV value set successfully");
            response.put("pvName", pvName);
            response.put("value", value);
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", e.getMessage());
            response.put("pvName", pvName);
            return ResponseEntity.status(500).body(response);
        }
    }

    // ========== BULK OPERATIONS ==========

    @GetMapping("/pvs")
    public ResponseEntity<Set<String>> getSubscribedPVs() {
        logger.debug("=== REST API: Getting subscribed PVs, current count: {} ===", currentPVData.size());
        logger.debug("Stored PVs: {}", currentPVData.keySet());
        return ResponseEntity.ok(currentPVData.keySet());
    }

    @GetMapping("/pvs/status")
    public ResponseEntity<Map<String, Object>> getAllPVStatus() {
        logger.debug("=== REST API: Getting all PV status ===");
        
        Map<String, Object> statusMap = new HashMap<>();
        
        for (Map.Entry<String, PVData> entry : currentPVData.entrySet()) {
            String pvName = entry.getKey();
            PVData pvData = entry.getValue();
            
            Map<String, Object> pvStatus = new HashMap<>();
            pvStatus.put("connectionStatus", pvData.getConnectionStatus());
            pvStatus.put("alarmStatus", pvData.getAlarmStatus());
            pvStatus.put("alarmSeverity", pvData.getAlarmSeverity());
            pvStatus.put("value", pvData.getValue());
            pvStatus.put("lastUpdate", pvData.getLastUpdate());
            
            statusMap.put(pvName, pvStatus);
        }
        
        return ResponseEntity.ok(statusMap);
    }

    @GetMapping("/pvs/summary")
    public ResponseEntity<Map<String, Object>> getPVsSummary() {
        logger.debug("=== REST API: Getting PVs summary ===");
        
        Map<String, Object> summary = new HashMap<>();
        
        long totalPVs = currentPVData.size();
        long connectedPVs = currentPVData.values().stream()
            .mapToLong(pv -> pv.getConnectionStatus() == PVData.ConnectionStatus.CONNECTED ? 1 : 0)
            .sum();
        long alarmedPVs = currentPVData.values().stream()
            .mapToLong(pv -> pv.getAlarmSeverity() != null && 
                           pv.getAlarmSeverity() != PVData.AlarmSeverity.NO_ALARM ? 1 : 0)
            .sum();
        
        summary.put("totalPVs", totalPVs);
        summary.put("connectedPVs", connectedPVs);
        summary.put("disconnectedPVs", totalPVs - connectedPVs);
        summary.put("alarmedPVs", alarmedPVs);
        summary.put("timestamp", LocalDateTime.now());
        
        return ResponseEntity.ok(summary);
    }

    @PostMapping("/pvs/subscribe-bulk")
    public ResponseEntity<Map<String, Object>> subscribeToBulkPVs(@RequestBody Map<String, Object> request) {
        logger.info("=== REST API: Bulk subscribe called ===");
        
        try {
            @SuppressWarnings("unchecked")
            List<String> pvNames = (List<String>) request.get("pvNames");
            
            if (pvNames == null || pvNames.isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "error");
                response.put("message", "pvNames list is required");
                return ResponseEntity.badRequest().body(response);
            }
            
            Map<String, String> results = new HashMap<>();
            int successCount = 0;
            int errorCount = 0;
            
            for (String pvName : pvNames) {
                try {
                    epicsService.subscribeToPV(pvName);
                    results.put(pvName, "success");
                    successCount++;
                } catch (Exception e) {
                    results.put(pvName, "error: " + e.getMessage());
                    errorCount++;
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "completed");
            response.put("totalRequested", pvNames.size());
            response.put("successCount", successCount);
            response.put("errorCount", errorCount);
            response.put("results", results);
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ========== LEGACY ENDPOINTS (Backward Compatibility) ==========

    @GetMapping("/pv/{pvName}/latest")
    public ResponseEntity<EPICSService.PVUpdate> getLatestValue(@PathVariable String pvName) {
        logger.debug("=== REST API: Get latest value (legacy) for: {} ===", pvName);
        
        List<EPICSService.PVUpdate> updates = pvUpdates.get(pvName);
        if (updates != null && !updates.isEmpty()) {
            return ResponseEntity.ok(updates.get(updates.size() - 1));
        }
        
        // Convert from new PVData to legacy PVUpdate
        PVData pvData = currentPVData.get(pvName);
        if (pvData != null) {
            EPICSService.PVUpdate legacyUpdate = new EPICSService.PVUpdate(
                pvData.getPvName(),
                pvData.getValue(),
                pvData.getConnectionStatus().toString(),
                pvData.getTimestamp() != null ? 
                    pvData.getTimestamp().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : 
                    System.currentTimeMillis()
            );
            return ResponseEntity.ok(legacyUpdate);
        }
        
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/pv/{pvName}/history-legacy")
    public ResponseEntity<List<EPICSService.PVUpdate>> getLegacyHistory(
            @PathVariable String pvName,
            @RequestParam(defaultValue = "10") int limit) {
        logger.debug("=== REST API: Get legacy history for: {} ===", pvName);
        
        List<EPICSService.PVUpdate> updates = pvUpdates.get(pvName);
        if (updates != null) {
            int fromIndex = Math.max(0, updates.size() - limit);
            return ResponseEntity.ok(updates.subList(fromIndex, updates.size()));
        }
        return ResponseEntity.ok(new ArrayList<>());
    }

    // ========== DATA STORAGE METHODS (Called by EPICSService) ==========

    /**
     * Store PVData updates (new enhanced method)
     */
    public void storePVData(PVData pvData) {
        if (pvData == null) {
            return;
        }
        
        String pvName = pvData.getPvName();
        logger.debug("=== Controller.storePVData called: PV={}, value={} ===", pvName, pvData.getValue());
        
        // Store current data
        currentPVData.put(pvName, pvData);
        
        // Store in history
        pvDataHistory.computeIfAbsent(pvName, k -> new ArrayList<>()).add(pvData);
        
        // Keep only last 100 updates per PV to prevent memory issues
        List<PVData> history = pvDataHistory.get(pvName);
        if (history.size() > 100) {
            history.remove(0);
        }
        
        // Also create legacy PVUpdate for backward compatibility
        EPICSService.PVUpdate legacyUpdate = new EPICSService.PVUpdate(
            pvData.getPvName(),
            pvData.getValue(),
            pvData.getConnectionStatus().toString(),
            pvData.getTimestamp() != null ? 
                pvData.getTimestamp().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : 
                System.currentTimeMillis()
        );
        
        storeUpdate(legacyUpdate);
        
        logger.debug("Total PV data stored for PV {}: {}", pvName, history.size());
        logger.debug("Current stored PVs: {}", currentPVData.keySet());
    }

    /**
     * Store legacy PVUpdate (for backward compatibility)
     */
    public void storeUpdate(EPICSService.PVUpdate update) {
        if (update == null) {
            return;
        }
        
        logger.debug("=== Controller.storeUpdate called: PV={}, value={} ===", update.getPvName(), update.getValue());
        pvUpdates.computeIfAbsent(update.getPvName(), k -> new ArrayList<>()).add(update);
        
        // Keep only last 100 updates per PV to prevent memory issues
        List<EPICSService.PVUpdate> updates = pvUpdates.get(update.getPvName());
        if (updates.size() > 100) {
            updates.remove(0);
        }
    }

    // ========== SEARCH AND FILTER ENDPOINTS ==========

    @GetMapping("/pvs/search")
    public ResponseEntity<List<String>> searchPVs(@RequestParam String pattern) {
        logger.debug("=== REST API: Search PVs with pattern: {} ===", pattern);
        
        List<String> matchingPVs = currentPVData.keySet().stream()
            .filter(pvName -> pvName.toLowerCase().contains(pattern.toLowerCase()))
            .sorted()
            .toList();
        
        return ResponseEntity.ok(matchingPVs);
    }

    @GetMapping("/pvs/filter")
    public ResponseEntity<List<Map<String, Object>>> filterPVs(
            @RequestParam(required = false) String connectionStatus,
            @RequestParam(required = false) String alarmSeverity,
            @RequestParam(required = false) String dataType) {
        logger.debug("=== REST API: Filter PVs - status: {}, severity: {}, type: {} ===", 
                    connectionStatus, alarmSeverity, dataType);
        
        List<Map<String, Object>> filteredPVs = new ArrayList<>();
        
        for (Map.Entry<String, PVData> entry : currentPVData.entrySet()) {
            PVData pvData = entry.getValue();
            boolean matches = true;
            
            if (connectionStatus != null && !connectionStatus.isEmpty()) {
                matches = matches && pvData.getConnectionStatus().toString().equalsIgnoreCase(connectionStatus);
            }
            
            if (alarmSeverity != null && !alarmSeverity.isEmpty()) {
                matches = matches && pvData.getAlarmSeverity() != null && 
                         pvData.getAlarmSeverity().toString().equalsIgnoreCase(alarmSeverity);
            }
            
            if (dataType != null && !dataType.isEmpty()) {
                matches = matches && pvData.getDataType() != null && 
                         pvData.getDataType().toLowerCase().contains(dataType.toLowerCase());
            }
            
            if (matches) {
                Map<String, Object> pvInfo = new HashMap<>();
                pvInfo.put("pvName", pvData.getPvName());
                pvInfo.put("value", pvData.getValue());
                pvInfo.put("dataType", pvData.getDataType());
                pvInfo.put("connectionStatus", pvData.getConnectionStatus());
                pvInfo.put("alarmStatus", pvData.getAlarmStatus());
                pvInfo.put("alarmSeverity", pvData.getAlarmSeverity());
                pvInfo.put("units", pvData.getUnits());
                pvInfo.put("lastUpdate", pvData.getLastUpdate());
                
                filteredPVs.add(pvInfo);
            }
        }
        
        return ResponseEntity.ok(filteredPVs);
    }

    // ========== ALARM MONITORING ENDPOINTS ==========

    @GetMapping("/alarms")
    public ResponseEntity<List<Map<String, Object>>> getActiveAlarms() {
        logger.debug("=== REST API: Get active alarms ===");
        
        List<Map<String, Object>> alarms = new ArrayList<>();
        
        for (Map.Entry<String, PVData> entry : currentPVData.entrySet()) {
            PVData pvData = entry.getValue();
            
            if (pvData.getAlarmSeverity() != null && 
                pvData.getAlarmSeverity() != PVData.AlarmSeverity.NO_ALARM) {
                
                Map<String, Object> alarm = new HashMap<>();
                alarm.put("pvName", pvData.getPvName());
                alarm.put("value", pvData.getValue());
                alarm.put("alarmStatus", pvData.getAlarmStatus());
                alarm.put("alarmSeverity", pvData.getAlarmSeverity());
                alarm.put("units", pvData.getUnits());
                alarm.put("alarmLimits", pvData.getAlarmLimits());
                alarm.put("lastUpdate", pvData.getLastUpdate());
                
                alarms.add(alarm);
            }
        }
        
        // Sort by severity (MAJOR, MINOR, INVALID)
        alarms.sort((a, b) -> {
            String severityA = a.get("alarmSeverity").toString();
            String severityB = b.get("alarmSeverity").toString();
            return getSeverityPriority(severityB) - getSeverityPriority(severityA); // Descending
        });
        
        return ResponseEntity.ok(alarms);
    }

    private int getSeverityPriority(String severity) {
        switch (severity) {
            case "MAJOR": return 3;
            case "MINOR": return 2;
            case "INVALID": return 1;
            default: return 0;
        }
    }

    @GetMapping("/alarms/count")
    public ResponseEntity<Map<String, Object>> getAlarmCounts() {
        logger.debug("=== REST API: Get alarm counts ===");
        
        Map<String, Integer> severityCounts = new HashMap<>();
        severityCounts.put("MAJOR", 0);
        severityCounts.put("MINOR", 0);
        severityCounts.put("INVALID", 0);
        
        for (PVData pvData : currentPVData.values()) {
            if (pvData.getAlarmSeverity() != null && 
                pvData.getAlarmSeverity() != PVData.AlarmSeverity.NO_ALARM) {
                
                String severity = pvData.getAlarmSeverity().toString();
                severityCounts.put(severity, severityCounts.getOrDefault(severity, 0) + 1);
            }
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("severityCounts", severityCounts);
        response.put("totalAlarms", severityCounts.values().stream().mapToInt(Integer::intValue).sum());
        response.put("timestamp", LocalDateTime.now());
        
        return ResponseEntity.ok(response);
    }

    // ========== STATISTICS ENDPOINTS ==========

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        logger.debug("=== REST API: Get statistics ===");
        
        Map<String, Object> stats = new HashMap<>();
        
        // Connection statistics
        Map<String, Integer> connectionStats = new HashMap<>();
        for (PVData.ConnectionStatus status : PVData.ConnectionStatus.values()) {
            connectionStats.put(status.toString(), 0);
        }
        
        // Data type statistics
        Map<String, Integer> dataTypeStats = new HashMap<>();
        
        // Alarm statistics
        Map<String, Integer> alarmStats = new HashMap<>();
        for (PVData.AlarmSeverity severity : PVData.AlarmSeverity.values()) {
            alarmStats.put(severity.toString(), 0);
        }
        
        // Process all PVs
        for (PVData pvData : currentPVData.values()) {
            // Connection stats
            String connStatus = pvData.getConnectionStatus().toString();
            connectionStats.put(connStatus, connectionStats.getOrDefault(connStatus, 0) + 1);
            
            // Data type stats
            String dataType = pvData.getDataType() != null ? pvData.getDataType() : "Unknown";
            dataTypeStats.put(dataType, dataTypeStats.getOrDefault(dataType, 0) + 1);
            
            // Alarm stats
            String alarmSeverity = pvData.getAlarmSeverity() != null ? 
                pvData.getAlarmSeverity().toString() : "NO_ALARM";
            alarmStats.put(alarmSeverity, alarmStats.getOrDefault(alarmSeverity, 0) + 1);
        }
        
        stats.put("totalPVs", currentPVData.size());
        stats.put("connectionStatistics", connectionStats);
        stats.put("dataTypeStatistics", dataTypeStats);
        stats.put("alarmStatistics", alarmStats);
        stats.put("timestamp", LocalDateTime.now());
        
        return ResponseEntity.ok(stats);
    }

    // ========== HEALTH CHECK ENDPOINTS ==========

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        logger.debug("=== REST API: Health check ===");
        
        Map<String, Object> health = new HashMap<>();
        
        try {
            // Check if EPICS service is working
            boolean epicsHealthy = epicsService != null;
            
            // Count connected PVs
            long connectedPVs = currentPVData.values().stream()
                .mapToLong(pv -> pv.getConnectionStatus() == PVData.ConnectionStatus.CONNECTED ? 1 : 0)
                .sum();
            
            // Count alarmed PVs
            long alarmedPVs = currentPVData.values().stream()
                .mapToLong(pv -> pv.getAlarmSeverity() != null && 
                               pv.getAlarmSeverity() != PVData.AlarmSeverity.NO_ALARM ? 1 : 0)
                .sum();
            
            health.put("status", epicsHealthy ? "UP" : "DOWN");
            health.put("epicsService", epicsHealthy ? "AVAILABLE" : "UNAVAILABLE");
            health.put("totalPVs", currentPVData.size());
            health.put("connectedPVs", connectedPVs);
            health.put("alarmedPVs", alarmedPVs);
            health.put("timestamp", LocalDateTime.now());
            
            return ResponseEntity.ok(health);
            
        } catch (Exception e) {
            health.put("status", "DOWN");
            health.put("error", e.getMessage());
            health.put("timestamp", LocalDateTime.now());
            
            return ResponseEntity.status(500).body(health);
        }
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> getVersion() {
        Map<String, Object> version = new HashMap<>();
        version.put("application", "EPICS REST API");
        version.put("version", "2.0.0");
        version.put("apiVersion", "v1");
        version.put("features", Arrays.asList(
            "PV Subscription/Unsubscription",
            "Real-time Value Monitoring", 
            "Metadata Extraction",
            "Alarm Monitoring",
            "Bulk Operations",
            "Search and Filter",
            "Statistics",
            "Legacy Compatibility"
        ));
        version.put("timestamp", LocalDateTime.now());
        
        return ResponseEntity.ok(version);
    }

    // ========== UTILITY ENDPOINTS ==========

    @PostMapping("/clear-cache")
    public ResponseEntity<Map<String, Object>> clearCache() {
        logger.info("=== REST API: Clear cache called ===");
        
        int pvDataCount = currentPVData.size();
        int historyCount = pvDataHistory.values().stream().mapToInt(List::size).sum();
        int legacyCount = pvUpdates.values().stream().mapToInt(List::size).sum();
        
        // Clear all caches but keep subscriptions active
        currentPVData.clear();
        pvDataHistory.clear();
        pvUpdates.clear();
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Cache cleared successfully");
        response.put("clearedPVData", pvDataCount);
        response.put("clearedHistoryEntries", historyCount);
        response.put("clearedLegacyEntries", legacyCount);
        response.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/memory-usage")
    public ResponseEntity<Map<String, Object>> getMemoryUsage() {
        logger.debug("=== REST API: Get memory usage ===");
        
        Runtime runtime = Runtime.getRuntime();
        
        Map<String, Object> memory = new HashMap<>();
        memory.put("totalMemory", runtime.totalMemory());
        memory.put("freeMemory", runtime.freeMemory());
        memory.put("usedMemory", runtime.totalMemory() - runtime.freeMemory());
        memory.put("maxMemory", runtime.maxMemory());
        
        // Calculate cache sizes
        int totalPVData = currentPVData.size();
        int totalHistoryEntries = pvDataHistory.values().stream().mapToInt(List::size).sum();
        int totalLegacyEntries = pvUpdates.values().stream().mapToInt(List::size).sum();
        
        memory.put("cacheSizes", Map.of(
            "currentPVData", totalPVData,
            "historyEntries", totalHistoryEntries,
            "legacyEntries", totalLegacyEntries
        ));
        
        memory.put("timestamp", LocalDateTime.now());
        
        return ResponseEntity.ok(memory);
    }
}