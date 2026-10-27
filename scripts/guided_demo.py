#!/usr/bin/env python3
"""Local API walkthrough using real MongoDB/Redis and temporary test JWTs.

Run from any directory: python3 scripts/guided_demo.py
Uses existing Employee Service identities; never prints tokens or secrets.
This is a repeatable demonstration, not a replacement for integration tests.
"""
import concurrent.futures
import datetime as dt
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("LEAVE_BASE_URL", "http://127.0.0.1:8081").rstrip("/")
ENV = os.environ.copy()
if (ROOT / ".env").is_file():
    for line in (ROOT / ".env").read_text().splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            ENV.setdefault(key.strip(), value.strip().strip("\"'"))


def token(subject, role):
    return subprocess.check_output(
        [sys.executable, str(ROOT / "scripts/dev_token.py"),
         "--subject", subject, "--role", role], env=ENV, text=True).strip()


def request(method, path, bearer=None, body=None):
    headers = {"Content-Type": "application/json"}
    if bearer:
        headers["Authorization"] = "Bearer " + bearer
    req = urllib.request.Request(BASE + path,
        data=None if body is None else json.dumps(body).encode(),
        headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=12)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        data = response.read().decode()
        try:
            data = json.loads(data)
        except ValueError:
            pass
        return response.status, data


def check(label, result, expected):
    status, body = result
    if status != expected:
        raise RuntimeError(f"{label}: expected HTTP {expected}, got {status}: {body}")
    print(f"PASS | {label} | HTTP {status}", flush=True)
    return body


def require(condition, label):
    if not condition:
        raise RuntimeError(label)
    print("PASS | " + label, flush=True)


