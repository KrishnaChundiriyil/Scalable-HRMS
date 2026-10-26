# HRMS — Person 1: Employee & Identity Management (Backend only)

Standalone Spring Boot REST API designed to open and run in IntelliJ IDEA. This is **Person 1's module only**; it does not require the frontend or other teammates' services.

This folder is imported from Person 1's final ZIP. For the integrated setup, start
with [Employee + Leave instructions](../docs/EMPLOYEE_LEAVE_INTEGRATION.md). Her
JPA/H2 persistence and original employee APIs are preserved.

## Included features
- Employee registration, login, and profile read/update
- Employee directory with name and department filters
- Roles: `ADMIN`, `HR`, `MANAGER`, `EMPLOYEE`
- JWT bearer authentication; passwords stored as BCrypt hashes
- Access rules: employees can read/update their own profile; HR/Admin can read/update profiles and create employee accounts; only Admin can delete employees
- Redis atomic employee ID counter (`hrms:employee:id:counter`)
- Trie-based employee-name and department autocomplete, with Redis result caching
- Redis profile cache with 10-minute TTL
- H2 file database so the project runs without installing a database server
- Graceful fallback for Redis-dependent features if Redis is not running (database/API still starts)

## Requirements
- JDK 17 or newer
- IntelliJ IDEA
- Internet on first Maven build to download dependencies
- Optional: Redis on `localhost:6379` to demonstrate the Redis lab concepts

## Run in IntelliJ
1. Generate/load the private environment settings described in the integration instructions.
2. IntelliJ: **File → Open** and choose `person1-employee-service` (the folder containing `pom.xml`). Pass those environment variables in the run configuration too.
3. Trust/open it as a Maven project and wait for dependency download.
4. Set Project SDK to JDK 17 or newer.
5. Run `EmployeeServiceApplication.java`.
6. Backend URL: `http://localhost:8080`; health check: `GET http://localhost:8080/api/auth/health`.

The database is stored in `./data/hrmsdb` and persists between restarts. H2 console
is disabled by default. For local debugging only, enable `ENABLE_H2_CONSOLE=true`;
the path is `/h2-console`, JDBC URL `jdbc:h2:file:./data/hrmsdb`, username `sa`, blank password.

## First login
There is no fixed default admin or signing key. Supply `HRMS_JWT_SECRET_BASE64`.
To create your first Admin, set `HRMS_BOOTSTRAP_ADMIN_ENABLED=true`,
`HRMS_ADMIN_EMAIL` and `HRMS_ADMIN_PASSWORD` (8+ characters). An account is created
only when that email is absent. Disable bootstrap after the first run. See the
integration instructions for environment commands.

## API quick start
Use Postman or IntelliJ HTTP Client. After login, copy `token` from the response and send `Authorization: Bearer YOUR_TOKEN` for protected endpoints. Example requests are in `postman/Person1-HRMS.postman_collection.json`.

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| GET | `/api/auth/health` | Public | Health check |
| POST | `/api/auth/register` | Public | Register a normal Employee account |
| POST | `/api/auth/login` | Public | Login and receive JWT |
| POST | `/api/employees` | Admin/HR | Create employee; role may be ADMIN, HR, MANAGER or EMPLOYEE |
| GET | `/api/employees?name=ka&department=IT` | Any logged-in user | Search directory; either query is optional |
| GET | `/api/employees/EMP01001` | Self or Admin/HR | View profile |
| PUT | `/api/employees/EMP01001` | Self or Admin/HR | Update name/department/jobTitle/phone |
| DELETE | `/api/employees/EMP01001` | Admin only | Delete employee |
| GET | `/api/employees/suggest?type=employee&prefix=ka` | Any logged-in user | Trie autocomplete (max 10 suggestions) |
| GET | `/api/employees/suggest?type=department&prefix=it` | Any logged-in user | Department autocomplete |

Registration JSON:
```json
{
  "name": "Kavya Kumar",
  "email": "kavya@example.com",
  "password": "Kavya@1234",
  "department": "Engineering",
  "jobTitle": "Software Engineer",
  "phone": "9876543210"
}
```

Login JSON:
```json
{"email":"your-admin@example.com","password":"your-configured-password"}
```

Profile update JSON:
```json
{"name":"Kavya K","department":"Engineering","jobTitle":"Backend Engineer","phone":"9876543210"}
```

## Optional Redis setup
If Docker is installed, run:
```bash
docker run --name hrms-redis -p 6379:6379 -d redis:7-alpine
```
Then restart the app. Redis is used for the atomic employee counter and cached profile/autocomplete results. The app remains usable without Redis, but the Redis-backed lab demonstration requires Redis to be running. For a clean Redis ID-counter demo, use a fresh Redis instance/key; existing employee IDs are still checked for uniqueness.

## Notes
- This is a local educational project, not production-hardened identity infrastructure.
- JWT credentials are supplied externally. Integrated Leave uses `HRMS_JWT_SECRET_BASE64`, `HRMS_JWT_ISSUER` and `HRMS_JWT_AUDIENCE` in both services.
- Search results are intentionally limited to safe profile fields; password hashes are never returned.

## Additive Leave integration API

`GET /api/internal/employees/{id}` returns employeeId, name, department, active,
managerId for self, assigned manager, HR/Admin or the restricted internal reader.
`GET /api/internal/employees/{managerId}/direct-reports` returns active reports
for that manager, HR/Admin or the internal reader.

Only active HR/Admin may `PUT /api/internal/employees/{id}/leave-settings` with
`{"managerId":"EMP01002","active":true}`. Manager assignment rejects non-reviewer
roles and cycles; `"managerId":""` clears it. Public registration and profile
update bodies cannot set manager/active fields.

`EMPLOYEE_SERVICE_INTERNAL_TOKEN` is optional but needed for Leave reminders.
Configure the same random value (32+ characters) in both services. It authenticates
only directory GET requests, never account changes or public CRUD.

`mvn clean verify` runs JWT and directory authorization tests. From the repository
root, `python3 scripts/verify_real_integration.py` runs both actual services.
