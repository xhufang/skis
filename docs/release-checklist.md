# SKIS formal release checklist

This checklist currently applies to the public `0.2.0` release, which packages the stabilization
work completed in internal `0.1.1` through `0.1.6` snapshot milestones. Those internal versions have
no release tag, Maven Central deployment, or GitHub Release. After `0.2.0`, new public capabilities
start in `0.2.1-SNAPSHOT` and later internal `0.2.x-SNAPSHOT` milestones, then ship in `0.3.0`.
The Central 0.0.4 publication contains only an empty `skis-parent` POM and is not an API
compatibility baseline; `0.1.0` is the first complete public baseline.

## Before tagging

- Confirm every project POM uses the intended non-snapshot release version and contains no SKIS
  `-SNAPSHOT` dependency.
- Confirm `CHANGELOG.md` has a dated section matching that version.
- Confirm the exact tag commit passes CI, PostgreSQL integration, API compatibility, and compliance.
- Confirm the independent H2 consumer passes with the release version and no reactor parent.
- Confirm the Fast Path smoke remains within its documented guardrails or has a reviewed written
  explanation for a regression.
- Confirm the `maven-central` environment contains `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`,
  `MAVEN_GPG_PRIVATE_KEY`, and `MAVEN_GPG_PASSPHRASE`.
- Confirm the signing public key is discoverable and the `io.github.xhufang` Central namespace is
  verified.
- Confirm the working tree is clean before creating the tag.

## Expected Central components

The authoritative component list is [`.github/release-components.txt`](../.github/release-components.txt).
For `0.2.0`, which publishes the completed 0.1.x stabilization line without the later SQL DSL or
MySQL work, it continues to contain exactly 16 GAVs:

- `skis-parent`, `skis-bom`;
- `skis-annotations`, `skis-core`, `skis-metadata`, `skis-processor`;
- `skis-sql-ast`, `skis-dialect-api`, `skis-mapping`, `skis-jdbc`;
- `skis-query`, `skis-mutation`, `skis-runtime`;
- `skis-dialect-postgresql`, `skis-dialect-h2`, `skis-spring`.

Every non-POM component must include its main JAR, POM, sources JAR, Javadoc JAR, checksums, and GPG
signatures. No planned, example, benchmark, test, Spring Boot, cache, or unsupported-dialect artifact
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
6. Move development to `0.2.1-SNAPSHOT` and restore the Unreleased comparison link. Later internal
   `0.2.x-SNAPSHOT` milestones remain unpublished until their accumulated capabilities ship in
   `0.3.0`.