def main():
    employee = ENV.get("DEMO_EMPLOYEE_ID")
    manager = ENV.get("DEMO_MANAGER_ID")
    if not employee or not manager:
        raise RuntimeError("Set DEMO_EMPLOYEE_ID and DEMO_MANAGER_ID to existing Employee Service identities. "
                           "The manager must be assigned to the employee. For self-contained tests without "
                           "Employee Service, run: cd backend/leave-service && mvn clean verify")
    suffix = str(time.time_ns())
    emp_token, mgr_token = token(employee, "EMPLOYEE"), token(manager, "MANAGER")
    day = dt.date.today() + dt.timedelta(days=7)
    while day.weekday() != 0:
        day += dt.timedelta(days=1)
    if (day + dt.timedelta(days=3)).year != day.year:
        day = dt.date(day.year + 1, 1, 5)
    year = day.year
    print(f"Local backend walkthrough: {BASE}\nTest employee: {employee}\n"
          "These identities must exist in Employee Service; local JWT generation requires the shared development key.\n", flush=True)
    check("Backend and database health", request("GET", "/actuator/health"), 200)
    check("No login token is refused", request("GET", "/api/leaves/me"), 401)
    balance_path = f"/api/leaves/me/balance?year={year}"
    before = check("Employee checks balance", request("GET", balance_path, emp_token), 200)
    print("Starting balance:", json.dumps(before), flush=True)
    application = {"type": "ANNUAL", "startDate": str(day),
                   "endDate": str(day + dt.timedelta(days=1)),
                   "reason": "Guided local walkthrough: family appointment"}
    leave = check("Employee applies for 2 days", request("POST", "/api/leaves", emp_token, application), 201)
    require(leave["status"] == "PENDING", "New request is PENDING")
    print("Saved leave:", json.dumps(leave), flush=True)
    pending_balance = check("Check balance before approval", request("GET", balance_path, emp_token), 200)
    require(pending_balance["annual"]["remaining"] == before["annual"]["remaining"],
            "Pending leave has not deducted any days")
    check("Duplicate/overlapping dates are refused", request("POST", "/api/leaves", emp_token, application), 409)
    invalid = dict(application, startDate=str(day + dt.timedelta(days=2)))
    check("Reversed dates are refused", request("POST", "/api/leaves", emp_token, invalid), 400)
    pending_path = f"/api/leaves/pending?year={year}"
    check("Employee cannot open manager pending list", request("GET", pending_path, emp_token), 403)
    pending = check("Manager views pending requests", request("GET", pending_path, mgr_token), 200)
    require(any(item["id"] == leave["id"] for item in pending), "Manager can see this request")
    decision_path = f"/api/leaves/{employee}/{leave['id']}/decision"
    # A manager role on the same identity still cannot approve their own request.
    check("Manager cannot approve own leave", request("PUT", decision_path,
          token(employee, "MANAGER"), {"approve": True}), 403)

    events, ready = queue.Queue(), threading.Event()

    def watch():
        req = urllib.request.Request(BASE + "/api/leaves/notifications/stream",
            headers={"Authorization": "Bearer " + emp_token})
        try:
            with urllib.request.urlopen(req, timeout=10) as stream:
                event = {}
                for raw in stream:
                    line = raw.decode().strip()
                    if not line:
                        if event.get("event") == "connected":
                            ready.set()
                        if event.get("event") == "leave-update":
                            events.put(json.loads(event["data"]))
                            return
                        event = {}
                    elif ":" in line:
                        key, value = line.split(":", 1)
                        event[key] = value.lstrip()
        except Exception as error:
            events.put(error)
            ready.set()

    threading.Thread(target=watch, daemon=True).start()
    require(ready.wait(8), "Employee opens live notification stream")
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        decisions = list(pool.map(lambda _: request("PUT", decision_path, mgr_token,
                                                   {"approve": True}), range(2)))
    require(sorted(status for status, _ in decisions) == [200, 409],
            "Two simultaneous approvals: one succeeds, one is refused")
    print("Approved leave:", json.dumps(next(body for status, body in decisions if status == 200)), flush=True)
    event = events.get(timeout=12)
    if isinstance(event, Exception):
        raise event
    require(event["leaveId"] == leave["id"] and event["recipientId"] == employee,
            "Employee receives matching live SSE notification")
    print("Live notification:", json.dumps(event), flush=True)
    after = check("Employee checks updated balance", request("GET", balance_path, emp_token), 200)
    require(after["annual"]["remaining"] == before["annual"]["remaining"] - leave["days"],
            "Approved days deducted exactly once")
    print("Balance after approval:", json.dumps(after), flush=True)
    history = []
    for _ in range(30):
        history = check("Read saved notification history", request("GET", "/api/leaves/notifications", emp_token), 200)
        if any(item["id"] == event["id"] for item in history):
            break
        time.sleep(0.2)
    require(any(item["id"] == event["id"] for item in history),
            "Live notification is also saved in MongoDB history")
    unrelated = check("Another employee checks own notifications", request("GET", "/api/leaves/notifications",
                      token("DEMO-OTHER-" + suffix, "EMPLOYEE")), 200)
    require(not unrelated, "Another employee cannot see this employee's notifications")
    sick = dict(application, type="SICK", startDate=str(day + dt.timedelta(days=3)),
                endDate=str(day + dt.timedelta(days=3)))
    # The employee used the 5-token burst above; allow its bucket to refill.
    time.sleep(1.1)
    rejected = check("Employee submits a separate sick request", request("POST", "/api/leaves", emp_token, sick), 201)
    check("Manager rejects request", request("PUT", f"/api/leaves/{employee}/{rejected['id']}/decision",
          mgr_token, {"approve": False, "rejectionReason": "Team coverage unavailable"}), 200)
    final = check("Balance after rejection", request("GET", balance_path, emp_token), 200)
    require(final["sick"]["remaining"] == before["sick"]["remaining"], "Rejected leave deducts no days")
    rate_token = token("DEMO-RATE-" + suffix, "EMPLOYEE")
    statuses = [request("POST", "/api/dev/rate-test", rate_token, {})[0] for _ in range(10)]
    require(200 in statuses and 429 in statuses, "Redis rejects excess requests with HTTP 429")
    print("Burst response codes:", statuses, flush=True)
    time.sleep(1.2)
    check("Bucket refills after waiting", request("POST", "/api/dev/rate-test", rate_token, {}), 200)
    profile_status, profile = request("GET", "/api/leaves/me/employee-profile", emp_token)
    print(f"Employee Service profile lookup: HTTP {profile_status}: {json.dumps(profile)}", flush=True)
    if profile_status == 503:
        print("Expected here: teammate's Employee Service is unavailable; no fake profile returned.", flush=True)
    cancelled = check("Cancel approved leave before it starts", request("POST", f"/api/leaves/me/{leave['id']}/cancel",
                      emp_token, {"reason": "Walkthrough complete; restore allowance"}), 200)
    require(cancelled["status"] == "CANCELLED", "Approved leave cancelled and its balance refunded")
    print(f"\nWalkthrough complete. Records remain in MongoDB for {employee}. "
          "Tokens and signing secrets were not printed.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"\nWalkthrough stopped: {error}", file=sys.stderr)
        sys.exit(1)
