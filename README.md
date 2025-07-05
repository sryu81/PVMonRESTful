# RESTful service for EPICS PV Monitor

A Java based RESTful service application for EPICS PV monitoring

## Prerequisites

- Java 11 or higher
- Maven 3.6 or higher
- Access to an EPICS IOC with process variables

## Building

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