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
TOKEN=$(curl -s -X POST http://localhost:4002/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"admin","password":"password"}' | jq -r .accessToken)

docker run --rm -i -v "$PWD/loadtest:/scripts" \
  -e BASE_URL=http://host.docker.internal:4000 -e TOKEN="$TOKEN" \
  -e PATIENT_ID=<an-existing-id> -e VUS=100 \
  grafana/k6 run --quiet /scripts/read.js
```

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
