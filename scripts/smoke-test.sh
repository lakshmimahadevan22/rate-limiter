#!/usr/bin/env bash
# End-to-end smoke test against a running stack (docker compose up, or the jars run locally).
# Walks through every feature over plain HTTP and exits non-zero on the first unexpected result.
#
#   ./scripts/smoke-test.sh                 # gateway on http://localhost:8080
#   BASE_URL=http://host:8080 ./scripts/smoke-test.sh
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
AUTH_USERNAME=${AUTH_USERNAME:-demo}
AUTH_PASSWORD=${AUTH_PASSWORD:-demo-password}
CLIENT_ID="smoke-$(date +%s)-$RANDOM"
# Request bodies live in variables: escaped quotes inside "$(...)" would let bash brace-expand the JSON.
CREATE_BODY="{\"clientId\":\"$CLIENT_ID\",\"capacity\":3,\"refillTokens\":1,\"refillPeriodMillis\":60000}"
WRONG_KEY_BODY="{\"clientId\":\"$CLIENT_ID\",\"apiKey\":\"wrong\"}"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

pass=0
check() { # check <description> <expected status> <actual status>
    if [[ "$2" == "$3" ]]; then
        printf '  \033[32mok\033[0m   %-58s %s\n' "$1" "$3"
        pass=$((pass + 1))
    else
        printf '  \033[31mFAIL\033[0m %-58s expected %s, got %s\n' "$1" "$2" "$3"
        exit 1
    fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
json() { curl -s -H 'Content-Type: application/json' "$@"; }
key_body() { jq -cn --arg id "$CLIENT_ID" --arg key "$1" '{clientId: $id, apiKey: $key}'; }

echo "Smoke-testing $BASE_URL"

echo "Health"
check "gateway /actuator/health" 200 "$(status "$BASE_URL/actuator/health")"

echo "Authentication"
check "operator login with wrong password" 401 \
    "$(status -H 'Content-Type: application/json' -d '{"username":"demo","password":"wrong"}' "$BASE_URL/auth/token")"
ADMIN_TOKEN=$(json -d "{\"username\":\"$AUTH_USERNAME\",\"password\":\"$AUTH_PASSWORD\"}" "$BASE_URL/auth/token" | jq -r .accessToken)
[[ -n "$ADMIN_TOKEN" && "$ADMIN_TOKEN" != null ]] || { echo "  operator login failed" >&2; exit 1; }
check "operator login issues a token" 200 200
check "/api without a token" 401 "$(status "$BASE_URL/api/demo/hello")"
check "/api with a garbage token" 401 "$(status -H 'Authorization: Bearer nope' "$BASE_URL/api/demo/hello")"
check "/api with the operator token" 200 \
    "$(status -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/api/demo/hello")"
check "POST route without its required header (no route)" 404 \
    "$(status -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
        -d '{"message":"hi"}' "$BASE_URL/api/demo/echo")"
check "POST route with X-Demo-Route: enabled" 200 \
    "$(status -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
        -H 'X-Demo-Route: enabled' -d '{"message":"hi"}' "$BASE_URL/api/demo/echo")"

echo "Admin API"
CREATE=$(json -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "$CREATE_BODY" \
    -w '\n%{http_code}' "$BASE_URL/admin/clients")
check "create client $CLIENT_ID" 201 "$(tail -n1 <<<"$CREATE")"
API_KEY=$(head -n1 <<<"$CREATE" | jq -r .apiKey)
check "create the same client again" 409 \
    "$(status -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
        -d "$CREATE_BODY" \
        "$BASE_URL/admin/clients")"
check "create with an invalid clientId" 400 \
    "$(status -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
        -d '{"clientId":"bad id!","capacity":3,"refillTokens":1,"refillPeriodMillis":1000}' "$BASE_URL/admin/clients")"
check "list clients" 200 "$(status -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/admin/clients")"
check "get client" 200 "$(status -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/admin/clients/$CLIENT_ID")"

echo "Client login"
check "client login with a wrong key" 401 \
    "$(status -H 'Content-Type: application/json' -d "$WRONG_KEY_BODY" \
        "$BASE_URL/auth/client-token")"
client_token() { json -d "$(key_body "$1")" "$BASE_URL/auth/client-token" | jq -r .accessToken; }
CLIENT_TOKEN=$(client_token "$API_KEY")
check "client token is locked out of /admin" 403 \
    "$(status -H "Authorization: Bearer $CLIENT_TOKEN" "$BASE_URL/admin/clients")"

echo "Rate limiting (client tier: burst 3, refill 1/min)"
for i in 1 2 3; do
    check "request $i within burst" 200 "$(status -H "Authorization: Bearer $CLIENT_TOKEN" "$BASE_URL/api/demo/hello")"
done
HEADERS=$(curl -s -D - -o /dev/null -H "Authorization: Bearer $CLIENT_TOKEN" "$BASE_URL/api/demo/hello")
check "request 4 is throttled" 429 "$(awk 'NR==1{print $2}' <<<"$HEADERS")"
grep -qi '^retry-after:' <<<"$HEADERS" && check "429 carries Retry-After" yes yes || check "429 carries Retry-After" yes no

echo "Dynamic tier update"
check "raise the client's tier" 200 \
    "$(status -X PATCH -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
        -d '{"capacity":100,"refillTokens":100,"refillPeriodMillis":1000}' "$BASE_URL/admin/clients/$CLIENT_ID")"
LIMIT=$(curl -s -D - -o /dev/null -H "Authorization: Bearer $CLIENT_TOKEN" "$BASE_URL/api/demo/hello" \
    | awk -F': ' 'tolower($1)=="x-ratelimit-limit"{print $2}' | tr -d '\r')
check "new tier applies immediately (X-RateLimit-Limit)" 100 "$LIMIT"

echo "Key rotation and revocation"
NEW_KEY=$(curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/admin/clients/$CLIENT_ID/api-key" | jq -r .apiKey)
check "old key no longer logs in" 401 \
    "$(status -H 'Content-Type: application/json' -d "$(key_body "$API_KEY")" "$BASE_URL/auth/client-token")"
check "rotated key logs in" 200 \
    "$(status -H 'Content-Type: application/json' -d "$(key_body "$NEW_KEY")" "$BASE_URL/auth/client-token")"
check "revoke client" 204 \
    "$(status -X DELETE -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/admin/clients/$CLIENT_ID")"
check "revoked client can't log in" 401 \
    "$(status -H 'Content-Type: application/json' -d "$(key_body "$NEW_KEY")" "$BASE_URL/auth/client-token")"
check "re-enable client" 200 \
    "$(status -X PATCH -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
        -d '{"enabled":true}' "$BASE_URL/admin/clients/$CLIENT_ID")"
check "re-enabled client logs in again" 200 \
    "$(status -H 'Content-Type: application/json' -d "$(key_body "$NEW_KEY")" "$BASE_URL/auth/client-token")"

echo "All $pass checks passed."
