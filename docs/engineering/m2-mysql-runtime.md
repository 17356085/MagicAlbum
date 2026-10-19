# M2.1 content-event MySQL runtime acceptance

`scripts/Test-ContentEventRuntime.ps1` is the isolated acceptance entry point for the
M2.1 content event boundary. It creates one disposable `mysql:8.0` container with a
random run label and two empty databases:

- `ba_content_upgrade_<runId>` is used by `ContentEventMySqlUpgradeAcceptance`.
  Flyway first migrates through `20261018.1`, inserts legal legacy user/section/thread
  rows, migrates through `20261018.2` and inserts an existing content event, then
  runs the complete migration and validation through `20261019.2`. It verifies
  delivery-field backfill, claim indexes and the search-control singleton as well
  as legacy rows and uniqueness constraints.
- `ba_content_runtime_<runId>` is used by `ThreadContentEventIntegrationTest`.
  Spring is started with Flyway enabled, Hibernate `validate`, the MySQL driver and
  an explicit `SELECT DATABASE()`/product guard. The six integration scenarios prove
  commit, caller rollback, database constraint rollback, permission/hidden/delete
  behavior, concurrent updates, and request-id handling through the real
  `ThreadService`.

The script supplies credentials only through the current process environment and a
temporary Docker env file. The file is removed in `finally`; the container has no
host volume or bind mount and is removed with its anonymous volumes. A random run ID,
container labels, database-name checks, and an empty artifact directory prevent stale
containers, databases, credentials, and reports from being reused. The script does
not stop Docker or other containers.

Run from the repository root with PowerShell 7.4. The Maven executable can be
overridden without putting a password on the command line:

```powershell
pwsh -File .\scripts\Test-ContentEventRuntime.ps1
pwsh -File .\scripts\Test-ContentEventRuntime.ps1 -MavenExecutable 'D:\Environment\apache-maven-3.9.11\bin\mvn.cmd'
```

The first Maven round explicitly runs one upgrade test plus the six integration
tests and requires seven non-skipped, non-failing JUnit cases. The second round runs
only `all_content_commands_commit_versions_events_and_real_tags_together` and requires
one case. Its MySQL read-back is the final persistence evidence: five ordered PENDING
events, version-5 `DELETED` thread state, the real `second-tag`, MySQL 8 and the latest
Flyway version in both databases, and no sensitive payload column on the event table.
Reports are kept under `outputs/m2-mysql-20261019/<runId>/`; the script never claims a
database result until these rounds and the final SQL checks succeed.

The default `ThreadContentEventIntegrationTest` path remains an independent H2
`create-drop`/Flyway-disabled fast test. H2 verifies the local migration shape and
transaction scenarios; the runtime script is the required evidence for Flyway
upgrade behavior, Hibernate validation against MySQL, real MySQL constraints and
durable read-back. JPA schema generation is not used as migration verification.
