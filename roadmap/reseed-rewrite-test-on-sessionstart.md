---
id: R418
title: "Always drop-and-recreate rewrite_test on SessionStart"
status: In Progress
bucket: tech-debt
priority: 5
theme: testing
depends-on: []
created: 2026-07-01
last-updated: 2026-07-02
---

# Always drop-and-recreate rewrite_test on SessionStart

The web-sandbox SessionStart hook (`.claude/scripts/session-start-web-env.sh`, step 2) creates and seeds `rewrite_test` **only when the database is absent** (`if ! ... SELECT 1 FROM pg_database WHERE datname='rewrite_test'`). That is the wrong idempotency for a fixture database whose entire definition lives in `graphitron-sakila-db/src/main/resources/init.sql`: a sandbox whose `rewrite_test` was created from an older `init.sql` keeps that stale schema forever, because the guard never re-runs the seed. R389 added the `party_*` tables (and composite PKs on `jti_app_account` / `jti_person`) to `init.sql`; any sandbox seeded before that revision silently lacks them. Under `-Plocal-db`, jOOQ codegen regenerates its catalog against the *live* database, so a stale DB yields a catalog missing `party`, and the R389 joined-table-inheritance corpus (`Query.allParties`, the `Party` `TableInterfaceType`) then fails to resolve its backing tables, producing an `UnclassifiedType` / `UnclassifiedField` cascade across the `graphitron` pipeline and classified-corpus tests (`JoinedTableInheritancePipelineTest`, `SourceShapeProjectionTest`, `VariantCoverageTest`, `WrapperAlgebraTest`, `ClassifiedDslTest`). The R389 `jooq.codegen.schema.version` bump (`efdd93077`, 2.2→2.3) forces catalog regeneration when the *catalog* is stale relative to the DB, but it cannot fix a stale *DB*: regeneration just re-reads the same outdated schema.

Fix: make the seed a pure function of the checked-out `init.sql` by dropping and recreating on every session start, e.g. `DROP DATABASE IF EXISTS rewrite_test WITH (FORCE);` (Postgres 16 supports `WITH FORCE`, so an open connection from a prior session cannot block the drop) then `CREATE DATABASE` + seed. Keep the whole block gated on `pg_ctlcluster` as it is today so it stays a no-op on local dev, where standard builds use TestContainers rather than a persistent `rewrite_test`. Re-seeding runs sub-second, so doing it unconditionally on startup is cheap; a checksum-of-`init.sql` guard to skip an already-current reseed is possible but likely more machinery than a small fixture DB warrants. Update `.claude/web-environment.md` step 2 to match. This is web-sandbox tooling, not reactor/generator code.

## Plan

**Scope.** Two files, both sandbox tooling, no reactor/generator or test code: `.claude/scripts/session-start-web-env.sh` (step 2) and `.claude/web-environment.md` (step 2 description + the "Catalog-jar clobber" recovery note, which currently reads as if a stale catalog were the only failure mode).

**Change (step 2 of the hook).** Today the create+seed is guarded by an existence check:

```bash
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='rewrite_test'" | grep -q 1; then
  CREATE DATABASE rewrite_test; seed from init.sql
fi
```

Replace the existence-guarded create with an unconditional drop-and-recreate, still inside the `pg_isready` success branch:

```bash
if sudo -u postgres psql -qc "DROP DATABASE IF EXISTS rewrite_test WITH (FORCE);" >/dev/null 2>&1 \
   && sudo -u postgres psql -qc "CREATE DATABASE rewrite_test;" >/dev/null 2>&1 \
   && sudo -u postgres psql -q -d rewrite_test -f "$REPO_ROOT/graphitron-sakila-db/src/main/resources/init.sql" >/dev/null 2>&1; then
  emit "PostgreSQL ready: rewrite_test dropped, recreated, and seeded from init.sql. -Plocal-db builds are ready."
else
  emit "rewrite_test drop/create/seed failed. See .claude/web-environment.md."
fi
```

**Decisions / constraints.**

- `WITH (FORCE)` (PG13+, cluster here is PG16) terminates lingering backends so the drop cannot fail on an open connection from a prior session or an idle build.
- Keep the outer `pg_ctlcluster` + `pg_isready` gating unchanged: the block already no-ops on local dev (no Debian cluster tooling; standard builds use TestContainers, so no persistent `rewrite_test` exists to clobber). Do not widen or narrow that gate.
- Leave the password-reset line (`ALTER USER postgres PASSWORD` guarded by `pg_was_running`) exactly as is; that concern is orthogonal.
- No checksum/skip optimization. The seed is sub-second on this fixture; unconditional reseed is the whole point (DB becomes a pure function of `init.sql`), and a skip-guard would reintroduce a staleness window.
- The message changes from "created and seeded" to "dropped, recreated, and seeded" so the session log makes the new behavior visible.

**Idempotency note.** The step remains idempotent in the correct sense: running it N times converges on exactly the current `init.sql` schema, rather than freezing whatever schema happened to exist first.

**Docs.** In `.claude/web-environment.md`: update the step-2 bullet to say the hook drops and recreates `rewrite_test` from `init.sql` every session (no longer "when absent"). Add a sentence to the "Catalog-jar clobber" section clarifying that a *stale sandbox DB* (as opposed to a stale `.m2` catalog jar) was a second, now-eliminated cause of the `UnclassifiedType` cascade, so future readers do not misfile a DB-staleness failure as a catalog-jar clobber.

## Verification

- **Static:** `bash -n .claude/scripts/session-start-web-env.sh` parses; `shellcheck` clean if available.
- **Stale-DB regression (the actual bug):** seed `rewrite_test` from a pre-R389 `init.sql` (no `party`), run the hook, then `mvn clean install -Plocal-db -P!docs` and confirm the previously failing tests pass and `SELECT count(*) FROM party` succeeds. (This session already reproduced the failure and confirmed drop+reseed+`clean` is the recovery, so this is the confirming re-run.)
- **No-op on local dev:** on a machine without `pg_ctlcluster`, confirm the block is skipped and prints nothing.
- **Re-run safety:** invoke the hook twice in succession against a running cluster; the second run must succeed (drop-with-force handles the connection the first run may have left) and leave a correctly seeded DB.

## Out of scope

- The `jooq.codegen.schema.version` mechanism (already correct on trunk via R389 `efdd93077`); this item does not touch it.
- The JDK-25 / libtree-sitter / Maven-proxy steps of the hook.
- Any change to `init.sql` itself or to the reactor.
