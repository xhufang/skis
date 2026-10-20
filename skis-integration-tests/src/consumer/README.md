# Independent current-version consumers

These Maven projects have no parent and are not reactor modules. CI copies each project into the
runner's temporary directory after installing only its SKIS dependency closure. They use the current
generator and runtime together and do not import framework internal packages or repository fixtures.

| Project | Direct SKIS dependencies and purpose |
| --- | --- |
| `spi-only` | Only `skis-dialect-api`; implements an external dialect without the support module. |
| `listener-only` | Only `skis-core`; implements and calls all five listener callbacks using public events. |
| `minimal-h2` | BOM, APT, runtime, H2 and supported SPI artifacts; verifies generated CRUD/projections, external dialect/Codec/connections, listener registration, Session inheritance, Page/Slice, stream termination, safety and re-entry. |
| `minimal-postgresql` | BOM, APT, runtime and PostgreSQL dialect with an explicit test driver; verifies generated CRUD/projections, Page/count, stream termination, rollback and duplicate-key diagnostics with listener snapshots. |

The Gradle fixture in `gradle-incremental` separately verifies cross-entity incremental projection
processing. It also remains outside the reactor.

PostgreSQL runs in the `Independent PostgreSQL consumer` CI job's disposable service. The job supplies
`SKIS_POSTGRES_URL`, `SKIS_POSTGRES_USER` and `SKIS_POSTGRES_PASSWORD`; the PostgreSQL consumer fails
if they are missing. It does not fall back to H2 or silently skip. No local PostgreSQL installation
is required. Its rows are isolated in the `consumer` schema and cleaned up by ID.

`verify-current-contracts.ps1` checks source/POM boundaries and requires JDK 21. Its
`VerifyQueryApiBudget.java` helper parses source declarations without building the reactor or
running annotation processors. The budget includes ordinary API subpackages and publicly accessible
nested types, including implicit public interface members; generated contracts and assembly
exceptions have explicit allowlists. Parser fixtures run in the same CI check.

The PostgreSQL failure check first confirms that the raw driver exception contains the conflicting
primary key, then rejects that value in the framework message and both completion snapshots.
H2 and PostgreSQL stream checks require completion at exhaustion before explicit close and verify
that close sends no duplicate completion.

`verify-test-reports.ps1` requires non-empty successful test reports with no skipped cases for the
named suites. Integration and
consumer reports are uploaded on success and failure. A submitted workflow is distinct from branch
protection configuration and from a completed CI run.

Current listener behavior is documented in [read-only execution listeners](../../../docs/execution-listeners.md).
