# HRMS – Recruitment, Job Discovery & Event Streaming (backend only)

Spring Boot 3 (Java 17+) · MongoDB · Redis · Kafka · Jsoup
Covers: **Web Crawler, URL Shortener, Consistent Hashing (virtual nodes), Apache Kafka**.
No frontend: test everything with Postman.

## Run
1. Install JDK 17+, Maven, Docker Desktop.
2. `docker compose up -d`   (Mongo, Redis, Kafka)
3. `mvn spring-boot:run`    (API on http://localhost:8084)

## Test with Postman
1. Import `postman/HRMS-Recruitment.postman_collection.json`.
2. In request **3.1** select `postman/sample-resume.txt` in the `resume` field (file fields cannot be pre-filled).
3. Run folders **1 to 6 in order** (Collection Runner). Send 3.1 about 8-10 times before folder 4.
   Each request has test scripts, so green ticks = pass.

## REST API
| Method | Path | Purpose |
|---|---|---|
| POST | /api/jobs | publish job + short link |
| GET | /api/jobs | list jobs (with click counts) |
| GET | /api/jobs/{id} | get one job (short link redirects here) |
| GET | /s/{code} | short link: 302 redirect + click counter |
| POST | /api/shorten {url} | shorten any URL |
| POST/GET | /api/candidates | register (idempotent by email) / list |
| POST | /api/applications (form-data: jobId, name, email, phone, resume) | apply |
| GET | /api/applications[?jobId=] | list |
| PUT | /api/applications/{id}/status {status} | HR updates status (Kafka event) |
| GET | /api/storage/nodes | nodes + resumes per node |
| POST/DELETE | /api/storage/nodes/{name} | add / remove node (rebalances files) |
| GET | /api/storage/simulate?keys=10000 | ring balance test |
| POST | /api/crawler/crawl?seed=URL&maxPages=30&reset=false | run crawler |
| GET | /api/crawler/jobs | crawled jobs |
| GET | /api/notifications | events consumed from Kafka |

Demo crawl target (served by the app): `http://localhost:8084/demo-jobs/index.html`

## Viva notes
- **URL shortener**: Redis atomic `INCR` -> base62 code, stored as `url:<code>`; atomic so no collisions across instances.
- **Consistent hashing**: MD5 ring (TreeMap), 100 virtual nodes per node, key = application id, first node clockwise. Adding/removing a node moves only ~1/N keys.
- **Crawler**: BFS with a queue + Redis `SADD` visited set (returns 0 if seen). Same host only, max page limit.
- **Kafka**: producer keyed by event type; consumer group `hrms-notification-group` stores notifications in Mongo (async decoupling). Topic has 3 partitions. Producer `max.block.ms` is 3s so the API fails fast if Kafka is down.

## Known limitations
- Ring membership is in memory (resets to node-A,B,C on restart).
- Nodes are local folders (`resume-storage/node-X/`), not separate machines.
- Crawler understands only elements with class `job`.
- No auth: endpoints are open.
