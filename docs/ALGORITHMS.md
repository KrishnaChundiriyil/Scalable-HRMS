# Algorithms and data structures

## 1. Date calculation and leave submission

1. Authenticate JWT and validate the employee against the live directory.
2. Validate configured type, future ordered dates, reason, and optional idempotency key.
3. For a matching stored idempotency key, return the existing request or reject a changed payload.
4. Walk inclusive dates. Count every day for calendar policies; otherwise skip
   weekends and the holiday HashSet. Group chargeable days into a year-to-count map.
5. Load one employee account. Compare dates with all PENDING/APPROVED requests,
   across every type/year. Reject any overlapping interval.
6. Check current approved usage and yearly allowance; pending entries reserve nothing.
7. Append a PENDING request with captured charges, and snapshot applicable yearly allowances.
8. Save with @Version. On a conflicting write, reload and repeat checks; stop after 12 conflicts.

Structures: array/list of requests, HashSet of holidays, map of yearly charges and
allowance snapshots. For n requests and d range days, validation is O(n+d). The
configured per-request/date limits bound d; request history has a configured cap.

## 2. Decision and cancellation

1. For a decision, require a live active reviewer and target; check role/direct-manager
   relationship and prevent self-decision. Cancellation uses only the token subject.
2. Load the account and require an allowed status/date transition.
3. For approval, recompute approved usage and reject insufficient balance. Require
   an eligibility review note where configured. Rejection requires a reason.
4. Change status, capture actor/time/reason, and append a UUID notification event
   to the same account's outbox.
5. Version-check and save that document once. Retry conflicts against new state.
6. Balance is the yearly entitlement minus currently approved charges. Cancellation
   refunds once because a request cannot transition from CANCELLED again.

One MongoDB document write commits status and event together. There is no separate
balance counter that can drift from approved history, and no distributed lock is
required. Time is O(n) for usage/overlap scans at project scale.

## 3. Redis token bucket

A Redis hash per authenticated identity stores `tokens` and `updatedAt`.
A single Lua script uses Redis TIME so all application instances use one clock:

```text
elapsed = max(0, redisNow - updatedAt)
tokens = min(capacity, tokens + elapsed * refillPerSecond)
if tokens >= 1: tokens -= 1; retry = 0
else: retry = ceil((1 - tokens) / refillPerSecond)
store tokens and redisNow; refresh key expiry
return retry
```

Lua executes atomically; a burst cannot overspend tokens. Positive retry produces
429 + Retry-After. Redis failure rejects mutations with 503. GET/history/SSE is
excluded. Work and storage per active identity are O(1).

## 4. Circuit breaker and bounded HTTP reads

CLOSED: call Employee Service through bounded connect/read timeouts and a small
retry budget for transport/502/503/504 failures. Record the final outcome in the
sliding window. Client 4xx responses are not dependency-outage samples.

OPEN: reject dependency calls immediately. After the configured wait,
HALF_OPEN admits a limited number of probes. Successful probes close the circuit;
failed probes reopen it. Logs capture transitions.

Read-only self-profile may return a five-minute Redis profile with source=CACHE
and stale=true. A write never uses that fallback. Missing/denied/inactive identity
or unavailable authority prevents mutation.

## 5. Persistent notifications and SSE

1. Store the notification outbox event atomically with the leave transition.
2. A scheduled dispatcher upserts the event by its stable ID into hr_notifications.
3. Publish its JSON on the Redis channel.
4. Remove only that outbox ID and increment the employee account version.
5. On failure leave it queued; retry later. Duplicate upserts do not duplicate
   documents, but live messages can repeat, so clients deduplicate by ID.
6. Redis listeners route only to that recipient's in-memory SSE emitters.
7. Heartbeat comments keep streams active; timeout/expiry/disconnect removes them.
8. On reconnect, retrieve history with recipient-owned cursors, paginate and
   reconcile delayed events. Last-Event-ID replays at most 100 saved notifications.

Structures: embedded outbox list, MongoDB notification collection with recipient/
time/ID index, ConcurrentHashMap of recipient to CopyOnWriteArrayList of emitters.
Redis Pub/Sub is live transport; MongoDB is durable history.


## Additional algorithms

Half-day allocation: validate enabled type and one working date; charge 0.5 to its
year. Two intersecting requests conflict unless both are single-date half days
with opposite sessions. All usage sums use only 1.0/0.5 increments, and the same
version-checked balance checks apply to concurrent approvals/refunds.

Preview: compute candidate daily/yearly allocation, compare current approved
usage and active overlaps, return the balance/shortfall explanation without
adding a request. Confirmation repeats validation; a preview reserves nothing.

Pagination: authoritative scope match -> indexed account candidate match -> unwind
requests -> exact row filters -> stable sort -> facet page items and total count.
Audit events use a per-account sequence rather than timestamps alone to order ties.

Reports: traverse scoped date-filtered pages, count current statuses, sum only
currently approved saved daily allocations inside the range into month/type and
paid/unpaid buckets, and average created-to-decided durations. Flag old records
without per-date allocation instead of inventing charges.

Reminders: scan pending requests older than 48 hours; resolve a current active
manager; reload the account; require pending/not-past-start and at least 24 hours
since the last reminder; append an in-app outbox event and timestamp; save with
@Version. Concurrent scans reload on conflict and see the winner's timestamp.

Notification reads: match recipient plus notification ID and readAt=null, then set
readAt once. Read-all updates only that recipient's unread documents; history is
retained and notification retries do not clear readAt.
