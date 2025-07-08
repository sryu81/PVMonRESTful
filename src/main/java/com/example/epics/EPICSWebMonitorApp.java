package com.example.epics;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
//import org.springframework.context.annotation.ComponentScan;
//import org.springframework.context.annotation.FilterType;

@SpringBootApplication
public class EPICSWebMonitorApp {
    
    public static void main(String[] args) {
        loadEPICSProperties();
        SpringApplication.run(EPICSWebMonitorApp.class, args);
    }

    private static void loadEPICSProperties() {
        try (InputStream input = EPICSWebMonitorApp.class.getClassLoader().getResourceAsStream("application.properties")) {    
            if (input == null) {
                System.out.println("Unable to find application.properties, using defaults");
                setDefaultEPICSProperties();
                return;
            }
            
            Properties props = new Properties();
            props.load(input);
            
            // Set IPv4 properties
            boolean forceIpv4 = Boolean.parseBoolean(props.getProperty("epics.force-ipv4", "true"));
            if (forceIpv4) {
                System.setProperty("java.net.preferIPv4Stack", "true");
                System.setProperty("java.net.preferIPv6Addresses", "false");
            }
            
            // Set EPICS properties
            System.setProperty("EPICS_CA_ADDR_LIST", props.getProperty("epics.ca.addr-list", ""));
            System.setProperty("EPICS_CA_AUTO_ADDR_LIST", props.getProperty("epics.ca.auto-addr-list", "YES"));
            System.setProperty("EPICS_CA_SERVER_PORT", props.getProperty("epics.ca.server-port", "5064"));
            System.setProperty("EPICS_CA_REPEATER_PORT", props.getProperty("epics.ca.repeater-port", "5065"));
            
            System.out.println("EPICS properties loaded from application.properties");
            
        } catch (IOException e) {
            System.err.println("Error loading application.properties: " + e.getMessage());
            setDefaultEPICSProperties();
        }
    }
    
    private static void setDefaultEPICSProperties() {
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.net.preferIPv6Addresses", "false");
        System.setProperty("EPICS_CA_ADDR_LIST", "");
        System.setProperty("EPICS_CA_AUTO_ADDR_LIST", "YES");
        System.setProperty("EPICS_CA_SERVER_PORT", "5064");
        System.setProperty("EPICS_CA_REPEATER_PORT", "5065");
        System.out.println("Using default EPICS properties");
    }
}