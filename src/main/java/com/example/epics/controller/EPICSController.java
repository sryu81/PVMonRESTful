package com.example.epics.controller;

import com.example.epics.service.EPICSService;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/epics")
@CrossOrigin(origins = "*") // Configure as needed
public class EPICSController {
    
    private static final Logger logger = LoggerFactory.getLogger(EPICSController.class);

    @Autowired
    private EPICSService epicsService;
    
    private final ConcurrentHashMap<String, List<EPICSService.PVUpdate>> pvUpdates = new ConcurrentHashMap<>();
    //private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        logger.info("=== EPICSController @PostConstruct called ===");
        epicsService.setController(this);
        logger.info("Controller set in EPICSService");
    }

    @PostMapping("/subscribe/{pvName}")
    public ResponseEntity<Map<String, Object>> subscribeToPV(@PathVariable String pvName) {
         logger.info("=== REST API: Subscribe called for PV: {} ===", pvName);

        try {
            epicsService.subscribeToPV(pvName);
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Subscribed to " + pvName);
            response.put("pvName", pvName);
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
    
    @DeleteMapping("/unsubscribe/{pvName}")
    public ResponseEntity<Map<String, Object>> unsubscribeFromPV(@PathVariable String pvName) {
        try {
            epicsService.unsubscribeFromPV(pvName);
            pvUpdates.remove(pvName); // Clean up stored updates
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
    
    @GetMapping("/pv/{pvName}/latest")
    public ResponseEntity<EPICSService.PVUpdate> getLatestValue(@PathVariable String pvName) {
        List<EPICSService.PVUpdate> updates = pvUpdates.get(pvName);
        if (updates != null && !updates.isEmpty()) {
            return ResponseEntity.ok(updates.get(updates.size() - 1));
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/pv/{pvName}/history")
    public ResponseEntity<List<EPICSService.PVUpdate>> getHistory(
            @PathVariable String pvName,
            @RequestParam(defaultValue = "10") int limit) {
        List<EPICSService.PVUpdate> updates = pvUpdates.get(pvName);
        if (updates != null) {
            int fromIndex = Math.max(0, updates.size() - limit);
            return ResponseEntity.ok(updates.subList(fromIndex, updates.size()));
        }
        return ResponseEntity.ok(new ArrayList<>());
    }
    
    @GetMapping("/pvs")
    public ResponseEntity<Set<String>> getSubscribedPVs() {
        logger.info("=== REST API: Getting subscribed PVs, current count: {} ===", pvUpdates.size());
        logger.info("Stored PVs: {}", pvUpdates.keySet());
        return ResponseEntity.ok(pvUpdates.keySet());
    }
    
    // Store updates when they come in
    public void storeUpdate(EPICSService.PVUpdate update) {
        //logger.info("=== Controller.storeUpdate called: PV={}, value={} ===", update.getPvName(), update.getValue());
        pvUpdates.computeIfAbsent(update.getPvName(), k -> new ArrayList<>()).add(update);
        
        // Keep only last 100 updates per PV to prevent memory issues
        List<EPICSService.PVUpdate> updates = pvUpdates.get(update.getPvName());
        //logger.info("Total updates stored for PV {}: {}", update.getPvName(), updates.size());
        //logger.info("Current stored PVs: {}", pvUpdates.keySet());

        if (updates.size() > 100) {
            updates.remove(0);
        }
    }
}