# ADR 0001 — No sharding layer: residency already hands us country as the shard key

- **Status:** Accepted
- **Date:** 2026-10-22
- **Context:** Arc E of the scale-readiness plan (see [ROADMAP](../../ROADMAP.md))
- **Supersedes:** nothing
- **Decision in one line:** do not build a sharding layer; deploy per country and treat the
  regulator's boundary as the shard boundary.

## Context

The scale-readiness plan worked down the usual ladder of levers — vertical scale, read replicas,
cache, stateless horizontal scale, data lifecycle — and sharding sits at the bottom as the last and
most invasive. Arcs A–D are done, so the question "do we shard?" is now live rather than theoretical.

The honest framing is not *can we shard* but *what problem would sharding solve that we have*. Two
facts decide it, and neither is a matter of taste.

**One: the regulator has already partitioned the data for us.** The target markets require
customer financial and health data to stay in-country. That is not a scaling preference, it is a
licence condition, and it means there is no single logical database to shard in the first place —
there is one deployment per country, each holding only that country's data. A sharding layer's whole
value proposition is splitting one logical dataset across many physical ones by key. Residency has
already done exactly that, with `country` as the key, enforced by law rather than by a routing
library.

**Two: nothing here is near a single-node limit.** Measured on this system (arcs B and C):

| Dimension | Measured | Headroom |
| --- | --- | --- |
| Connections | 14 per instance vs MariaDB's `max_connections=151` | ~10 instances per DB |
| Write ceiling | ~200 credits/sec **per account**, lock-bound above ~16 concurrent writers | not a data-volume limit |
| Read path | 1,782 req/s, 78% of a no-op endpoint's ceiling | host-bound, not DB-bound |

The write ceiling is the interesting one: it is **per-account lock contention**, not table size or
node capacity. Sharding splits *rows across nodes*; it does nothing for ten thousand people
crediting one merchant account, because those rows live on one shard by definition. So the one
measured ceiling in the money path is a ceiling sharding cannot raise.

## Decision

**Deploy one stack per country. Do not introduce ShardingSphere, Vitess, or an application-level
shard router.**

Each country gets its own deployment of every service and its own databases. Routing a request to
the right country happens at the edge (DNS/gateway), not in a data-access layer. Within a country,
scale with the levers that are already in place: read replicas, cache, stateless replicas, and the
hot/cold data lifecycle.

## Consequences

### What this buys

**ACID stays inside the transaction boundary.** This is the concrete, already-paid-for benefit.
`TransferServiceImpl` is a *local ACID transaction*, deliberately not a saga, and its javadoc says
why: "both accounts live in this one database". Shard by customer and a transfer between two
customers becomes a cross-shard write — which means a saga, with a `PENDING` state, a compensating
leg, and a reconciliation job for the ambiguous cases. We have built that machinery once already for
payouts (`PayoutSagaWorker`), so this is not speculation about difficulty: we know precisely what it
costs, and it is the right design for an *external* rail and the wrong one for two rows in the same
database.

**Unique constraints keep meaning "unique".** The ledger archival work already ran this experiment
at small scale, and it is the strongest evidence in this repo. Partitioning `ledger_entries` was
rejected because MariaDB forces every unique key to contain the partition column (ERROR 1503),
which silently turns `UNIQUE (idempotency_key)` into *per-partition* uniqueness — "a
double-payment hole", in the migration's own words. Even the chosen two-table split cost something
real: global uniqueness stopped being one constraint and became *(hot UNIQUE) AND (archive UNIQUE)
AND the move being atomic*, forcing `LedgerEntryLookup` to read both halves in a specific order for
correctness.

Sharding is that same loss, N times over, **without** the atomic move that made the two-table
version safe. The idempotency key is the correctness backstop for money in this system; a sharded
deployment would have to replace a database constraint with a distributed uniqueness service, and
that service becomes a new single point of failure on the payment path.

**Foreign keys survive.** `ledger_entries` and `ledger_entries_archive` both carry
`FOREIGN KEY (account_id) REFERENCES billing_accounts (id)`. Cross-shard foreign keys do not exist;
they become application-enforced invariants, i.e. invariants that hold until a bug or a partial
failure says otherwise.

**Operational simplicity.** No resharding, no hot-shard rebalancing, no shard-aware migrations, no
"which shard is this customer on" debugging. Flyway migrations already have a loud failure mode on
version ordering; multiplying that by shard count is a real cost.

