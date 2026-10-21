// Write-path load test: POST /api/v1/billing-accounts/{id}/credit.
//
// This is the measurement the read tests cannot make. A read scales by copying: cache it, replicate
// it, add instances. A credit takes a pessimistic write lock on the account row --
//
//     accountRepository.findByIdForUpdate(accountId)   // SELECT ... FOR UPDATE
//
// -- held for the rest of the transaction (balance update + ledger insert + commit). Two credits to
// the SAME account therefore cannot overlap: the second waits for the first to commit. Throughput
// against one account is bounded by 1/hold_time no matter how much hardware you add, and adding
// instances makes it WORSE, because more concurrency means more threads queued on the same row.
//
// THE EXPERIMENT. One variable, two runs, same total load:
//
//   MODE=one    every VU credits the SAME account  -> fully serialised; the lock IS the ceiling
//   MODE=many   each VU credits a DIFFERENT account -> locks on distinct rows; no contention
//
// If `many` scales with VUs while `one` stays flat, the ceiling is provably the lock rather than
// CPU, connections or the network -- and the flat line's value is the lock hold time, since
// serialised throughput is 1/hold_time. That is the hot-account problem, and it is not theoretical
// in banking: a payroll run crediting thousands of salaries out of one corporate account, a
// utility's single collection account on a due date, a merchant settling a day's takings. Those are
// exactly MODE=one.
//
// WHY THIS MEASUREMENT SURVIVES A LAPTOP. The absolute req/s here describes this host (the whole
// stack and the load generator share 8 cores; ~2,300 req/s is the ceiling for *anything* -- see
// control.js). But lock contention bites at tens of requests per second against one row, far below
// where the hardware binds. The one-vs-many RATIO is a property of the code, so it transfers.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// Split out so the summary distinguishes "the server refused" from "the server was slow". A 409 is
// the generic idempotency layer rejecting a duplicate in flight; a 500 under load is usually a
// lock-wait timeout surfacing, and that is a finding, not noise.
const applied = new Counter('credits_applied');
const conflicted = new Counter('credits_conflicted_409');
const failed = new Counter('credits_failed');
const lockWaitLatency = new Trend('credit_duration_ms', true);

export const options = {
    scenarios: {
        load: {
            executor: 'constant-vus',
            vus: Number(__ENV.VUS || 10),
            duration: __ENV.DURATION || '30s',
        },
    },
    // The tail is the whole story on a contended lock: the average stays respectable while p99
    // climbs into lock-wait territory. An average alone would hide exactly what we came to find.
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
    // No thresholds on purpose -- this script measures a ceiling, it does not assert one. A
    // threshold would make a successful discovery look like a failed test run.
};

const BASE = __ENV.BASE_URL;
const TOKEN = __ENV.TOKEN;
const MODE = __ENV.MODE || 'many';

// Comma-separated account ids, produced by seed-accounts.sh. In `many` mode we need at least as
// many accounts as VUs or some VUs would share a row and quietly contaminate the control arm.
const ACCOUNTS = (__ENV.ACCOUNT_IDS || '').split(',').filter((s) => s.length > 0);

export function setup() {
    if (!BASE || !TOKEN || ACCOUNTS.length === 0) {
        fail('BASE_URL, TOKEN and ACCOUNT_IDS are all required');
    }
    const vus = Number(__ENV.VUS || 10);
    if (MODE === 'many' && ACCOUNTS.length < vus) {
        // Loud, because silently reusing accounts would make `many` look contended and the whole
        // comparison would conclude the opposite of the truth.
        fail(`MODE=many needs >= VUS accounts; got ${ACCOUNTS.length} accounts for ${vus} VUs`);
    }
    return { mode: MODE, accounts: ACCOUNTS.length };
}

export default function () {
    // `one` pins every VU to the first account -- maximum contention, one row.
    // `many` gives each VU its own account, so no two VUs ever contend.
    const accountId = MODE === 'one' ? ACCOUNTS[0] : ACCOUNTS[__VU % ACCOUNTS.length];

    // A UNIQUE key per request. Reusing one would be replayed from the idempotency table and never
    // reach the lock at all -- we would be load-testing the cache. The domain's unique
    // idempotency_key column means a collision is a hard failure, not a retry, so make it
    // collision-proof: VU + iteration is unique within a run, and the timestamp across runs.
    const idempotencyKey = `lt-${MODE}-${Date.now()}-${__VU}-${__ITER}`;

    const res = http.post(
        `${BASE}/api/v1/billing-accounts/${accountId}/credit`,
        JSON.stringify({ amount: '1.00', description: 'loadtest' }),
        {
            headers: {
                Authorization: `Bearer ${TOKEN}`,
                'Content-Type': 'application/json',
                'Idempotency-Key': idempotencyKey,
            },
            // Tag by mode so a combined run can be split in the output; also keeps the URL from
            // being cardinality-exploded by the account id in k6's per-URL metrics.
            tags: { mode: MODE, name: 'credit' },
        },
    );

    lockWaitLatency.add(res.timings.duration);
    if (res.status === 200) {
        applied.add(1);
    } else if (res.status === 409) {
        conflicted.add(1);
    } else {
        failed.add(1);
    }

    check(res, {
        'credit applied (200)': (r) => r.status === 200,
    });
}
