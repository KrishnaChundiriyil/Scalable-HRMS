# Understand and run your leave backend

## The flow in easy language

Think of this as a digital leave register:

1. The employee signs in through Person 1's Employee Service. Its signed token is
   their ID card. Your backend verifies who issued it, who it belongs to, expiry
   and whether it is intended for this service.
2. They choose a leave type, dates and a reason. Redis checks that they are not
   sending requests too fast. The backend checks their active employee status.
3. The leave rules count the days, check their current balance, and reject
   overlapping requests. A valid request is saved as PENDING. No days are reserved.
4. Their assigned manager or HR/Admin reviews it. Approval checks the balance
   again and marks it APPROVED. Rejection records a reason and deducts nothing.
5. A notification is queued in the same save as the decision. A worker stores
   durable notification history and sends a live message through Redis/SSE.
6. The employee can cancel pending or approved leave before its start date.
   Approved days are refunded once; their original request remains in history.

```text
Signed token -> Security -> Redis rate check -> Live Employee Service check
                                                      |
                                             Leave rules + MongoDB
                                                      |
                                           Manager approves/rejects
                                                      |
                                 Saved decision + pending notification together
                                                      |
                                  Notification history -> Redis -> live SSE
```

MongoDB is the permanent register. Redis handles fast temporary state and live
message transport. Spring Boot runs the API and rules. Resilience4j stops repeated
calls to a failing Employee Service and probes when it may have recovered.

## What you can run without your teammate

The complete isolated verification suite starts real disposable MongoDB/Redis
containers and uses an Employee Service stub located only in test source:

```bash
cd /home/krish/Downloads/Scalable-HRMS/leave-service
mvn clean verify
```

31 tests passed during implementation. This includes approval, cancellation,
manager permissions, concurrent requests, live SSE, rate limits and circuit
recovery. Reports are in target/surefire-reports; the regular local database is
not used by those tests. Docker must be running and accessible to your user.

## Start the live backend

Stop any older leave-service instance first. From the repository root:

```bash
cd /home/krish/Downloads/Scalable-HRMS
bash scripts/setup_local_env.sh
set -a
source .env
set +a
docker compose -f infrastructure/docker-compose-dev.yml up -d
cd leave-service
mvn spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

Open <http://localhost:8081/actuator/health>. UP means the backend's configured
health checks succeed. It does not mean that the external Employee Service exists.
This is an API backend: the root URL is not an employee webpage.

Unlike the original starter, submissions, approvals and cancellations now require
live Employee Service validation. With that teammate's service absent they return
503; this is intentional safe behavior, not permission to fabricate employee data.
Read your own locally stored history/balances with a valid signed token, or run
the isolated test suite to see a complete flow before Person 1 is ready.

## See and send business requests

Import docs/postman/Scalable-HRMS-Leave.postman_collection.json into Postman. Supply
real employee/manager tokens and the employee's ID. Apply -> pending list -> approve
-> balance -> notification history -> cancel before start -> restored balance.
Use another pending application for rejection, with a rejection reason.

Once Person 1 has existing development identities and the same development key:

```bash
cd /home/krish/Downloads/Scalable-HRMS
export DEMO_EMPLOYEE_ID=EMP001
export DEMO_MANAGER_ID=MGR001
python3 scripts/guided_demo.py
```

Use your real test IDs, and make sure managerId is the assigned manager. This script
creates local leave records and prints explanatory PASS lines. It does not create
employee accounts. The local JWT generator is a development utility only.

## Inspect MongoDB

```bash
docker compose -f infrastructure/docker-compose-dev.yml exec mongodb mongosh hrms_leave
```

Inside the shell, replace EMP001 with your employee ID:

```javascript
db.leave_accounts.find({_id: "EMP001"}).pretty()
db.hr_notifications.find({recipientId: "EMP001"}).pretty()
```

The account contains requests, allowance snapshots and a pending outbox. Look for
status, chargedDays, reviewedBy, rejectionReason and cancellation fields. The
outbox drains after delivery, while hr_notifications keeps the history. Original
leave_ledgers remain for migration reference and are no longer written by this
version. Type exit to leave the MongoDB shell.

## Study for your viva

Read LeaveController first (URLs), LeaveService second (business rules), then
LeaveAccount/@Version (concurrency), LeavePolicy (day counting), EmployeeAccess
(manager permissions), RedisRateLimiter (Lua), EmployeeDirectoryClient (circuit
breaker), and NotificationService (outbox + SSE). CODE_WALKTHROUGH.md explains each
important class/method and annotation; ALGORITHMS.md gives presentation steps.

See LEAVE_POLICY.md for your agreed rules and editable defaults, TESTING.md for
actual verification, and INTEGRATION_CONTRACTS.md for what teammates must provide.
The frontend, actual employee login, payroll calculations and Kafka remain separate
team integrations. Half days, carry-forward and automatic legal eligibility are
not implemented policies in this version.
