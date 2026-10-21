# Load testing

Scripts and findings for measuring where this system actually saturates.

Open [`scale-audit.html`](scale-audit.html) in a browser for the written audit — the measured
numbers, the three things that don't scale by adding machines, and what a multi-country deployment
changes. The plan that came out of it lives in [`../ROADMAP.md`](../ROADMAP.md) under
"Scale-readiness plan".

## Running

k6 runs from its Docker image, so nothing needs installing.

```bash
# Platform ceiling: an endpoint that does no work. Every other number is relative to this one.
docker run --rm -i -v "$PWD/loadtest:/scripts" \
  -e BASE_URL=http://host.docker.internal:4000 \
  grafana/k6 run --quiet --summary-export=/scripts/control.json /scripts/control.js

# A real read: JWT-verified, cache-backed.
TOKEN=$(curl -s -X POST http://localhost:4020/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"admin","password":"password"}' | jq -r .accessToken)

docker run --rm -i -v "$PWD/loadtest:/scripts" \
  -e BASE_URL=http://host.docker.internal:4000 -e TOKEN="$TOKEN" \
  -e PATIENT_ID=<an-existing-id> -e VUS=100 \
  grafana/k6 run --quiet /scripts/read.js
```

The write path has its own driver, which seeds its own accounts and sweeps both arms:

```bash
# One-vs-many contention sweep. Resolves ports itself; see the caveat under "The write path".
VU_LEVELS="1 2 4 8 16 32" DURATION=20s ./loadtest/contention-sweep.sh
```

**Ports are discovered, not hardcoded.** Arc B gave the app services port *ranges* so
`docker compose --scale` works, so billing comes up on whichever of `4010-4013` is free — after a
restart with the old binding still in `TIME_WAIT`, that is often not `4010`. `ports.sh` asks Docker:

```bash
BILLING_PORT=$(./loadtest/ports.sh billing-service 4001)
TOKEN=$(./loadtest/token.sh)                       # admin; money movement is admin-only
ACCOUNT_IDS=$(COUNT=32 TOKEN="$TOKEN" ./loadtest/seed-accounts.sh)
```

A hardcoded port fails as "connection refused", which looks exactly like a service that is down —
so you debug the wrong thing.

ApacheBench (`ab`, preinstalled on macOS) is useful for a quick concurrency sweep, because finding
the knee matters more than any single number:

```bash
for c in 10 25 50 100 200; do
  ab -n 6000 -c $c -q -H "Authorization: Bearer $TOKEN" \
    "http://localhost:4000/api/v1/patients/$PID" 2>/dev/null \
    | awk '/Requests per second/{r=$4} /^  95%/{p=$2} END{printf "c=%s  %s req/s  p95=%sms\n", '"$c"', r, p}'
done
```

`*.json` here is per-run summary output and is git-ignored — it describes one machine on one day.

## Three things to get right, or the numbers lie

**Never target the gateway (`:4004`).** Its per-user/IP token bucket will reject the load and you
will be measuring the rate limiter. Hit a service port directly.

**Measure the harness before the application.** `control.js` deliberately hits an endpoint that
touches no database, cache or auth. If a real endpoint lands near that ceiling, the platform is the
constraint and tuning the service is wasted effort. Note that `/actuator/health` is *not* a no-op —
it runs the dependency health indicators — which is why the control uses `/actuator/health/liveness`.

**Sweep concurrency; never report one number.** Throughput rises, peaks, then *falls* while latency
climbs. Past that knee, more load produces less work — queueing, not failure. A benchmark quoted at a
single concurrency level says nothing about where the system actually breaks.

## Reading the results

On an 8-core laptop with the whole stack and the load generator sharing the same CPUs, the ceiling
for *anything* is ~2,300 req/s, and the host hits 0% idle with more than half its CPU in the kernel.
**Absolute figures from that environment describe the machine, not the system.** What transfers is
the relative comparison at fixed concurrency: cache on vs off, one account vs many, one instance vs
two. Those hold regardless of hardware.

## The write path (Arc C)

`write.js` + `contention-sweep.sh` measure the money path: `POST /billing-accounts/{id}/credit`,
which takes `SELECT … FOR UPDATE` on the account row. `MODE=one` sends every request to one account
(maximum contention); `MODE=many` gives each VU its own (none). Same load, and the only difference
is whether the locks meet.

**What it found — a defect, before any throughput number.** Twelve concurrent credits to one account
returned 4×200 and **8×500**: MariaDB error 1020 ("Record has changed since last read"). It needs a
**non-locking** read of a row, a concurrent commit to it, then `FOR UPDATE` on it, all under
REPEATABLE READ — and `applyMovement` has exactly that shape, because the idempotency-key lookup
opens the snapshot before the account is locked. The lock meant to *serialize* concurrent movements
made them **error**. `TransferServiceImpl` and `PayoutServiceImpl` already set `READ_COMMITTED` and
explain why; the oldest money path never got it, and had no concurrent-same-account test. Fixed, and
`ConcurrentMoneyMovementIntegrationTest` now fails without the fix (8/10 → 500) and passes with it.

A sweep for the same bug elsewhere found **no other instance**: patient/auth/analytics take no
pessimistic locks at all, and `PayoutSagaWorker` takes this one *without* `READ_COMMITTED` but is
safe, because `claimDuePending` is itself `FOR UPDATE SKIP LOCKED` and the account is never read
before it is locked. Verified in SQL both ways — snapshot-read → update → locking read fails 1020;
locking-read → update → locking read just blocks and succeeds. So the rule keys on **read
ordering**, not on "is this a money path".

**What it found about the ceiling — not what was predicted.** The hypothesis was that one account
would stay flat while many scaled, proving the lock was the ceiling. Measured, on one account:

| concurrent | throughput | mean latency |
| --- | --- | --- |
| 1 | 38/s | 14ms |
| 8 | 186/s | 19ms |
| 16 | 210/s | 17ms |
| 32 | 131/s | 105ms |
| 64 | 209/s | 149ms |

One account *outperformed* sixteen (210/s vs 116/s at the same concurrency) — one hot row stays in
InnoDB's buffer pool while sixteen spread across more pages. The lock is held for only a fraction of
a ~14ms request, so below ~16 concurrent writers the arrivals rarely overlap and **the lock is not
the constraint**. It becomes one above that: from 32 concurrent, throughput plateaus around
130–210/s while mean latency climbs 19 → 105 → 149ms, roughly linearly. Work per request is
unchanged, so the extra time is purely queueing — the serialisation signature. Call it **~200
credits/sec per account** on this host, with latency, not errors, as the symptom.

**Do not trust the `contention-sweep.sh` throughput columns on a laptop.** During the sweep the host
load average hit **28 on 8 cores** while billing used 1.6% CPU and MariaDB 0.24% — the rig, not the
application, was the bottleneck, and it swamped the signal. The per-request latency probes above are
the reliable read, because queueing shows up in latency long before it shows up in throughput.
