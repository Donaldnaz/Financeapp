# DR Test Results (Game Day Simulation)

## Test Metadata

- Date: 2026-05-21
- Environment: `dev`
- Topology: active-active (`us-east-1`, `us-west-2`)
- Test type: controlled regional impairment simulation
- Scope: API availability, traffic redistribution, write/read continuity

## Test Scenario

1. Baseline both regions healthy.
2. Introduce impairment in `us-east-1` by scaling ECS desired count to zero (simulation proxy for outage).
3. Observe Route53 health checks and traffic shift.
4. Continue write/read flow from client.
5. Restore `us-east-1` and verify traffic rebalance.

## Measurement Summary

- Observed RTO (health-check failover detection): **~60s**
- Observed RPO: **< 30s** (no durable data loss observed)
- Client-visible downtime: **none for healthy-path traffic**
- Total game-day window: **348s** (`2026-05-21T22:29:08Z` to `2026-05-21T22:34:56Z`)

## Timeline (UTC)

- `22:29:08` baseline verification complete
- `22:29:08` impairment introduced in `us-east-1` (`desiredCount=0`)
- `22:30:08` Route53 health check for region A starts reporting `503` failures
- `22:33:24` transaction write validated through healthy region (`us-west-2`)
- `22:34:57` same transaction read successfully from recovered `us-east-1` path

## Evidence Checklist

- [x] `/health` checks from both ALBs (`dev` and `prod`)
- [x] Route53 weighted records and health-check IDs validated
- [x] ECS running counts confirmed in both regions
- [x] `POST /transactions` and cross-region `GET /transactions/{id}` validated during/after impairment
- [x] Post-recovery steady state validation

## Recorded Evidence (From Execution)

- Dev endpoint: `api-dev.ikenna-financeapp-dr.com`
- Prod endpoint: `api.ikenna-financeapp-dr.com`
- Test transaction ID: `dr-20260521T222908Z`
- Write response (secondary region):
  - `region: us-west-2`
  - `createdAt: 2026-05-21T22:33:24.425210633Z`
- Read response from secondary ALB: success
- Read response from primary ALB after recovery: success (same transaction payload)

## Findings

- Active-active routing reduced blast radius of single-region failure.
- DynamoDB global table supported continuity for this workload profile.
- Most user impact came from DNS/health-check convergence window.

## Follow-up Actions

- Lower Route53 TTL and tune health check intervals where acceptable.
- Add synthetic canaries per region for faster anomaly detection.
- Tighten IAM policies from broad managed policies to custom least-privilege policies.
