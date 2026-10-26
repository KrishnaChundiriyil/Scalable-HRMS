# HRMS — Person 1: Employee & Identity Management (Backend only)

Standalone Spring Boot REST API designed to open and run in IntelliJ IDEA. This is **Person 1's module only**; it does not require the frontend or other teammates' services.

## Included features
- Employee registration, login, and profile read/update
- Employee directory with name and department filters
- Roles: `ADMIN`, `HR`, `EMPLOYEE`
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
1. Extract the ZIP.
2. IntelliJ: **File → Open** and choose the extracted `person1-employee-service` folder (the folder containing `pom.xml`).
3. Trust/open it as a Maven project and wait for dependency download.
4. Set Project SDK to JDK 17 or newer.
5. Run `EmployeeServiceApplication.java`.
6. Backend URL: `http://localhost:8081`; health check: `GET http://localhost:8081/api/auth/health`.

The database is stored in `./data/hrmsdb` and persists between restarts. H2 console: `http://localhost:8081/h2-console`, JDBC URL `jdbc:h2:file:./data/hrmsdb`, username `sa`, blank password.

## First login
A development Admin is created automatically if it doesn't already exist:
- Email: `admin@hrms.local`
- Password: `Admin@12345`

Change/remove this development account and the local JWT secret before any real deployment. This default credential is for local demo only.

## API quick start
Use Postman or IntelliJ HTTP Client. After login, copy `token` from the response and send `Authorization: Bearer YOUR_TOKEN` for protected endpoints. Example requests are in `postman/Person1-HRMS.postman_collection.json`.

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| GET | `/api/auth/health` | Public | Health check |
| POST | `/api/auth/register` | Public | Register a normal Employee account |
| POST | `/api/auth/login` | Public | Login and receive JWT |
| POST | `/api/employees` | Admin/HR | Create employee; role may be ADMIN, HR or EMPLOYEE |
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
{"email":"admin@hrms.local","password":"Admin@12345"}
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
- JWT secret is a demo value in `application.properties`; override `app.jwt.secret` outside local demos.
- Search results are intentionally limited to safe profile fields; password hashes are never returned.
