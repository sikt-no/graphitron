---
id: R404
title: "Reintroduce @sourceRow documentation when it enters the supported surface"
status: Backlog
bucket: docs
theme: docs
depends-on: []
deferred: true
notes: "gated on @sourceRow re-entering the supported surface; not a release priority"
created: 2026-06-30
last-updated: 2026-06-30
---

# Reintroduce @sourceRow documentation when it enters the supported surface

R400 withholds `@sourceRow` from the first-release advertised surface (it is implemented and
test-covered but used by no consumer schema, so v1 does not document it). The directive stays
declared and working; it is simply not advertised. When `@sourceRow` later enters the supported
surface, its documentation must come back. This is the parking ticket so the removal is
non-destructive: the prose is intact in version control and just needs restoring, not rewriting.
**Not a release priority.**

## Recovery source (when reintroducing the docs)

`@sourceRow` carries more than a reference page: it has a **dedicated recipe**. R400 Stage 2 removes
both. The recovery is anchor-free (no hardcoded SHA to go stale): for each removed file, find the
commit that deleted it and restore from its parent.

```
git log --oneline --diff-filter=D -- docs/manual/reference/directives/sourceRow.adoc
git checkout <that-commit>^ -- docs/manual/reference/directives/sourceRow.adoc docs/manual/how-to/source-row.adoc
```

Then re-thread the nav/index entries (`reference/directives/index.adoc` alphabetical + *Joining*
category; `reference/index.adoc`; `how-to/index.adoc`) and the `@sourceRow` teaching passages /
`xref`s in the recipes that framed it (`how-to/result-types.adoc`, `external-code.adoc`,
`add-custom-conditions.adoc`, `handle-services.adoc`, `batch-lookups.adoc`, and the others in R400
Stage 2's removal diff), and remove `sourceRow` from the `WITHHELD_FROM_V1` set in
`DirectiveSupportReport` so it reappears under "Supported directives".

## Trigger

Pick this up when `@sourceRow` is promoted into the advertised surface (a real consumer adopts it,
or we decide to advertise it). Inverse of R400; only meaningful after R400 ships. Deferred until
then.
