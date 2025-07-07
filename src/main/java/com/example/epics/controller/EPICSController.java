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
@CrossOrigin(origins = "*")
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
            
            // Merged connection status - use string enum, easier for frontend
            response.put("connectionStatus", pvData.getConnectionStatus().toString());
            response.put("isConnected", pvData.getConnectionStatus() == PVData.ConnectionStatus.CONNECTED);
            
            // Alarm information - fix the missing values
            String alarmSeverity = null;
            String alarmStatus = null;
            boolean hasAlarm = false;
            
            if (pvData.getAlarmSeverity() != null) {
                alarmSeverity = pvData.getAlarmSeverity().toString();
                hasAlarm = pvData.getAlarmSeverity() != PVData.AlarmSeverity.NO_ALARM;
            }
            
            if (pvData.getAlarmStatus() != null) {
                alarmStatus = pvData.getAlarmStatus().toString();
            }
            
            // Try to get alarm info from field data if not available in PVData
            if (alarmSeverity == null || alarmStatus == null) {
                Map<String, Object> fieldData = epicsService.getAllFieldData(pvName);
                if (fieldData != null) {
                    if (alarmSeverity == null) {
                        // Check current alarm severity from EPICS
                        Object sevr = fieldData.get("SEVR");
                        if (sevr != null) {
                            alarmSeverity = sevr.toString();
                            hasAlarm = !alarmSeverity.equals("NO_ALARM") && !alarmSeverity.equals("0");
                        }
                    }
                    if (alarmStatus == null) {
                        Object stat = fieldData.get("STAT");
                        if (stat != null) {
                            alarmStatus = stat.toString();
                        }
                    }
                }
            }
            
            response.put("alarmSeverity", alarmSeverity);
            response.put("alarmStatus", alarmStatus);
            response.put("hasAlarm", hasAlarm);
            
            // Metadata - fix missing values by checking field data
            String description = pvData.getDescription();
            String units = pvData.getUnits();
            Integer precision = pvData.getPrecision();
            
            // Get from field data if not available in PVData
            Map<String, Object> fieldData = epicsService.getAllFieldData(pvName);
            if (fieldData != null) {
                if (description == null || description.trim().isEmpty()) {
                    Object desc = fieldData.get("DESC");
                    if (desc != null && !desc.toString().trim().isEmpty()) {
                        description = desc.toString();
                    }
                }
                
                if (units == null || units.trim().isEmpty()) {
                    Object egu = fieldData.get("EGU");
                    if (egu != null && !egu.toString().trim().isEmpty()) {
                        units = egu.toString();
                    }
                }
                
                if (precision == null) {
                    Object prec = fieldData.get("PREC");
                    if (prec != null) {
                        try {
                            precision = Integer.parseInt(prec.toString());
                        } catch (NumberFormatException e) {
                            // Keep null
                        }
                    }
                }
            }
            
            response.put("description", description);
            response.put("units", units);
            response.put("precision", precision);
            
            // Formatted value for display (using precision if available)
            String formattedValue;
            if (pvData.getValue() instanceof Number && precision != null && precision > 0) {
                Number numValue = (Number) pvData.getValue();
                String format = "%." + precision + "f";
                formattedValue = String.format(format, numValue.doubleValue());
            } else {
                formattedValue = pvData.getValue() != null ? pvData.getValue().toString() : null;
            }
            response.put("formattedValue", formattedValue);
            
            // Display value with units
            String displayValue = formattedValue;
            if (units != null && !units.trim().isEmpty() && displayValue != null) {
                displayValue = displayValue + " " + units;
            }
            response.put("displayValue", displayValue);
            
            // Timestamps
            response.put("timestamp", pvData.getTimestamp());
            response.put("lastUpdate", pvData.getLastUpdate());
            
            // Writability - simplified check
            response.put("isWritable", pvData.getControlLimits() != null);
            
            return ResponseEntity.ok(response);
        }
        
        return ResponseEntity.notFound().build();
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
        logger.debug("Stored PVs: {}\n", currentPVData.keySet());
        return ResponseEntity.ok(currentPVData.keySet());
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

}