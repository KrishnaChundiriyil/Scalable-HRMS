# Person 3 — Payroll & Database Scalability

This is a working starter contribution for integration for the Scalable HRMS repository. It uses Java 21, Spring Boot 3.5.x, Maven, and MongoDB, matching the existing `leave-service` conventions.

## Contents
- `payroll-service/`: REST APIs for payroll calculation, monthly payroll records, payslip retrieval, and employee payroll history.
- `infrastructure/docker-compose-payroll.yml`: local MongoDB + three payroll instances + Nginx. Uses a dedicated MongoDB port to avoid colliding with the existing dev compose.
- `infrastructure/nginx-payroll.conf`: Nginx upstream for three backend instances.
- `infrastructure/docker-compose-sharded.yml`: separate local MongoDB sharded-cluster lab (config server, two shards, mongos, and a one-shot initialization service). This is for learning/demo; not production-hardened.
- `jmeter/Payroll-Load-Test.jmx`: basic JMeter plan for the payroll health endpoint and calculation endpoint.
- `docs/INTEGRATION.md`: integration and test steps.

## Important integration notes
1. Copy `payroll-service/` into the root of your existing `HR_project` folder, next to `leave-service/`.
2. Do not overwrite your existing `infrastructure/docker-compose-dev.yml`. The included compose files are separate examples.
3. The payroll app stores a payroll record only when the `POST /api/payroll` endpoint is called. The calculation API is stateless.
4. The starter implementation accepts salary inputs in the request. It does not yet fetch attendance or employee details from another service because the employee API contract and attendance source have not been confirmed.
5. This is an educational starter, not production payroll software. Authentication/authorization, tax rules, audit controls, idempotency, currency/pay period rules, and secure payslip delivery must be agreed and implemented before real use.
6. MongoDB sharding requires `mongos` as the connection endpoint. The normal payroll compose uses a standalone MongoDB for basic development; the sharded compose is a separate lab setup.

## Requirements
- JDK 21
- Docker Desktop with Docker Compose
- IntelliJ IDEA or Maven

## Run basic payroll service locally
Start the existing team's MongoDB service if it is not already running, then in a terminal:
```bash
cd payroll-service
mvn spring-boot:run
```
Default MongoDB URI: `mongodb://localhost:27017/hrms_payroll`

## Run the three-instance Nginx demo
From the root of the repository after copying the included `infrastructure` files:
```bash
docker compose -f infrastructure/docker-compose-payroll.yml up --build
```
Nginx is exposed at `http://localhost:8088`. MongoDB for this demo is exposed at `localhost:27018`, so it won't conflict with the existing dev compose on 27017.

## Useful endpoints
- `GET /api/payroll/health`
- `POST /api/payroll/calculate`
- `POST /api/payroll` (calculate and save a monthly payroll record)
- `GET /api/payroll/{id}`
- `GET /api/payroll/employee/{employeeId}`
- `GET /api/payroll/employee/{employeeId}/year/{year}/month/{month}`
- `GET /actuator/health`

Use sample requests in `docs/INTEGRATION.md`.


## Status and limitations
- Payroll unit tests and basic payroll API flows were tested locally by the contributor. The three-instance Docker deployment and Nginx routing were also exercised. JMeter was run with 10 virtual users, 10-second ramp-up, and 10 loops; the reported run had 200 samples and 0% errors. These are local demonstration results, not production performance guarantees.
- The MongoDB sharding Compose file is an educational setup and must be validated on the recipient's machine. Do not claim sharding is working until `sh.status()` confirms both shards and the target collection is sharded.
- The single-instance versus three-instance JMeter comparison is not yet complete.
- This contribution does not yet include PDF payslip generation, attendance integration, or authentication/authorization for payroll APIs. Confirm whether these are required by the final project specification.
- Merge only the listed Person 3 files into the shared repository. Do not overwrite existing team files, especially `infrastructure/docker-compose-dev.yml` or the repository's root README.
