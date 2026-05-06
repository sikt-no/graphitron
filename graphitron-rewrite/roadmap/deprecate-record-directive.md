---
id: R96
title: "Deprecate and remove the @record directive"
status: Backlog
bucket: cleanup
priority: 6
theme: model-cleanup
depends-on: [emit-input-records, synthesize-payload-carrier]
---

# Deprecate and remove the @record directive

The `@record` directive (declared at `directives.graphqls:247` as
`directive @record(record: ExternalCodeReference) on OBJECT |
INPUT_OBJECT`) tells graphitron the developer-supplied Java class
backing an SDL type. Today it drives accessor resolution, payload
construction in error/DML flows, type binding for `@externalField`
chains, and input-type classification. It carries weight in the
classifier (`TypeBuilder.buildResultType`,
`TypeBuilder.buildNonTableInputType`,
`MutationInputResolver.classifyDml`) and in seven model variants
(`PojoInputType`, `JavaRecordInputType`, `ResultType`,
`PolymorphicReturnType`, etc.).

This item argues `@record` adds no information graphitron can't get
from introspection or from existing directives, and lays out the
deprecation surface.

## Where `@record` carries weight today

Six categories on output types (sakila + tests):

1. **DML payload types** — `CreateFilmPayload`, `DeleteFilmPayload`,
   etc. The DML emitter needs a class to instantiate; `@record`
   provides it.
2. **`@service`-returning payloads** — `FilmLookupPayload`,
   `FilmReviewPayload`. The class graphitron's catch arm constructs
   from on dispatch; the type accessor resolution targets.
3. **jOOQ-table-record wrapping** — `FilmCard @record(...FilmRecord)`,
   `FilmDetails @record(...FilmRecord)`. Binds an SDL type to a
   jOOQ-generated record.
4. **Hand-rolled wrapper records** — `FilmCardWrapper
   @record(...FilmCardData)`. Binds an SDL type to a developer record
   that wraps other types.
5. **Polymorphic / interface dispatch** — base-typed service return,
   `@record` pinning the concrete subtype.
6. **Cross-module backing classes** — backing class the SDL author
   can't import.

Two on input types (the surface this item subsumes from R94):

7. **Pojo / Java-record input classification** —
   `JavaRecordInputType` vs. `PojoInputType` vs. `JavaPlainInputType`
   in the input-side hierarchy.
8. **`@table + @record` shadow rule on inputs** — see the comment at
   `TypeBuilder.java:652-657`.

## Why `@record` is redundant in each case

- **(1) DML payloads** — covered by R75
  (`synthesize-payload-carrier.md`, Backlog), which synthesizes the
  payload carrier inside graphitron. `@record` on DML payloads is
  already on a deprecation path written into another roadmap item.
- **(2) `@service`-returning payloads** — graphitron introspects the
  `@service` method's return type
  (`ServiceCatalog.reflectServiceMethod` already captures it). The
  return type *is* the backing class.
- **(3) jOOQ-table-record wrapping** — `@table` resolution already
  produces the jOOQ-generated record class
  (`Tables.FILM.recordType()`); the producing field's accessor
  return type chains the binding through. No `@record` needed.
- **(4) Wrapper records via field traversal** — the producing
  `@externalField(reference: {...})` carries the class via its own
  attribute; or the parent's accessor return type tells graphitron
  what the child is. `@record` duplicates that signal.
- **(5) Polymorphic dispatch** — the polymorphism is real, but
  `@record` doesn't actually solve it (a `TypeResolver` or
  `__typename`-driven dispatch is the right shape). The consumer
  can also just change the service signature to return the concrete
  type.
- **(6) Cross-module backing** — introspection works across modules
  too. No special handling needed.
- **(7) Pojo/JavaRecord input classification** — covered by R94,
  which emits a uniform graphitron-internal record per SDL `input`
  type. `@record` on inputs becomes redundant the moment R94 ships.
- **(8) `@table + @record` shadow rule** — vanishes when (7)
  vanishes; no input has `@record` to shadow with.

## Misconfiguration risk

