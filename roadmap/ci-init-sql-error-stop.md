---
id: R593
title: "Fail CI loudly when init.sql seeding fails"
status: Backlog
bucket: dx
priority: 4
theme: tooling
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# Fail CI loudly when init.sql seeding fails

The "Apply graphitron-sakila-db init.sql" step in `.github/workflows/rewrite-build.yml` runs
`psql -f init.sql` with no `-v ON_ERROR_STOP=1`. `psql` exits 0 when individual statements fail, so a
broken or partially-applied `init.sql` seeds an incomplete schema and the step reports success. The
failure then surfaces one step later as the cascade `.claude/web-environment.md` already documents at
length: `UnclassifiedType` / `NoSuchElement` / `table … could not be resolved in the jOOQ catalog`
across every pipeline test that touches the missing fixture, with nothing pointing at the seed.

That cascade has cost real time before. `.claude/web-environment.md` records a stale sandbox DB as a
now-eliminated cause of it (the session hook reseeds on every start), and one earlier item's Done
review recorded chasing exactly this shape before finding the DB was missing that item's new
fixtures. CI's seeding step is the one remaining place the same class of failure can pass silently.

The fix is one flag on the `psql` invocation, plus a check that the failure mode it produces is
readable. Verified 2026-08-04 that today's `init.sql` applies cleanly from scratch under
`-v ON_ERROR_STOP=1` against an empty database, so the flag is a no-op on the current file: this is
pure hardening for the next fixture-table addition, not a fix for a live break.

Worth checking two neighbours in the same pass, since they share the failure mode rather than the
mechanism:

* The `local-db` recovery instructions in `.claude/web-environment.md`, which tell a reader how to
  reseed by hand. If they omit `ON_ERROR_STOP`, a hand reseed can leave the same partial schema the
  CI step can.
* Whether the same silent-success shape exists anywhere else the build shells out to a tool whose
  exit code does not track per-item failure.

Filed out of the `@node` inference work, which added four fixture tables to `init.sql` and noticed
the seeding step would not have caught a typo in them.
