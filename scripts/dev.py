#!/usr/bin/env python3
"""Start/stop the local Alder workspace, preserving the existing databases."""
import argparse
import json
import os
from pathlib import Path
import signal
import shutil
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
STATE = ROOT / '.runtime'
SERVICES = {
    'employee': ('employee-service', 'person1-employee-service-1.0.0.jar', 'EMPLOYEE_SERVICE_PORT', 8180, '/api/auth/health'),
    'leave': ('leave-service', 'leave-service-0.2.0-SNAPSHOT.jar', 'LEAVE_SERVICE_PORT', 8181, '/actuator/health'),
    'payroll': ('payroll-service', 'payroll-service-0.1.0-SNAPSHOT.jar', 'PAYROLL_SERVICE_PORT', 8183, '/actuator/health'),
    'recruitment': ('recruitment-service', 'recruitment-service-1.0.0.jar', 'RECRUITMENT_SERVICE_PORT', 8184, '/actuator/health'),
}

def environment():
    env = dict(os.environ)
    for line in (ROOT / '.env').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            env[key.strip()] = value.strip().strip('"').strip("'")
    env.pop('SERVER_PORT', None)
    return env

def owned(item):
    try:
        raw = Path(f"/proc/{item['pid']}/stat").read_text()
        fields = raw.rsplit(')',1)[1].split()
        started = fields[19] if item.get('identityVersion') == 2 else raw.split()[21]
        return fields[0] != 'Z' and started == item['started']
    except (FileNotFoundError, ProcessLookupError):
        return False

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'stop', 'restart', 'status', 'build'], nargs='?', default='start')
    args = parser.parse_args()
    STATE.mkdir(mode=0o700, exist_ok=True)
    state_file = STATE / 'processes.json'
    state = json.loads(state_file.read_text()) if state_file.exists() else {}
    if args.action in ['stop', 'restart', 'status']:
        for name, item in state.items():
            running = owned(item)
            if running and args.action in ['stop', 'restart']:
                os.killpg(item['pid'], signal.SIGTERM)
            print(name + ': ' + ('stopping' if args.action in ['stop', 'restart'] and running else 'running' if running else 'stopped'))
        if args.action != 'status':
            deadline = time.monotonic() + 20
            while any(owned(item) for item in state.values()) and time.monotonic() < deadline:
                time.sleep(0.2)
            for item in state.values():
                if owned(item):os.killpg(item['pid'], signal.SIGKILL)
            state_file.unlink(missing_ok=True)
            state={}
        if args.action != 'restart':return
    if args.action == 'build':
        subprocess.run(['mvn', '-B', '--no-transfer-progress', 'verify'], cwd=ROOT, check=True)
        subprocess.run(['npm', 'run', 'build'], cwd=ROOT / 'frontend', check=True)
        return
    subprocess.run(['bash', 'scripts/setup_local_env.sh'], cwd=ROOT, check=True)
    env = environment()
    subprocess.run(['docker', 'compose', '-f', 'infrastructure/docker-compose-dev.yml', 'up', '-d', '--wait', 'mongodb', 'redis', 'kafka'], cwd=ROOT, env=env, check=True)
    def stale(module, jar):
        artifact = ROOT / 'backend' / module / 'target' / jar
        return not artifact.exists() or any(path.stat().st_mtime > artifact.stat().st_mtime for path in (ROOT / 'backend' / module / 'src/main').rglob('*') if path.is_file())
    if any(stale(module, jar) for module, jar, *_ in SERVICES.values()):
        subprocess.run(['mvn', '-B', '--no-transfer-progress', '-DskipTests', 'package'], cwd=ROOT, check=True)
    legacy = ROOT / 'backend/employee-service/data/hrmsdb.mv.db'
    if legacy.exists():
        backup = STATE / 'backups/hrmsdb-before-mongo.mv.db'
        backup.parent.mkdir(mode=0o700,exist_ok=True)
        if not backup.exists():shutil.copy2(legacy,backup)
        env['EMPLOYEE_IMPORT_LEGACY_H2']='true'
    urls = {}
    for name, (_, _, key, default, _) in SERVICES.items():
        if name in state and owned(state[name]):
            urls[name] = state[name]['url']
            continue
        port = int(env.get(key, default))
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            try:
                listener.bind(('127.0.0.1', port))
            except OSError:
                listener.bind(('127.0.0.1', 0))
                port = listener.getsockname()[1]
        env[key] = str(port)
        urls[name] = f'http://127.0.0.1:{port}'
    env.update(EMPLOYEE_SERVICE_URL=urls['employee'], LEAVE_SERVICE_URL=urls['leave'],
               RECRUITMENT_BASE_URL='http://127.0.0.1:' + env.get('FRONTEND_PORT', '5173'))
    (STATE / 'services.json').write_text(json.dumps(urls, indent=2))
    def launch(name, command, cwd, url):
        if name in state and owned(state[name]):
            return
        with (STATE / f'{name}.log').open('a') as log:
            process = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        state[name] = {'pid': process.pid, 'started': Path(f'/proc/{process.pid}/stat').read_text().rsplit(')',1)[1].split()[19], 'identityVersion':2,'url': url}
        state_file.write_text(json.dumps(state, indent=2))
    for name, (module, jar, _, _, health) in SERVICES.items():
        snapshot = STATE / 'run' / f'{name}.jar'
        if name not in state or not owned(state[name]):
            snapshot.parent.mkdir(mode=0o700, exist_ok=True)
            shutil.copy2(ROOT / 'backend' / module / 'target' / jar, snapshot)
        launch(name, ['java', '-Xms64m', '-Xmx384m', '-jar', str(snapshot)], ROOT / 'backend' / module, urls[name])
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            if not owned(state[name]):
                raise RuntimeError(f'{name} exited. See .runtime/{name}.log')
            try:
                with urllib.request.urlopen(urls[name] + health, timeout=3) as response:
                    if response.status == 200:
                        break
            except OSError:
                time.sleep(0.5)
        else:
            raise TimeoutError(f'{name} did not become healthy. See .runtime/{name}.log')
        print(name + ': ready', flush=True)
    if not (ROOT / 'frontend/node_modules').exists():
        subprocess.run(['npm', 'ci'], cwd=ROOT / 'frontend', check=True)
    index = ROOT / 'frontend/dist/index.html'
    if not index.exists() or any(p.stat().st_mtime > index.stat().st_mtime for p in (ROOT / 'frontend/src').rglob('*') if p.is_file()):
        subprocess.run(['npm', 'run', 'build'], cwd=ROOT / 'frontend', env=env, check=True)
    launch('frontend', ['npm', 'run', 'preview'], ROOT / 'frontend', 'http://127.0.0.1:' + env.get('FRONTEND_PORT', '5173'))
    print('Alder & Co. is opening at ' + state['frontend']['url'])
    print('Your private first-admin credentials are in .env. Logs are in .runtime/.')

if __name__ == '__main__':
    main()
