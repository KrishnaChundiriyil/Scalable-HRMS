# Run the final Employee and Leave services together

Use `person1-employee-service/` from the final ZIP and `leave-service/`. The older
`scalable-hrms-person1/` snapshot is preserved but is not the active Employee app.
There is no new frontend or production directory mock in this integration.

## What changed

The teammate's JPA/H2 database, employee IDs, public registration, profile CRUD,
directory search, Redis caches, Trie autocomplete, original profile DTO and login
response remain. The original Maven dependencies and versions remain too.

The small integration additions are:

- Login JWTs keep the employee ID subject, email and singular role, and add the
  issuer, audience and roles array Leave already requires. Both apps use the same
  external base64 HS256 signing key.
- Employee records gain an active flag and assigned manager ID. Existing rows
  whose active field is null are treated as active for compatibility.
- HR/Admin can create MANAGER accounts and assign managers/active status through
  a separate endpoint. Self-service profile updates cannot change these fields.
- The directory API enforces current self/assigned-manager/HR/Admin scope and
  reads directly from the employee database for approval authorization.
- A separate configured credential permits internal directory GET requests for
  reminder lookups. It cannot log in, change employees or approve leave.
- Employee uses port 8080 and Leave uses 8081. A fixed demo signing key and
  automatic known-password admin are replaced by explicit environment settings.

Employee remains on the teammate's H2 database. Leave remains on MongoDB, and both
use Redis. The services communicate through HTTP; they do not need the same
database. A MongoDB conversion would change her persistence layer unnecessarily.

## Local setup

Requires Java 21 (which also builds the Java 17 Employee module), Maven, Docker.
From the repository root:

```bash
bash scripts/setup_local_env.sh
set -a
source .env
set +a
docker compose -f infrastructure/docker-compose-dev.yml up -d
```

The setup script preserves existing values and adds missing secret variables.
If your `.env` already has an empty secret assignment, fill it with a strong
secret before starting. Do not commit `.env`. Both terminals must load this same
file. Leave `HRMS_JWT_JWK_SET_URI` unset for this shared-key integration.

In the Employee terminal, choose your own first admin credentials, then start:

```bash
set -a; source .env; set +a
export HRMS_BOOTSTRAP_ADMIN_ENABLED=true
read -r -p 'First admin email: ' HRMS_ADMIN_EMAIL
read -r -s -p 'First admin password (8+ characters): ' HRMS_ADMIN_PASSWORD
echo
export HRMS_ADMIN_EMAIL HRMS_ADMIN_PASSWORD
cd person1-employee-service
mvn clean verify
mvn spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

The account is created only if that email is absent. Disable bootstrap on future
starts. Employee data persists in `person1-employee-service/data/hrmsdb` when run
from that module. This does not import data from the obsolete Mongo Employee app.
H2 console is disabled unless `ENABLE_H2_CONSOLE=true` is explicitly set.

In the Leave terminal from the repository root:

```bash
set -a; source .env; set +a
cd leave-service
mvn clean verify
mvn spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

Health: Employee `http://localhost:8080/api/auth/health`, Leave
`http://localhost:8081/actuator/health`. A health response alone does not verify
the integration; use the real flow below or run the automated check.

## Follow the business flow in Postman

1. Log in at `POST http://localhost:8080/api/auth/login` with the bootstrap email
   and password. Copy `token`; send it as `Authorization: Bearer <token>`.
2. As Admin create a manager through `POST /api/employees` with name, email,
   password, department and `"role":"MANAGER"`. Create/register a normal employee.
   Public `POST /api/auth/register` always creates an EMPLOYEE and returns a
   profile, not a token; use login afterwards.
3. Assign the manager as Admin with
   `PUT /api/internal/employees/{employeeId}/leave-settings`:
   `{"managerId":"<manager employee ID>","active":true}`.
4. Log in as the employee. Use that token on port 8081 for
   `POST /api/leaves/preview` and `POST /api/leaves`:
   `{"type":"ANNUAL","startDate":"2027-02-01","endDate":"2027-02-01","session":"MORNING","reason":"Appointment"}`.
   Choose a future working day. Pending requests leave the balance unchanged.
5. Log in as the manager. `GET /api/leaves/pending` shows their direct reports.
   Approve using `PUT /api/leaves/{employeeId}/{leaveId}/decision` with
   `{"approve":true}`. Another manager cannot approve it.
6. With the employee token, check `GET /api/leaves/me/balance`, `/me`,
   `/me/audit` and `/notifications`. Approval deducts 0.5 day. Cancel before the
   start date via `POST /api/leaves/me/{leaveId}/cancel` with
   `{"reason":"Plans changed"}`; the 0.5 day is refunded.

The Employee Postman collection is in `person1-employee-service/postman/` and the
Leave collection is in `docs/postman/`. Set collection URLs/tokens separately;
the internal directory credential is never the user's login token.

## Automated real integration check

After building both modules, from the repository root:

```bash
python3 scripts/verify_real_integration.py
```

This starts both packaged apps on temporary ports with a temporary H2 database,
new disposable MongoDB/Redis containers and generated test credentials. It logs
in through the actual Employee API and checks approval, exact half-day charging,
cancellation refunds, audit, privacy, read state, directory credential scope,
deactivation and dependency failure. It stops its own processes and containers
afterwards and does not reset your existing development data. Logs are saved in
the printed `/tmp/hrms-real-integration-*` folder. CI runs the same check.
