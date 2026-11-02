# SKIS formal release checklist

This checklist preserves the published `0.2.0` component boundary and now governs preparation of the
next public `0.3.0` release. `0.2.0` packages the stabilization work completed in internal `0.1.1`
through `0.1.6` snapshot milestones; those internal versions have no release tag, Maven Central
deployment, or GitHub Release. New public capabilities start in `0.2.1-SNAPSHOT` and later internal
`0.2.x-SNAPSHOT` milestones, then ship in `0.3.0`.
The Central 0.0.4 publication contains only an empty `skis-parent` POM; `0.1.0` is the first
complete public release. Releases before `1.0.0` validate the current candidate and do not use
either publication as a cross-version API/ABI baseline.

Internal `0.2.10` write/stream acceptance uses `9a7db594` and its completed
[CI](https://github.com/xhufang/skis/actions/runs/41395524566) /
[Compliance](https://github.com/xhufang/skis/actions/runs/41395524562). On 2026-11-02 the user cancelled
the `0.2.11` milestone and pre-release performance verification. Stabilization, fresh consumers,
security/compliance and final documentation now belong directly to `0.3.0` release preparation.
The root README remains deferred to the final documentation pass. The current working tree is still
`0.2.10-SNAPSHOT`; committing, changing versions and publishing are subsequent steps.

## Preparation order

Save and commit current changes, switch versions, verify the final candidate, update
README/topic documentation/Javadoc/CHANGELOG, then
publish `0.3.0` according to this checklist. No `0.2.11` release or separate performance phase is scheduled.

## Before tagging

- Confirm every project POM uses the intended non-snapshot release version and contains no SKIS
  `-SNAPSHOT` dependency.
- Confirm `CHANGELOG.md` has a dated section matching that version.
- Confirm the exact tag commit passes CI, PostgreSQL integration, release-boundary metadata,
  and compliance.
- Confirm the dialect-api-only, core-only listener, H2 and real PostgreSQL independent consumers
  pass with the release version and no reactor parent. Required reports must be non-empty with
  zero failures, errors and skipped tests.
- Complete final contract, consumer, security/compliance, release-artifact and documentation checks on
  the actual release candidate; do not use the `9a7db594` baseline as evidence for a later commit.
- Keep `skis-benchmark` empty and excluded from publication. `0.3.0` does not require performance
  measurements, a benchmark rebuild/run, numeric performance thresholds or a performance report.
  Structural, concurrency, resource and correctness checks remain required; they do not establish
  measured throughput, latency, allocation or cross-framework comparisons.
- Confirm the `maven-central` environment contains `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`,
  `MAVEN_GPG_PRIVATE_KEY`, and `MAVEN_GPG_PASSPHRASE`.
- Confirm the signing public key is discoverable and the `io.github.xhufang` Central namespace is
  verified.
- Confirm the working tree is clean before creating the tag.

## Expected Central components

The authoritative component list is [
`.github/release-components.txt`](../.github/release-components.txt).
For the current `0.2.10-SNAPSHOT` cumulative release set it contains exactly 17 GAVs; the already
published `0.2.0` component set remains a historical release fact and is not retroactively changed:

- `skis-parent`, `skis-bom`;
- `skis-annotations`, `skis-core`, `skis-metadata`, `skis-processor`;
- `skis-sql-ast`, `skis-dialect-api`, `skis-dialect-support`, `skis-mapping`, `skis-jdbc`;
- `skis-query`, `skis-mutation`, `skis-runtime`;
- `skis-dialect-postgresql`, `skis-dialect-h2`, `skis-spring`.

Every non-POM component must include its main JAR, POM, sources JAR, Javadoc JAR, checksums, and GPG
signatures. No planned, example, test, Spring Boot, cache, unsupported-dialect, or internal
`skis-benchmark` artifact
may appear. CI cross-checks the list against `skis-bom`, every reactor module's effective
`maven.deploy.skip`, and the Central plugin exclusions. A later minor may add a component only after
that module is implemented, documented, tested, included in the BOM, and deliberately added to the
allowlist.

## Publish

1. Create an annotated `v<version>` tag on the reviewed commit and push it.
2. The release workflow builds, signs, uploads, and waits for Central validation. It intentionally
   does not auto-publish and creates a draft GitHub Release.
3. Inspect the validated deployment in Central Portal against the component list above.
4. Publish the validated Central deployment manually.
5. After Central reports `PUBLISHED`, inspect and publish the draft GitHub Release.
6. Move development to the planned next snapshot and restore the Unreleased comparison link.
   The `0.2.0` release began the internal `0.2.x-SNAPSHOT` line; after public `0.3.0`, the next
   internal line is `0.3.1-SNAPSHOT`. Internal milestones do not create release tags.
