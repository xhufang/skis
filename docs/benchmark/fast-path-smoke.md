# Fast Path performance smoke

The 0.1 maintenance line uses a small PostgreSQL `findById` smoke to detect obvious regressions in
the reflection-free generated path. It is not a replacement for the full multi-ORM benchmark or a
general database performance claim.

## Reference

The committed 0.1.1 reference is
[`find-by-id-all-gc-20260828.json`](find-by-id-all-gc-20260828.json). In that run, hand-written
JDBC averaged `60.92 us/op` and `2376.87 B/op`; SKIS averaged `61.51 us/op` and `2592.81 B/op`.
The useful reference values are the same-run SKIS/JDBC ratios, not the absolute latency of one
machine.

## Automated smoke

The `Fast Path Smoke` workflow provisions PostgreSQL 16, creates the fixed `skis_user` row, and runs
only the JDBC and SKIS benchmark methods with one fork, three warmup iterations, five measurement
iterations, and the JMH GC profiler. It uploads the raw JSON result.

The comparison script rejects only an obvious maintenance-line regression:

- the SKIS/JDBC average-time ratio exceeds both `1.20` and 110% of the reference ratio; or
- the SKIS/JDBC allocation ratio exceeds both `1.25` and 110% of the reference ratio.

These loose limits keep the smoke useful on shared CI hardware. A failure requires investigation or
a written explanation; it must not be hidden by relaxing the limit in the same change.

For a controlled local run, configure the database variables described in
[`skis-benchmark/README.md`](../../skis-benchmark/README.md), build the runner, and use the same JMH
arguments shown in [the workflow](../../.github/workflows/fast-path-smoke.yml). Credentials and raw
database URLs must not be committed.
