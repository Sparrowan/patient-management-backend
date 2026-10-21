#!/usr/bin/env bash
#
# Resolves a service's published host port by asking Docker, rather than assuming it.
#
# WHY THIS IS NECESSARY. Arc B made the app services scalable, which meant giving up both
# `container_name` and fixed host ports -- they are precisely what `docker compose --scale` forbids.
# Services now publish a RANGE (billing `4010-4013:4001`), and Docker hands out the next free port
# in that range. So the host port is an output of the environment, not a constant: after a restart
# where the previous binding is still in TIME_WAIT, billing can come up on 4011, 4012 or 4013.
#
# Hardcoding 4010 therefore points the load test at nothing, and -- the dangerous part -- curl's
# "connection refused" looks exactly like a service that is down, so you debug the wrong thing. This
# is the load-test-side cost of a scalable compose file, and it is cheaper to pay here than to give
# up `--scale`.
#
# Usage:
#   BILLING_URL="http://localhost:$(./loadtest/ports.sh billing-service 4001)"
set -euo pipefail

SERVICE="${1:?usage: ports.sh <compose-service> <container-port>}"
CONTAINER_PORT="${2:?usage: ports.sh <compose-service> <container-port>}"

cd "$(dirname "$0")/.."

# `compose port` resolves through the compose project, so it works without a container_name.
mapping=$(docker compose port "$SERVICE" "$CONTAINER_PORT" 2>/dev/null || true)

if [[ -z "$mapping" ]]; then
    echo "could not resolve a host port for $SERVICE:$CONTAINER_PORT -- is it running?" >&2
    exit 1
fi

# "0.0.0.0:4013" -> "4013"
printf '%s' "${mapping##*:}"
