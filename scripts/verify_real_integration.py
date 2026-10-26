#!/usr/bin/env python3
"""Verify the final Employee and Leave jars together against disposable databases.

Requires Java 21, Docker, and `mvn clean verify` in both active modules.
No dev JWTs, directory stubs, existing databases, or fixed account passwords.
"""
import base64
import datetime as dt
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent


def main():
    logs = Path(tempfile.mkdtemp(prefix="hrms-real-integration-"))
    processes, containers, files = [], [], []
    checks = 0

    def check(condition, label):
        nonlocal checks
        if not condition:
            raise AssertionError(label)
        checks += 1
        print(f"PASS {label}", flush=True)

    def docker(*args):
        return subprocess.check_output(["docker", *args], text=True).strip()

    def container(image, port):
        name = "hrms-integration-" + uuid.uuid4().hex[:12]
        docker("run", "--rm", "-d", "--name", name, "-p", f"127.0.0.1::{port}", image)
        containers.append(name)
        return int(docker("port", name, f"{port}/tcp").rsplit(":", 1)[1])

    def free_port():
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            return listener.getsockname()[1]

    def start(module, jar, env):
        output = (logs / f"{module}.log").open("w")
        files.append(output)
        process = subprocess.Popen(["java", "-jar", str(ROOT / module / "target" / jar)],
                                   cwd=logs, env=env, stdout=output, stderr=subprocess.STDOUT)
        processes.append(process)
        return process

    def request(base, path, token=None, data=None, method="GET", expected=200):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(base + path, method=method, headers=headers,
                                     data=None if data is None else json.dumps(data).encode())
        try:
            with urllib.request.urlopen(req, timeout=12) as response:
                status, body = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, body = error.code, error.read()
        if status not in (expected if isinstance(expected, tuple) else (expected,)):
            raise AssertionError(f"{method} {path}: expected {expected}, received {status}; {body[:400]!r}")
        return json.loads(body) if body else None

    def ready(base, path, process):
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"Application exited; inspect {logs}")
            try:
                request(base, path)
                return
            except (OSError, AssertionError):
                time.sleep(0.5)
        raise TimeoutError(f"Application did not start; inspect {logs}")

    try:
        mongo_port = container("mongo:7", 27017)
        redis_port = container("redis:7-alpine", 6379)
        employee_port, leave_port = free_port(), free_port()
        employee_base, leave_base = f"http://127.0.0.1:{employee_port}", f"http://127.0.0.1:{leave_port}"
        password, internal = secrets.token_urlsafe(24), secrets.token_urlsafe(40)
        # Prevent a developer's shell configuration from selecting real databases,
        # external config files or JVM-injected properties.
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(("SPRING_", "HRMS_", "EMPLOYEE_", "LEAVE_", "MONGODB_", "REDIS_"))
               and key not in {"JWT_SECRET", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
        env.update(HRMS_JWT_SECRET_BASE64=base64.b64encode(secrets.token_bytes(48)).decode(),
                   HRMS_JWT_ISSUER="hrms-employee-service", HRMS_JWT_AUDIENCE="hrms-leave-service",
                   EMPLOYEE_SERVICE_INTERNAL_TOKEN=internal, REDIS_HOST="127.0.0.1", REDIS_PORT=str(redis_port),
                   EMPLOYEE_SERVICE_PORT=str(employee_port), LEAVE_SERVICE_PORT=str(leave_port),
                   EMPLOYEE_SERVICE_URL=employee_base, EMPLOYEE_DIRECTORY_PATH="/api/internal/employees",
                   EMPLOYEE_DATABASE_URL="jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1", EMPLOYEE_DATABASE_USERNAME="sa",
                   EMPLOYEE_DATABASE_PASSWORD="", HRMS_BOOTSTRAP_ADMIN_ENABLED="true",
                   HRMS_ADMIN_EMAIL="admin@example.test", HRMS_ADMIN_PASSWORD=password,
                   MONGODB_URI=f"mongodb://127.0.0.1:{mongo_port}/hrms_integration", LEAVE_HOLIDAYS="",
                   LEAVE_REMINDERS_ENABLED="false", HRMS_RATE_CAPACITY="100")
        employee_process = start("person1-employee-service", "person1-employee-service-1.0.0.jar", env)
        ready(employee_base, "/api/auth/health", employee_process)
        leave_process = start("leave-service", "leave-service-0.2.0-SNAPSHOT.jar", env)
        ready(leave_base, "/actuator/health", leave_process)

        def login(email):
            return request(employee_base, "/api/auth/login", data={"email": email, "password": password}, method="POST")["token"]

        # Health may be available just before the bootstrap runner completes.
        for attempt in range(30):
            try:
                admin = login("admin@example.test")
                break
            except AssertionError:
                if attempt == 29:
                    raise
                time.sleep(0.3)

        def register(name, email, role, privileged=True):
            return request(employee_base, "/api/employees" if privileged else "/api/auth/register",
                           admin if privileged else None, {"name": name, "email": email, "password": password,
                           "department": "Engineering", "jobTitle": "Engineer", "role": role}, "POST", 201)

        manager = register("Assigned Manager", "manager@example.test", "MANAGER")
        outsider = register("Other Manager", "other@example.test", "MANAGER")
        employee = register("Integration Employee", "employee@example.test", "ADMIN", False)
        check(employee["role"] == "EMPLOYEE", "public registration cannot grant privileged roles")
        employee_id, manager_id = employee["employeeId"], manager["employeeId"]
        employee_token, manager_token, outsider_token = login(employee["email"]), login(manager["email"]), login(outsider["email"])
        claims = json.loads(base64.urlsafe_b64decode(employee_token.split(".")[1] + "=="))
        check(claims["sub"] == employee_id and claims["roles"] == ["EMPLOYEE"], "real login uses employee ID and Leave role claims")
        settings = f"/api/internal/employees/{employee_id}/leave-settings"
        request(employee_base, settings, admin, {"managerId": manager_id, "active": True}, "PUT")
        check(request(employee_base, f"/api/employees/{employee_id}", employee_token)["name"] == employee["name"], "original self-profile API still works")
        check(employee["name"] in request(employee_base, "/api/employees/suggest?prefix=Integration", employee_token)["suggestions"], "original Trie autocomplete still works")
        request(employee_base, settings, employee_token, {"active": True}, "PUT", 403)
        request(employee_base, settings, internal, {"active": False}, "PUT", (401, 403))
        request(employee_base, "/api/employees", internal, expected=(401, 403))
        check(request(employee_base, f"/api/internal/employees/{employee_id}", internal)["managerId"] == manager_id, "internal credential permits directory reads but cannot mutate or use public APIs")

        day = dt.datetime.now(dt.timezone(dt.timedelta(hours=5, minutes=30))).date() + dt.timedelta(days=10)
        while day.weekday() > 4:
            day += dt.timedelta(days=1)
        date, year = day.isoformat(), day.year
        balance_path = f"/api/leaves/me/balance?year={year}"
        check(request(leave_base, balance_path, employee_token)["annual"]["remaining"] == 20, "real Employee JWT is accepted by Leave")
        body = {"type": "ANNUAL", "startDate": date, "endDate": date, "session": "MORNING", "reason": "Private reason"}
        check(request(leave_base, "/api/leaves/preview", employee_token, body, "POST")["chargedDays"] == 0.5, "half-day preview")
        leave = request(leave_base, "/api/leaves", employee_token, body, "POST", 201)
        check(request(leave_base, balance_path, employee_token)["annual"]["remaining"] == 20, "pending requests do not deduct balance")
        check(any(item["id"] == leave["id"] for item in request(leave_base, f"/api/leaves/pending?year={year}", manager_token)), "assigned manager sees pending requests via real directory")
        decision = f"/api/leaves/{employee_id}/{leave['id']}/decision"
        request(leave_base, decision, outsider_token, {"approve": True}, "PUT", 403)
        request(leave_base, decision, employee_token, {"approve": True}, "PUT", 403)
        approved = request(leave_base, decision, manager_token, {"approve": True}, "PUT")
        check(approved["status"] == "APPROVED" and request(leave_base, balance_path, employee_token)["annual"]["remaining"] == 19.5, "only assigned reviewer approves and deducts exactly half a day")
        calendar = request(leave_base, f"/api/leaves/calendar?from={date}&to={date}", manager_token)["items"]
        check(len(calendar) == 1 and calendar[0]["employeeName"] == employee["name"] and "reason" not in calendar[0] and "type" not in calendar[0], "team calendar resolves real names and hides leave type/reason")
        request(leave_base, f"/api/leaves/reports?from={date}&to={date}", manager_token)
        check(request(leave_base, "/api/leaves/requests?status=APPROVED", manager_token)["totalElements"] == 1, "manager search and reporting use authoritative direct reports")
        for attempt in range(30):
            notifications = request(leave_base, "/api/leaves/notifications", employee_token)
            if notifications:
                break
            time.sleep(0.2)
        check(bool(notifications), "approval notifications persist through Mongo outbox and Redis dispatch")
        request(leave_base, f"/api/leaves/notifications/{notifications[0]['id']}/read", employee_token, method="PUT", expected=204)
        check(request(leave_base, "/api/leaves/notifications", employee_token)[0]["readAt"] is not None, "notification read state persists")
        request(leave_base, f"/api/leaves/me/{leave['id']}/cancel", employee_token, {"reason": "Plans changed"}, "POST")
        check(request(leave_base, balance_path, employee_token)["annual"]["remaining"] == 20, "cancellation refunds approved balance")
        check(request(leave_base, "/api/leaves/me/audit", employee_token)["totalElements"] == 3, "submit, approval and cancellation are audited")
        request(leave_base, "/api/leaves/me/employee-profile", employee_token)
        request(employee_base, settings, admin, {"active": False}, "PUT")
        time.sleep(1)  # Allow the normal per-user write bucket to refill.
        request(leave_base, "/api/leaves", employee_token, body, "POST", 403)
        check(True, "deactivated employee cannot submit even after a profile was cached")
        request(employee_base, settings, admin, {"active": True}, "PUT")
        employee_process.terminate()
        employee_process.wait(timeout=20)
        time.sleep(1)
        request(leave_base, "/api/leaves", employee_token, body, "POST", 503)
        check(len(request(leave_base, "/api/leaves/me", employee_token)) == 1, "Employee outage blocks writes while durable leave history stays readable")
        print(f"\n{checks} integration checks passed. Logs: {logs}", flush=True)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
        for output in files:
            output.close()
        for name in containers:
            subprocess.run(["docker", "stop", "-t", "1", name], stdout=subprocess.DEVNULL, check=False)


if __name__ == "__main__":
    main()
