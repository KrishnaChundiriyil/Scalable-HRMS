#!/usr/bin/env python3
"""Show a real leave-update SSE event using the seeded employee and manager.

Creates and approves a future one-day request, receives its SSE event, then
cancels the request to refund the day. Never prints passwords or JWTs.
"""
import datetime as dt
import json
from pathlib import Path
import threading
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parent.parent

def main():
    services=json.loads((ROOT/'.runtime/services.json').read_text())
    accounts={item['role']:item for item in json.loads((ROOT/'.runtime/demo-accounts.json').read_text())}
    def request(service,path,token=None,body=None,method='GET'):
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        with urllib.request.urlopen(urllib.request.Request(services[service]+path,
                data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method),timeout=20) as response:
            data=response.read();return json.loads(data) if data else None
    def login(role):
        account=accounts[role]
        return request('employee','/api/auth/login',body={'email':account['email'],'password':account['password']},method='POST')['token']
    employee=login('Employee');manager=login('Manager')
    day=dt.date.today()+dt.timedelta(days=45)
    while True:
        if day.weekday()<5:
            preview=request('leave','/api/leaves/preview',employee,{'type':'ANNUAL','startDate':day.isoformat(),'endDate':day.isoformat(),'session':'FULL_DAY'},'POST')
            if preview['canSubmit']:break
        day+=dt.timedelta(days=1)
        if day>dt.date.today()+dt.timedelta(days=120):raise RuntimeError('No suitable date within 120 days')
    leave=request('leave','/api/leaves',employee,{'type':'ANNUAL','startDate':day.isoformat(),'endDate':day.isoformat(),'reason':'SSE demonstration '+uuid.uuid4().hex[:8]},'POST')
    connected=threading.Event();received=threading.Event();errors=[]
    def listen():
        try:
            stream=urllib.request.Request(services['leave']+'/api/leaves/notifications/stream',headers={'Authorization':'Bearer '+employee,'Accept':'text/event-stream'})
            with urllib.request.urlopen(stream,timeout=25) as response:
                event='';identifier=''
                for line in response:
                    text=line.decode().strip()
                    if text.startswith('event:'):
                        event=text[6:].strip()
                        if event=='connected':connected.set();print('SSE connected: server is ready.',flush=True)
                    elif text.startswith('id:'):identifier=text[3:].strip()
                    elif text.startswith('data:') and event=='leave-update':
                        data=json.loads(text[5:].strip())
                        if data.get('leaveId')==leave['id']:
                            print('Received event: leave-update',flush=True)
                            print('Event ID: '+identifier,flush=True)
                            print('Message: '+data['message'],flush=True)
                            received.set();break
        except Exception as error:errors.append(error);connected.set();received.set()
    thread=threading.Thread(target=listen,daemon=True);thread.start()
    try:
        if not connected.wait(15) or errors:raise RuntimeError('SSE did not connect: '+str(errors))
        request('leave',f'/api/leaves/{accounts["Employee"]["employeeId"]}/{leave["id"]}/decision',manager,{'approve':True},'PUT')
        if not received.wait(20) or errors:raise RuntimeError('SSE event not received: '+str(errors))
        print('PASS: a real manager approval reached the employee over SSE.',flush=True)
    finally:
        request('leave',f'/api/leaves/me/{leave["id"]}/cancel',employee,{'reason':'SSE demonstration finished; refund the sample day'},'POST')
        print('Sample request cancelled; the approved day was refunded.',flush=True)

if __name__=='__main__':main()
