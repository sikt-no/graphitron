---
id: R817
title: "AuthoredClaimConflicts.fieldGrain reads a recursive view once per conflict row"
status: Backlog
bucket: bug
priority: 3
theme: diagnostics
depends-on: []
created: 2026-08-23
last-updated: 2026-08-23
---

# AuthoredClaimConflicts.fieldGrain reads a recursive view once per conflict row

`AuthoredClaimConflicts.fieldGrain` drives a loop over the conflict rows at field coordinates and,
inside it, calls `claimsAt` once per row. `claimsAt` reads `intent_authored_field_claim`, whose body
is a `WITH RECURSIVE`, so one detection pass evaluates a recursive derivation as many times as there
are violated field coordinates. Inside that loop it then calls `enrich` once per claim, which is a
second read per row of the first.

The fix is sitting in the same file. `typeGrain` next door reads its claims once, through
`typeClaims`, and pairs them against the conflict rows in memory. `fieldClaims` already exists, and
its javadoc calls it "the field-grain sibling of `typeClaims`, keyed by the violated coordinate",
which is exactly the shape `fieldGrain` needs and does not use. So this is plausibly a one-call
change plus the pairing, not a redesign.

Two things a spec should settle rather than assume. Whether `fieldClaims` returns everything
`claimsAt` does: `claimsAt` builds a `ClaimRow` carrying the trigger, the decoded flag and a source
location, where `fieldClaims` is typed to `List<AuthoredClaim>`, so the paired form may need the
richer row type or a second paired read. And what to do about `enrich`, which is the inner per-claim
read and may want the same treatment on its own relation.

Not urgent, which is why the priority is 3. The detection pass runs once per capture and the loop is
over violated coordinates, so a schema with no `@` claim conflicts pays nothing and a schema with a
handful pays a handful. It is a real N+1 over a recursive view on the build path, and it is cheap to
remove, which is the case for doing it rather than the case for doing it now.

Recorded nowhere before this item. The class carries no comment about the cost, and the store-read
discipline this violates is the one `nested-jooq` states and `docs/architecture/explanation/fact-model.adoc`
argues from measurement: a view carrying a window function or a recursive term is taken once and
paired on its key, never correlated per driving row. Found while measuring the materialized targets'
read cost, and deliberately kept out of that item, whose subject is the targets rather than their
callers.
