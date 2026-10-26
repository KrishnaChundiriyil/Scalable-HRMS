# JMeter performance plan — Person 1

Use JMeter to test the employee directory API after registering an account and obtaining a JWT.

## Suggested scenarios
- Endpoint: `GET http://localhost:8080/api/employees`
- Endpoint: `GET http://localhost:8080/api/employees/suggest?q=eng`
- Header: `Authorization: Bearer <JWT>`
- Header: `Content-Type: application/json`

## Workload levels
| Level | Threads / concurrent users | Ramp-up | Loop count |
|---|---:|---:|---:|
| Low | 10 | 10 sec | 10 |
| Medium | 50 | 25 sec | 10 |
| High | 100 | 50 sec | 10 |

These are starting test values, not claimed results. Run each scenario separately and record:
- Average, median, 90th/95th percentile response time
- Throughput (requests/sec)
- Error percentage
- CPU and memory use
- Autocomplete cold-cache vs warm-cache response time

Save the JMeter `.jtl` result and screenshots in `docs/performance/`. Compare the baseline with Redis caching enabled and disabled only if you can change the configuration safely. Do not claim performance improvements without measured results.
