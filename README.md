# RESTful service for EPICS PV Monitor

A Java based RESTful service application for EPICS PV monitoring.

Currently supports CA protocol but will add PVA soon...

## Prerequisites

- Java 11 or higher
- Maven 3.6 or higher
- Access to an EPICS IOC with process variables

## Building
Before building, you need to check src/main/resource/application.properties and change the proeperties that fits to you environment.

```bash
mvn clean install
```

## Executing

```bash
./scripts/run.sh
```

# Test RESTful API

## Prerequisite
- Must have an EPICS IOC running
- Prepare your PV list

## Subscribe PV
```bash
curl -x POST http://localhost:8080//api/epics/subscribe/YOUR_PVNAME
```

## Get the list of PVs in subscription
```bash
curl http://localhost:8080//api/epics/pvs
```
