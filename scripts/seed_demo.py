#!/usr/bin/env python3
"""Populate the local workspace through its real APIs; preserve existing records.

Requires scripts/dev.py start. Generated passwords and progress are kept privately
in .runtime/demo-seed.json, never in the committed fictional dataset.
"""
import datetime as dt
import json
from pathlib import Path
import secrets
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parent.parent
STATE=ROOT/'.runtime'

def main():
    services=json.loads((STATE/'services.json').read_text())
    env={line.split('=',1)[0]:line.split('=',1)[1] for line in (ROOT/'.env').read_text().splitlines() if '=' in line and not line.startswith('#')}
    progress=STATE/'demo-seed.json'
    state=json.loads(progress.read_text()) if progress.exists() else {'passwords':{},'date':dt.date.today().isoformat()}
    def save():
        progress.write_text(json.dumps(state,indent=2));progress.chmod(0o600)
    def api(service,path,token=None,body=None,method='GET',content_type='application/json',allow=()):
        headers={'Content-Type':content_type}
        if token:headers['Authorization']='Bearer '+token
        payload=body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else None
        try:
            with urllib.request.urlopen(urllib.request.Request(services[service]+path,data=payload,headers=headers,method=method),timeout=30) as response:
                data=response.read();return json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            if error.code==429:
                time.sleep(min(5,max(1,int(error.headers.get('Retry-After','1')))))
                return api(service,path,token,body,method,content_type,allow)
            if error.code in allow:return None
            detail=error.read().decode()[:300]
            raise RuntimeError(f'{service} {method} {path}: {error.code}: {detail}') from None
    def login(email,password):return api('employee','/api/auth/login',body={'email':email,'password':password},method='POST')['token']
    bootstrap=login(env['HRMS_ADMIN_EMAIL'],env['HRMS_ADMIN_PASSWORD'])
    rows=json.loads((ROOT/'data/demo/employees.json').read_text())['employees']
    candidate={'name':'Maya Iyer','email':'maya.iyer@demo.alder.example','department':'Applicants','jobTitle':'Product Designer','role':'CANDIDATE','phone':''}
    directory={e['email']:e for e in api('employee','/api/employees',bootstrap)}
    accounts={}
    for row in rows+[candidate]:
        email=row['email']
        if email not in state['passwords']:state['passwords'][email]=secrets.token_urlsafe(15)+'!7';save()
        body={key:row.get(key,'') for key in ['name','email','department','jobTitle','role','phone']}
        body['password']=state['passwords'][email]
        if row['role']=='CANDIDATE':
            try:token=login(email,body['password'])
            except RuntimeError:
                api('employee','/api/auth/candidate-register',body=body,method='POST');token=login(email,body['password'])
            profile=api('employee','/api/auth/login',body={'email':email,'password':body['password']},method='POST')['employee']
        else:
            profile=directory.get(email) or api('employee','/api/employees',bootstrap,body,'POST')
            token=login(email,body['password'])
        accounts[email]={'profile':profile,'token':token}
    admin=accounts['kavya.iyer@demo.alder.example']['token']
    for row in rows:
        target=accounts[row['email']]['profile']['employeeId']
        manager=accounts[row['managerEmail']]['profile']['employeeId'] if row['managerEmail'] else ''
        api('employee',f'/api/internal/employees/{target}/leave-settings',admin,
            {'managerId':manager,'active':True,'emailOnlyLeave':row['emailOnlyLeave'],'leaveRequestEmail':env.get('DEMO_LEAVE_EMAIL','leave@alder.example')},'PUT')
    api('employee','/api/company',admin,{
        'name':'Alder & Co.','tagline':'Good people. Thoughtful work. Room to grow.',
        'about':'We are a product and technology company bringing design, engineering and care together to make everyday work simpler. From our Bengaluru home to our connected teams, we build useful things and make room for people to do their best work.',
        'mission':'Create useful technology and a workplace where people can grow with confidence.',
        'location':'Bengaluru, India · Flexible, connected teams',
        'values':[{'title':'People before process','description':'Listen carefully, respect different perspectives and make the workday feel human.'},{'title':'Thoughtful by design','description':'Bring clarity, care and intention to the things we build and the decisions we make.'},{'title':'Better, together','description':'Share what you know, ask good questions and give others room to grow.'}],
        'announcements':[{'title':'A place for your next chapter','detail':'We are welcoming new colleagues across engineering, design, product and operations. Explore the current roles in Opportunities.','category':'WE ARE HIRING'},{'title':'Meet the teams behind the work','detail':'Our shared directory helps you find a colleague, discover their team and connect the right people.','category':'PEOPLE & CULTURE'},{'title':'About this demonstration','detail':'The people, company stories, applications and payroll examples in this workspace are fictional sample records for exploring the application.','category':'DEMO WORKSPACE'}]
    },'PUT')
    base=dt.date.fromisoformat(state['date'])
    def workday(offset):
        day=base+dt.timedelta(days=offset)
        while day.weekday()>4:day+=dt.timedelta(days=1)
        return day.isoformat()
    # Widely separated dates exercise approval, half days, rejection, cancellation,
    # unpaid context and a pending request without overlap or bypassing policy.
    scenarios=[('ANNUAL',2,'FULL_DAY','APPROVED'),('SICK',7,'MORNING','APPROVED'),('CASUAL',12,'FULL_DAY','PENDING'),('ANNUAL',17,'FULL_DAY','REJECTED'),('ANNUAL',22,'FULL_DAY','CANCELLED'),('UNPAID',27,'FULL_DAY','APPROVED')]
    for row in rows:
        account=accounts[row['email']];employee=account['profile']['employeeId'];token=account['token']
        if not row['emailOnlyLeave']:
            existing=api('leave','/api/leaves/me',token)
            for index,(kind,offset,session,status) in enumerate(scenarios):
                reason=f'Demo workspace: {status.lower()} {kind.lower()} scenario {state["date"]}'
                request=next((item for item in existing if item['reason']==reason),None)
                if request is None:
                    request=api('leave','/api/leaves',token,{'type':kind,'startDate':workday(offset),'endDate':workday(offset),'session':session,'reason':reason},'POST')
                if request['status']=='PENDING' and status!='PENDING':
                    reviewer=accounts[row['managerEmail']]['token'] if row['managerEmail'] else admin
                    api('leave',f'/api/leaves/{employee}/{request["id"]}/decision',reviewer,{'approve':status!='REJECTED','rejectionReason':'Demo: team coverage is needed on this date' if status=='REJECTED' else None},'PUT')
                    if status=='CANCELLED':api('leave',f'/api/leaves/me/{request["id"]}/cancel',token,{'reason':'Demo: plans changed and the approved day is refunded'},'POST')
        for ago in range(3):
            month_index=base.year*12+base.month-1-ago;year,month=month_index//12,month_index%12+1
            salary=180000 if row['emailOnlyLeave'] else 110000 if row['role']=='MANAGER' else 75000 if row['role'] in ['ADMIN','HR'] else 55000+rows.index(row)%8*2500
            api('payroll','/api/payroll',admin,{'employeeId':employee,'year':year,'month':month,'basicSalary':salary,'allowances':7500,'deductions':2500},'POST',allow=(409,))
    roles=[('Product Designer','Bengaluru · Hybrid'),('Backend Engineer','Bengaluru · Hybrid'),('Data Analyst','Bengaluru · Hybrid'),('Product Manager','Bengaluru · Hybrid'),('People Operations Associate','Bengaluru · On-site'),('Frontend Engineer','India · Remote')]
    published=api('recruitment','/api/jobs',admin);jobs=[]
    for title,location in roles:
        job=next((item for item in published if item['title']==title and item['company']=='Alder & Co.'),None)
        if job is None:job=api('recruitment','/api/jobs',admin,{'title':title,'company':'Alder & Co.','location':location,'description':f'Join the {title} team at Alder & Co. Work with thoughtful colleagues to build useful products and services. We value clear communication, curiosity and care for the people who use our work. Bring relevant experience, a collaborative approach and an interest in growing together. This is a fictional demonstration vacancy.'},'POST')
        jobs.append(job)
    def apply(job,person,token=None):
        boundary='alder-'+uuid.uuid4().hex
        fields={'jobId':job['id'],'name':person['name'],'email':person['email'],'phone':''}
        parts=[]
        for key,value in fields.items():parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="resume"; filename="sample-resume.txt"\r\nContent-Type: text/plain\r\n\r\n{person["name"]}\nFictional sample resume for demonstration.\nExperience: product collaboration, thoughtful problem solving and communication.\r\n--{boundary}--\r\n'.encode())
        return api('recruitment','/api/applications',token,b''.join(parts),'POST','multipart/form-data; boundary='+boundary)
    candidate_token=accounts[candidate['email']]['token'];owned=api('recruitment','/api/applications/me',candidate_token)
    for index,job in enumerate(jobs[:3]):
        application=next((item for item in owned if item['jobId']==job['id']),None) or apply(job,candidate,candidate_token)
        if application['status']=='SUBMITTED' and index<2:api('recruitment',f'/api/applications/{application["id"]}/status',admin,{'status':['INTERVIEW','SHORTLISTED'][index]},'PUT')
    existing=api('recruitment','/api/applications',admin)
    for index,row in enumerate(rows[10:20]):
        person={'name':row['name']+' (sample applicant)','email':'applicant.'+row['email']}
        if not any(item['candidateName']==person['name'] for item in existing):apply(jobs[index%len(jobs)],person)
    primary=[('Job seeker','maya.iyer'),('Employee','rohan.menon'),('Manager','ananya.rao'),('Senior leader','meera.nair'),('Admin','kavya.iyer')]
    credentials=[]
    for role,handle in primary:
        email=handle+'@demo.alder.example';profile=accounts[email]['profile']
        credentials.append({'role':role,'name':profile['name'],'email':email,'password':state['passwords'][email],'employeeId':profile['employeeId']})
    path=STATE/'demo-accounts.json';path.write_text(json.dumps(credentials,indent=2));path.chmod(0o600)
    save()
    print('Demo ready: 40 fictional staff, one job seeker, leave scenarios, 120 payslips, six roles and candidate applications.')
    print('Private sample login details: .runtime/demo-accounts.json')

if __name__=='__main__':
    try:main()
    except (RuntimeError,FileNotFoundError) as error:print(str(error),file=sys.stderr);sys.exit(1)
