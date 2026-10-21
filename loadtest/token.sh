#!/usr/bin/env bash
#
# Prints an admin access token. Money movement is admin-only (@PreAuthorize("hasRole('ADMIN')")),
# so a USER token gets a 403 on every credit and the load test measures the authorization filter.
#
# Hits auth-service directly on :4020, not the gateway -- the gateway's Redis token bucket
# (10/s replenish, 20 burst) would throttle the load test itself.
set -euo pipefail

# Discovered, not hardcoded: auth publishes a port RANGE (4020-4023) so it can be scaled, so the
# host port depends on what was free when the container started. See ports.sh.
AUTH_URL="${AUTH_URL:-http://localhost:$("$(dirname "$0")/ports.sh" auth-service 4002)}"
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-password}"

response=$(curl -sf -X POST "$AUTH_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"usernameOrEmail\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")

token=$(printf '%s' "$response" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
if [[ -z "$token" ]]; then
    echo "login failed against $AUTH_URL -- response: $response" >&2
    exit 1
fi
printf '%s' "$token"