### What it costs, stated plainly

**Per-country deployments multiply fixed overhead.** Every market pays for its own Kafka, its own
Prometheus, its own replicas — even a small market with a fraction of the traffic. That is a genuine
cost of this decision and the main argument against it. It is accepted because residency makes the
deployments mandatory anyway: we are choosing not to add a *second* partitioning scheme on top, not
choosing to partition at all.

**A single country could eventually outgrow one database.** Nothing above rules this out. The
trigger conditions are in "When to revisit" below, and the honest answer is that we would reach for
vertical scale, then more aggressive archival, then functional splits — well before a shard router.

**Some things are genuinely global and need a home.** These do not fit the per-country model, and
pretending otherwise would be the real flaw in this decision:

| Concern | Why it is global | Where it goes |
| --- | --- | --- |
| **Cross-border transfers** | source and destination are in different countries, so no single DB holds both | an **orchestrated saga** across two deployments, using the existing payout pattern: reserve in-country, settle via the rail, compensate on definitive decline. Never a distributed transaction. |
| **FX rates** | one rate set, consumed everywhere, changes constantly | a global read-only service, replicated per region, cached locally. Rates are reference data: stale-tolerant for display, but a transfer must pin the rate it quoted into the transaction, not re-read it at settlement. |
| **Group reporting** | regulators want per-country, the board wants consolidated | per-country analytics projections (the CQRS read side already exists) feeding an **aggregate** export. Ship derived aggregates across the border, never raw PHI/customer rows — that is the residency rule, not a design preference. |
| **A customer in two markets** | the same human, two legal relationships | **two customer records, deliberately.** Not one record with two addresses. Each country's record is governed by that country's rules, including deletion. A global identity service may map "these two are the same person" for fraud and KYC, holding identifiers only. |
| **Auth / token issuance** | tokens are verified everywhere | per-country issuer with its own keys, since credentials are customer data. The `sub` claim is only required to be stable *within* a country, which the cross-service token contract already assumes. |

The cross-border transfer deserves emphasis: it is the one place where the thing we avoided for
same-country transfers (a saga) is genuinely unavoidable. That is fine — it is unavoidable because
of physics and law, not because of a routing library we chose to install. The distinction matters: a
sharding layer would have forced saga semantics onto *every* transfer, including the 99% that are
domestic and could have been one ACID transaction.

### Fraud detection is the real counter-case

Fraud wants the opposite of residency: the widest possible view, cross-border, in near real time.
This is the one workload that argues for centralisation, and the resolution is to move **signals,
not records** — ship derived features and scores across the boundary rather than customer rows,
keeping the raw data in-country. Worth noting as the known tension rather than papering over it.

## When to revisit

This decision should be reopened if any of these becomes true — and only then:

1. **A single country's working set outgrows one node** after vertical scale, read replicas and
   archival are exhausted. The signal is buffer-pool thrash and backup/restore windows, not row
   count — an indexed lookup barely notices table size.
2. **Residency relaxes** (a regional data bloc replaces per-country rules), which would create a
   genuinely large single logical dataset for the first time.
3. **A tenant inside one country needs isolation** for regulatory or contractual reasons — at which
   point the shard key is the tenant, and the same argument as this ADR applies one level down.
4. **Write throughput becomes node-bound rather than lock-bound.** Today the measured write ceiling
   is per-account lock contention (~200/sec/account); sharding does not help that. If the binding
   constraint moves to aggregate write volume across *many* accounts, the calculus changes.

Note what is *not* on this list: "we have a lot of customers". Customer count alone has never been
the trigger; the working set and the binding constraint are.

## Alternatives considered

**ShardingSphere-JDBC with `country` as the shard key.** Rejected as redundant: it would route
within one logical database to achieve what separate deployments already achieve physically, while
reintroducing every cross-shard cost above. It would also be the *more fragile* enforcement of
residency — a routing rule in application config versus a database that physically does not contain
other countries' data. For a licence condition, the physical guarantee is the one worth having.

**Shard by customer id within a country.** Rejected as premature and aimed at the wrong constraint.
It breaks same-country transfers into sagas (see above) to relieve a limit we are nowhere near, and
it does nothing for the hot-account contention that is the actual measured ceiling.

**A single global deployment.** Not an option: it violates residency in the target markets.
Recorded here because it is the baseline the other options are measured against, not because it was
ever viable.
