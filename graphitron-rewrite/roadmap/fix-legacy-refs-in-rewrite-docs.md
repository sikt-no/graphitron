---
title: Fix stale legacy references in rewrite docs
status: Backlog
bucket: cleanup
priority: 4
---

# Fix stale legacy references in rewrite docs

Mechanical sweep over `graphitron-rewrite/docs/` to update references that
still point at `graphitron-common` or describe the rewrite tree as it stood
several landings ago. Three known sites; a grep pass for `graphitron-common`
and the module count should turn up any others.

## Known sites

- `code-generation-triggers.md:289` lists the directive SDL location as
  `graphitron-common/src/main/resources/directives.graphqls`. Per changelog
  entry `c31771d`, the rewrite ships its own copy at
  `graphitron-rewrite/graphitron/src/main/resources/directives.graphqls` and
  `RewriteSchemaLoader` auto-injects it. Update the link to the rewrite-local
  path.
- `rewrite-design-principles.md:113` says "builds the **five** rewrite
  modules (`graphitron-javapoet`, `graphitron`, `graphitron-fixtures`,
  `graphitron-maven`, `graphitron-test`)". The aggregator now has eight
  modules (`graphitron-javapoet`, `graphitron`, `graphitron-fixtures-codegen`,
  `graphitron-fixtures`, `graphitron-maven`, `graphitron-test`,
  `graphitron-lsp`, `roadmap-tool`). Update the count and list.
- Anywhere that "five rewrite modules" or
  `verify-standalone-build.sh`'s forbidden-coords list is paraphrased
  inconsistently.

## Scope

One commit, one focused diff. Out of scope:

- `runtime-extension-points.md` — tracked under
  [`runtime-extension-points-rewrite.md`](runtime-extension-points-rewrite.md);
  it is not a sweep, it is a rewrite.
- Cross-references from `/docs/README.md` and `/README.md` at the repo root
  back into the rewrite tree are correct as of today; only flag if the grep
  pass surfaces drift.
