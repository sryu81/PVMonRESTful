package com.example.epics.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PVData {
    private String pvName;
    private Object value;
    private String dataType;
    private ConnectionStatus connectionStatus;
    private AlarmStatus alarmStatus;
    private AlarmSeverity alarmSeverity;
    private String units;
    private Integer precision;
    private DisplayLimits displayLimits;
    private AlarmLimits alarmLimits;
    private ControlLimits controlLimits;
    private Map<String, Object> fieldData;           // All EPICS field data
    private Map<String, String> stateNames;          // State names (ZNAM, ONAM, etc.)
    private Map<String, Object> alarmSeverities;     // Alarm severities (HHSV, HSV, etc.)
      
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime timestamp;
    
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime lastUpdate;
    
    private String description;
    private Long count;
    private String host;

    // Constructors
    public PVData() {}

    public PVData(String pvName) {
        this.pvName = pvName;
        this.lastUpdate = LocalDateTime.now();
        this.connectionStatus = ConnectionStatus.DISCONNECTED;
    }

    // Enums
    public enum ConnectionStatus {
        CONNECTED, DISCONNECTED, CONNECTING, ERROR, TIMEOUT
    }

    public enum AlarmStatus {
        NO_ALARM, READ_ALARM, WRITE_ALARM, HIHI_ALARM, HIGH_ALARM, 
        LOLO_ALARM, LOW_ALARM, STATE_ALARM, COS_ALARM, COMM_ALARM, 
        TIMEOUT_ALARM, HW_LIMIT_ALARM, CALC_ALARM, SCAN_ALARM, 
        LINK_ALARM, SOFT_ALARM, BAD_SUB_ALARM, UDF_ALARM, 
        DISABLE_ALARM, SIMM_ALARM, READ_ACCESS_ALARM, WRITE_ACCESS_ALARM
    }

    public enum AlarmSeverity {
        NO_ALARM, MINOR, MAJOR, INVALID
    }
    // ... existing constructors and methods ...
    
    // New getters and setters
    public Map<String, Object> getFieldData() { return fieldData; }
    public void setFieldData(Map<String, Object> fieldData) { this.fieldData = fieldData; }
    
    public Map<String, String> getStateNames() { return stateNames; }
    public void setStateNames(Map<String, String> stateNames) { this.stateNames = stateNames; }
    
    public Map<String, Object> getAlarmSeverities() { return alarmSeverities; }
    public void setAlarmSeverities(Map<String, Object> alarmSeverities) { this.alarmSeverities = alarmSeverities; }
    
    // Convenience methods for common field access
    public Object getFieldValue(String fieldName) {
        return fieldData != null ? fieldData.get(fieldName) : null;
    }
    
    public String getStateName(int state) {
        return stateNames != null ? stateNames.get(String.valueOf(state)) : null;
    }
    
    public String getStateName(Object value) {
        if (value instanceof Number) {
            return getStateName(((Number) value).intValue());
        }
        return null;
    }

    // Inner classes for limits
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DisplayLimits {
        private Double lowerDisplayLimit;
        private Double upperDisplayLimit;
        private Double lowerWarningLimit;
        private Double upperWarningLimit;

        public DisplayLimits() {}

        public DisplayLimits(Double lowerDisplay, Double upperDisplay, Double lowerWarning, Double upperWarning) {
            this.lowerDisplayLimit = lowerDisplay;
            this.upperDisplayLimit = upperDisplay;
            this.lowerWarningLimit = lowerWarning;
            this.upperWarningLimit = upperWarning;
        }

        // Getters and setters
        public Double getLowerDisplayLimit() { return lowerDisplayLimit; }
        public void setLowerDisplayLimit(Double lowerDisplayLimit) { this.lowerDisplayLimit = lowerDisplayLimit; }
        
        public Double getUpperDisplayLimit() { return upperDisplayLimit; }
        public void setUpperDisplayLimit(Double upperDisplayLimit) { this.upperDisplayLimit = upperDisplayLimit; }
        
        public Double getLowerWarningLimit() { return lowerWarningLimit; }
        public void setLowerWarningLimit(Double lowerWarningLimit) { this.lowerWarningLimit = lowerWarningLimit; }
        
        public Double getUpperWarningLimit() { return upperWarningLimit; }
        public void setUpperWarningLimit(Double upperWarningLimit) { this.upperWarningLimit = upperWarningLimit; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AlarmLimits {
        private Double lowerAlarmLimit;
        private Double upperAlarmLimit;
        private Double lowerWarningLimit;
        private Double upperWarningLimit;

        public AlarmLimits() {}

        public AlarmLimits(Double lowerAlarm, Double upperAlarm, Double lowerWarning, Double upperWarning) {
            this.lowerAlarmLimit = lowerAlarm;
            this.upperAlarmLimit = upperAlarm;
            this.lowerWarningLimit = lowerWarning;
            this.upperWarningLimit = upperWarning;
        }

        // Getters and setters
        public Double getLowerAlarmLimit() { return lowerAlarmLimit; }
        public void setLowerAlarmLimit(Double lowerAlarmLimit) { this.lowerAlarmLimit = lowerAlarmLimit; }
        
        public Double getUpperAlarmLimit() { return upperAlarmLimit; }
        public void setUpperAlarmLimit(Double upperAlarmLimit) { this.upperAlarmLimit = upperAlarmLimit; }
        
        public Double getLowerWarningLimit() { return lowerWarningLimit; }
        public void setLowerWarningLimit(Double lowerWarningLimit) { this.lowerWarningLimit = lowerWarningLimit; }
        
        public Double getUpperWarningLimit() { return upperWarningLimit; }
        public void setUpperWarningLimit(Double upperWarningLimit) { this.upperWarningLimit = upperWarningLimit; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ControlLimits {
        private Double lowerControlLimit;
        private Double upperControlLimit;

        public ControlLimits() {}

        public ControlLimits(Double lowerControl, Double upperControl) {
            this.lowerControlLimit = lowerControl;
            this.upperControlLimit = upperControl;
        }

        // Getters and setters
        public Double getLowerControlLimit() { return lowerControlLimit; }
        public void setLowerControlLimit(Double lowerControlLimit) { this.lowerControlLimit = lowerControlLimit; }
        
        public Double getUpperControlLimit() { return upperControlLimit; }
        public void setUpperControlLimit(Double upperControlLimit) { this.upperControlLimit = upperControlLimit; }
    }

    // Main getters and setters
    public String getPvName() { return pvName; }
    public void setPvName(String pvName) { this.pvName = pvName; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public ConnectionStatus getConnectionStatus() { return connectionStatus; }
    public void setConnectionStatus(ConnectionStatus connectionStatus) { this.connectionStatus = connectionStatus; }

    public AlarmStatus getAlarmStatus() { return alarmStatus; }
    public void setAlarmStatus(AlarmStatus alarmStatus) { this.alarmStatus = alarmStatus; }

    public AlarmSeverity getAlarmSeverity() { return alarmSeverity; }
    public void setAlarmSeverity(AlarmSeverity alarmSeverity) { this.alarmSeverity = alarmSeverity; }

    public String getUnits() { return units; }
    public void setUnits(String units) { this.units = units; }

    public Integer getPrecision() { return precision; }
    public void setPrecision(Integer precision) { this.precision = precision; }

    public DisplayLimits getDisplayLimits() { return displayLimits; }
    public void setDisplayLimits(DisplayLimits displayLimits) { this.displayLimits = displayLimits; }

    public AlarmLimits getAlarmLimits() { return alarmLimits; }
    public void setAlarmLimits(AlarmLimits alarmLimits) { this.alarmLimits = alarmLimits; }

    public ControlLimits getControlLimits() { return controlLimits; }
    public void setControlLimits(ControlLimits controlLimits) { this.controlLimits = controlLimits; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public LocalDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(LocalDateTime lastUpdate) { this.lastUpdate = lastUpdate; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getCount() { return count; }
    public void setCount(Long count) { this.count = count; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
}