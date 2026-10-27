#!/usr/bin/env python3
"""Verify Employee and Leave, optionally Recruitment, against disposable databases.

Requires Java 21, Docker, and `mvn clean verify` in each selected module.
No dev JWTs, directory stubs, existing databases, or fixed account passwords.
"""
import base64
import argparse
from concurrent.futures import ThreadPoolExecutor
import datetime as dt
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--with-recruitment',action='store_true',help='Also verify Recruitment using a real Kafka broker')
    parser.add_argument('--with-payroll',action='store_true',help='Also verify three Payroll instances through Nginx')
    parser.add_argument('--frontend',action='store_true',help='Run browser workflows against these real disposable services')
    options=parser.parse_args()
    if options.frontend:
        options.with_recruitment=True
        options.with_payroll=True
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

    def kafka_container():
        port=free_port()
        name='hrms-integration-'+uuid.uuid4().hex[:12]
        settings={
            'KAFKA_NODE_ID':'1', 'KAFKA_PROCESS_ROLES':'broker,controller',
            'KAFKA_LISTENERS':'PLAINTEXT://:9092,CONTROLLER://:9093',
            'KAFKA_ADVERTISED_LISTENERS':f'PLAINTEXT://127.0.0.1:{port}',
            'KAFKA_CONTROLLER_LISTENER_NAMES':'CONTROLLER',
            'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP':'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
            'KAFKA_CONTROLLER_QUORUM_VOTERS':'1@localhost:9093',
            'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR':'1',
            'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR':'1',
            'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR':'1',
            'KAFKA_HEAP_OPTS':'-Xms128m -Xmx256m',
        }
        args=['run','--rm','-d','--name',name,'-p',f'127.0.0.1:{port}:9092']
        for key,value in settings.items():
            args+=['-e',f'{key}={value}']
        docker(*args,'apache/kafka:3.8.0')
        containers.append(name)
        return port

    def start(module, jar, env,label=None):
        output = (logs / f"{label or module}.log").open("w")
        files.append(output)
        process = subprocess.Popen(["java", "-Xms64m", "-Xmx384m", "-jar", str(ROOT / "backend" / ("employee-service" if module == "person1-employee-service" else module) / "target" / jar)],
                                   cwd=logs, env=env, stdout=output, stderr=subprocess.STDOUT)
        processes.append(process)
        return process

    def stop(process):
        # Outage checks must stop even when a browser left a long-lived SSE stream.
        # Only processes/groups created by this verifier are signalled.
        group=getattr(process,'hrms_group',False)
        try:
            if group:os.killpg(process.pid,signal.SIGTERM)
            elif process.poll() is None:process.terminate()
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            if group:os.killpg(process.pid,signal.SIGKILL)
            else:process.kill()
            process.wait(timeout=10)
        except ProcessLookupError:pass
        if group:
            try:os.killpg(process.pid,signal.SIGKILL)
            except ProcessLookupError:pass

    def request(base, path, token=None, data=None, method="GET", expected=200,content_type="application/json"):
        headers = {"Content-Type": content_type}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(base + path, method=method, headers=headers,
                                     data=data if isinstance(data,bytes) else None if data is None else json.dumps(data).encode())
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
        kafka_port=kafka_container() if options.with_recruitment else None
        employee_port, leave_port = free_port(), free_port()
        employee_base, leave_base = f"http://127.0.0.1:{employee_port}", f"http://127.0.0.1:{leave_port}"
        password, internal = secrets.token_urlsafe(24), secrets.token_urlsafe(40)
        # Prevent a developer's shell configuration from selecting real databases,
        # external config files or JVM-injected properties.
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(("SPRING_", "HRMS_", "EMPLOYEE_", "LEAVE_", "MONGODB_", "REDIS_", "RECRUITMENT_", "KAFKA_", "APP_", "PAYROLL_"))
               and key not in {"JWT_SECRET", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "SERVER_PORT"}}
        env.update(HRMS_JWT_SECRET_BASE64=base64.b64encode(secrets.token_bytes(48)).decode(),
                   HRMS_JWT_ISSUER="hrms-employee-service", HRMS_JWT_AUDIENCE="hrms-leave-service",
                   HRMS_RECRUITMENT_JWT_AUDIENCE="hrms-recruitment-service",
                   HRMS_PAYROLL_JWT_AUDIENCE="hrms-payroll-service",
                   EMPLOYEE_SERVICE_INTERNAL_TOKEN=internal, REDIS_HOST="127.0.0.1", REDIS_PORT=str(redis_port),
                   EMPLOYEE_SERVICE_PORT=str(employee_port), LEAVE_SERVICE_PORT=str(leave_port),
                   EMPLOYEE_SERVICE_URL=employee_base, EMPLOYEE_DIRECTORY_PATH="/api/internal/employees",
                   EMPLOYEE_MONGODB_URI=f"mongodb://127.0.0.1:{mongo_port}/hrms_employee_integration", EMPLOYEE_DATABASE_USERNAME="sa",
                   EMPLOYEE_DATABASE_PASSWORD="", HRMS_BOOTSTRAP_ADMIN_ENABLED="true",
                   HRMS_ADMIN_EMAIL="admin@example.test", HRMS_ADMIN_PASSWORD=password,
                   MONGODB_URI=f"mongodb://127.0.0.1:{mongo_port}/hrms_integration", LEAVE_HOLIDAYS="",
                   LEAVE_REMINDERS_ENABLED="false", HRMS_RATE_CAPACITY="100")
        employee_process = start("person1-employee-service", "person1-employee-service-1.0.0.jar", env)
        ready(employee_base, "/api/auth/health", employee_process)
        leave_process = start("leave-service", "leave-service-0.2.0-SNAPSHOT.jar", env)
        ready(leave_base, "/actuator/health", leave_process)
        recruitment_base=None
        if options.with_recruitment:
            recruitment_base=f'http://127.0.0.1:{free_port()}'
            recruitment_env=dict(env,RECRUITMENT_SERVICE_PORT=recruitment_base.rsplit(':',1)[1],
                RECRUITMENT_BASE_URL=recruitment_base, RECRUITMENT_STORAGE_ROOT=str(logs/'resumes'),
                RECRUITMENT_MONGODB_URI=f'mongodb://127.0.0.1:{mongo_port}/hrms_recruitment_integration',
                KAFKA_BOOTSTRAP_SERVERS=f'127.0.0.1:{kafka_port}',RECRUITMENT_KAFKA_TOPIC='hrms.integration.events',
                RECRUITMENT_KAFKA_GROUP='hrms-integration-notifications')
            recruitment_process=start('recruitment-service','recruitment-service-1.0.0.jar',recruitment_env)
            ready(recruitment_base,'/actuator/health',recruitment_process)

        payroll_base=None
        payroll_bases=[]
        if options.with_payroll:
            payroll_processes=[]
            for number in range(3):
                port=free_port()
                payroll_bases.append(f'http://127.0.0.1:{port}')
                payroll_env=dict(env,PAYROLL_SERVICE_PORT=str(port),
                    PAYROLL_MONGODB_URI=f'mongodb://127.0.0.1:{mongo_port}/hrms_payroll_integration',LEAVE_SERVICE_URL=leave_base)
                payroll_processes.append(start('payroll-service','payroll-service-0.1.0-SNAPSHOT.jar',payroll_env,f'payroll-{number+1}'))
            for base,process in zip(payroll_bases,payroll_processes):
                ready(base,'/actuator/health',process)
            proxy_port=free_port()
            nginx=(ROOT/'infrastructure/nginx-payroll.conf').read_text()
            for number,base in enumerate(payroll_bases,1):
                nginx=nginx.replace(f'payroll{number}:8081',base.removeprefix('http://'))
            nginx="log_format integration '$upstream_addr';\naccess_log /var/log/nginx/access.log integration;\n"+nginx.replace('listen 80;',f'listen {proxy_port};')
            config=logs/'nginx.conf'
            config.write_text(nginx)
            proxy_name='hrms-integration-'+uuid.uuid4().hex[:12]
            docker('run','--rm','-d','--name',proxy_name,'--network','host','-v',f'{config}:/etc/nginx/conf.d/default.conf:ro','nginx:1.27-alpine')
            containers.append(proxy_name)
            payroll_base=f'http://127.0.0.1:{proxy_port}'
            ready(payroll_base,'/api/payroll/health',payroll_processes[0])

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
        candidate_account=request(employee_base,"/api/auth/candidate-register",data={"name":"Test Candidate","email":"applicant@example.test","password":password,"department":"Applicants","jobTitle":"Designer","role":"ADMIN"},method="POST",expected=201)
        candidate_token=login(candidate_account["email"])
        check(candidate_account["role"]=="CANDIDATE","candidate registration cannot grant employee or administrator access")
        request(employee_base,"/api/employees",candidate_token,expected=403)
        request(leave_base,"/api/leaves/me/balance",candidate_token,expected=403)
        if payroll_base:request(payroll_base,"/api/payroll/employee/"+candidate_account['employeeId'],candidate_token,expected=403)
        check(True,"job seeker cannot access staff directory, leave or payroll APIs")
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

        with ThreadPoolExecutor(max_workers=6) as pool:
            parallel=list(pool.map(lambda i:register("Concurrent Staff "+str(i),f"concurrent{i}@example.test","EMPLOYEE"),range(6)))
        check(len({item['employeeId'] for item in parallel})==6,"MongoDB sequence allocates unique employee IDs during concurrent registration")

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

        if recruitment_base or payroll_base:
            hr=register('HR Reviewer','hr@example.test','HR')
            hr_token=login(hr['email'])
            hr_settings='/api/internal/employees/'+hr['employeeId']+'/leave-settings'

        if recruitment_base:
            job_body={'title':'Java Engineer','company':'Integration Company','location':'India','description':'Integration job'}
            request(recruitment_base,'/api/jobs',data=job_body,method='POST',expected=401)
            request(recruitment_base,'/api/jobs',employee_token,job_body,'POST',403)
            request(recruitment_base,'/api/jobs',manager_token,job_body,'POST',403)
            request(recruitment_base,'/api/jobs',internal,job_body,'POST',401)
            check(True,'Recruitment staff mutations reject anonymous, employee, manager and directory credentials')
            job=request(recruitment_base,'/api/jobs',hr_token,job_body,'POST')
            check(job['title']==job_body['title'],'real HR login publishes a Recruitment job')
            public_jobs=request(recruitment_base,'/api/jobs')
            check(any(item['id']==job['id'] for item in public_jobs),'job browsing remains public')
            redirect=request(recruitment_base,'/s/'+job['shortCode'])
            check(redirect['id']==job['id'] and request(recruitment_base,'/api/jobs/'+job['id'])['clicks']==1,'Redis short links redirect and count clicks')
            shortened=request(recruitment_base,'/api/shorten',hr_token,{'url':recruitment_base+'/api/jobs/'+job['id']},'POST')
            check(request(recruitment_base,'/s/'+shortened['code'])['id']==job['id'],'HR can create additional short links')
            candidate_body={'name':'Public Candidate','email':'candidate@example.test','phone':'1234567890'}
            candidate=request(recruitment_base,'/api/candidates',data=candidate_body,method='POST')
            check(request(recruitment_base,'/api/candidates',data=candidate_body,method='POST')['id']==candidate['id'],'candidate registration stays public and idempotent by email')
            request(recruitment_base,'/api/candidates',expected=401)
            request(recruitment_base,'/api/candidates',employee_token,expected=403)
            check(request(recruitment_base,'/api/candidates',hr_token)[0]['id']==candidate['id'],'candidate personal data is restricted to HR/Admin')

            boundary='hrms-'+uuid.uuid4().hex
            parts=[]
            for name,value in dict(jobId=job['id'],name=candidate_body['name'],email=candidate_body['email'],phone=candidate_body['phone']).items():
                parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
            resume=b'Integration resume: Java, Spring Boot, MongoDB.'
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="resume"; filename="resume.txt"\r\nContent-Type: text/plain\r\n\r\n'.encode()+resume+b'\r\n')
            parts.append(f'--{boundary}--\r\n'.encode())
            application=request(recruitment_base,'/api/applications',data=b''.join(parts),method='POST',content_type='multipart/form-data; boundary='+boundary)
            original_path=Path(application['storedPath'])
            check(application['status']=='SUBMITTED' and original_path.is_relative_to(logs/'resumes') and original_path.read_bytes()==resume,'public application stores the actual resume using the consistent hash ring')
            resume_url='/api/applications/'+application['id']+'/resume'
            request(recruitment_base,resume_url,expected=401)
            request(recruitment_base,resume_url,employee_token,expected=403)
            resume_request=urllib.request.Request(recruitment_base+resume_url,headers={'Authorization':'Bearer '+hr_token})
            with urllib.request.urlopen(resume_request,timeout=10) as response:
                check(response.read()==resume and 'attachment' in response.headers['Content-Disposition'],'only active HR/Admin can download the actual resume')
            request(recruitment_base,'/api/applications',expected=401)
            request(recruitment_base,'/api/applications',employee_token,expected=403)
            check(request(recruitment_base,'/api/applications?jobId='+job['id'],hr_token)[0]['id']==application['id'],'application lists are private to HR/Admin')
            changed=request(recruitment_base,'/api/applications/'+application['id']+'/status',hr_token,{'status':'SHORTLISTED'},'PUT')
            check(changed['status']=='SHORTLISTED','HR updates application status through shared identity')
            request(recruitment_base,'/api/storage/nodes',hr_token,expected=403)
            nodes=request(recruitment_base,'/api/storage/nodes',admin)
            check(sum(nodes['resumesPerNode'].values())==1,'only Admin can administer resume storage')
            removal=request(recruitment_base,'/api/storage/nodes/'+application['storageNode'],admin,method='DELETE')
            moved=request(recruitment_base,'/api/applications',hr_token)[0]
            check(removal['moved']==1 and moved['storageNode']!=application['storageNode'] and Path(moved['storedPath']).read_bytes()==resume and not original_path.exists(),'removing a storage node rebalances the resume without changing its contents')
            request(recruitment_base,'/api/storage/nodes/node-D',admin,method='POST')
            check('node-D' in request(recruitment_base,'/api/storage/nodes',admin)['nodes'],'storage nodes can be added through the preserved API')
            seed=urllib.parse.quote(recruitment_base+'/demo-jobs/index.html',safe='')
            crawl=request(recruitment_base,'/api/crawler/crawl?seed='+seed+'&maxPages=10&reset=true',hr_token,method='POST')
            check(crawl['pagesVisited']==3 and crawl['newJobsFound']==4,'authenticated crawler traverses the real demo board with BFS')
            repeated=request(recruitment_base,'/api/crawler/crawl?seed='+seed+'&maxPages=10',hr_token,method='POST')
            check(repeated['pagesVisited']==0 and len(request(recruitment_base,'/api/crawler/jobs',hr_token))==4,'Redis visited set prevents repeat crawling and duplicate jobs')
            request(recruitment_base,'/api/notifications',expected=401)
            for attempt in range(80):
                events=request(recruitment_base,'/api/notifications',hr_token)
                if {'JOB_PUBLISHED','APPLICATION_SUBMITTED','APPLICATION_STATUS'} <= {item['type'] for item in events}:
                    break
                time.sleep(0.25)
            check({'JOB_PUBLISHED','APPLICATION_SUBMITTED','APPLICATION_STATUS'} <= {item['type'] for item in events},'real Kafka publishes and consumes job, application and status events into Mongo notifications')
            hr_settings='/api/internal/employees/'+hr['employeeId']+'/leave-settings'
            request(employee_base,hr_settings,admin,{'active':False},'PUT')
            request(recruitment_base,'/api/jobs',hr_token,job_body,'POST',403)
            request(recruitment_base,'/api/notifications',hr_token,expected=403)
            check(len(request(recruitment_base,'/api/jobs'))==1,'deactivated HR loses staff access immediately while public job browsing remains available')
            request(employee_base,hr_settings,admin,{'active':True},'PUT')

            own_application=request(recruitment_base,'/api/applications',candidate_token,data=b''.join(parts),method='POST',content_type='multipart/form-data; boundary='+boundary)
            check([item['id'] for item in request(recruitment_base,'/api/applications/me',candidate_token)]==[own_application['id']],'candidate application history is linked to authenticated account, not an email supplied by a public form')
            check(request(recruitment_base,'/api/applications/me',employee_token)==[],'other users cannot see a candidate application history')
            request(recruitment_base,'/api/applications',candidate_token,expected=403)
            request(recruitment_base,'/api/applications/'+application['id']+'/resume',candidate_token,expected=403)

        if payroll_base:
            pay={'employeeId':employee_id,'year':year,'month':day.month,'basicSalary':30000,'allowances':5000,'deductions':2000}
            request(payroll_base,'/api/payroll/calculate',data=pay,method='POST',expected=401)
            request(payroll_base,'/api/payroll/calculate',employee_token,pay,'POST',403)
            request(payroll_base,'/api/payroll',manager_token,pay,'POST',403)
            request(payroll_base,'/api/payroll',internal,pay,'POST',401)
            check(True,'Payroll generation and calculation require a real HR/Admin login')
            calculation=request(payroll_base,'/api/payroll/calculate',hr_token,pay,'POST')
            check(calculation['grossSalary']==35000 and calculation['netSalary']==33000,'Payroll preserves the teammate salary formula and HR-entered deductions')
            check(request(payroll_base,'/api/payroll/employee/'+employee_id,employee_token)==[],'calculation preview does not save payroll records')
            invalid=dict(pay,employeeId='UNKNOWN')
            request(payroll_base,'/api/payroll',hr_token,invalid,'POST',404)
            request(payroll_base,'/api/payroll/calculate',hr_token,dict(pay,deductions=40000),'POST',400)
            request(payroll_base,'/api/payroll/calculate',hr_token,dict(pay,month=13),'POST',400)
            request(payroll_base,'/api/payroll/calculate',hr_token,dict(pay,basicSalary=-1),'POST',400)
            check(True,'Payroll validates real employees, periods and salary amounts')
            payslip=request(payroll_base,'/api/payroll',hr_token,pay,'POST',201)
            path='/api/payroll/'+urllib.parse.quote(payslip['id'],safe='')
            check(request(payroll_base,path,employee_token)['netSalary']==33000,'employee can read their own stored payslip')
            request(payroll_base,path,outsider_token,expected=403)
            request(payroll_base,'/api/payroll/employee/'+employee_id,manager_token,expected=403)
            request(payroll_base,'/api/payroll/employee/'+employee_id+'/year/'+str(year)+'/month/'+str(day.month),outsider_token,expected=403)
            check(True,'other employees and managers cannot read private payroll by record, employee or period')
            check(request(payroll_base,path,admin)['id']==payslip['id'],'Admin can inspect payroll records')
            request(payroll_base,'/api/payroll',hr_token,pay,'POST',409)
            check(request(payroll_base,'/api/payroll/employee/'+employee_id,employee_token)[0]['id']==payslip['id'],'duplicate monthly creation cannot overwrite an existing payslip')
            next_period=(day.replace(day=28)+dt.timedelta(days=4)).replace(day=1)
            concurrent_pay=dict(pay,year=next_period.year,month=next_period.month)
            def competing_create(number):
                try:
                    result=request(payroll_bases[number%3],'/api/payroll',hr_token,concurrent_pay,'POST',201)
                    return 201,result['id']
                except AssertionError as error:
                    if 'received 409;' in str(error):return 409,None
                    raise
            with ThreadPoolExecutor(max_workers=12) as executor:
                results=list(executor.map(competing_create,range(12)))
            check(sum(code==201 for code,_ in results)==1 and sum(code==409 for code,_ in results)==11,'twelve concurrent requests across three Payroll instances create exactly one monthly record')
            with ThreadPoolExecutor(max_workers=12) as executor:
                calculations=list(executor.map(lambda _:request(payroll_base,'/api/payroll/calculate',hr_token,pay,'POST'),range(24)))
            check(all(item['netSalary']==33000 for item in calculations),'concurrent calculations work through the Nginx load balancer')
            proxy_log=docker('logs',proxy_name)
            check(all(base.removeprefix('http://') in proxy_log for base in payroll_bases),'Nginx actually routes requests to all three Payroll instances')

            period_end=next_period-dt.timedelta(days=1)
            unpaid_body={'type':'UNPAID','startDate':period_end.isoformat(),'endDate':next_period.isoformat(),'reason':'Payroll period allocation check'}
            unpaid=request(leave_base,'/api/leaves',employee_token,unpaid_body,'POST',201)
            request(leave_base,f"/api/leaves/{employee_id}/{unpaid['id']}/decision",manager_token,{'approve':True},'PUT')
            summary_path=f'/api/payroll/employee/{employee_id}/leave-summary?year={year}&month={day.month}'
            summary=request(payroll_base,summary_path,hr_token)
            check(summary['leaveReport']['unpaidDays']==1 and summary['deductionsAppliedAutomatically'] is False,'Payroll reads a real approved Leave report clipped to the payroll month without applying deductions')
            check(request(payroll_base,summary_path,employee_token)['leaveReport']['unpaidDays']==1,'employee can see their own monthly leave context')
            request(payroll_base,summary_path,outsider_token,expected=403)
            check(request(payroll_base,path,employee_token)['netSalary']==33000,'approved unpaid leave does not change the saved salary when HR controls deductions')
            request(employee_base,settings,admin,{'active':False},'PUT')
            request(payroll_base,path,employee_token,expected=403)
            request(payroll_base,'/api/payroll',hr_token,dict(pay,month=1 if day.month!=1 else 2),'POST',403)
            request(employee_base,settings,admin,{'active':True},'PUT')
            request(employee_base,hr_settings,admin,{'active':False},'PUT')
            request(payroll_base,'/api/payroll/calculate',hr_token,pay,'POST',403)
            request(employee_base,hr_settings,admin,{'active':True},'PUT')
            check(True,'inactive employees and HR lose Payroll access immediately')
            senior=register("Senior Leader","leader@example.test","MANAGER")
            senior_token=login(senior['email'])
            request(employee_base,'/api/internal/employees/'+senior['employeeId']+'/leave-settings',admin,{'emailOnlyLeave':True,'leaveRequestEmail':'leave@alder.example'},'PUT')
            request(leave_base,'/api/leaves',senior_token,body,'POST',403)
            check(request(leave_base,balance_path,senior_token)['annual']['remaining']==20,'most senior leader can view balances but must email HR instead of submitting for approval')
            if options.frontend:
                ui_port=free_port()
                targets=logs/'services.json'
                targets.write_text(json.dumps({'employee':employee_base,'leave':leave_base,'payroll':payroll_base,'recruitment':recruitment_base}))
                ui_env=dict(env,ALDER_RUNTIME_FILE=str(targets),FRONTEND_PORT=str(ui_port),
                    ALDER_E2E_BASE_URL=f'http://127.0.0.1:{ui_port}',ALDER_E2E_PASSWORD=password,
                    ALDER_E2E_EMPLOYEE_ID=employee_id,ALDER_E2E_PAY_PERIOD=f'{year}-{day.month:02d}',
                    PLAYWRIGHT_BROWSERS_PATH=str(ROOT/'.runtime/browsers'))
                output=(logs/'frontend.log').open('w');files.append(output)
                ui_process=subprocess.Popen(['npm','run','preview'],cwd=ROOT/'frontend',env=ui_env,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
                ui_process.hrms_group=True
                processes.append(ui_process)
                deadline=time.monotonic()+45
                while time.monotonic()<deadline:
                    try:
                        with urllib.request.urlopen(f'http://127.0.0.1:{ui_port}',timeout=3) as response:
                            if response.status==200:break
                    except OSError:time.sleep(0.5)
                else:raise RuntimeError('Frontend did not start')
                subprocess.run(['npm','run','test:e2e'],cwd=ROOT/'frontend',env=ui_env,check=True)
                check(True,'real browser workflows, role boundaries, mobile layout and accessibility pass')
            stop(leave_process)
            request(payroll_base,summary_path,hr_token,expected=503)
            check(request(payroll_base,path,employee_token)['netSalary']==33000,'Leave outage blocks leave summaries while existing payslips remain available')

        stop(employee_process)
        time.sleep(1)
        if not payroll_base:
            request(leave_base, "/api/leaves", employee_token, body, "POST", 503)
            check(len(request(leave_base, "/api/leaves/me", employee_token)) == 1, "Employee outage blocks writes while durable leave history stays readable")
        if payroll_base:
            request(payroll_base,'/api/payroll/calculate',hr_token,pay,'POST',503)
            request(payroll_base,path,employee_token,expected=503)
            check(request(payroll_base,'/api/payroll/health')['status']=='UP','Payroll blocks private operations during Employee outage while public health remains available')
        if recruitment_base:
            request(recruitment_base,'/api/jobs',hr_token,job_body,'POST',503)
            request(recruitment_base,'/api/notifications',hr_token,expected=503)
            check(any(item['id']==job['id'] for item in request(recruitment_base,'/api/jobs')),'Recruitment blocks staff access during Employee outage and keeps public job browsing online')
        print(f"\n{checks} integration checks passed. Logs: {logs}", flush=True)
    finally:
        for process in reversed(processes):
            if process.poll() is None or getattr(process,'hrms_group',False):stop(process)
        for output in files:
            output.close()
        for name in containers:
            subprocess.run(["docker", "stop", "-t", "1", name], stdout=subprocess.DEVNULL, check=False)


if __name__ == "__main__":
    main()
