# Integrating Person 3's module

## 1. Copy into the existing repository
Copy the `payroll-service` directory into the root of `HR_project`, next to `leave-service`.
Copy `infrastructure/nginx-payroll.conf`, `infrastructure/docker-compose-payroll.yml`, and `infrastructure/docker-compose-sharded.yml` into your existing `infrastructure` directory. Do not replace `docker-compose-dev.yml`.

## 2. Import in IntelliJ
Right-click `payroll-service/pom.xml` and choose **Add as Maven Project** (or open the Maven tool window and reload). Set Project SDK to JDK 21.

## 3. Run basic local version
Ensure MongoDB is running on `localhost:27017`, then from `payroll-service`:
```bash
mvn spring-boot:run
```
Health check:
```bash
curl http://localhost:8081/api/payroll/health
```

## 4. Test calculation
```bash
curl -X POST http://localhost:8081/api/payroll/calculate \
  -H "Content-Type: application/json" \
  -d '{"employeeId":"EMP001","year":2026,"month":10,"basicSalary":30000,"allowances":5000,"deductions":2000}'
```
Expected gross salary: `35000.00`; expected net salary: `33000.00`.

## 5. Save a payroll record
```bash
curl -X POST http://localhost:8081/api/payroll \
  -H "Content-Type: application/json" \
  -d '{"employeeId":"EMP001","year":2026,"month":10,"basicSalary":30000,"allowances":5000,"deductions":2000}'
```
The response contains the MongoDB ID. Repeating the same employee/year/month is rejected.

## 6. Run the Nginx three-instance demo
From repository root:
```bash
docker compose -f infrastructure/docker-compose-payroll.yml up --build
```
Then test:
```bash
curl http://localhost:8088/api/payroll/health
```
Stop it with Ctrl+C, then optionally:
```bash
docker compose -f infrastructure/docker-compose-payroll.yml down
```
The included demo uses a separate MongoDB mapped to host port 27018 so it doesn't collide with the existing dev database. All three backend instances share the same database.

## 7. Sharding lab
The file `docker-compose-sharded.yml` is a separate educational sharding demo. Start it with:
```bash
docker compose -f infrastructure/docker-compose-sharded.yml up -d
```
Check the router:
```bash
mongosh "mongodb://localhost:27020"
```
Then in mongosh:
```javascript
sh.status()
```
Use `mongodb://localhost:27020/hrms_payroll` as the app URI only after verifying the cluster has initialized. The initialization uses timing delays and may need troubleshooting on slower computers. This is a local demo, not a production replica-set design.

## 8. JMeter comparison
Open `jmeter/Payroll-Load-Test.jmx` in Apache JMeter.
- Three-instance path: keep port `8088` (Nginx).
- One-instance path: change the HTTP sampler port to `8081` and start the app locally, or pass JMeter properties as appropriate.
Run comparable tests with the same thread count, ramp-up, loop count, machine, and data. Record average response time, 90th/95th percentile, throughput, and error percentage. Increase users gradually; do not claim a speedup without measured results.

## Current limitations / work still needed
- No integration with attendance or employee service yet.
- No JWT/role authorization yet. Do not expose payroll endpoints publicly.
- No statutory tax/benefit rules; deductions are supplied by the caller.
- Payslips are represented by stored payroll records and JSON APIs; PDF generation is not included.
- Sharded compose is a teaching scaffold; verify startup and `sh.status()` before using it in a demonstration.
- Add repository-specific integration tests and review with the team before pushing.


## Sharding verification note
The `docker-compose-sharded.yml` file is a separate educational cluster, not the MongoDB instance used by `docker-compose-payroll.yml`. Start and validate it separately. After initialization, connect to `mongos` and run `sh.status()`; verify that both shard replica sets are registered and that `hrms_payroll.payroll_records` is listed as sharded. If the initialization container exits with an error, inspect its logs and fix that before claiming sharding is complete. This configuration has not been verified as production-ready.

## JMeter note
The JMeter plan includes JSON request headers for the payroll calculation sampler and a Summary Report listener. The default target is Nginx at `localhost:8088`. Use the same workload and request mix when comparing one backend with three, and record errors as well as response times and throughput.
