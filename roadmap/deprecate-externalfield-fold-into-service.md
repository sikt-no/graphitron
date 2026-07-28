---
id: R555
title: "Deprecate @externalField: fold the computed-field shape into @service"
status: Backlog
bucket: cleanup
priority: 5
theme: service
depends-on: []
created: 2026-07-28
last-updated: 2026-07-28
---

# Deprecate `@externalField`: fold the computed-field shape into `@service`

## Proposal

Deprecate `@externalField` and extend `@service` to cover its use case. Both
directives mean "this field is resolved by external Java code" and both carry
the same `ExternalCodeReference` input; the split forces schema authors to
learn two directive names for one concept. The disambiguator becomes the
reflected Java method signature, which `@service` classification already
leans on for its return-shape projection (table-bound / scalar / class-backed
payload / polymorphic): a referenced method that takes a single jOOQ
`Table<>`-subtype parameter and returns a parameterised `Field<X>` classifies
to `ChildField.ComputedField` exactly as `@externalField` does today, while
every existing `@service` signature keeps its current classification
unchanged.

## Why the shapes do not collide

The two contracts are structurally disjoint in `ServiceCatalog`:

- `reflectServiceMethod` accepts parameters classified as `DSLContext`,
  argument-bound (`ParamSource.Arg` via name or `argMapping`), context-bound
  (`contextArguments`), or `List`/`Set` batch-key containers
  (`SourceKey.Wrap`). It has no `Table<?>`-parameter arm.
- `reflectExternalField` requires `public static`, exactly one parameter
  assignable from `org.jooq.Table`, and a return type of exactly
  parameterised `org.jooq.Field`. `@service` return classification has no
  `Field<X>` arm.

So a "try the computed-field shape, else the service shapes" branch inside
the `@service` resolver is unambiguous today. The risk is not collision but
diagnostics: a user who writes a broken signature must get a message that
names the contract they were aiming for, not a confusing rejection from the
other contract's validator. `pickMethod` already rejects same-name overloads
outright, which keeps the branch deterministic.

## Known tensions (resolve at Spec)

- **Execution model becomes invisible in SDL.** `@externalField` inlines the
  returned `Field<X>` into the parent's SELECT projection at query-build time
  (via `$fields`, read back by result-key alias); `@service` calls the method
  at request time and, on child fields, requires `@splitQuery` plus the
  DataLoader batch contract. After the fold, one directive name covers both
  execution models and only the Java signature tells them apart. Argument in
  favour: `@service` classification is already signature-driven for return
  shapes, and the SQL-inlined form is strictly the better default when it
  applies. Argument against: a schema reader can no longer see from SDL
  whether a field costs a batched sub-query or rides the parent SELECT.
  Mitigation (agreed direction): surface the distinction through the LSP.
  The hover/classification catalog already separates the two shapes
  (`FieldClassification.Computed` vs `FieldClassification.ServiceBacked`),
  so the editor can tell users whether a `@service` field is embedded in
  the parent SELECT or is a DataLoader field; the fold adds that hover
  text as a deliverable rather than treating SDL as the only reading
  surface.
  Related: `@service` on a non-root field currently hard-requires
  `@splitQuery`; the computed-field arm must relax that requirement for the
  `Table<> -> Field<X>` signature only, which makes the `@splitQuery`
  validation signature-dependent too.
- **Directive parameters that do not apply.** `contextArguments` and
  `argMapping` are meaningful for service methods but inert for the
  computed-field shape (the method's only parameter is the parent table).
  Decide whether to reject, warn, or silently ignore them on that arm.
- **Static-only stays.** The computed-field arm keeps the `public static`
  requirement: the generated projection code calls the method during query
  construction, so the instance-holder shape (`InstanceWithDslHolder`) does
  not transfer.
- **Root coordinate.** The computed-field shape is meaningless at root (no
  parent table); the existing root `@service` validation path must reject it
  there with a signature-aware message.

## Relationship to existing items

- **Competes with R54** (`rename-externalfield-directive`), which resolves
  the same deprecation by renaming instead (`@computed` / `@calculated`
  candidates); this item answers the successor-name question with "no new
  name, the successor surface is `@service`". The two items are mutually
  exclusive resolutions of the same problem: whichever completes first
  discards the other (R54 carries the matching back-pointer). R54's
  remaining open questions apply here too and should be absorbed into this
  item's Spec: deprecation-warning channel (warn-not-fail tier for the
  classifier), parallel-support window length, and migration tooling for the
  ~49 known Sikt call sites.
- **R109** (`list-valued-external-field-multiset`, Spec) authors docs and
  fixtures that name `@externalField`; if both land, whichever lands second
  updates the directive spelling in the other's surfaces.
- **R240** (`tablemethod-return-type-token-threading`) cites the
  `@externalField` path as one of two remaining `MethodRef.StaticOnly` mint
  sites; the fold moves that mint site into the `@service` resolver but does
  not change the carrier.

## Out of scope

- Renaming `ChildField.ComputedField` or restructuring the classified model;
  both directives already converge on the same variant, so the fold is a
  parse/classify-surface change.
- Removing `@externalField` outright. It stays accepted for the migration
  window with a deprecation warning; removal is a follow-up gated on the
  window decision inherited from R54.
