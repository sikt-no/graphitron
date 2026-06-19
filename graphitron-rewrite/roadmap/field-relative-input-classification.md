---
id: R327
title: "Field-relative input classification (retire @table-on-input and the findReturnTablesForInput aggregate)"
status: Spec
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-18
last-updated: 2026-06-19
---

# Field-relative input classification (retire @table-on-input and the findReturnTablesForInput aggregate)

Split out of R317 slice 4 (the classify-and-emit collapse), which deferred this as the one
non-byte-identical change so the collapse could stay a pure structural delta.

Today an input type's table-boundness is decided *globally*: `TypeBuilder.buildInputType` consults
`@table` on the input, then the `findReturnTablesForInput(name)` aggregate over every field that
takes the input, and classifies it as table-bound only when that aggregate resolves to exactly one
table (it bails to non-table on zero or more than one). This is the wrong altitude: whether an input
is table-bound is a function of the *field's target table at the use site*, not a global property of
the input type. The aggregate's `> 1` bail means an input reused across two tables silently
classifies non-table everywhere, and `@table`-on-input is a manual override papering over the missing
field-relative derivation.

The R317 read-free work makes the field-relative model reachable: `lookAheadVerdict` already resolves
a field's target verdict registry-free at the edge, so the input arg can be classified *after* its
field's target, deriving table-boundness from the target's table rather than the global aggregate.
`@table`-on-input is then de-emphasised (eventually deprecated; the field-relative derivation
subsumes its common use).

This is **not** automatically byte-identical, which is why it is its own item: an input used across
more than one table classifies non-table today (the aggregate bails on `> 1`) but becomes table-bound
per field under the field-relative model. The change must be gated against the fixtures, and where a
verdict shifts, pinned as the intentional consequence (with execution-tier coverage proving the
per-field table binding generates correct SQL).

## Evidence: R330 and the override-condition routing fork (correcting an earlier mis-attribution)

An earlier draft of this note cited R330 (FK-target `@nodeId` + `@condition(override)`) as a
`@table`-on-input failure ("behaviour forked on the presence of `@table`"). That was wrong, and the
correction sharpens this item's actual thesis: **there is no intrinsic "plain input type".** An
input's table-boundness is *derived*, and R330 was diverted off the table path by the override
condition itself, not by the absence of `@table`.

`TypeBuilder.buildInputType` (`TypeBuilder.java:1333`) decides table-boundness in order:

1. `@table` present → `TableInputType`.
2. **else `isUsedWithOverrideCondition(name)` → non-table `InputType`** (`TypeBuilder.java:1348`,
   logic at `:1582`). Fires when *any* field of the input carries `@condition(override:true)`, by
   design: "the consumer supplies custom condition code, so the input's fields should not be
   validated against table columns."
3. else the `findReturnTablesForInput` aggregate (exactly one `@table`-returning consumer →
   `TableInputType`; zero or more than one → non-table).

The real `SoknadsmangeltypeFilterInput` has no `@table` *and* is used by exactly one
`@table`-returning field (`soknadsmangeltyper: [Soknadsmangeltype!]`), so by rule 3 alone it would
have been **promoted to `TableInputType`**. It is non-table only because rule 2 fires first: its
`regelverksamlingId` field carries `@condition(override:true)`, the very directive R330 is about. So
the input was diverted off the table path **by the override condition**, not by the absence of
`@table`. Removing `@table`-on-input from the language would not change R330's routing at all; R330 is
therefore **not** evidence for the `@table`-on-input retirement.

What R330 *is* evidence for is this item's thesis from a different direction: the override diversion
(rule 2) is too coarse. It is meant to skip *column-coverage* validation (correct: the consumer owns
the predicate), but it also skips the FK-target **structural** check, because the validator
structurally walks only `TableInputType` (`validateTableInputType` → `validateInputFieldRecursive`,
`GraphitronSchemaValidator.java:370`); the non-table `InputType` leaves get an empty
`validateInputType` (`:366`). So an identical schema-author error produced a build-time rejection
when the type happened to be table-routed and a *silently generated broken call* when an override
field diverted it off that path, detonating at the consumer's `javac`. The lesson for the
field-relative rewrite: structural validation must key off the consuming field's resolved target (so
it runs once on `SqlGeneratingField.filters()` regardless of how the type was routed), not off
whether the type landed in the `TableInputType` bucket.

