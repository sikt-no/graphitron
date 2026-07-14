---
id: R180
title: "Resolved accessors for record-parent column reads (recordColumnReadArgs)"
status: Spec
theme: classification-model
depends-on: []
created: 2026-05-19
last-updated: 2026-07-14
---

# Resolved accessors for record-parent column reads

Re-specced 2026-07-13 against the current model; the original spec (see git history of this file) was written against a surface that no longer exists and had shrunk to one live concern. What changed under it:

* `ResultType` is a four-arm seal (`GraphitronType.java:94`): R276 deleted `PojoResultType.NoBacking`, and `PojoResultType` permits only `Backed`. Every five-arm switch the old spec designed for is gone.
* `FetcherEmitter.propertyOrRecordValue`, the old spec's primary migration target, no longer exists. The fetcher path resolves accessors at classification time.
* The multi-site duplication that motivated a shared `ColumnReadShape` dispatcher is gone. Exactly one column-read switch over `ResultType` survives: `GeneratorUtils.recordColumnReadArgs` (`generators/GeneratorUtils.java:290`, javadoc'd as the shared per-column reader), consumed by `buildFkRowKey` (`:274`) and `buildProducedRecordsKeyMany` (both reached via `buildRecordParentKeyExtraction`, `:238`).

With one site, a cross-site dispatcher is pointless. What survives of R180 is the old spec's "second asymmetry", previously deferred to a non-goal, now the whole item:

## The live problem

`recordColumnReadArgs` synthesizes accessor names by convention instead of using a resolved accessor:

* `JavaRecordType` arm emits `((Backing) expr).<camelCase(sqlName)>()`.
* `PojoResultType.Backed` arm emits `((Backing) expr).get<CamelCase(sqlName)>()`.

Both ride on the unverified assumption that the backing class's accessor names follow the camel-case convention derived from the column's SQL name. A backing class whose accessor deviates (renamed component, non-conventional getter, `@field(name:)`-style remaps once those reach this path) produces generated code that fails to compile, with no classify-time diagnostic. The fetcher path already does this right: it reflects on the backing class at classification time and threads a resolved accessor to the emitter.

R461 (`unify-sdl-field-accessor-resolution`, Done as of 2026-07-14; its file self-deleted, see the changelog) consolidated accessor-candidate enumeration behind `ClassAccessorResolver.enumerate` with a `probe` entry point for the discovery direction and a sealed `AccessorProbe (Grounded | NoMatch)` result. That is precisely the machinery this item should consume: resolve the per-column accessor at classification time via the R461 surface, carry it to the key-extraction emitter, and fail at classify time (typed rejection with candidates) instead of emitting uncompilable code.

## Direction for the revised plan

* Resolve the accessor for each FK/key column of a record-backed parent at classification time (where the backing class is already reflected on), using `ClassAccessorResolver` rather than a parallel name-synthesis rule.
* Carry the resolution to the emitter. The natural carrier is `SourceKey` (the old spec's non-goal 1); note `SourceKey.Reader.AccessorCall` already carries a resolved accessor for the auto-lift path, so the shape has precedent. R431 (`decompose-sourcekey`) is reworking that record; sequence this after R431 or land it as part of R431's reshaping rather than widening the current record independently.
* `recordColumnReadArgs`'s jOOQ arms (`.get(Tables.X.COL)` / `.get(sqlName)`) are correct as-is and stay.
* The `ClassName.bestGuess(fqClassName())` re-parses in the same arms are R412's concern (`nested-backing-class-emitter-lift`); do not fold that here, but do not make it worse.

## Non-goals (unchanged from the original spec)

* `backingClassOf` (`GeneratorUtils.java`) and `SourceRowDirectiveResolver.parentBackingClass` answer a different question ("give me the parent's backing class") and stay out of scope.
* No per-dispatcher unit tests asserting `CodeBlock` string equality; pipeline tier verifies emission by compiling generated code against the sakila catalog, per `development-principles.adoc`.

## Status note

This item is in Spec; the revision above replaces a plan whose reviewer-visible shape changed materially, so the next Spec to Ready transition needs a fresh independent sign-off per the workflow's reviewer rule.
