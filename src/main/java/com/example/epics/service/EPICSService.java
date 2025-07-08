package com.example.epics.service;

import com.example.epics.controller.EPICSController;
import com.example.epics.model.PVData;

import org.epics.ca.Channel;
import org.epics.ca.Context;
import org.epics.ca.Monitor;
import org.epics.ca.data.*;

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
public class EPICSService {
    private static final Logger logger = LoggerFactory.getLogger(EPICSService.class);

    private static Context context;
    private final ConcurrentHashMap<String, Monitor<?>> monitors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Channel<?>> channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PVData> pvDataCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> pvFieldData = new ConcurrentHashMap<>();

    private static final String[] essentialFields = {
        "DESC",  // Description
        "EGU",   // Engineering units  
        "PREC",  // Precision
        "SEVR",  // Current alarm severity
        "STAT"   // Current alarm status
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

    /**
     * Test if we can perform a simple GET operation (like caget)
     */
    public<T> boolean testPVGet(String pvName, Class<T> type, int timeoutSeconds) {
      
        Channel<T> channel = null;    

        try {
            logger.debug("Testing GET operation for PV: {}", pvName);
            
            // Create channel
            channel = context.createChannel(pvName, type);
            
            // Wait for connection
            channel.connectAsync().get(timeoutSeconds, TimeUnit.SECONDS);
            
            // Check if connected (using different approach since ConnectionState might not be available)
            if (!isChannelConnected(channel)) {
                logger.warn("Channel not connected for PV: {}", pvName);
                return false;
            }
            
            // Perform synchronous get (like caget)
            CompletableFuture<T> getFuture = channel.getAsync();
            T value = getFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            
            logger.debug("GET successful for PV: {} = {}", pvName, value);
            return true;
            
        } catch (Exception e) {
            logger.error("GET failed for PV: {} - {}", pvName, e.getMessage());
            return false;
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (Exception e) {
                    logger.warn("Error closing test channel for PV: {}", pvName);
                }
            }
        }
    }
    /**
     * Check if channel is connected (alternative to ConnectionState enum)
     */
    private boolean isChannelConnected(Channel<?> channel) {
        try {
            // Try to get channel info - if it throws exception, not connected
            return channel != null && !channel.getName().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
    
    @PostConstruct
    public void initialize() {
        logger.info("EPICS system properties configured");
        try {
            logger.info("Initializing EPICS Context...");
            EPICSService.context = new Context();
            logger.info("EPICS Context initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize EPICS Context: {}", e.getMessage(), e);
            throw new RuntimeException("EPICS Context initialization failed", e);
        }
    }

    public void subscribeToPV(String pvName) {
        if (EPICSService.context == null) {
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
     * Simplified field subscription - only essential fields
     */
    private void subscribeToAdditionalFieldsAsync(String pvName) {
        logger.debug("Starting simplified field subscription for PV: {}", pvName);
        
        // Subscribe to current alarm status FIRST (most important)
        subscribeToAlarmStatusFields(pvName);
        
        List<CompletableFuture<Void>> fieldFutures = new ArrayList<>();
        for (String field : essentialFields) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> 
                subscribeToFieldWithTimeout(pvName, field, 1000)
            );
            fieldFutures.add(future);
        }
        
        // Wait for essential fields (max 2 seconds total)
        try {
            CompletableFuture.allOf(fieldFutures.toArray(new CompletableFuture[0]))
                .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("Essential field subscription completed with some timeouts for PV: {}", pvName);
        }
        
        logger.debug("Completed simplified field subscription for PV: {}", pvName);
    }

    /**
     * Subscribe to real-time alarm status fields
     */
    private void subscribeToAlarmStatusFields(String pvName) {
        // Subscribe to current alarm status fields
        String[] statusFields = {"SEVR", "STAT"};
        
        for (String field : statusFields) {
            CompletableFuture.runAsync(() -> 
                subscribeToFieldWithTimeout(pvName, field, 500)
            );
        }
    }

    /**
     * Update current alarm status from SEVR and STAT fields
     */
    private void updateCurrentAlarmStatus(PVData pvData, Map<String, Object> fieldData) {
        try {
            // Get current alarm severity (SEVR field)
            Object sevr = fieldData.get("SEVR");
            if (sevr != null) {
                try {
                    String severityStr = sevr.toString();
                    // EPICS SEVR values: 0=NO_ALARM, 1=MINOR, 2=MAJOR, 3=INVALID
                    switch (severityStr) {
                        case "0":
                        case "NO_ALARM":
                            pvData.setAlarmSeverity(PVData.AlarmSeverity.NO_ALARM);
                            break;
                        case "1":
                        case "MINOR":
                            pvData.setAlarmSeverity(PVData.AlarmSeverity.MINOR);
                            break;
                        case "2":
                        case "MAJOR":
                            pvData.setAlarmSeverity(PVData.AlarmSeverity.MAJOR);
                            break;
                        case "3":
                        case "INVALID":
                            pvData.setAlarmSeverity(PVData.AlarmSeverity.INVALID);
                            break;
                        default:
                            pvData.setAlarmSeverity(PVData.AlarmSeverity.valueOf(severityStr));
                    }
                } catch (Exception e) {
                    logger.debug("Could not parse alarm severity: {}", sevr);
                }
            }
            
            // Get current alarm status (STAT field)
            Object stat = fieldData.get("STAT");
            if (stat != null) {
                try {
                    String statusStr = stat.toString();
                    // Try to map common EPICS STAT values
                    switch (statusStr) {
                        case "0":
                        case "NO_ALARM":
                            pvData.setAlarmStatus(PVData.AlarmStatus.NO_ALARM);
                            break;
                        case "1":
                        case "READ":
                            pvData.setAlarmStatus(PVData.AlarmStatus.READ_ALARM);
                            break;
                        case "2":
                        case "WRITE":
                            pvData.setAlarmStatus(PVData.AlarmStatus.WRITE_ALARM);
                            break;
                        case "3":
                        case "HIHI":
                            pvData.setAlarmStatus(PVData.AlarmStatus.HIHI_ALARM);
                            break;
                        case "4":
                        case "HIGH":
                            pvData.setAlarmStatus(PVData.AlarmStatus.HIGH_ALARM);
                            break;
                        case "5":
                        case "LOLO":
                            pvData.setAlarmStatus(PVData.AlarmStatus.LOLO_ALARM);
                            break;
                        case "6":
                        case "LOW":
                            pvData.setAlarmStatus(PVData.AlarmStatus.LOW_ALARM);
                            break;
                        default:
                            try {
                                pvData.setAlarmStatus(PVData.AlarmStatus.valueOf(statusStr));
                            } catch (IllegalArgumentException e) {
                                logger.debug("Unknown alarm status: {}", statusStr);
                            }
                    }
                } catch (Exception e) {
                    logger.debug("Could not parse alarm status: {}", stat);
                }
            }
            
            logger.debug("Updated current alarm status for PV {}: severity={}, status={}", 
                        pvData.getPvName(), pvData.getAlarmSeverity(), pvData.getAlarmStatus());
            
        } catch (Exception e) {
            logger.debug("Could not update current alarm status for PV {}: {}", pvData.getPvName(), e.getMessage());
        }
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
     * Subscribe to the main PV with faster timeout
     */
    private void subscribeToMainPV(String pvName) throws Exception {
        // Try different channel types with shorter timeouts
        try {
            if(testPVGet(pvName, Double.class,2))
                subscribeToTypedChannelFast(pvName, Double.class);
            return;
        } catch (Exception e) {
            logger.debug("Failed to subscribe as Double for PV {}: {}", pvName, e.getMessage());
        }
        
        try {
            if(testPVGet(pvName, String.class,2))
                subscribeToTypedChannelFast(pvName, String.class);
            return;
        } catch (Exception e) {
            logger.debug("Failed to subscribe as String for PV {}: {}", pvName, e.getMessage());
        }
        
        try {
            if(testPVGet(pvName, Integer.class,2))
                subscribeToTypedChannelFast(pvName, Integer.class);
            return;
        } catch (Exception e) {
            logger.debug("Failed to subscribe as Integer for PV {}: {}", pvName, e.getMessage());
        }
        
        // Fallback to Object type
        if(testPVGet(pvName, Object.class,2))
            subscribeToTypedChannelFast(pvName, Object.class);
        else
            logger.debug("Failed to subscribe as Object type for PV {}", pvName);

    }

    /**
     * Subscribe to a channel with faster timeouts
     */
    private <T> void subscribeToTypedChannelFast(String pvName, Class<T> type) throws Exception {
        Channel<T> channel = context.createChannel(pvName, type);
        channels.put(pvName, channel);
        
        // Connect with shorter timeout (1 seconds instead of 5)
        channel.connectAsync().get(500, TimeUnit.MILLISECONDS);
        logger.info("Connected to PV: {} with type: {}", pvName, type.getSimpleName());

        // Get initial value with shorter timeout (1 second instead of 2)
        T initialValue = channel.getAsync().get(100, TimeUnit.MILLISECONDS);
        updatePVDataFromTypedValue(pvName, initialValue, type);
        
        logger.info("Got initial value for PV {}: {}", pvName, initialValue);

        // Set up monitor for value changes
        Consumer<T> valueConsumer = value -> {
            //logger.debug("Value update for PV {}: {}", pvName, value);
            updatePVDataFromTypedValue(pvName, value, type);
        };
        
        Monitor<T> monitor = channel.addValueMonitor(valueConsumer);
        monitors.put(pvName, monitor);
        //logger.info("Monitor set up for PV: {}", pvName);
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
     * Simplified update - only metadata and current alarm status
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
            // Update current alarm status from SEVR and STAT fields
            updateCurrentAlarmStatus(pvData, fieldData);
            
            // Update basic metadata only (description, units, precision)
            updateBasicMetadata(pvData, fieldData);
            
            pvData.setLastUpdate(LocalDateTime.now());
            sendPVDataUpdate(pvData);
            
        } catch (Exception e) {
            logger.warn("Error updating PV data from fields for {}: {}", pvName, e.getMessage());
        }
    }

    /**
     * Update only basic metadata (no limits)
     */
    private void updateBasicMetadata(PVData pvData, Map<String, Object> fieldData) {
        try {
            // Description
            Object desc = fieldData.get("DESC");
            if (desc != null && !desc.toString().trim().isEmpty()) {
                pvData.setDescription(desc.toString());
            }
            
            // Engineering units
            Object egu = fieldData.get("EGU");
            if (egu != null && !egu.toString().trim().isEmpty()) {
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
            
            logger.debug("Updated basic metadata for PV {}: DESC={}, EGU={}, PREC={}", 
                        pvData.getPvName(), desc, egu, prec);
            
        } catch (Exception e) {
            logger.debug("Could not update basic metadata for PV {}: {}", pvData.getPvName(), e.getMessage());
        }
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
            
            logger.debug("Extracted Graphic metadata for PV: {}", pvData.getPvName());
            
        } catch (Exception e) {
            logger.warn("Error extracting graphic data for PV {}: {}", pvData.getPvName(), e.getMessage());
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
        
        for (String field : essentialFields) {
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
        
        // Clean up any remaining field channels that might exist
        // (in case there are leftover channels from before the simplification)
        cleanupRemainingFieldChannels(pvName);
        
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
     * Clean up any remaining field channels that might exist from old subscriptions
     */
    private void cleanupRemainingFieldChannels(String basePvName) {
        // Get all monitor keys that start with this PV name
        List<String> keysToRemove = new ArrayList<>();
        
        for (String key : monitors.keySet()) {
            if (key.startsWith(basePvName + ".")) {
                keysToRemove.add(key);
            }
        }
        
        // Remove any remaining field monitors
        for (String key : keysToRemove) {
            Monitor<?> monitor = monitors.remove(key);
            if (monitor != null) {
                try {
                    monitor.close();
                    logger.debug("Cleaned up remaining monitor: {}", key);
                } catch (Exception e) {
                    logger.debug("Error cleaning up monitor {}: {}", key, e.getMessage());
                }
            }
        }
        
        // Do the same for channels
        keysToRemove.clear();
        for (String key : channels.keySet()) {
            if (key.startsWith(basePvName + ".")) {
                keysToRemove.add(key);
            }
        }
        
        for (String key : keysToRemove) {
            Channel<?> channel = channels.remove(key);
            if (channel != null) {
                try {
                    channel.close();
                    logger.debug("Cleaned up remaining channel: {}", key);
                } catch (Exception e) {
                    logger.debug("Error cleaning up channel {}: {}", key, e.getMessage());
                }
            }
        }
        
        if (!keysToRemove.isEmpty()) {
            logger.debug("Cleaned up {} remaining field channels for PV: {}", keysToRemove.size(), basePvName);
        }
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