The residual structural check left behind is **inert**, not a live bug: the one surviving guard
(every FK-target join hop must be an `FkJoin`, mirroring `FkTargetConditionEmitter`'s emit-time
precondition) is unreachable for input conditions. `@nodeId` FK-target paths are guaranteed
all-`FkJoin` by `NodeIdLeafResolver` (it rejects non-FK hops), and a condition-only `@reference` path
on an input field is rejected earlier at classification ("cannot resolve target table because the
carrier field's return type has no `@table` binding"), so no `FkTargetConditionFilter` with a
non-`FkJoin` hop can be constructed from any input. The composite rejection that *was* reachable (and
that bit the consumer via the diverted path) is gone now that composite is supported. So a standalone
"validate non-table inputs too" fix has no falsifiable behaviour change and was correctly not shipped
(confirmed by attempting it: the negative case cannot be constructed). The right move is to make
input-field validation field-relative *as part of this item's* classification rewrite, removing the
divergence without adding speculative untestable code. Folded into this item's scope, not patched
separately.

## Spec

### The mistake in `buildInputType` to fix

`TypeBuilder.buildInputType` classifies an input type *globally* into `TableInputType` or a non-table
`InputType` leaf via three ordered steps (`@table` → override-gate → `findReturnTablesForInput`
aggregate). The design lead's critique, point by point:

- **Step 1** (`@table` → `TableInputType`) is on the deprecation path, but is **kept untouched in
  this item's first slice** (see slicing): it is the only signal that names the *mutation write
  target*, a per-type fact, and `MutationInputResolver.resolveInput` (`MutationInputResolver.java:372`)
  hard-depends on `instanceof TableInputType` + `tit.table()` with no call-site fallback.
- **Step 2** (`isUsedWithOverrideCondition` → non-table) is **wrong**: it overloads a *per-field
  validation modifier* (`override` = "the consumer owns this predicate, skip column-coverage on it")
  into a *whole-type routing gate*, demoting the input to a different consumption bucket
  (`PlainInputArg` rather than `TableInputArg`). "Has an override field" and "is table-bound" are
  orthogonal axes; conflating them is the binary short-sightedness. The override flag is already
  threaded field-relative at the call site (`FieldBuilder.classifyArgument:1017`, `enclosingOverride`),
  so it has no business deciding the type's bucket. This demotion is what silently broke R330.
- **Step 3** (`findReturnTablesForInput`) is the right idea (derive table from the consumer) at the
  wrong altitude: a *global aggregate* over every consuming field that bails to non-table on `> 1` and
  whose success "moves to" the step-1 outcome (`TableInputType`). It is exactly the "predicate over
  pre-resolved data, evaluated globally" smell; the consuming field's resolved target (`rt`) is
  already computed at the call site, so the aggregate recomputes what each call site already knows.

### Two orthogonal axes hide in `@table`-on-input

The architectural payload of this item is naming the split the current single directive splices
together:

| Axis | Altitude | Mechanism today | Target |
|------|----------|-----------------|--------|
| Query-side filter/lookup binding | per *(input, consuming field)* | global `buildInputType` verdict → `TableInputArg` vs `PlainInputArg` | per-call-site derivation from `rt` (this item) |
| Mutation-side write target | per input type | `TableInputType.table()` consumed by `MutationInputResolver` | stays per-type (slice 4 migrates encoded-ID INSERT/UPSERT only) |

Per-type emission is **not** threatened by the per-call-site query axis: the emitted record
(`InputRecordShape`, `buildInputRecordShape:1451`) is table-*independent* (it walks SDL field Java
types only, never `TableRef`), and the binding already lives on `ArgumentRef.InputTypeArg`, not on the
type. Two call sites against two tables already produce two `PlainInputArg`s today. So "one input type
reused across two tables" is the redesign's *safety* argument, not a hazard: the residual `@table`-input
binding decision moves onto the same per-call-site seam the plain-input path already occupies.

### Target invariant

Query-side table-boundness is a property of the *(input type, consuming field)* pair, derived from the
consuming field's resolved target table at the call site; `override` only suppresses per-field
column-coverage validation and never changes the consumption bucket.

### Slice plan

1. **Classification-decision fix (first shippable slice).** Collapse steps 2 and 3 of `buildInputType`
   onto the call-site derivation `PlainInputArg` already performs: remove the override-routing gate and
   the global `findReturnTablesForInput` aggregate (including the `>1` bail). **Step 1 (`@table` →
   `TableInputType`) is untouched** so the mutation consumer is unaffected. Independently verifiable at
   the pipeline tier (SDL → classified `List<ArgumentRef>`); the existing `PlainInputArg` path is the
   proof the call-site model works in isolation. Same commit retires/repoints the
   `argument-resolution.adoc` paragraph documenting `isUsedWithOverrideCondition` as a live mechanism.
2. **`@table`-on-input deprecation signal + removal.** Coordinated with R332 (announce) and the R97 /
   R222 cluster; narrows the directive scope once the query axis no longer needs it.
3. **INSERT/UPSERT write-target migration.** Extend the UPDATE/DELETE field-relative walker carrier
   (R246/R266) to encoded-ID/scalar-return INSERT/UPSERT, so step 1 can finally move off the mutation
   axis. Deferred because the write target is a *different axis*, not merely a later milestone.

### Validator-mirror obligation (the gate on slice 1)

Decoupling override from routing turns today's 1-D gate into a 2×2: `override` (yes/no) ×
`column-resolves` (yes/no). Per "validator mirrors classifier invariants," each cell that implies a
generator branch must fail at validate time if unimplemented, or the change silently shifts which
schemas validate. The `InputFieldResolver` already lifts column-miss to `UnboundField` uniformly
(R215), so the machinery exists; slice 1 must pin an explicit truth table mirroring
`argument-resolution.adoc` §Truth table, with a pipeline-tier test per row:

| override | column resolves | outcome |
|----------|-----------------|---------|
| false | true | implicit column predicate emitted (today's table-bound default) |
| false | false | `UnboundField` → validator rejects (R215: implicit predicate required, no column) |
| true | true | consumer condition owns the predicate; implicit suppressed |
| true | false | consumer condition owns the predicate; column-miss admitted (the R330 / opptak shape) |

The fourth row is the one step 2 used to reach by demoting the whole type; slice 1 must reach it
field-relative and prove (execution tier) it generates the correct correlated predicate.

## Relation to R332 (the deprecation signal)

R332 (`table-on-input-deprecation-signal`, Backlog) is the cheap, ship-now announcement that
`@table`-on-input is on its way out. It does **not** own the deprecation *policy*; this item does.
So the "warn-then-remove vs. keep-as-override" decision belongs here at Spec, but the user-facing
*signal* (directive description, build warning, LSP hint, docs) is R332's surface. Coordinate rather
than re-open: R332 announces, R327 removes. R332 already cross-references this item as the mechanism
its carve-out (below) waits on; the two should stay consistent.

## This item owns the INSERT/UPSERT write-target migration

R332 carves out one case it must **not** flag as deprecated until this item lands: INSERT/UPSERT
mutations whose return type is an encoded ID or scalar (`createFilm(...): ID`). For those,
`@table`-on-input is currently the *only* signal naming the write target. Per
`MutationField.DmlTableField` (`MutationField.java:82-109`), INSERT/UPSERT "carry the `@table`
`TableInputArg` that drives the statement **directly**" (`:92-94`), while UPDATE/DELETE already moved
to a field-relative walker carrier (R246/R266). The field-relative derivation this item introduces is
exactly what unblocks the INSERT/UPSERT arms: the write target must come from the consuming mutation
field's resolved target, not its return type (the return type can be a bare `ID`). Extending the
UPDATE/DELETE field-relative pattern to INSERT/UPSERT is in scope here, and is the gate R332's
carve-out is waiting on. The `findReturnTablesForInput` removal and the `@table`-on-input retirement
this item already names also overlap R222 Stage 5 / Stage 7 and R97; reconcile ownership across that
cluster (mapped in R332's "Related items" section) when this moves to Spec.

