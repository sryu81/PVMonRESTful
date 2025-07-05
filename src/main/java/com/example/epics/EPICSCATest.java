package com.example.epics;


import org.epics.ca.Channel;
import org.epics.ca.Context;
import org.epics.ca.Monitor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class EPICSCATest {
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java EPICSCATest <PV_NAME>");
            return;
        }
        
        String pvName = args[0];
        
        try (Context context = new Context()) {
            
            try (Channel<Double> channel = context.createChannel(pvName, Double.class)) {
                
                System.out.println("Connecting to PV: " + pvName);
                channel.connectAsync().get(5, TimeUnit.SECONDS);
                System.out.println("Successfully connected to " + pvName);
                
                // Get current value
                Double currentValue = channel.getAsync().get(2, TimeUnit.SECONDS);
                System.out.println("Current value: " + currentValue);
                
                // Set up monitor using Consumer
                Consumer<Double> valueConsumer = value -> {
                    System.out.println("Monitor update - New value: " + value + " at " + new java.util.Date());
                };
                
                try (Monitor<Double> monitor = channel.addValueMonitor(valueConsumer)) {
                    System.out.println("Monitoring PV changes. Press Ctrl+C to exit...");
                    Thread.sleep(Long.MAX_VALUE);
                }
                
            } catch (Exception e) {
                System.err.println("Error with channel operations: " + e.getMessage());
                e.printStackTrace();
            }
            
        } catch (Exception e) {
            System.err.println("Error creating context: " + e.getMessage());
            e.printStackTrace();
        }
    }
}