---
id: R574
title: "Clear the residual inaccuracies the @table-on-input deprecation reopen left behind"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Clear the residual inaccuracies the @table-on-input deprecation reopen left behind

Reopening the `@table`-on-input deprecation window (accept, ignore, warn) left four
small residuals that the acceptance criterion did not reach, surfaced by the
In Review gate on that item. None is a behaviour bug and none blocked the gate, but
each is a live inaccuracy or dead code, so they are worth one tidy-up pass.

- `InputBeanResolver.java:40` still carries `import static
  no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE;`. It is the only remaining
  mention of `DIR_TABLE` in that file: the nested-`@table` rejection arm that read
  it was deleted, and Java does not error on an unused static import. Delete it.
- `docs/manual/reference/directives/table.adoc`, the first *Constraints* bullet:
  "The `name` parameter must resolve to a real jOOQ table; the generator fails the
  build with an unclassified-type error if the catalog has no matching `Table`
  class." That is now true only on `OBJECT` / `INTERFACE`. On an input the argument
  is never read, so an unresolvable name is inert (pinned by
  `TableOnInputDeprecationWarningTest.unknownTableName_warnsAndIsOtherwiseInert`).
  The deprecation admonition two sections up already says so, so the page is
  reconcilable in context, but the bullet reads unqualified. The multi-schema
  ambiguity bullet below it has the same scope problem.
- `docs/manual/how-to/migrating-from-legacy.adoc:5`: the intro's four-category
  taxonomy still labels the WARN-today bucket "the synthesis shims (build succeeds
  with a WARN today, will fail later)". That bucket was retitled to
  `== WARN today, error later` and now also holds `@table` on input types, which is
  not a synthesis shim. The page's `:description:` was reconciled; this sentence
  was not.
- Same page, the new `== WARN today, error later` section opener points readers at
  `roadmap/retire-synthesis-shims.md` by slug. That is a pre-existing reference
  carried through the rewording, not something the reopen introduced, but the
  workflow's user-facing-doc check calls out plan-by-slug references in the manual:
  a reader who arrived from search has no `roadmap/` directory. Either drop the
  pointer or restate it as "no committed date" alone.

