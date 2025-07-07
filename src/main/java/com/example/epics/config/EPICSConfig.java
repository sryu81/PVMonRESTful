package com.example.epics.config;

//import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
public class EPICSConfig {
    private static final Logger logger = LoggerFactory.getLogger(EPICSConfig.class);
    
    @PostConstruct
    public void validateEPICSProperties() {
        logger.info("Validating EPICS properties that were set before Spring startup:");
        logger.info("  EPICS_CA_ADDR_LIST: {}", System.getProperty("EPICS_CA_ADDR_LIST"));
        logger.info("  EPICS_CA_AUTO_ADDR_LIST: {}", System.getProperty("EPICS_CA_AUTO_ADDR_LIST"));
        logger.info("  EPICS_CA_SERVER_PORT: {}", System.getProperty("EPICS_CA_SERVER_PORT"));
        logger.info("  EPICS_CA_REPEATER_PORT: {}", System.getProperty("EPICS_CA_REPEATER_PORT"));
        logger.info("  java.net.preferIPv4Stack: {}", System.getProperty("java.net.preferIPv4Stack"));
        logger.info("  server.port: {}", System.getProperty("server.port"));
        
        // Validate that required properties are set
        if (System.getProperty("EPICS_CA_ADDR_LIST") == null) {
            logger.error("EPICS_CA_ADDR_LIST not set! EPICS connections may fail.");
        }
    }
}