Beyond redundancy, `@record` is a configuration drift source. The
SDL declares a backing class string
(`@record(record: {className: "..."})`); the actual producer
(introspection, `@table`, `@externalField`) declares another. When
they disagree, graphitron has to either error, ignore one, or pick a
precedence rule — all real surface area. The existing
`@table + @record` shadow rule (`TypeBuilder.java:652-657, 657-664`)
is exactly this kind of "two sources of truth, here's how we picked
a winner" comment, and it exists to paper over a class of
misconfigurations the directive itself enabled.

Removing `@record` removes the class of misconfigurations entirely.

## Phasing

The deprecation has natural phase boundaries; each phase is
independently shippable.

### Phase 1 (parallel with R94): drop `@record` on input types

Already in scope under R94's *Non-goals* and Phase 1; this item
inherits that work as the input-side half of the deprecation.
Concretely: narrow `directives.graphqls:247` to `on OBJECT`, remove
the `@record`-driven arm in `TypeBuilder.buildNonTableInputType`,
remove `JavaRecordInputType` and the `@table + @record` shadow rule.
See R94 (`emit-input-records.md`) for the migration checklist.

### Phase 2 (depends on R75): drop `@record` on DML payload types

R75 synthesizes DML payload carriers; once that lands, `@record` on
DML payloads becomes literal dead weight. Migrate fixtures
(`CreateFilmPayload`, `DeleteFilmPayload` and their counterparts in
sakila + tests), drop the corresponding `@record(record: ...)`
declarations, drop the developer-supplied payload classes that
duplicate the synthesized shape.

### Phase 3 (this item's own work): drop `@record` on remaining output types

The remaining cases — `@service`-returning payloads,
jOOQ-table-record wrapping, hand-rolled wrapper records — all rely
on existing classifier signals (introspection, `@table`,
`@externalField` reference attributes). Phase 3 walks each model
variant that currently consumes `@record`, replaces the consumer
with the appropriate alternate signal, and removes the directive's
`OBJECT` scope. Order:

1. **Audit each `@record`-consuming classifier branch.** Identify
   which signal replaces it: `@service`-method introspection,
   `@table` record-class derivation, `@externalField` reference
   attribute, parent-accessor return type, `TypeResolver` for
   polymorphic.
2. **Wire each replacement** with `@LoadBearingClassifierCheck`
   keys so emitters that previously relied on `@record`-derived
   class resolution now rely on the introspection-derived
   equivalent.
3. **Migrate sakila SDL fixtures** — the seven `@record`-on-output
   declarations enumerated above. Each one drops `@record`; the
   producer signal stays.
4. **Drop the directive declaration entirely** from
   `directives.graphqls`. Remove the `OBJECT` scope.
5. **Remove the model-side handling** —
   `TypeBuilder.buildResultType`'s `@record`-aware arm, the
   `JavaRecord*Type` model variants, `MutationInputResolver`'s
   `@record` reference, etc.

### Phase 4: housekeeping

- Update `code-generation-triggers.adoc:112` to remove the `@record`
  row.
- Update LSP completion + diagnostics to drop `@record` from the
  directive registry.
- Update `docs/README.adoc` and any other places that document
  `@record` as a directive consumers should reach for.
- Add a migration note in `changelog.md` naming the SHA where the
  directive ships zero scope.

## Out of scope

- Replacing `@record` with a different "explicit type binding"
  directive. The whole point is that explicit type binding is
  redundant with the signals graphitron already has. If a future
  case can't be covered by introspection or existing directives, it
  surfaces as `UnclassifiedField` and gets its own dedicated
  classifier signal (not a re-introduction of `@record`).
- Changing the consumer's API surface. Phase 3's migration of
  sakila SDL fixtures is fixture-only; the consumer-facing types
  (`FilmReviewPayload`, etc.) keep their shape. The directive goes
  away, not the developer-supplied classes.

## Tests

Each phase carries its own test surface; no item-wide test plan
needed here. Phase 3's audit produces a checklist that maps each
removed `@record` to the alternate signal that replaces it; the
audit's output is the test plan for that phase.

## Risk

The R75 dependency for Phase 2 means this item can't fully ship
until R75 ships. Phases 1 and 3 are independent: Phase 1 ships with
R94; Phase 3 can ship before R75 if Phase 2 is deferred. The
directive declaration only goes to zero scope after all three
phases land.
