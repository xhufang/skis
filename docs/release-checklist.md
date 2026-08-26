# SKIS 0.1.0 release checklist

Version 0.1.0 is the first complete public SKIS release. The Central 0.0.4 publication contains only
an empty `skis-parent` POM and is not an API compatibility baseline.

## Before tagging

- Confirm every project POM uses `0.1.0` and contains no SKIS `-SNAPSHOT` dependency.
- Confirm `CHANGELOG.md` has a dated `## [0.1.0]` section.
- Confirm the exact tag commit passes CI, PostgreSQL integration, API compatibility, and compliance.
- Confirm the `maven-central` environment contains `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`,
  `MAVEN_GPG_PRIVATE_KEY`, and `MAVEN_GPG_PASSPHRASE`.
- Confirm the signing public key is discoverable and the `io.github.xhufang` Central namespace is
  verified.
- Confirm the working tree is clean before creating the tag.

## Expected Central components

The validated deployment must contain exactly these 16 GAVs for version 0.1.0:

- `skis-parent`, `skis-bom`;
- `skis-annotations`, `skis-core`, `skis-metadata`, `skis-processor`;
- `skis-sql-ast`, `skis-dialect-api`, `skis-mapping`, `skis-jdbc`;
- `skis-query`, `skis-mutation`, `skis-runtime`;
- `skis-dialect-postgresql`, `skis-dialect-h2`, `skis-spring`.

Every non-POM component must include its main JAR, POM, sources JAR, Javadoc JAR, checksums, and GPG
signatures. No planned, example, benchmark, test, Spring Boot, cache, or unsupported-dialect artifact
may appear.

## Publish

1. Create the annotated tag `v0.1.0` on the reviewed commit and push it.
2. The release workflow builds, signs, uploads, and waits for Central validation. It intentionally
   does not auto-publish and creates a draft GitHub Release.
3. Inspect the validated deployment in Central Portal against the component list above.
4. Publish the validated Central deployment manually.
5. After Central reports `PUBLISHED`, inspect and publish the draft GitHub Release.
6. Move development to `0.1.1-SNAPSHOT` (or the explicitly planned next minor version) and restore
   the Unreleased comparison link.
