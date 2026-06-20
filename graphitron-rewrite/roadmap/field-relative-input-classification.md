---
id: R327
title: "Field-relative input classification (retire @table-on-input and the findReturnTablesForInput aggregate)"
status: Spec
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: [coordinate-lowers-to-datafetcher-queryparts]
created: 2026-06-18
last-updated: 2026-06-20
---

# Field-relative input classification (retire @table-on-input and the findReturnTablesForInput aggregate)

## Reopened 2026-06-20: reframed around R333 (read this first)

Reopened Ready → Spec. An In Progress attempt to land the slice 1 below revealed that its
framing was a local patch to a model that is wrong one layer down. This section is the current
thesis; the sections from "## Evidence" onward are retained for their analysis (the R330
attribution, the `buildInputType` mechanics, the two-axes split, the validator-mirror truth table
all still hold), but the **slice plan and its "collapse onto `PlainInputArg`" mechanism are
withdrawn**.

**The reframing (from a design discussion with the design lead).** Input classification is
*contextual*, a function of the consuming output field, not a global property of the input type.
The same input type used by N output fields is N classifications, each bound to that field's
table or record. The binding lives on the *argument* (the schema coordinate), never on the type.
And there is no genuinely "plain" input: the consuming field's kind always supplies a binding
target.

- Consuming field is a `TableField` / inline table field (or a `ServiceTableField` where a
  `@reference` / `@condition` is in play) → the input's fields bind against that field's **table**
  (columns, FK-target refs, conditions).
- Consuming field is a `ServiceField` / `ServiceTableField` params → the input's fields bind
  against the **reflected service-method parameter** types. That is the existing `PojoInputType` /
  `JavaRecordInputType` / `JooqRecordInputType` / `JooqTableRecordInputType`, which are bindings
  against a Java parameter shape, not "plain".

The only contextless artifact in the system is the `PojoInputType` with `fqClassName=null` that a
query-only input falls into today (neither a table nor a backing class). That state is the bug, and
it exists only because `buildInputType` is asked to classify globally with no consumer in view.
Per-coordinate, it cannot occur: a declared input is always reachable from some output field
(directly, or as a nested field of another input, recursively).

**Why this points at R333.** This is the input-side instance of R333's normalization. R333
("Lower each schema coordinate to a DataFetcher and its QueryParts") makes the schema coordinate
`(parentType, fieldName)` the natural key and treats any per-type binding as a *denormalized view*
of a per-coordinate lowering; the data's natural keys (columns, PK, FK) are the join graph, and a
binding is a join *addressed to a coordinate's anchor*. The global `buildInputType` verdict
(`TableInputType` vs `PojoInputType`, `@table`-on-input) is exactly such a denormalized view, the
input-side twin of the leaf zoo R333 dissolves. R333 already lowers the input surface this way
without naming it: its argument/input-granular node kind is the condition method, "a pure function
of the field's typed argument values ... driven by the input surface, not by the field". Under that
model, "is this input a `TableInputType`?" is not a question the system asks, and `@table`-on-input
has nothing left to do. R333 normalizes the *output* leaf zoo exhaustively but never walks the input
relation; this item is that missing instance.

**Open planning fork (resolve in this Spec pass).**

1. *Fold the input-relation normalization into R333, make R327 the behavioral consequence.* R333
   grows a section doing for the input relation what it does for the output leaf zoo; R327 becomes
   "retire `@table`-on-input", landing when R333's model is consumed, depending on R333 rather than
   racing it. This is the honest home: R333 is the model, R327 is the behavior. (Recommended.)
2. *Keep R327 narrow and R333-forward-compatible.* Scope it to the one move correct under today's
   model and a strict subset of where R333 lands: bind a query input arg to the consuming field's
   table on the **argument** (table = the field's `rt`), so the binding lives on the coordinate,
   not the type. Leaves the global hierarchy, service inputs, and mutations untouched; kills the
   null-`PojoInputType` state without pre-empting R333's addressing/naming decisions (still open at
   R333 lines 391-417).

**Exploration result (the withdrawn attempt, branch `claude/focused-bardeen-si3crx`,
wip `f8290ff`).** Removing steps 2 & 3 of `buildInputType` and routing every non-`@table` input
through `PlainInputArg` passed **all** execution-tier, compilation-tier, and pipeline tests, which
confirms the binding is equivalent. But it (a) produced the meaningless null-class `PojoInputType`
for shifted query inputs, and (b) broke the R144 implicit-table `@lookupKey` lookup capability
(`LOOKUP_FIELD_IMPLICIT_TABLE_INPUT_TYPE_ARG_ADMITS_EVERY_FIELD`), because `PlainInputArg` carries
filter semantics only, not the lookup shape. Both are symptoms of collapsing at the wrong layer:
the type-level demotion throws away context the coordinate already has. That is the evidence this
item is R333-shaped, not a `buildInputType` edit.

---

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

> **Superseded by the reframing above (2026-06-20).** The mechanism below ("collapse steps 2 & 3
> onto `PlainInputArg`", the slice plan) is withdrawn: it patches the global `buildInputType`
> verdict, but the verdict itself is the denormalized artifact R333 dissolves. The analysis in this
> section (the three-step critique, the two orthogonal axes, the validator-mirror truth table)
> remains accurate as a description of *today's* code and is kept as input to the replanning. Do not
> implement this slice plan; the new plan is pending the fork resolution above.

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
field-relative and prove (execution tier) it generates the correct correlated predicate. The
execution-tier evidence for this row already exists as
`projectNotesByPlainFilter_plainInputCompositeFkTargetOverride_filtersByForeignTable` (and its
`…Connection` sibling) in `GraphQLQueryTest`; slice 1 keeps it green rather than authoring it anew.

This 2×2 (`override` × `column-resolves`) is a *new axis pair*, not the axis pair the existing
`argument-resolution.adoc` §Truth table tabulates (`any-enclosing-override` × `@condition`
presence/override). "Mirroring" here means landing the column-resolution axis in the same section as
an adjacent table (or an explicit extension), not editing the six existing rows in place; the
implementer should reconcile the two tables under one §Truth table heading so a reader sees both axes
together.

**`@lookupKey` interaction with the step-3 removal.** Removing the `findReturnTablesForInput`
aggregate means a *non-`@table`* input previously promoted to `TableInputType` by the aggregate, and
consumed with arg-level `@lookupKey`, would now classify `PlainInputArg`, where the `@lookupKey`
binding path (`FieldBuilder.classifyArgument:974`, `buildLookupBindings`) does not run, silently
dropping the lookup binding. No current fixture exercises this (every input-object `@lookupKey` arg
pairs with an `@table` input, e.g. `FilmActorKey @table`, so step 1 keeps it `TableInputArg`), so it
is not a fixture regression. Per "validator mirrors classifier invariants," slice 1 must nonetheless
make `@lookupKey` on a now-plain input arg a *validate-time rejection* rather than a silent no-op, or
confirm (and cite) the existing validator rule that requires `@table` on any `@lookupKey`-consumed
input. Fold the chosen disposition into the validator-mirror set above.

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

