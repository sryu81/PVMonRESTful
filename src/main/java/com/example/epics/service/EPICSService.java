package com.example.epics.service;

import com.example.epics.controller.EPICSController;
import com.example.epics.model.PVData;

import org.epics.ca.Channel;
import org.epics.ca.Context;
import org.epics.ca.Monitor;
import org.epics.ca.data.*;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final ConcurrentHashMap<String, PVData> pvDataCache = new ConcurrentHashMap<>();
    
    // Store field data for each PV
    private final ConcurrentHashMap<String, Map<String, Object>> pvFieldData = new ConcurrentHashMap<>();

    // Common EPICS fields to monitor
    private static final String[] ALARM_FIELDS = {
        "HIHI", "HIGH", "LOW", "LOLO",           // Alarm limits
        "HHSV", "HSV", "LSV", "LLSV"             // Alarm severities
    };
    
    private static final String[] STATE_FIELDS = {
        "ZNAM", "ONAM",                          // Binary state names
        "ZRST", "ONST", "TWST", "THST", "FRST",  // Multi-state names (0-4)
        "FVST", "SXST", "SVST", "EIST", "NIST",  // Multi-state names (5-9)
        "TEST", "ELST", "TVST", "TTST", "FTST",  // Multi-state names (10-14)
        "FFST"                                   // Multi-state name (15)
    };
    
    private static final String[] METADATA_FIELDS = {
        "EGU",   // Engineering units
        "PREC",  // Precision
        "DESC",  // Description
        "DRVH",  // Drive high limit
        "DRVL",  // Drive low limit
        "HOPR",  // High operating range
        "LOPR"   // Low operating range
    };

    public EPICSService() {
        logger.info("EPICSService constructor called");
    }
    
    private EPICSController controller;
    
    public void setController(EPICSController controller) {
        this.controller = controller;
    }
    
    private void sendPVDataUpdate(PVData pvData) {
        if (controller != null) {
            controller.storePVData(pvData);
        }
    }
    
    @PostConstruct
    public void initialize() {
        logger.info("EPICS system properties configured");
        try {
            logger.info("Initializing EPICS Context...");
            this.context = new Context();
            logger.info("EPICS Context initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize EPICS Context: {}", e.getMessage(), e);
            throw new RuntimeException("EPICS Context initialization failed", e);
        }
    }
    
    public void subscribeToPV(String pvName) {
        if (context == null) {
            logger.error("EPICS Context not initialized, cannot subscribe to PV: {}", pvName);
            PVData errorData = new PVData(pvName);
            errorData.setConnectionStatus(PVData.ConnectionStatus.ERROR);
            errorData.setDescription("EPICS Context not available");
            sendPVDataUpdate(errorData);
            return;
        }

        try {
            logger.info("Subscribing to PV: {}", pvName);

            // Create PVData object for this PV
            PVData pvData = new PVData(pvName);
            pvData.setConnectionStatus(PVData.ConnectionStatus.CONNECTING);
            pvDataCache.put(pvName, pvData);
            
            // Initialize field data storage
            pvFieldData.put(pvName, new ConcurrentHashMap<>());

            // Subscribe to the main PV (VAL field) FIRST - this is fast
            subscribeToMainPV(pvName);
            
            // Subscribe to additional fields ASYNCHRONOUSLY in background
            // This won't block the main subscription response
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(100); // Small delay to let main PV connect first
                    subscribeToAdditionalFieldsAsync(pvName);
                } catch (Exception e) {
                    logger.warn("Error in async field subscription for {}: {}", pvName, e.getMessage());
                }
            });
            
        } catch (Exception e) {
            logger.error("Error subscribing to PV: {}", pvName, e);
            PVData errorData = pvDataCache.getOrDefault(pvName, new PVData(pvName));
            errorData.setConnectionStatus(PVData.ConnectionStatus.ERROR);
            errorData.setDescription("Error: " + e.getMessage());
            sendPVDataUpdate(errorData);
        }
    }

    /**
     * Subscribe to additional EPICS fields asynchronously with shorter timeouts
     */
    private void subscribeToAdditionalFieldsAsync(String pvName) {
        logger.debug("Starting async field subscription for PV: {}", pvName);
        
        // Use shorter timeouts and parallel execution for fields
        List<CompletableFuture<Void>> fieldFutures = new ArrayList<>();
        
        // Subscribe to most important fields first (alarm limits)
        String[] priorityFields = {"HIHI", "HIGH", "LOW", "LOLO", "EGU", "PREC", "DESC"};
        for (String field : priorityFields) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> 
                subscribeToFieldWithTimeout(pvName, field, 1000) // 1 second timeout
            );
            fieldFutures.add(future);
        }
        
        // Wait for priority fields to complete (max 2 seconds total)
        try {
            CompletableFuture.allOf(fieldFutures.toArray(new CompletableFuture[0]))
                .get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.debug("Priority field subscription timed out for PV: {}", pvName);
        } catch (Exception e) {
            logger.debug("Error in priority field subscription for PV: {}", pvName);
        }
        
        // Subscribe to secondary fields (state names, alarm severities) with even shorter timeout
        fieldFutures.clear();
        String[] secondaryFields = {"HHSV", "HSV", "LSV", "LLSV", "ZNAM", "ONAM", "DRVH", "DRVL"};
        for (String field : secondaryFields) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> 
                subscribeToFieldWithTimeout(pvName, field, 500) // 500ms timeout
            );
            fieldFutures.add(future);
        }
        
        // Wait for secondary fields (max 1 second total)
        try {
            CompletableFuture.allOf(fieldFutures.toArray(new CompletableFuture[0]))
                .get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("Secondary field subscription completed with some timeouts for PV: {}", pvName);
        }
        
        // Subscribe to multi-state fields only if we detected it's an enum PV
        if (shouldSubscribeToMultiStateFields(pvName)) {
            subscribeToMultiStateFields(pvName);
        }
        
        logger.debug("Completed async field subscription for PV: {}", pvName);
    }

    /**
     * Subscribe to a field with configurable timeout
     */
    private void subscribeToFieldWithTimeout(String basePvName, String fieldName, int timeoutMs) {
        String fieldPvName = basePvName + "." + fieldName;
        
        try {
            // Try String first (most fields are strings)
            Channel<String> fieldChannel = context.createChannel(fieldPvName, String.class);
            
            // Connect with short timeout
            fieldChannel.connectAsync().get(timeoutMs, TimeUnit.MILLISECONDS);
            
            // Get initial value with short timeout
            String initialValue = fieldChannel.getAsync().get(timeoutMs / 2, TimeUnit.MILLISECONDS);
            storeFieldValue(basePvName, fieldName, initialValue);
            
            // Set up monitor
            Consumer<String> fieldConsumer = value -> {
                logger.debug("Field update for {}.{}: {}", basePvName, fieldName, value);
                storeFieldValue(basePvName, fieldName, value);
                updatePVDataFromFields(basePvName);
            };
            
            Monitor<String> fieldMonitor = fieldChannel.addValueMonitor(fieldConsumer);
            monitors.put(fieldPvName, fieldMonitor);
            channels.put(fieldPvName, fieldChannel);
            
            logger.debug("Subscribed to field: {} ({}ms)", fieldPvName, timeoutMs);
            
        } catch (TimeoutException e) {
            logger.debug("Timeout subscribing to field: {} ({}ms)", fieldPvName, timeoutMs);
        } catch (Exception e) {
            // Try as Double for numeric fields
            try {
                Channel<Double> fieldChannel = context.createChannel(fieldPvName, Double.class);
                fieldChannel.connectAsync().get(timeoutMs, TimeUnit.MILLISECONDS);
                
                Double initialValue = fieldChannel.getAsync().get(timeoutMs / 2, TimeUnit.MILLISECONDS);
                storeFieldValue(basePvName, fieldName, initialValue);
                
                Consumer<Double> fieldConsumer = value -> {
                    logger.debug("Field update for {}.{}: {}", basePvName, fieldName, value);
                    storeFieldValue(basePvName, fieldName, value);
                    updatePVDataFromFields(basePvName);
                };
                
                Monitor<Double> fieldMonitor = fieldChannel.addValueMonitor(fieldConsumer);
                monitors.put(fieldPvName, fieldMonitor);
                channels.put(fieldPvName, fieldChannel);
                
                logger.debug("Subscribed to numeric field: {} ({}ms)", fieldPvName, timeoutMs);
                
            } catch (Exception e2) {
                logger.debug("Could not subscribe to field {} within {}ms: {}", fieldPvName, timeoutMs, e2.getMessage());
            }
        }
    }

    /**
     * Check if we should subscribe to multi-state fields based on current value
     */
    private boolean shouldSubscribeToMultiStateFields(String pvName) {
        PVData pvData = pvDataCache.get(pvName);
        if (pvData == null) return false;
        
        // Check if it's an integer/enum type
        Object value = pvData.getValue();
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return true;
        }
        
        // Check if we already found ZNAM or ONAM
        Map<String, Object> fieldData = pvFieldData.get(pvName);
        if (fieldData != null && (fieldData.containsKey("ZNAM") || fieldData.containsKey("ONAM"))) {
            return true;
        }
        
        return false;
    }


    /**
     * Subscribe to multi-state fields (ZRST, ONST, etc.) with very short timeout
     */
    private void subscribeToMultiStateFields(String pvName) {
        String[] multiStateFields = {"ZRST", "ONST", "TWST", "THST", "FRST", 
                                    "FVST", "SXST", "SVST", "EIST", "NIST", 
                                    "TEST", "ELST", "TVST", "TTST", "FTST", "FFST"};
        
        for (String field : multiStateFields) {
            // Use very short timeout for multi-state fields (many won't exist)
            subscribeToFieldWithTimeout(pvName, field, 200); // 200ms timeout
        }
    }

    /**
     * Subscribe to the main PV with faster timeout
     */
    private void subscribeToMainPV(String pvName) throws Exception {
        // Try different channel types with shorter timeouts
        try {
            subscribeToTypedChannelFast(pvName, Double.class);
            return;
        } catch (Exception e) {
            logger.debug("Failed to subscribe as Double for PV {}: {}", pvName, e.getMessage());
        }
        
        try {
            subscribeToTypedChannelFast(pvName, String.class);
            return;
        } catch (Exception e) {
            logger.debug("Failed to subscribe as String for PV {}: {}", pvName, e.getMessage());
        }
        
        try {
            subscribeToTypedChannelFast(pvName, Integer.class);
            return;
        } catch (Exception e) {
            logger.debug("Failed to subscribe as Integer for PV {}: {}", pvName, e.getMessage());
        }
        
        // Fallback to Object type
        subscribeToTypedChannelFast(pvName, Object.class);
    }

    /**
     * Subscribe to a channel with faster timeouts
     */
    private <T> void subscribeToTypedChannelFast(String pvName, Class<T> type) throws Exception {
        Channel<T> channel = context.createChannel(pvName, type);
        channels.put(pvName, channel);
        
        // Connect with shorter timeout (2 seconds instead of 5)
        channel.connectAsync().get(2, TimeUnit.SECONDS);
        logger.info("Connected to PV: {} with type: {}", pvName, type.getSimpleName());

        // Get initial value with shorter timeout (1 second instead of 2)
        T initialValue = channel.getAsync().get(1, TimeUnit.SECONDS);
        updatePVDataFromTypedValue(pvName, initialValue, type);
        
        logger.info("Got initial value for PV {}: {}", pvName, initialValue);

        // Set up monitor for value changes
        Consumer<T> valueConsumer = value -> {
            logger.debug("Value update for PV {}: {}", pvName, value);
            updatePVDataFromTypedValue(pvName, value, type);
        };
        
        Monitor<T> monitor = channel.addValueMonitor(valueConsumer);
        monitors.put(pvName, monitor);
        logger.info("Monitor set up for PV: {}", pvName);
    }
    /**
     * Store field value in the field data cache
     */
    private void storeFieldValue(String basePvName, String fieldName, Object value) {
        Map<String, Object> fieldData = pvFieldData.get(basePvName);
        if (fieldData != null) {
            fieldData.put(fieldName, value);
            logger.debug("Stored field {}.{} = {}", basePvName, fieldName, value);
        }
    }
    
    /**
     * Update PVData with information from field data
     */
    private void updatePVDataFromFields(String pvName) {
        PVData pvData = pvDataCache.get(pvName);
        if (pvData == null) {
            return;
        }
        
        Map<String, Object> fieldData = pvFieldData.get(pvName);
        if (fieldData == null) {
            return;
        }
        
        try {
            // Update alarm limits from fields
            updateAlarmLimitsFromFields(pvData, fieldData);
            
            // Update control limits from fields
            updateControlLimitsFromFields(pvData, fieldData);
            
            // Update display limits from fields
            updateDisplayLimitsFromFields(pvData, fieldData);
            
            // Update metadata from fields
            updateMetadataFromFields(pvData, fieldData);
            
            // Update state information
            updateStateInformationFromFields(pvData, fieldData);
            
            // Store the field data in PVData for frontend access
            pvData.setFieldData(new HashMap<>(fieldData));
            
            pvData.setLastUpdate(LocalDateTime.now());
            sendPVDataUpdate(pvData);
            
        } catch (Exception e) {
            logger.warn("Error updating PV data from fields for {}: {}", pvName, e.getMessage());
        }
    }
    
    /**
     * Update alarm limits from EPICS fields
     */
    private void updateAlarmLimitsFromFields(PVData pvData, Map<String, Object> fieldData) {
        try {
            Double hihi = parseDoubleField(fieldData.get("HIHI"));
            Double high = parseDoubleField(fieldData.get("HIGH"));
            Double low = parseDoubleField(fieldData.get("LOW"));
            Double lolo = parseDoubleField(fieldData.get("LOLO"));
            
            if (hihi != null || high != null || low != null || lolo != null) {
                PVData.AlarmLimits alarmLimits = new PVData.AlarmLimits(lolo, hihi, low, high);
                pvData.setAlarmLimits(alarmLimits);
                logger.debug("Updated alarm limits from fields for PV {}: LOLO={}, LOW={}, HIGH={}, HIHI={}", 
                            pvData.getPvName(), lolo, low, high, hihi);
            }
            
            // Store alarm severities
            Map<String, Object> alarmSeverities = new HashMap<>();
            alarmSeverities.put("HHSV", fieldData.get("HHSV"));
            alarmSeverities.put("HSV", fieldData.get("HSV"));
            alarmSeverities.put("LSV", fieldData.get("LSV"));
            alarmSeverities.put("LLSV", fieldData.get("LLSV"));
            pvData.setAlarmSeverities(alarmSeverities);
            
        } catch (Exception e) {
            logger.debug("Could not update alarm limits from fields for PV {}: {}", pvData.getPvName(), e.getMessage());
        }
    }
    
    /**
     * Update control limits from EPICS fields
     */
    private void updateControlLimitsFromFields(PVData pvData, Map<String, Object> fieldData) {
        try {
            Double drvh = parseDoubleField(fieldData.get("DRVH"));
            Double drvl = parseDoubleField(fieldData.get("DRVL"));
            
            if (drvh != null || drvl != null) {
                PVData.ControlLimits controlLimits = new PVData.ControlLimits(drvl, drvh);
                pvData.setControlLimits(controlLimits);
                logger.debug("Updated control limits from fields for PV {}: DRVL={}, DRVH={}", 
                            pvData.getPvName(), drvl, drvh);
            }
            
        } catch (Exception e) {
            logger.debug("Could not update control limits from fields for PV {}: {}", pvData.getPvName(), e.getMessage());
        }
    }
    
    /**
     * Update display limits from EPICS fields
     */
    private void updateDisplayLimitsFromFields(PVData pvData, Map<String, Object> fieldData) {
        try {
            Double hopr = parseDoubleField(fieldData.get("HOPR"));
            Double lopr = parseDoubleField(fieldData.get("LOPR"));
            Double high = parseDoubleField(fieldData.get("HIGH"));
            Double low = parseDoubleField(fieldData.get("LOW"));
            
            if (hopr != null || lopr != null || high != null || low != null) {
                PVData.DisplayLimits displayLimits = new PVData.DisplayLimits(lopr, hopr, low, high);
                pvData.setDisplayLimits(displayLimits);
                logger.debug("Updated display limits from fields for PV {}: LOPR={}, HOPR={}, LOW={}, HIGH={}", 
                            pvData.getPvName(), lopr, hopr, low, high);
            }
            
        } catch (Exception e) {
            logger.debug("Could not update display limits from fields for PV {}: {}", pvData.getPvName(), e.getMessage());
        }
    }
    
    /**
     * Update metadata from EPICS fields
     */
    private void updateMetadataFromFields(PVData pvData, Map<String, Object> fieldData) {
        try {
            // Engineering units
            Object egu = fieldData.get("EGU");
            if (egu != null) {
                pvData.setUnits(egu.toString());
            }
            
            // Precision
            Object prec = fieldData.get("PREC");
            if (prec != null) {
try {
                    pvData.setPrecision(Integer.parseInt(prec.toString()));
                } catch (NumberFormatException e) {
                    logger.debug("Could not parse precision value: {}", prec);
                }
            }
            
            // Description
            Object desc = fieldData.get("DESC");
            if (desc != null) {
                pvData.setDescription(desc.toString());
            }
            
            logger.debug("Updated metadata from fields for PV {}: EGU={}, PREC={}, DESC={}", 
                        pvData.getPvName(), egu, prec, desc);
            
        } catch (Exception e) {
            logger.debug("Could not update metadata from fields for PV {}: {}", pvData.getPvName(), e.getMessage());
        }
    }
    
    /**
     * Update state information from EPICS fields
     */
    private void updateStateInformationFromFields(PVData pvData, Map<String, Object> fieldData) {
        try {
            Map<String, String> stateNames = new HashMap<>();
            
            // Binary states
            Object znam = fieldData.get("ZNAM");
            Object onam = fieldData.get("ONAM");
            if (znam != null) stateNames.put("0", znam.toString());
            if (onam != null) stateNames.put("1", onam.toString());
            
            // Multi-states (0-15)
            String[] multiStateFields = {"ZRST", "ONST", "TWST", "THST", "FRST", 
                                        "FVST", "SXST", "SVST", "EIST", "NIST", 
                                        "TEST", "ELST", "TVST", "TTST", "FTST", "FFST"};
            
            for (int i = 0; i < multiStateFields.length; i++) {
                Object stateValue = fieldData.get(multiStateFields[i]);
                if (stateValue != null && !stateValue.toString().trim().isEmpty()) {
                    stateNames.put(String.valueOf(i), stateValue.toString());
                }
            }
            
            if (!stateNames.isEmpty()) {
                pvData.setStateNames(stateNames);
                logger.debug("Updated state names for PV {}: {}", pvData.getPvName(), stateNames);
            }
            
        } catch (Exception e) {
            logger.debug("Could not update state information from fields for PV {}: {}", pvData.getPvName(), e.getMessage());
        }
    }
    
    /**
     * Parse a field value as Double, handling various formats
     */
    private Double parseDoubleField(Object fieldValue) {
        if (fieldValue == null) {
            return null;
        }
        
        if (fieldValue instanceof Number) {
            double value = ((Number) fieldValue).doubleValue();
            // Check for invalid/unset values (EPICS often uses large numbers for unset limits)
            if (Double.isNaN(value) || Double.isInfinite(value) || 
                Math.abs(value) > 1e30) {
                return null;
            }
            return value;
        }
        
        if (fieldValue instanceof String) {
            String str = ((String) fieldValue).trim();
            if (str.isEmpty() || str.equalsIgnoreCase("nan") || str.equalsIgnoreCase("inf")) {
                return null;
            }
            try {
                double value = Double.parseDouble(str);
                if (Double.isNaN(value) || Double.isInfinite(value) || 
                    Math.abs(value) > 1e30) {
                    return null;
                }
                return value;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        return null;
    }
    

    
    /**
     * Update PV data from typed channel value with proper inheritance handling
     */
    private <T> void updatePVDataFromTypedValue(String pvName, T channelValue, Class<T> expectedType) {
        PVData pvData = pvDataCache.getOrDefault(pvName, new PVData(pvName));
        
        try {
            // Update basic connection status
            pvData.setConnectionStatus(PVData.ConnectionStatus.CONNECTED);
            pvData.setLastUpdate(LocalDateTime.now());
            
            if (channelValue == null) {
                pvData.setValue(null);
                pvData.setDataType("NULL");
                sendPVDataUpdate(pvData);
                return;
            }

            // Extract the actual value (T)
            Object actualValue = extractActualValue(channelValue);
            pvData.setValue(actualValue);
            pvData.setDataType(determineDataType(channelValue, actualValue));
            
            // Handle timestamped values
            if (channelValue instanceof Timestamped) {
                handleTimestampedValue(pvData, channelValue);
            } else {
                pvData.setTimestamp(LocalDateTime.now());
            }

            // Handle metadata in order of inheritance (most specific first)
            // Control has ALL metadata (control + graphic + alarm)
            if (channelValue instanceof Control) {
                handleControlData(pvData, (Control<?, ?>) channelValue);
            } 
            // Graphic has graphic + alarm metadata  
            else if (channelValue instanceof Graphic) {
                handleGraphicData(pvData, (Graphic<?, ?>) channelValue);
            }
            // Alarm has only alarm metadata
            else if (channelValue instanceof Alarm) {
                handleAlarmData(pvData, (Alarm<?>) channelValue);
            }

            // Store updated data
            pvDataCache.put(pvName, pvData);
            
            // Update with field data (this will trigger another update)
            updatePVDataFromFields(pvName);
            
        } catch (Exception e) {
            logger.error("Error updating PV data for {}: {}", pvName, e.getMessage());
            pvData.setConnectionStatus(PVData.ConnectionStatus.ERROR);
            pvData.setDescription("Update error: " + e.getMessage());
            sendPVDataUpdate(pvData);
        }
    }
    
    /**
     * Get field value for a specific PV and field
     */
    public Object getFieldValue(String pvName, String fieldName) {
        Map<String, Object> fieldData = pvFieldData.get(pvName);
        if (fieldData != null) {
            return fieldData.get(fieldName);
        }
        return null;
    }
    
    /**
     * Get all field data for a PV
     */
    public Map<String, Object> getAllFieldData(String pvName) {
        Map<String, Object> fieldData = pvFieldData.get(pvName);
        return fieldData != null ? new HashMap<>(fieldData) : new HashMap<>();
    }
    
    /**
     * Set a field value for a PV
     */
    public void setFieldValue(String pvName, String fieldName, Object value) throws Exception {
        String fieldPvName = pvName + "." + fieldName;
        Channel<?> channel = channels.get(fieldPvName);
        
        if (channel == null) {
            throw new Exception("Field not subscribed: " + fieldPvName);
        }
        
        try {
            setPVValueTypeSafe(channel, value);
            logger.info("Set field {}.{} to value: {}", pvName, fieldName, value);
        } catch (Exception e) {
            logger.error("Error setting field {}.{} to value {}: {}", pvName, fieldName, value, e.getMessage());
            throw new Exception("Failed to set field value: " + e.getMessage());
        }
    }
    
    // [Continue with all the existing methods from the previous implementation...]
    
    /**
     * Handle Control data (includes ALL metadata: control + graphic + alarm)
     */
    private void handleControlData(PVData pvData, Control<?, ?> control) {
        try {
            // Debug the control object
            if (logger.isDebugEnabled()) {
                debugGenericObject(control, "Control");
            }
            
            // 1. Handle Control-specific metadata (control limits)
            handleControlLimits(pvData, control);
            
            // 2. Handle Graphic metadata (since Control extends Graphic)
            handleGraphicMetadata(pvData, control);
            
            // 3. Handle Alarm metadata (since Graphic extends Alarm)
            handleAlarmMetadata(pvData, control);
            
            logger.debug("Extracted complete Control metadata for PV: {}", pvData.getPvName());
            
        } catch (Exception e) {
            logger.warn("Error extracting control data for PV {}: {}", pvData.getPvName(), e.getMessage());
        }
    }

    /**
     * Handle Graphic data (includes graphic + alarm metadata)
     */
    private void handleGraphicData(PVData pvData, Graphic<?, ?> graphic) {
        try {
            // Debug the graphic object
            if (logger.isDebugEnabled()) {
                debugGenericObject(graphic, "Graphic");
            }
            
            // 1. Handle Graphic metadata
            handleGraphicMetadata(pvData, graphic);
            
            // 2. Handle Alarm metadata (since Graphic extends Alarm)
            handleAlarmMetadata(pvData, graphic);
            
            logger.debug("Extracted Graphic metadata for PV: {}", pvData.getPvName());
            
        } catch (Exception e) {
            logger.warn("Error extracting graphic data for PV {}: {}", pvData.getPvName(), e.getMessage());
        }
    }

    /**
     * Handle Alarm data (only alarm metadata)
     */
    private void handleAlarmData(PVData pvData, Alarm<?> alarm) {
        try {
            handleAlarmMetadata(pvData, alarm);
            logger.debug("Extracted Alarm metadata for PV: {}", pvData.getPvName());
            
        } catch (Exception e) {
            logger.warn("Error extracting alarm data for PV {}: {}", pvData.getPvName(), e.getMessage());
        }
    }

    /**
     * Extract Control-specific limits using reflection (works with generics)
     */
    private void handleControlLimits(PVData pvData, Control<?, ?> control) {
        try {
            Double lowerLimit = reflectiveGetLimitValue(control, "getLowerControlLimit");
            Double upperLimit = reflectiveGetLimitValue(control, "getUpperControlLimit");
            
            if (lowerLimit != null || upperLimit != null) {
                PVData.ControlLimits controlLimits = new PVData.ControlLimits(lowerLimit, upperLimit);
                pvData.setControlLimits(controlLimits);
                logger.debug("Set control limits for PV {}: [{}, {}]", 
                            pvData.getPvName(), lowerLimit, upperLimit);
            }
            
        } catch (Exception e) {
            logger.debug("Could not extract control limits for PV {}: {}", pvData.getPvName(), e.getMessage());
        }
    }

    /**
     * Extract Graphic metadata using reflection (works with generics)
     */
    private void handleGraphicMetadata(PVData pvData, Graphic<?, ?> graphic) {
        try {
            // Basic graphic info using reflection
            String units = reflectiveGetStringValue(graphic, "getUnits");
            Integer precision = reflectiveGetIntegerValue(graphic, "getPrecision");
            
            // Only set if not already set by field data (field data takes precedence)
            if (pvData.getUnits() == null && units != null) {
                pvData.setUnits(units);
            }
            if (pvData.getPrecision() == null && precision != null) {
                pvData.setPrecision(precision);
            }
            
            // Get all limits using reflection (field data will override these)
            Double lowerDisplay = reflectiveGetLimitValue(graphic, "getLowerDisplayLimit");
            Double upperDisplay = reflectiveGetLimitValue(graphic, "getUpperDisplayLimit");
            Double lowerWarning = reflectiveGetLimitValue(graphic, "getLowerWarningLimit");
            Double upperWarning = reflectiveGetLimitValue(graphic, "getUpperWarningLimit");
            Double lowerAlarm = reflectiveGetLimitValue(graphic, "getLowerAlarmLimit");
            Double upperAlarm = reflectiveGetLimitValue(graphic, "getUpperAlarmLimit");
            
            // Set display limits if not already set by field data
            if (pvData.getDisplayLimits() == null && 
                (lowerDisplay != null || upperDisplay != null || 
                 lowerWarning != null || upperWarning != null)) {
                
                PVData.DisplayLimits displayLimits = new PVData.DisplayLimits(
                    lowerDisplay, upperDisplay, lowerWarning, upperWarning
                );
                pvData.setDisplayLimits(displayLimits);
            }
            
            // Set alarm limits if not already set by field data
            if (pvData.getAlarmLimits() == null &&
                (lowerAlarm != null || upperAlarm != null || 
                 lowerWarning != null || upperWarning != null)) {
                
                PVData.AlarmLimits alarmLimits = new PVData.AlarmLimits(
                    lowerAlarm, upperAlarm, lowerWarning, upperWarning
                );
                pvData.setAlarmLimits(alarmLimits);
            }
            
            logger.debug("Set graphic metadata for PV {}: units={}, precision={}", 
                        pvData.getPvName(), units, precision);
            
        } catch (Exception e) {
            logger.debug("Could not extract graphic metadata for PV {}: {}", pvData.getPvName(), e.getMessage());
        }
    }

    /**
     * Extract Alarm metadata using reflection (works with generics)
     */
    private void handleAlarmMetadata(PVData pvData, Alarm<?> alarm) {
        try {
            // Get alarm status and severity using reflection
            Object alarmStatus = reflectiveInvokeMethod(alarm, "getAlarmStatus");
            Object alarmSeverity = reflectiveInvokeMethod(alarm, "getAlarmSeverity");
            
            if (alarmStatus != null) {
                try {
                    pvData.setAlarmStatus(PVData.AlarmStatus.valueOf(alarmStatus.toString()));
                } catch (IllegalArgumentException e) {
                    logger.debug("Unknown alarm status: {}", alarmStatus);
                    pvData.setAlarmStatus(PVData.AlarmStatus.NO_ALARM);
                }
            }
            
            if (alarmSeverity != null) {
                try {
                    pvData.setAlarmSeverity(PVData.AlarmSeverity.valueOf(alarmSeverity.toString()));
                } catch (IllegalArgumentException e) {
logger.debug("Unknown alarm severity: {}", alarmSeverity);
                    pvData.setAlarmSeverity(PVData.AlarmSeverity.NO_ALARM);
                }
            }
            
            logger.debug("Set alarm metadata for PV {}: status={}, severity={}", 
                        pvData.getPvName(), alarmStatus, alarmSeverity);
            
        } catch (Exception e) {
            logger.debug("Could not extract alarm metadata for PV {}: {}", pvData.getPvName(), e.getMessage());
        }
    }

    /**
     * Handle timestamped values with proper type checking
     */
    private void handleTimestampedValue(PVData pvData, Object channelValue) {
        if (channelValue instanceof Timestamped) {
            Timestamped<?> timestamped = (Timestamped<?>) channelValue;
            
            // Debug the timestamped object (only in debug mode)
            if (logger.isDebugEnabled()) {
                debugTimestampedObject(timestamped);
            }
            
            try {
                // Extract and set the EPICS timestamp
                pvData.setTimestamp(LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(timestamped.getSeconds(), timestamped.getNanos()),
                    ZoneId.systemDefault()
                ));
                
                logger.debug("Set EPICS timestamp for PV {}: {}", 
                            pvData.getPvName(), pvData.getTimestamp());
                
            } catch (Exception e) {
                logger.warn("Could not extract timestamp from timestamped value for PV {}: {}", 
                           pvData.getPvName(), e.getMessage());
                // Fallback to current time
                pvData.setTimestamp(LocalDateTime.now());
            }
        }
    }

    /**
     * Extract actual value (T) from any of the data types
     */
    private Object extractActualValue(Object channelValue) {
        if (channelValue == null) {
            return null;
        }
        
        // Handle timestamped values first
        if (channelValue instanceof Timestamped) {
            return extractValueFromTimestamped((Timestamped<?>) channelValue);
        }
        
        // For Control, Graphic, Alarm - they all should have getValue() method
        if (channelValue instanceof Control || 
            channelValue instanceof Graphic || 
            channelValue instanceof Alarm) {
            
            return extractValueUsingReflection(channelValue);
        }
        
        // For simple types
        if (channelValue instanceof Number || 
            channelValue instanceof String || 
            channelValue instanceof Boolean) {
            return channelValue;
        }
        
        // For arrays
        if (channelValue.getClass().isArray()) {
            return channelValue;
        }
        
        // Fallback
        return channelValue.toString();
    }

    /**
     * Extract value from timestamped object with proper error handling
     */
    private Object extractValueFromTimestamped(Timestamped<?> timestamped) {
        if (timestamped == null) {
            return null;
        }
        
        try {
            // Method 1: Try direct getValue() method (most common)
            java.lang.reflect.Method getValueMethod = timestamped.getClass().getMethod("getValue");
            Object value = getValueMethod.invoke(timestamped);
            
            logger.debug("Extracted value from timestamped object: {} (type: {})", 
                        value, value != null ? value.getClass().getSimpleName() : "null");
            return value;
            
        } catch (NoSuchMethodException e) {
            logger.debug("No getValue() method found for timestamped class: {}", 
                        timestamped.getClass().getSimpleName());
        } catch (Exception e) {
            logger.debug("Error invoking getValue() on timestamped object: {}", e.getMessage());
        }
        
        try {
            // Method 2: Try to access value field directly
            java.lang.reflect.Field valueField = timestamped.getClass().getDeclaredField("value");
            valueField.setAccessible(true);
            Object value = valueField.get(timestamped);
            
            logger.debug("Extracted value from timestamped field: {} (type: {})", 
                        value, value != null ? value.getClass().getSimpleName() : "null");
            return value;
            
        } catch (NoSuchFieldException e) {
            logger.debug("No 'value' field found for timestamped class: {}", 
                        timestamped.getClass().getSimpleName());
        } catch (Exception e) {
            logger.debug("Error accessing value field on timestamped object: {}", e.getMessage());
        }
        
        try {
            // Method 3: Try common timestamped interface methods
            return extractValueByTimestampedType(timestamped);
            
        } catch (Exception e) {
            logger.debug("Error extracting value by timestamped type: {}", e.getMessage());
        }
        
        // Method 4: Fallback to string representation
        logger.warn("Could not extract value from timestamped object for class {}, using toString()", 
                   timestamped.getClass().getSimpleName());
        return timestamped.toString();
    }

    /**
     * Try to extract value based on common timestamped type patterns
     */
    private Object extractValueByTimestampedType(Timestamped<?> timestamped) {
        //String className = timestamped.getClass().getSimpleName().toLowerCase();
        
        // Try different method names based on class name patterns
        String[] possibleMethods = {
            "getValue",
            "getDoubleValue", 
            "getFloatValue",
            "getIntValue",
            "getStringValue",
            "getShortValue",
            "getByteValue",
            "value"
        };
        
        for (String methodName : possibleMethods) {
            try {
                java.lang.reflect.Method method = timestamped.getClass().getMethod(methodName);
                Object result = method.invoke(timestamped);
                
                if (result != null) {
                    logger.debug("Successfully extracted value using method {}: {}", methodName, result);
                    return result;
                }
            } catch (Exception e) {
                // Continue trying other methods
            }
        }
        
        // If no methods worked, try to cast to known interfaces/classes
        return extractValueByKnownTypes(timestamped);
    }

    /**
     * Try to extract value by casting to known timestamped types
     */
    private Object extractValueByKnownTypes(Timestamped<?> timestamped) {
        try {
            if (timestamped instanceof CharSequence) {
                return timestamped.toString();
            }
            
            // Try to get any public field that might contain the value
            java.lang.reflect.Field[] fields = timestamped.getClass().getFields();
            for (java.lang.reflect.Field field : fields) {
                if (field.getName().toLowerCase().contains("value") || 
                    field.getName().toLowerCase().contains("data")) {
                    try {
                        Object fieldValue = field.get(timestamped);
                        if (fieldValue != null) {
                            logger.debug("Found value in field {}: {}", field.getName(), fieldValue);
                            return fieldValue;
                        }
                    } catch (Exception e) {
                        // Continue with next field
                    }
                }
            }
            
            // Try getter methods that might return the value
            java.lang.reflect.Method[] methods = timestamped.getClass().getMethods();
            for (java.lang.reflect.Method method : methods) {
                String methodName = method.getName().toLowerCase();
                if ((methodName.startsWith("get") && methodName.contains("value")) ||
                    methodName.equals("value") ||
                    methodName.equals("data")) {
                    
                    try {
                        if (method.getParameterCount() == 0) {
                            Object methodResult = method.invoke(timestamped);
                            if (methodResult != null) {
                                logger.debug("Found value using method {}: {}", method.getName(), methodResult);
                                return methodResult;
                            }
                        }
                    } catch (Exception e) {
                        // Continue with next method
                    }
                }
            }
            
        } catch (Exception e) {
            logger.debug("Error in extractValueByKnownTypes: {}", e.getMessage());
        }
        
        return null;
    }

    /**
     * Extract value using reflection (works for Control, Graphic, Alarm)
     */
    private Object extractValueUsingReflection(Object dataObject) {
        try {
            java.lang.reflect.Method getValueMethod = dataObject.getClass().getMethod("getValue");
            Object value = getValueMethod.invoke(dataObject);
            logger.debug("Extracted value using reflection: {} from {}", value, dataObject.getClass().getSimpleName());
            return value;
        } catch (Exception e) {
            logger.debug("Could not extract value using reflection from {}: {}", 
                        dataObject.getClass().getSimpleName(), e.getMessage());
            return dataObject.toString();
        }
    }

    /**
     * Determine data type string for JSON response
     */
    private String determineDataType(Object channelValue, Object actualValue) {
        if (actualValue == null) {
            return "NULL";
        }
        
        // For arrays, show array type
        if (actualValue.getClass().isArray()) {
            Class<?> componentType = actualValue.getClass().getComponentType();
            return componentType.getSimpleName() + "[]";
        }
        
        // For timestamped values, show the inner type
        if (channelValue instanceof Timestamped) {
            return "Timestamped<" + actualValue.getClass().getSimpleName() + ">";
        }
        
        // For control/graphic/alarm values, show the wrapper type
        if (channelValue instanceof Control) {
            return "Control<" + actualValue.getClass().getSimpleName() + ">";
        }
        
        if (channelValue instanceof Graphic) {
            return "Graphic<" + actualValue.getClass().getSimpleName() + ">";
        }
        
        if (channelValue instanceof Alarm) {
            return "Alarm<" + actualValue.getClass().getSimpleName() + ">";
        }
        
        // For simple types
        return actualValue.getClass().getSimpleName();
    }

    // [Include all the reflection helper methods from previous implementation...]
    
    /**
     * Reflectively get a limit value and convert to Double
     */
    private Double reflectiveGetLimitValue(Object object, String methodName) {
        try {
            Object result = reflectiveInvokeMethod(object, methodName);
            return safeConvertToDouble(result);
        } catch (Exception e) {
            logger.debug("Could not get limit value using method {}: {}", methodName, e.getMessage());
            return null;
        }
    }

    /**
     * Reflectively get a String value
     */
    private String reflectiveGetStringValue(Object object, String methodName) {
        try {
            Object result = reflectiveInvokeMethod(object, methodName);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            logger.debug("Could not get string value using method {}: {}", methodName, e.getMessage());
            return null;
        }
    }

    /**
     * Reflectively get an Integer value
     */
    private Integer reflectiveGetIntegerValue(Object object, String methodName) {
        try {
            Object result = reflectiveInvokeMethod(object, methodName);
            if (result instanceof Number) {
                return ((Number) result).intValue();
            }
            if (result instanceof String) {
                return Integer.parseInt((String) result);
            }
            return null;
        } catch (Exception e) {
            logger.debug("Could not get integer value using method {}: {}", methodName, e.getMessage());
            return null;
        }
    }

    /**
     * Generic method to invoke any method using reflection
     */
    private Object reflectiveInvokeMethod(Object object, String methodName) {
        if (object == null || methodName == null) {
            return null;
        }
        
        try {
            // Try to find the method
            java.lang.reflect.Method method = object.getClass().getMethod(methodName);
            
            // Make sure it's accessible
            method.setAccessible(true);
            
            // Invoke the method
            Object result = method.invoke(object);
            
            logger.debug("Successfully invoked {} on {}: {}", 
                        methodName, object.getClass().getSimpleName(), result);
            
            return result;
            
        } catch (NoSuchMethodException e) {
            logger.debug("Method {} not found on class {}", methodName, object.getClass().getSimpleName());
            return null;
        } catch (Exception e) {
            logger.debug("Error invoking method {} on class {}: {}", 
                        methodName, object.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Enhanced safe conversion that handles more types
     */
    private Double safeConvertToDouble(Object value) {
        if (value == null) {
            return null;
        }
        
        // Handle Number types (most common)
        if (value instanceof Number) {
            Number num = (Number) value;
            // Check for special values
            double doubleValue = num.doubleValue();
            if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
                logger.debug("Limit value is NaN or Infinite: {}", doubleValue);
                return null;
            }
            return doubleValue;
        }
        
        // Handle String types
        if (value instanceof String) {
            String str = ((String) value).trim();
            if (str.isEmpty() || str.equalsIgnoreCase("null") || str.equalsIgnoreCase("n/a")) {
                return null;
            }
            try {
                double doubleValue = Double.parseDouble(str);
                if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
                    return null;
                }
                return doubleValue;
            } catch (NumberFormatException e) {
                logger.debug("Could not parse string '{}' to double", str);
                return null;
            }
        }
        
        // Handle Boolean types
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1.0 : 0.0;
        }
        
        // Handle Character types
        if (value instanceof Character) {
            return (double) ((Character) value);
        }
        
        // Try toString() and parse as last resort
        try {
            String str = value.toString().trim();
            if (!str.isEmpty() && !str.equalsIgnoreCase("null")) {
                double doubleValue = Double.parseDouble(str);
                if (!Double.isNaN(doubleValue) && !Double.isInfinite(doubleValue)) {
                    return doubleValue;
                }
            }
        } catch (NumberFormatException e) {
            logger.debug("Could not convert object '{}' (type: {}) to double via toString()", 
                        value, value.getClass().getSimpleName());
        }
        
        return null;
    }

    /**
     * Debug method to see what methods are available on generic objects
     */
    private void debugGenericObject(Object object, String objectType) {
        if (logger.isDebugEnabled()) {
            logger.debug("=== {} OBJECT DEBUG ===", objectType.toUpperCase());
            logger.debug("Actual class: {}", object.getClass().getName());
            logger.debug("Generic superclass: {}", object.getClass().getGenericSuperclass());
            logger.debug("Interfaces: {}", Arrays.toString(object.getClass().getInterfaces()));
            
            logger.debug("Available methods:");
java.lang.reflect.Method[] methods = object.getClass().getMethods();
            for (java.lang.reflect.Method method : methods) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    logger.debug("  - {} returns {}", method.getName(), method.getReturnType().getSimpleName());
                }
            }
            logger.debug("========================");
        }
    }

    /**
     * Debug method to inspect timestamped objects
     */
    private void debugTimestampedObject(Timestamped<?> timestamped) {
        if (logger.isDebugEnabled()) {
            logger.debug("=== TIMESTAMPED OBJECT DEBUG ===");
            logger.debug("Class: {}", timestamped.getClass().getName());
            logger.debug("Superclass: {}", timestamped.getClass().getSuperclass());
            logger.debug("Interfaces: {}", Arrays.toString(timestamped.getClass().getInterfaces()));
            
            logger.debug("Methods:");
            for (java.lang.reflect.Method method : timestamped.getClass().getMethods()) {
                if (method.getName().toLowerCase().contains("value") || 
                    method.getName().toLowerCase().contains("get")) {
                    logger.debug("  - {}", method.getName());
                }
            }
            
            logger.debug("Fields:");
            for (java.lang.reflect.Field field : timestamped.getClass().getFields()) {
                logger.debug("  - {} ({})", field.getName(), field.getType().getSimpleName());
            }
            
            logger.debug("Timestamp: seconds={}, nanos={}", 
                        timestamped.getSeconds(), timestamped.getNanos());
            logger.debug("================================");
        }
    }
    
    public PVData getPVData(String pvName) {
        return pvDataCache.get(pvName);
    }
    
    public void setPVValue(String pvName, Object value) throws Exception {
        Channel<?> channel = channels.get(pvName);
        if (channel == null) {
            throw new Exception("PV not subscribed: " + pvName);
        }
        
        try {
            // Type-safe value setting
            setPVValueTypeSafe(channel, value);
            logger.info("Set PV {} to value: {}", pvName, value);
        } catch (Exception e) {
            logger.error("Error setting PV {} to value {}: {}", pvName, value, e.getMessage());
            throw new Exception("Failed to set PV value: " + e.getMessage());
        }
    }
    
    /**
     * Set PV value with type safety
     */
    @SuppressWarnings("unchecked")
    private void setPVValueTypeSafe(Channel<?> channel, Object value) throws Exception {
        // Try to cast to the appropriate channel type and set value
        try {
            if (value instanceof Number) {
                ((Channel<Double>) channel).putAsync(((Number) value).doubleValue()).get(5, TimeUnit.SECONDS);
            } else if (value instanceof String) {
                ((Channel<String>) channel).putAsync((String) value).get(5, TimeUnit.SECONDS);
            } else {
                ((Channel<Object>) channel).putAsync(value).get(5, TimeUnit.SECONDS);
            }
        } catch (ClassCastException e) {
            // If casting fails, try with Object type
            ((Channel<Object>) channel).putAsync(value).get(5, TimeUnit.SECONDS);
        }
    }
    
    public void unsubscribeFromPV(String pvName) {
        logger.info("Unsubscribing from PV: {}", pvName);
        
        // Remove main PV monitor and channel
        Monitor<?> monitor = monitors.remove(pvName);
        if (monitor != null) {
            try {
                monitor.close();
                logger.info("Unsubscribed monitor for PV: {}", pvName);
            } catch (Exception e){
                logger.error("Error closing monitor for PV: {}", pvName, e);
            }
        }
        
        Channel<?> channel = channels.remove(pvName);
        if (channel != null) {
            try {
                channel.close();
                logger.info("Closed channel for PV: {}", pvName);
            } catch (Exception e){
                logger.error("Error closing channel for PV: {}", pvName, e);
            }
        }
        
        // Remove field monitors and channels
        String[] allFields = concatenateArrays(ALARM_FIELDS, STATE_FIELDS, METADATA_FIELDS);
        for (String field : allFields) {
            String fieldPvName = pvName + "." + field;
            
            Monitor<?> fieldMonitor = monitors.remove(fieldPvName);
            if (fieldMonitor != null) {
                try {
                    fieldMonitor.close();
                    logger.debug("Closed field monitor for: {}", fieldPvName);
                } catch (Exception e) {
                    logger.error("Error closing field monitor for {}: {}", fieldPvName, e.getMessage());
                }
            }
            
            Channel<?> fieldChannel = channels.remove(fieldPvName);
            if (fieldChannel != null) {
                try {
                    fieldChannel.close();
                    logger.debug("Closed field channel for: {}", fieldPvName);
                } catch (Exception e) {
                    logger.error("Error closing field channel for {}: {}", fieldPvName, e.getMessage());
                }
            }
        }
        
        // Remove from caches and mark as disconnected
        PVData pvData = pvDataCache.get(pvName);
        if (pvData != null) {
            pvData.setConnectionStatus(PVData.ConnectionStatus.DISCONNECTED);
            pvData.setLastUpdate(LocalDateTime.now());
            sendPVDataUpdate(pvData);
        }
        
        pvDataCache.remove(pvName);
        pvFieldData.remove(pvName);
        
        logger.info("Completed unsubscription for PV: {}", pvName);
    }
    
    /**
     * Utility method to concatenate arrays
     */
    private String[] concatenateArrays(String[]... arrays) {
        int totalLength = 0;
        for (String[] array : arrays) {
            totalLength += array.length;
        }
        
        String[] result = new String[totalLength];
        int currentIndex = 0;
        
        for (String[] array : arrays) {
            System.arraycopy(array, 0, result, currentIndex, array.length);
            currentIndex += array.length;
        }
        
        return result;
    }
    
    @PreDestroy
    public void cleanup() {
        logger.info("Cleaning up EPICS Service...");
        
        monitors.values().forEach(monitor -> {
            try {
                monitor.close();
            } catch (Exception e) {
                logger.error("Error closing monitor: {}", e.getMessage());
            }
        });
        
        channels.values().forEach(channel -> {
            try {
                channel.close();
            } catch (Exception e) {
                logger.error("Error closing channel: {}", e.getMessage());
            }
        });
        
        if (context != null) {
            try {
                context.close();
            } catch (Exception e) {
                logger.error("Error closing context: {}", e.getMessage());
            }
        }
        
        logger.info("EPICS Service cleanup completed.");
    }
    
    // Legacy PVUpdate class for backward compatibility
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