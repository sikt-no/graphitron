---
id: R418
title: "Always drop-and-recreate rewrite_test on SessionStart"
status: Backlog
bucket: tech-debt
priority: 5
theme: testing
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# Always drop-and-recreate rewrite_test on SessionStart

The web-sandbox SessionStart hook (`.claude/scripts/session-start-web-env.sh`, step 2) creates and seeds `rewrite_test` **only when the database is absent** (`if ! ... SELECT 1 FROM pg_database WHERE datname='rewrite_test'`). That is the wrong idempotency for a fixture database whose entire definition lives in `graphitron-sakila-db/src/main/resources/init.sql`: a sandbox whose `rewrite_test` was created from an older `init.sql` keeps that stale schema forever, because the guard never re-runs the seed. R389 added the `party_*` tables (and composite PKs on `jti_app_account` / `jti_person`) to `init.sql`; any sandbox seeded before that revision silently lacks them. Under `-Plocal-db`, jOOQ codegen regenerates its catalog against the *live* database, so a stale DB yields a catalog missing `party`, and the R389 joined-table-inheritance corpus (`Query.allParties`, the `Party` `TableInterfaceType`) then fails to resolve its backing tables, producing an `UnclassifiedType` / `UnclassifiedField` cascade across the `graphitron` pipeline and classified-corpus tests (`JoinedTableInheritancePipelineTest`, `SourceShapeProjectionTest`, `VariantCoverageTest`, `WrapperAlgebraTest`, `ClassifiedDslTest`). The R389 `jooq.codegen.schema.version` bump (`efdd93077`, 2.2→2.3) forces catalog regeneration when the *catalog* is stale relative to the DB, but it cannot fix a stale *DB*: regeneration just re-reads the same outdated schema.

Fix: make the seed a pure function of the checked-out `init.sql` by dropping and recreating on every session start, e.g. `DROP DATABASE IF EXISTS rewrite_test WITH (FORCE);` (Postgres 16 supports `WITH FORCE`, so an open connection from a prior session cannot block the drop) then `CREATE DATABASE` + seed. Keep the whole block gated on `pg_ctlcluster` as it is today so it stays a no-op on local dev, where standard builds use TestContainers rather than a persistent `rewrite_test`. Re-seeding runs sub-second, so doing it unconditionally on startup is cheap; a checksum-of-`init.sql` guard to skip an already-current reseed is possible but likely more machinery than a small fixture DB warrants. Update `.claude/web-environment.md` step 2 to match. This is web-sandbox tooling, not reactor/generator code.
