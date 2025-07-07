#!/bin/bash

# Check if PV name is provided
#if [ $# -eq 0 ]; then
#    echo "Usage: $0 <PV_NAME>"
#    echo "Example: $0 TEST:PV:VALUE"
#    exit 1
#fi

#PV_NAME=$1

# Set EPICS environment variables if needed
#export EPICS_CA_ADDR_LIST="localhost"
#export EPICS_CA_AUTO_ADDR_LIST="YES"

# Run the application
java -jar target/pv-monitor-restful-1.1.0.jar