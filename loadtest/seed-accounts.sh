#!/usr/bin/env bash
#
# Seeds billing accounts for the write-path load test and prints their ids as a comma-separated
# list, ready to pass straight to write.js as ACCOUNT_IDS.
#
# MODE=many needs one account per VU, so the highest concurrency you intend to sweep sets the count.
#
# Why this talks to billing directly instead of registering patients: opening an account needs only
# a patientId and a currency -- there is no cross-service foreign key (ACID stops at the service
# boundary, by design), so a synthetic UUID is a perfectly valid account owner here. Going through
# patient registration would route every seed through the outbox relay and Kafka, making the seed
# slow, asynchronous and -- worse -- it would leave the relay publishing in the background while we
# measure, polluting the very thing under test.
#
# Usage:
#   TOKEN=$(./loadtest/token.sh)
#   ACCOUNT_IDS=$(COUNT=100 TOKEN="$TOKEN" ./loadtest/seed-accounts.sh)
set -euo pipefail

: "${TOKEN:?TOKEN is required (see token.sh)}"
# Discovered, not hardcoded -- billing publishes the range 4010-4013. See ports.sh.
BILLING_URL="${BILLING_URL:-http://localhost:$("$(dirname "$0")/ports.sh" billing-service 4001)}"
COUNT="${COUNT:-100}"

ids=()
for _ in $(seq 1 "$COUNT"); do
    patient_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
    # Capture the created account's own id -- NOT the patient id. The credit endpoint is keyed on
    # the account id, and they are different UUIDs.
    account_id=$(curl -sf -X POST "$BILLING_URL/api/v1/billing-accounts" \
        -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' \
        -d "{\"patientId\":\"$patient_id\",\"currency\":\"USD\"}" \
        | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

    if [[ -z "$account_id" ]]; then
        echo "failed to open an account -- is billing-service up on $BILLING_URL?" >&2
        exit 1
    fi
    ids+=("$account_id")
done

# Comma-separated, no trailing newline noise, so it can be captured with $(...) and passed as one
# environment variable.
(IFS=,; printf '%s' "${ids[*]}")
