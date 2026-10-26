# Scalable HRMS — Person 1: Employee & Identity Management

This is the **Person 1 module** for the Scalable HRMS mini-project. It includes:
- Employee registration, login, JWT authentication
- Role-based access control (`ADMIN`, `HR`, `EMPLOYEE`)
- Employee CRUD and directory search by name, department, and job title
- Profile-management UI with view/edit controls
- Admin-only role management to assign `ADMIN`, `HR`, or `EMPLOYEE` roles
- Redis-backed unique employee IDs (`EMP1001`, `EMP1002`, ...)
- Employee-name/department autocomplete using a Trie, with Redis caching
- Redis caching for employee profile reads and lightweight session metadata (8-hour TTL)
- Docker Compose for MongoDB + Redis
- Basic backend tests and a JMeter workload plan

## Stack
- Backend: Java 17, Spring Boot 3.4.5, Spring Security, Spring Data MongoDB
- Database: MongoDB
- Cache / atomic counter: Redis
- Frontend: React + Vite
- Authentication: JWT

## Requirements
- Java 17+
- Maven 3.9+
- Node.js 20+
- Docker Desktop

## Run the infrastructure
From this folder:
```bash
docker compose up -d
```
MongoDB runs on `localhost:27017`; Redis runs on `localhost:6379`.

## Run the backend
```bash
cd backend
mvn spring-boot:run
```
Backend: `http://localhost:8080`

The first user can register as an `ADMIN` only when the user collection is empty. After an admin exists, public registration creates `EMPLOYEE` accounts. In a real deployment, admin bootstrap should be handled through a secure one-time process.

## Run the frontend
In another terminal:
```bash
cd frontend
npm install
npm run dev
```
Open the Vite URL printed in the terminal (normally `http://localhost:5173`).

## Quick test flow
1. Register the first account. It becomes `ADMIN`.
2. Log in and copy the JWT automatically stored by the frontend in memory.
3. Create employees through the Employee Directory form, or use the API.
4. Search employees and try autocomplete.
5. Open Redis CLI and inspect the counter/cache:
```bash
docker exec -it hrms-redis redis-cli
GET hrms:employee:id-counter
KEYS hrms:employee:*
```

## Main API endpoints
| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | Public | Register account; first user becomes ADMIN |
| POST | `/api/auth/login` | Public | Login and receive JWT |
| GET | `/api/employees` | ADMIN, HR, EMPLOYEE | List/search employees |
| POST | `/api/employees` | ADMIN, HR | Create employee |
| GET | `/api/employees/{id}` | ADMIN, HR, EMPLOYEE | Read employee profile |
| PUT | `/api/employees/{id}` | ADMIN, HR or own profile | Update employee |
| DELETE | `/api/employees/{id}` | ADMIN, HR | Delete employee |
| GET | `/api/employees/suggest?q=ka` | ADMIN, HR, EMPLOYEE | Autocomplete employee names/departments |
| GET | `/api/auth/me` | Authenticated | Current account |
| PUT | `/api/admin/users/{email}/role` | ADMIN | Assign a role to a registered account; body: `{ "role": "HR" }` |

## Important implementation notes
- Passwords are stored as BCrypt hashes, never as plaintext.
- JWT signing secret is configurable using `JWT_SECRET`. Change it before deployment.
- CORS allows the local Vite dev server only by default.
- Redis cache is an optimization; MongoDB remains the source of truth.
- On login/registration, Redis stores lightweight session metadata under `hrms:session:<email>` for 8 hours. JWT remains stateless; this metadata is not a token-revocation mechanism.
- Employee mutations clear profile and autocomplete caches to avoid stale results.
- The Trie is rebuilt from the current employee directory when employee data changes. This is suitable for a mini-project/demo; for multiple backend replicas, replace it with a shared search index or Redis-backed autocomplete index.
- This is an educational mini-project module, not a production security review. Public registration promotes only the first account to ADMIN; subsequent accounts are EMPLOYEE. An ADMIN can assign HR roles from the UI. Before real deployment, add email verification, password reset, audit logs, stronger admin bootstrap, TLS, secret management, token revocation, and rate limiting.

## Person 1 demo checklist
- [ ] Register/login and show JWT authentication
- [ ] Show role restriction: employee cannot create/delete employees
- [ ] Create an employee and show generated `EMP...` ID
- [ ] Search by name/department
- [ ] Type a prefix in autocomplete
- [ ] Show Redis cache and ID counter
- [ ] Run workload tests at 10, 50, and 100 concurrent users and record latency, throughput, errors, CPU and memory
