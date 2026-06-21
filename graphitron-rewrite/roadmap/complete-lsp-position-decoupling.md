---
id: R352
title: "Complete LSP position decoupling: lift jOOQ half and hover descriptions onto the source index"
status: Backlog
bucket: architecture
priority: 5
theme: lsp
depends-on: []
created: 2026-06-21
last-updated: 2026-06-21
---

# Complete LSP position decoupling: lift jOOQ half and hover descriptions onto the source index

Follow-up to R349, which decoupled the **service half** (LSP goto-definition for
`@service` / `@condition` / `@externalField` class and method references) from the
generator build by giving the LSP a `SourceWalker.Index` refreshed on the `.java`
cadence, and typed the resolution outcome as a sealed `DefinitionTarget`. R349 drew
three scope boundaries through the middle of a resolved value / cadence rather than
around it, each a deliberate transitional state, and this item closes them so the
decoupling is complete and `CompletionData` carries no source positions at all:

- **jOOQ half still rides the build.** `CompletionData.Table` / `Column` / `Reference`
  keep their `SourceLocation definition`, built by `CatalogBuilder.build` on the build
  cadence, and `Definitions` still resolves jOOQ goto-definition through the
  `uri().isEmpty()` sentinel test in `asLocation` rather than the sealed
  `DefinitionTarget`. R349's justification (the jOOQ sources are generated build
  artifacts that only change on a build) holds for *refresh frequency* but not for
  *which cadence drives the walk*; the divergence means two halves of the same request
  path resolve through two different shapes. Lift the jOOQ table / column / FK join onto
  the LSP-owned source index (keyed by the generated table-class FQN and `FieldKey`,
  both already computed in `buildTable` / `buildColumn`), switch the jOOQ arms onto
  `DefinitionTarget`, and retire `CompletionData.SourceLocation` from the catalog.

- **Description and position split across cadences.** A `SourceWalker.Decl` is one
  parse fact `(location, javadoc)`. R349 routes its `location` through the source-cadence
  index but leaves its `javadoc` flowing into `CompletionData`'s `description` slot on the
  build cadence (so hover, which reads `description`, stayed untouched). During a live edit
  that both adds a Javadoc line and shifts a declaration, hover and goto-definition read two
  different snapshots of the same `Decl` until the next build. Let the LSP own the whole
  `Decl`: hover reads `javadoc` from the source index too, and the catalog's
  `description` carries only the bytecode-derivable fallback (or nothing). Then hover and
  goto-definition cannot disagree.

- **Process-wide static `SourceWalker.CACHE` spans two cadences.** R349 keeps the cache
  `static` and shares it between the build-cadence driver (`CatalogBuilder.build`) and the
  source-cadence driver (the dev-goal `.java` watcher). It is correct (content-addressed by
  path + mtime) but re-introduces a global coupling channel between exactly the two cadences
  the decoupling separates. Once the jOOQ half and hover move off the build-cadence walk,
  `CatalogBuilder.build` no longer walks sources at all, leaving the LSP the sole walker;
  move the cache to an instance the LSP owns (alongside the `volatile sourceIndex` in
  `Workspace`) so "who refreshes this, on what cadence" is answerable from the field's owner.

## Forcing function

R349 ships the bounded fix for the reported bug. This item is not urgent: the transitional
splits are correct and the only observable cost is transient hover/goto-definition skew
during a live edit. Pick it up when the jOOQ goto-definition path next needs work, or when
the `CompletionData.SourceLocation` field becomes a maintenance irritant.
