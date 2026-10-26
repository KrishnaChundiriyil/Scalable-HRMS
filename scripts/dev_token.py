#!/usr/bin/env python3
"""LOCAL DEVELOPMENT ONLY. Create a signed JWT for backend integration testing.
Production tokens must come from the real Employee/Auth Service (Person 1).
"""
import argparse, base64, hashlib, hmac, json, os, sys, time
p = argparse.ArgumentParser()
p.add_argument('--subject', required=True, help='employee ID for test-only identity')
p.add_argument('--role', required=True, choices=['EMPLOYEE','MANAGER','HR','ADMIN'])
a = p.parse_args()
raw = os.getenv('HRMS_JWT_SECRET_BASE64')
if not raw:
    sys.exit('Please export HRMS_JWT_SECRET_BASE64 from .env first')
secret = base64.b64decode(raw)
if len(secret) < 32: sys.exit('JWT signing secret must be >= 32 bytes')
def b64(obj):
    if isinstance(obj, dict): obj = json.dumps(obj, separators=(',', ':')).encode()
    return base64.urlsafe_b64encode(obj).rstrip(b'=').decode()
now = int(time.time())
header = b64({'alg':'HS256','typ':'JWT'})
payload = b64({'sub':a.subject,'roles':[a.role],'iat':now,'exp':now+3600})
signed = f'{header}.{payload}'
sig = b64(hmac.new(secret,signed.encode(),hashlib.sha256).digest())
print(signed+'.'+sig)
