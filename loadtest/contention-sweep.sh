#!/usr/bin/env bash
#
# Runs the Arc C experiment: the same write load against ONE account and against MANY, swept across
# concurrency levels, printing a table you can read the conclusion straight off.
#
# The sweep is the point. A single concurrency level says nothing: throughput rises, peaks, then
# FALLS while latency climbs, and the interesting number is where that knee sits. Two arms at every
# level is what isolates the lock:
#
#   many  -- locks on distinct rows. Throughput should climb with VUs until the host binds.
#   one   -- all VUs on one row, fully serialised. Throughput should go FLAT almost immediately,
#            and p99 should climb roughly linearly with VUs, because each arrival queues behind
#            every other.
#
# A flat `one` next to a climbing `many` is the proof that the ceiling is the pessimistic lock and
# not CPU, connections or the network. The flat value is also the measurement worth keeping:
# serialised throughput is 1/hold_time, so it tells you how long the credit transaction holds the
# account row.
set -euo pipefail

cd "$(dirname "$0")/.."

# Resolved once, from Docker. Billing publishes a port range (4010-4013) so it can be scaled, so the
# actual host port varies between runs -- see ports.sh for why this is not a hardcoded constant.
BILLING_PORT="${BILLING_PORT:-$(./loadtest/ports.sh billing-service 4001)}"
BILLING_URL="${BILLING_URL:-http://localhost:$BILLING_PORT}"
# k6 runs in a container, so it cannot use localhost to reach the host's published ports.
K6_BILLING_URL="${K6_BILLING_URL:-http://host.docker.internal:$BILLING_PORT}"
VU_LEVELS="${VU_LEVELS:-1 2 4 8 16 32}"
DURATION="${DURATION:-20s}"

highest=0
for vus in $VU_LEVELS; do
    [[ $vus -gt $highest ]] && highest=$vus
done

echo "==> admin token"
TOKEN="${TOKEN:-$(./loadtest/token.sh)}"

# One account per VU at the widest level, so the `many` arm never doubles up and contaminates itself.
echo "==> seeding $highest accounts"
ACCOUNT_IDS=$(COUNT="$highest" TOKEN="$TOKEN" BILLING_URL="$BILLING_URL" ./loadtest/seed-accounts.sh)

run() {
    local mode=$1 vus=$2
    # --quiet suppresses the progress bar; the summary still prints, and we pull the two numbers
    # that matter out of it. k6 exits non-zero if a check fails, which we tolerate: a failed check
    # under contention is data (it usually means a lock-wait timeout) and must not abort the sweep.
    docker run --rm -i -v "$PWD/loadtest:/scripts" \
        -e BASE_URL="$K6_BILLING_URL" \
        -e TOKEN="$TOKEN" \
        -e ACCOUNT_IDS="$ACCOUNT_IDS" \
        -e MODE="$mode" \
        -e VUS="$vus" \
        -e DURATION="$DURATION" \
        grafana/k6 run --quiet /scripts/write.js 2>&1 || true
}

extract() {
    # Pulls throughput, p99 and the failure counters out of k6's text summary.
    awk '
        /http_reqs/                 { for (i=1;i<=NF;i++) if ($i ~ /\/s$/) { sub(/\/s$/,"",$i); rps=$i } }
        /credit_duration_ms/        { for (i=1;i<=NF;i++) if ($i ~ /^p\(99\)=/) { sub(/^p\(99\)=/,"",$i); p99=$i } }
        /credits_failed/            { for (i=1;i<=NF;i++) if ($i ~ /^[0-9]+$/) { failed=$i; break } }
        /credits_conflicted_409/    { for (i=1;i<=NF;i++) if ($i ~ /^[0-9]+$/) { conflict=$i; break } }
        END { printf "%s|%s|%s|%s", (rps?rps:"?"), (p99?p99:"?"), (failed?failed:"0"), (conflict?conflict:"0") }
    '
}

printf '\n%-6s | %-22s | %-22s\n' "VUs" "one account" "many accounts"
printf '%-6s | %-22s | %-22s\n' "" "req/s      p99      err" "req/s      p99      err"
printf '%s\n' "-------+------------------------+------------------------"

for vus in $VU_LEVELS; do
    one=$(run one "$vus" | extract)
    many=$(run many "$vus" | extract)

    IFS='|' read -r o_rps o_p99 o_fail o_conf <<<"$one"
    IFS='|' read -r m_rps m_p99 m_fail m_conf <<<"$many"

    printf '%-6s | %-10s %-8s %-3s | %-10s %-8s %-3s\n' \
        "$vus" "$o_rps" "$o_p99" "$((o_fail + o_conf))" \
        "$m_rps" "$m_p99" "$((m_fail + m_conf))"
done

cat <<'EOF'

Reading it: if `many` climbs while `one` stays flat, the write ceiling is the pessimistic lock on
the account row, not the machine. The flat `one` figure is ~1/hold_time, i.e. the per-account
write ceiling that no amount of horizontal scaling will raise.
EOF
