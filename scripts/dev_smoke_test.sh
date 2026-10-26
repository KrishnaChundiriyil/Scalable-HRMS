#!/usr/bin/env bash
# Local-only API smoke test: actual MongoDB + Redis; no fake employee directory.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source .env
set +a
BASE=${LEAVE_BASE_URL:-http://localhost:8081}
E_TOKEN=$(python3 scripts/dev_token.py --subject EMP001 --role EMPLOYEE)
M_TOKEN=$(python3 scripts/dev_token.py --subject MGR001 --role MANAGER)
echo '== Health =='
curl -fsS "$BASE/actuator/health"; echo
echo '== Submit leave =='
start=$(date -d '+7 days' +%F)
end=$(date -d '+8 days' +%F)
result=$(curl -fsS -X POST "$BASE/api/leaves" -H "Authorization: Bearer $E_TOKEN" -H 'Content-Type: application/json' -d "{\"type\":\"ANNUAL\",\"startDate\":\"$start\",\"endDate\":\"$end\",\"reason\":\"Family appointment\"}")
echo "$result"
leaveid=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<< "$result")
echo '== Manager approves =='
curl -fsS -X PUT "$BASE/api/leaves/EMP001/$leaveid/decision" -H "Authorization: Bearer $M_TOKEN" -H 'Content-Type: application/json' -d '{"approve":true}'; echo
echo '== Check balance =='
curl -fsS "$BASE/api/leaves/me/balance" -H "Authorization: Bearer $E_TOKEN"; echo
echo '== Notification history =='
curl -fsS "$BASE/api/leaves/notifications" -H "Authorization: Bearer $E_TOKEN"; echo
