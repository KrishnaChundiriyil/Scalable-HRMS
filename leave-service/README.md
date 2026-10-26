# Leave Management and Reliability backend

Java 21, Spring Boot 3.5.16, MongoDB, Redis Lua token bucket, Resilience4j and SSE.
This is Person 2's backend only. No frontend or production fake Employee Service
is included.

## Start locally

From the repository root:

```bash
bash scripts/setup_local_env.sh
set -a
source .env
set +a
docker compose -f infrastructure/docker-compose-dev.yml up -d
cd leave-service
mvn clean verify
mvn spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

Open <http://localhost:8081/actuator/health>. Health does not mean Employee Service
is available: actual submissions/cancellations/manager operations require Person
1's service on EMPLOYEE_SERVICE_URL. Until then they fail safely; run clean verify
for a complete isolated MongoDB/Redis/API test without that teammate's server.

`.env.example` lists settings; setup_local_env.sh generates a private HMAC key.
Person 1 must use the same development signing key, issuer and audience, or supply
JWKS/RS256 configuration. Never commit .env or access tokens.

## Policy and API

Seven editable types: Annual, Sick, Casual, Bereavement, Paternity, Maternity and
Unpaid. Ordinary leave counts Monday–Friday excluding configured company holidays.
Maternity/unpaid use calendar days. Pending requests do not reserve balance;
approval deducts, and pre-start cancellation refunds. Parental approval records
an eligibility review. See `../docs/LEAVE_POLICY.md` for exact defaults and limits.

Read `../docs/INTEGRATION_CONTRACTS.md` for every endpoint and JWT contract. Import
`../docs/postman/Scalable-HRMS-Leave.postman_collection.json` for business requests.
The normal flow is POST /api/leaves -> manager PUT decision -> GET balance/history/
notifications -> optional POST /api/leaves/me/{id}/cancel before start.

## Docker application profile

With the old host Java application stopped, from repository root:

```bash
docker compose --env-file .env -f infrastructure/docker-compose-dev.yml --profile app up -d --build
```

The optional app profile builds leave-service/Dockerfile, runs Java as a non-root
user, connects to the same MongoDB/Redis services, and exposes only localhost:8081.
For an Employee Service on the host, use DOCKER_EMPLOYEE_SERVICE_URL (default
http://host.docker.internal:8082). The Employee Service must listen on an interface
reachable from the Docker bridge. Bare up -d starts only MongoDB/Redis.

Container image build skips tests; run mvn clean verify separately. Compose stop
preserves data; do not use down -v unless you deliberately intend to delete it.

## Migration and operating limits

Stop all previous application writers before rollout. AccountStore imports old
leave_ledgers into leave_accounts without deleting the source, preserving IDs,
days and allowance snapshots. New accounts serialize all types/years via @Version.
The outbox commits with decisions and retries durable notification delivery.

At project scale an employee account supports 5,000 requests and 1,000 queued
notifications; plan archival before the limit. Manager lists are currently
unpaginated. Half days, carry-forward/proration, automatic statutory eligibility,
full payroll/Kafka modules and the frontend are outside this implementation.

Start learning with `../docs/RUN_AND_UNDERSTAND.md`, then CODE_WALKTHROUGH.md,
ARCHITECTURE.md, ALGORITHMS.md, TESTING.md and INTEGRATION_CONTRACTS.md.
