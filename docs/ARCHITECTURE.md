# HRMS integration overview

```text
           React frontend (later; single HRMS experience)
                             |
                  JWT Bearer access token
                             |
            +----------------+-------------------+
            |                                    |
       Employee Service                    Leave Service :8081
       Person 1 :8082                +-------------+------------------+
       owns accounts, JWT           | Controllers & Spring Security  |
                                    | RateLimitFilter -> Redis Lua  |
                                    | LeaveService -> MongoDB      |
                                    | NotificationService -> Redis |
                                    +-------------+----------------+
                                                  |
                              GET read-only profile (Resilience4j CB)
                                                  |
                                            Employee Service

              MongoDB = leave ledgers and durable notifications
              Redis = rate-limit state + profile cache + notification fan-out
              SSE = live employee notifications over an authenticated stream
```

## Leave processing
1. Employee gets a signed JWT from Person 1's **real** auth module (not implemented here).
2. Calls `POST /api/leaves`. Backend trusts only verified JWT `sub` as employee ID.
3. Redis atomically checks the request rate (capacity 5, refill 1/sec).
4. Spring validates dates, entitlements, and overlap with existing requests.
5. MongoDB persists the pending request in one per-employee/year/type ledger document.
6. Manager sends decision using manager's JWT. Authorization is role-based; self-approval is forbidden.
7. MongoDB uses a single-document conditional atomic update to change PENDING to APPROVED/REJECTED and, if approved, subtract leave balance in the **same atomic update**.
8. Notification is written to MongoDB; Redis Pub/Sub distributes the event to SSE clients connected to any leave-service replica.
9. Employee can fetch leave history, balances, notification history or stream live updates.

## Operational boundaries
- Pending and approved requests for a given employee/year/type are embedded within a bounded ledger document. This is deliberate to get atomic balance and status updates without Mongo replica-set transactions. Annual ledgers are bounded; revisit if a large volume of individual short absences creates document growth.
- This module does not implement login, autocomplete, payroll, job crawling, or sharding. Those belong to other teammates.
- A circuit breaker protects a **read-only** profile lookup to Person 1's real employee service. If no cached data is available during failure it returns **503**, never an invented employee profile.
- Notification persistence + Redis Pub/Sub provides durable notification history and best-effort live delivery. Redis Pub/Sub by itself is not durable; reconnecting clients should query history.
- Fine-grained approval-policy constraints (manager-to-employee relationship), leave policy variations, holiday calendars, and production identity-provider settings require agreement with the team before going live.
