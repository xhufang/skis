# Fast Path performance smoke

The PostgreSQL Fast Path smoke detects obvious regressions in the reflection-free generated path.
It covers `findById`, an unfiltered entity query, and a single-property equality entity query. It is
not a replacement for the full multi-ORM benchmark or a general database performance claim.

## Reference

The committed 0.1.1 reference is
[`find-by-id-all-gc-20260828.json`](find-by-id-all-gc-20260828.json). In that run, hand-written
JDBC averaged `60.92 us/op` and `2376.87 B/op`; SKIS averaged `61.51 us/op` and `2592.81 B/op`.
The useful reference values are the same-run SKIS/JDBC ratios, not the absolute latency of one
machine.

## Automated smoke

The `Fast Path Smoke` workflow provisions PostgreSQL 16, creates the fixed `skis_user` row, and runs
the matching JDBC and SKIS methods with one fork, three warmup iterations, five measurement
iterations, and the JMH GC profiler. It uploads the raw JSON result. The two query methods execute
the same SQL and decode the same full row shape; they exercise the bounded precompiled no-condition
and single-equality plans rather than a Join plan.

The comparison script rejects only an obvious Fast Path regression:

- the SKIS/JDBC average-time ratio exceeds both `1.20` and 110% of the reference ratio; or
- the SKIS/JDBC allocation ratio exceeds both `1.25` and 110% of the reference ratio.

The two query Fast Paths have no historical JSON baseline yet, so each is compared with its
same-run hand-written JDBC method. The smoke rejects a time ratio above `1.35` or an allocation
ratio above `1.50`. These are detection guardrails, not claimed steady-state performance targets.

These loose limits keep the smoke useful on shared CI hardware. A failure requires investigation or
a written explanation; it must not be hidden by relaxing the limit in the same change.

For a controlled local run, configure the database variables described in
[`skis-benchmark/README.md`](../../skis-benchmark/README.md), build the runner, and use the same JMH
arguments shown in [the workflow](../../.github/workflows/fast-path-smoke.yml). Credentials and raw
database URLs must not be committed.
