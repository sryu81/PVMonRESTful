package com.example.epics.service;

import com.example.epics.controller.EPICSController;

import org.epics.ca.Channel;
import org.epics.ca.Context;
import org.epics.ca.Monitor;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@Lazy
public class EPICSService {
    private static final Logger logger = LoggerFactory.getLogger(EPICSService.class);

    private static Context context;
    private final ConcurrentHashMap<String, Monitor<?>> monitors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Channel<?>> channels = new ConcurrentHashMap<>();

    public EPICSService() {
        logger.info("EPICSService constructor called");
    }
    
    private EPICSController controller; // Replace WebSocketHandler
    
    public void setController(EPICSController controller) {
        this.controller = controller;
    }
    
    private void sendValueUpdate(String pvName, Object value, String status) {
        if (controller != null) {
            PVUpdate update = new PVUpdate(pvName, value, status, System.currentTimeMillis());
            controller.storeUpdate(update); // For Option 1
            // controller.broadcastUpdate(update); // For Option 2
        }
    }
    
    @PostConstruct
    public void initialize() {
        logger.info("EPICS system properties configured");
                    try {
                logger.info("Initializing EPICS Context...");
                this.context = new Context();
                //this.contextInitialized = true;
                logger.info("EPICS Context initialized successfully");
            } catch (Exception e) {
                logger.error("Failed to initialize EPICS Context: {}", e.getMessage(), e);
                throw new RuntimeException("EPICS Context initialization failed", e);
            }
    }
    
    public void subscribeToPV(String pvName) {
        //ensureContextInitialized(); // Create context when first needed

        if (context == null) {
            logger.error("EPICS Context not initialized, cannot subscribe to PV: {}", pvName);
            sendValueUpdate(pvName, null, "error: EPICS Context not available");
            return;
        }

        try {
            logger.info("Subscribing to PV: {}", pvName);

            // Create channel for the PV
            Channel<Double> channel = context.createChannel(pvName, Double.class);
            channels.put(pvName, channel);
            
            // Connect to the channel
            channel.connectAsync().get(5, TimeUnit.SECONDS);
            logger.info("Connected to PV: {}", pvName);

            
            // Get initial value
            Double initialValue = channel.getAsync().get(2, TimeUnit.SECONDS);
            sendValueUpdate(pvName, initialValue, "connected");
            logger.info("Got initial value for PV {}: {}", pvName, initialValue);

            // Set up monitor
            Consumer<Double> valueConsumer = value -> {
                logger.debug("Value update for PV {}: {}", pvName, value);
                sendValueUpdate(pvName, value, "updated");
            };
            
            Monitor<Double> monitor = channel.addValueMonitor(valueConsumer);
            monitors.put(pvName, monitor);
            logger.info("Monitor set up for PV: {}", pvName);
            
        } catch (Exception e) {
            logger.error("Error subscribing to PV: {}", pvName, e);
            sendValueUpdate(pvName, null, "error: " + e.getMessage());
        }
    }
    
    public void unsubscribeFromPV(String pvName) {
        Monitor<?> monitor = monitors.remove(pvName);
        if (monitor != null) {
            try {
                monitor.close();
                logger.info("Unsubscribing to PV: {}", pvName);
            } catch (Exception e){
                logger.error("Error closing monitor for PV: {}", pvName, e);
            }
        }
        
        Channel<?> channel = channels.remove(pvName);
        if (channel != null) {
            try {
                channel.close();
                logger.info("Closed to PV: {}", pvName);
            } catch (Exception e){
                logger.error("Error closing channel for PV: {}", pvName, e);
            }
        }
    }
    
    @PreDestroy
    public void cleanup() {
        System.out.println("Cleaning up EPICS Service...");
        monitors.values().forEach(monitor -> {
            try {
                monitor.close();
            } catch (Exception e) {
                System.err.println("Error closing monitor: " + e.getMessage());
            }
        });
        
        channels.values().forEach(channel -> {
            try {
                channel.close();
            } catch (Exception e) {
                System.err.println("Error closing channel: " + e.getMessage());
            }
        });
        
        if (context != null) {
            try {
                context.close();
            } catch (Exception e) {
                System.err.println("Error closing context: " + e.getMessage());
            }
        }
        
        System.out.println("EPICS Service cleanup completed.");
    }
    
    // Data class for PV updates
    public static class PVUpdate {
        private String pvName;
        private Object value;
        private String status;
        private long timestamp;
        
        public PVUpdate(String pvName, Object value, String status, long timestamp) {
            this.pvName = pvName;
            this.value = value;
            this.status = status;
            this.timestamp = timestamp;
        }
        
        // Getters and setters
        public String getPvName() { return pvName; }
        public void setPvName(String pvName) { this.pvName = pvName; }
        
        public Object getValue() { return value; }
        public void setValue(Object value) { this.value = value; }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
}