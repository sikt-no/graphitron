---
id: R97
title: "Deprecate @table on input types; consumer-derived tables + argMapping grouping"
status: Backlog
bucket: architecture
priority: 6
theme: classification-model
depends-on: []
---

# Deprecate `@table` on input types; consumer-derived tables + `argMapping` grouping

The `@table` directive on input types declares "this input maps to columns
of table X". The classifier consumes it to produce
`GraphitronType.TableInputType` (`TypeBuilder.buildTableInputType` at
`TypeBuilder.java:686-718`), and downstream `MutationInputResolver`,
`EnumMappingResolver.buildLookupBindings`, `FieldBuilder` (line ~697), and
`GraphitronSchemaValidator.validateTableInputType` all switch on that
variant. The directive is the structural signal that drives DML emit,
`@lookupKey` resolution, and condition-input column binding.

This item argues `@table` on input is the same kind of redundant
metadata as `@record`: the table is always derivable from the consuming
field's signature (its return type's `@table`), and the directive on
the input duplicates that signal. Where convention-based resolution
can't tie-break (input fields fan out across multiple service params,
divergent naming, polymorphic targets), `argMapping` is the existing
escape valve and should be extended with a grouping form to cover
GG-376's fan-out use cases without introducing a new directive.

Closes JIRA GG-376 (the proposed `@param` directive becomes
`argMapping` grouping in this item's design).

## Why there is nothing to classify (the fact-model framing)

R327 (`field-relative-input-classification`, folded here 2026-06-22) reached
this item from R333's fact-based model and supplies its load-bearing rationale.
The decisive point: **in the fact model the input type is not an entity.** A
schema coordinate (R333) lowers to a graph of output-field *facts* (a condition,
a write target, a projection); the input merely *supplies the data those facts
consume*. So "what table is this input bound to?" is not a question the input
answers, it is a property of the consuming field's fact (its condition binds the
input's values to columns; the mutation field's write-target fact derives the
table). The global `buildInputType` verdict (`TableInputType` vs
`PojoInputType`, the `@table`-on-input directive, the `findReturnTablesForInput`
aggregate) exists only because today we classify the input *as an entity, with no
consumer in view*; under the fact model it has nothing left to decide and
retires. The contextless artifact this produces, a `PojoInputType` with
`fqClassName=null` for a query-only input that is neither a table nor a backing
class, is the tell that the entity framing is wrong: per-coordinate it cannot
occur, because a declared input is always reachable from some consuming field
(directly, or as a nested field of another input, recursively).

**Deferred: input-as-entity until Jakarta validation.** Modelling the input type
as a first-class entity *is* warranted for exactly one use case, and it is
already filed: the Jakarta-validation cluster R92 (`catalog-check-constraint-validation`,
Spec) and R98 (`multi-source-input-validation`). There a constraint (a DB `CHECK`
lifted to `@Pattern`/`@Min`, an SDL-declared rule, a Jakarta annotation) is a fact
*about the input itself*, true regardless of which field consumes it, and so
cannot be reduced to an output-field fact. That is the boundary: until R92/R98,
the input is data for output facts and this item's consumer-derived resolution is
the whole story; from R92/R98 on, the input acquires its own facts and earns an
entity in the model. This item must not pre-empt that by inventing an input
relation early (the withdrawn R327 attempt that did, branch
`claude/focused-bardeen-si3crx` wip `f8290ff`, passed every execution/compile/pipeline
test but produced the meaningless null-class verdict and broke R144's `@lookupKey`
lookup, the evidence that the entity altitude is wrong for the general case).

## What `@table` on input drives today

| Consumer                                       | What it does                                                          |
|------------------------------------------------|-----------------------------------------------------------------------|
| `MutationInputResolver.classifyDml`            | DML INSERT/UPDATE/DELETE column binding (`MutationInputResolver.java:243`) |
| `EnumMappingResolver.buildLookupBindings`      | `@lookupKey` resolution against table's unique keys                   |
| `FieldBuilder` (line ~697)                     | Switches on `TableInputType` to construct `LookupTableField` etc.     |
| `GraphitronSchemaValidator.validateTableInputType` | Validation invariants on table-bound inputs                       |

Plus the `@table + @record` shadow rule at `TypeBuilder.java:657-664`,
which papers over the conflict between two sources of truth. R96 takes
care of the `@record` side; this item takes care of the `@table` side.
Together they remove the rule.

## Why `@table` on input is redundant

For each fixture pattern in sakila:

- **INSERT/UPDATE/DELETE mutations.** `Mutation.createFilm(in:
  FilmCreateInput!) @mutation(typeName: INSERT)` returns `Film @table(name:
  "film")`. The mutation's table is derivable from the return type. The
  input's `@table(name: "film")` says the same thing.
- **Filter / condition inputs.** `Query.films(filter:
  FilmConditionInput!): [Film]` returns `Film @table(name: "film")`. Same
  derivation; same answer.
- **Lookup-key inputs.** `input FilmActorKey @table(name: "film_actor")
  { ... }` is consumed by a field whose return type's `@table` provides
  the same name.
- **Reuse across consumers.** `FilmConditionInput @table(name: "film")`
  used by both `Query.films` and `Query.filmsByGenre`, both returning
  `[Film]` — both consumer-derived resolutions agree. No emission
  difference.
- **Cross-table reuse** (today: silent misconfiguration; tomorrow:
  classify-time error). If `FilmConditionInput @table(name: "film")` is
  used by a query returning a non-`film` table, today's setup silently
  miscompiles. Consumer-derived resolution fails at classify time with
  the actual consumer's table named, surfacing as `UnclassifiedField`.
  Net win.

The redundancy holds for every case in the existing fixture set.

## What replaces it

**Convention** — the consuming field's return-type-derived table
provides the column-resolution context for every input field carrying
`@field(name: ...)`, `@lookupKey`, or `@nodeId`. Nested inputs
inherit the same context through the consumer chain.

**Convention with `argMapping` escape valve** — when convention can't
tie-break (input fans out across multiple service params, divergent
naming, fields scatter across multiple jOOQ records), the developer
declares the mapping explicitly via `argMapping`. This preserves the
current escape-valve pattern: the SDL declares the shape, `argMapping`
declares the binding, graphitron derives everything else.

**LSP feedback** — the resolved table appears on hover over an SDL
input type. The user sees what graphitron inferred without having to
trace the consumer chain themselves. Combined with classify-time
rejection messages naming the actual consumer's table, the inference
path is fully transparent.

## `argMapping` grouping (extends current `argMapping` syntax)

Today's `argMapping` (with R84's path expressions) handles single-source
to single-target:

```graphql
filmsByPath(input: FilmsByPathInput!): [Film!]!
    @service(service: {
        className: "...",
        method: "filmsByPath",
        argMapping: "filmIds: input.ids"
    })
```

The right-hand side is a path into the input arg; the left-hand side is
a service-method param name.

This item extends `argMapping` with a **grouping form** that handles
multi-source to single-target, addressing GG-376's fan-out cases
without introducing a new directive:

```graphql
type Mutation {
    createOrder(input: CreateOrderInput!): Order
        @service(service: {
            className: "OrderService",
            method: "create",
            argMapping: """
                order: { orderNumber: input.orderNumber, customerId: input.customerId },
                shipTo: { street: input.street, city: input.city }
            """
        })
}
```

The classifier introspects `OrderService.create`'s signature, sees two
parameters (`OrderRecord order`, `AddressRecord shipTo`), and uses the
`argMapping` grouping to fill each:
- `order` is built by setting `OrderRecord.orderNumber` from
  `input.orderNumber` and `OrderRecord.customerId` from
  `input.customerId`.
- `shipTo` is built by setting `AddressRecord.street` from
  `input.street` and `AddressRecord.city` from `input.city`.

This subsumes GG-376's `@param` proposal:

| GG-376's `@param`             | `argMapping` grouping equivalent           |
|-------------------------------|--------------------------------------------|
| `name`                        | left-hand side of grouping entry           |
| `target` (table or record)    | derived from service method's param type   |
| `fields`                      | grouping entries' right-hand sides         |

`@nodeId` decoding inside a grouping works the same way as today's
`argMapping`: a field carrying `@nodeId(typeName: "Customer")` is
decoded into the appropriate ID column of the target.

Rules (mirror the GG-376 validation rules, restated as `argMapping`
extensions):

- Each input field that participates in a grouping must appear in
  exactly one grouping entry's right-hand side.
- The grouping entry's left-hand side must match a service-method
  parameter name (with `-parameters`).
- The set of right-hand-side fields must match (or be a subset of) the
  target type's canonical constructor params (record) or settable
  fields (POJO).
- Convention defaults (the existing R94 Layer 2 `Constructed` binding
  by-name resolution) still apply when `argMapping` doesn't provide a
  grouping for a service-method param.

## Interaction with other roadmap items

- **R94 (`emit-input-records`)** — settles Layer 1 (graphitron emits
  the per-input record) and Layer 2 (`Constructed` binding from
  graphitron-record components into service params). R97 builds on
  Layer 2: instead of by-name resolution against the consumer's domain
  type, the resolution can be `argMapping`-driven for the cases that
  need it. R94 should land first; R97 piggybacks on its Layer 2
  carrier.
- **R96 (shipped)** — the symmetric directive deprecation on `@record`.
  Same architectural argument: `@record`-on-output is redundant with
  introspection; `@table`-on-input is redundant with consumer-derived
  tables. R97 applies the same principle to the input-side `@table`.
  The `@table + @record` shadow rule goes away once both directives
  are removed (R96 shipped the reflection-driven binding + Shadowed-by-
  `@table` directive-ignored warning; R97 + the R96 follow-on retire
  the directive declarations).
- **GG-376 (Jira)** — proposes `@param` for fan-out. R97's
  `argMapping` grouping subsumes that proposal: fan-out is expressed
  via grouping entries on the existing directive rather than a new
  one. The closure note on GG-376 should reference R97.
- **R333 (`coordinate-lowers-to-datafetcher-queryparts`)** — the fact-based
  model this item's rationale rests on (see "Why there is nothing to classify").
  R333 lowers a schema coordinate to a graph of output-field facts; this item is
  the statement that the input supplies data to those facts and is not itself a
  modeled relation. Conceptual ancestor, not a hard build dependency: the phases
  here land on today's call-site resolution, which already resolves input fields
  against the consumer's `rt`.
- **R92 / R98 (Jakarta validation)** — the boundary where the deferral ends.
  R92 (`catalog-check-constraint-validation`, Spec) and R98
  (`multi-source-input-validation`) are where the input acquires facts of its own
  (constraints true regardless of consumer) and earns a first-class entity. This
  item must not pre-empt that model; it covers the input-as-data era up to that
  point.
- **R332 (`table-on-input-deprecation-signal`)** — the user-facing announce of
  the deprecation this item performs. R332 announces; R97 removes. R332's
  encoded-ID INSERT/UPSERT carve-out is gated on this item's Phase 2b; coordinate
  so R332's warning fires on those inputs once Phase 2b lands.
- **R337 (`input-nesting-projection-classification`)** — the honest *surfacing*
  of a nested-grouping projection (the LSP label / `TypeClassification` that today
  reads `PojoInput(null)`). R337 is a redirect parked behind this item; the
  null-backed `PojoInputType` it targets is dissolved by the consumer-derived
  resolution here. If this item lands but leaves that surfacing on the
  `PojoInput(null)` label, R337 revives narrowly for the surfacing residual only.
- **R327 (`field-relative-input-classification`, folded here 2026-06-22)** — split
  out of R317 slice 4 as the one non-byte-identical change, reframed around R333,
  then folded into this item. Its surviving analysis lives in the fact-model
  framing, the Phase 2 `findReturnTablesForInput` / override-routing retirement and
  validator-mirror obligation, and the Phase 2b write-target migration above. R327
  is discarded; this item is its home.

## Architectural principle this codifies

The rewrite has been pushing toward "classify everything from SDL
declarations + consumer-supplied type info" — generation-thinking
applied. This item names the wall that approach hits: some
configurations can't be unambiguously inferred from types alone.
Rather than adding new directives that try to encode every case at
the type level (`@param`, `@table` on input, `@record` on either
side), the rewrite leans into the pattern that's already worked:

1. **Default to convention.** Name-match, type-match, consumer-chain
   table resolution.
2. **Reach for `argMapping`** when convention can't tie-break or
   fan out.
3. **Surface what was inferred via LSP** so users see the result
   without tracing the chain themselves.
4. **Document directly in error messages** when convention fails:
   "graphitron couldn't infer X because Y; either change Z or add
   `argMapping`."

This is the existing "convention + explicit override" pattern,
codified across the input boundary. R94 + R96 + R97 together remove
the three "explicit type binding" directives whose information is
already available through introspection or `argMapping`.

## Phasing

Three phases, ordered so each is independently shippable.

### Phase 1: extend `argMapping` with grouping syntax

- Parser change in the `argMapping` value parser (the R84
  path-expression parser is the existing precedent).
- Resolver change in the `argMapping` consumer (likely
  `EnumMappingResolver.enrichArgExtractions` or a new `ArgMapping*`
  module, depending on where the parsing currently lives).
- Sealed-result extension to `ArgBinding` to carry grouping
  outcomes.
- Compact-constructor-enforced grouping invariants on the new
  carrier (every input field belongs to exactly one grouping entry;
  each group entry's RHS matches the target type's constructor params).
- Pipeline-tier coverage: SDL with a multi-target service method →
  emitted fetcher constructs each target from the grouped input
  fields.
- Execution-tier coverage: a sakila mutation that fans out across
  two jOOQ records.

Acceptance: `argMapping` grouping works end-to-end for at least one
sakila fixture; existing single-source `argMapping` is unchanged.

### Phase 2: switch table resolution to consumer-derived

- New classifier branch in `TypeBuilder` that resolves an input's
  table from the consuming field's return type. The branch produces
  the same `TableInputType` model variant as today, just from a
  different source.
- **Retire the `findReturnTablesForInput` global aggregate**
  (`TypeBuilder.buildInputType`, the third of its three ordered steps).
  It is the right idea (derive the table from the consumer) at the wrong
  altitude: a global aggregate over *every* consuming field that bails to
  non-table on more than one distinct table (the `> 1` bail), so an input
  reused across two tables silently classifies non-table everywhere. The
  consuming field's resolved target (`rt`) is already computed at the call
  site (`lookAheadVerdict` resolves a field's target registry-free at the
  edge since R317), so the aggregate recomputes what each call site already
  knows. Consumer-derived resolution replaces it per call site; the `> 1`
  case stops being a silent demotion and becomes two correct per-consumer
  bindings (the "reuse across consumers" row above), or a classify-time
  rejection naming the offending consumer's table.
- **Decouple the override-condition gate from type routing**
  (`buildInputType` step 2, `isUsedWithOverrideCondition`). Today, if *any*
  field of an input carries `@condition(override:true)`, the whole input is
  routed off the table path into a non-table `InputType`. That overloads a
  *per-field validation modifier* (`override` = "the consumer owns this
  predicate, skip column-coverage on it") into a *whole-type routing gate*.
  "Has an override field" and "is table-bound" are orthogonal axes; the
  override flag is already threaded field-relative at the call site
  (`FieldBuilder.classifyArgument`, `enclosingOverride`), so it has no
  business deciding the type's bucket. This conflation is what silently
  broke R330 (`SoknadsmangeltypeFilterInput` would have resolved to a
  table by the consumer, but an override field diverted it, and the
  diverted non-table path skipped the FK-target *structural* check, so an
  identical schema-author error was rejected at build time on the
  table-routed path and silently miscompiled on the diverted one). The fix
  is the consumer-derived resolution above plus the field-relative
  validation below: `override` only suppresses per-field column-coverage,
  never the structural check and never the routing.
- `MutationInputResolver`, `EnumMappingResolver`,
  `FieldBuilder.classifyChildField`, and
  `GraphitronSchemaValidator.validateTableInputType` continue to
  consume `TableInputType`; no change to their internals.
- Structural invariant: every `TableInputType` field carries either
  a consumer-derived table or an explicit `argMapping` binding,
  enforced at the producer site via a non-null typed carrier.
- Existing `@table` declarations on inputs become a no-op (still
  parsed, but the directive's value isn't consulted; the
  consumer-derived value wins). Surfaces as a build warning during
  this phase: "`@table` on input is redundant; consumer-derived
  table resolution is in effect. Remove the directive."
- LSP work: hover on an SDL input type shows the resolved
  `@table` (per-consumer if multiple consumers).

Acceptance: every sakila fixture compiles unchanged; the warning
fires on every `@table`-decorated input; LSP hover shows the
resolved table.

#### Validator-mirror obligation (gate on Phase 2)

Decoupling `override` from routing turns today's 1-D gate into a 2x2:
`override` (yes/no) x `column-resolves` (yes/no). Per "validator mirrors
classifier invariants," each cell that implies a generator branch must fail at
validate time if unimplemented, or the change silently shifts which schemas
validate. The `InputFieldResolver` already lifts a column-miss to `UnboundField`
uniformly (R215), so the machinery exists; Phase 2 must pin an explicit truth
table mirroring `argument-resolution.adoc` §Truth table, with a pipeline-tier
test per row:

| override | column resolves | outcome |
|----------|-----------------|---------|
| false | true | implicit column predicate emitted (today's table-bound default) |
| false | false | `UnboundField` → validator rejects (R215: implicit predicate required, no column) |
| true | true | consumer condition owns the predicate; implicit suppressed |
| true | false | consumer condition owns the predicate; column-miss admitted (the R330 / opptak shape) |

The fourth row is the one the old step-2 gate reached by demoting the whole
type; Phase 2 must reach it field-relative and prove (execution tier) it
generates the correct correlated predicate. The execution-tier evidence already
exists as `projectNotesByPlainFilter_plainInputCompositeFkTargetOverride_filtersByForeignTable`
(and its `…Connection` sibling) in `GraphQLQueryTest`; keep it green rather than
authoring it anew. Reconcile this `override` x `column-resolves` table with the
existing `argument-resolution.adoc` §Truth table (which tabulates
`any-enclosing-override` x `@condition` presence) under one heading, so a reader
sees both axes together.

**`@lookupKey` on a now-resolved input.** Removing the `findReturnTablesForInput`
aggregate means a non-`@table` input previously promoted by the aggregate and
consumed with arg-level `@lookupKey` must still reach the `@lookupKey` binding
path (`FieldBuilder.classifyArgument`, `buildLookupBindings`) via the
consumer-derived table, not silently drop the lookup. No current fixture
exercises this (every input-object `@lookupKey` arg pairs with an `@table` input
today, e.g. `FilmActorKey`), so it is not a regression, but Phase 2 must either
route `@lookupKey` through the consumer-derived table or make `@lookupKey` on an
unresolved input a validate-time rejection rather than a no-op. Fold the chosen
disposition into the validator-mirror set above.

### Phase 2b: INSERT/UPSERT write-target migration (a different axis)

Phase 2's consumer-derived resolution covers the *query-side* binding (filters,
conditions, lookups) and the mutations whose write target *is* derivable from
the return type. It does **not** cover one mutation shape, and this phase is
that gap: INSERT/UPSERT mutations whose return type is an encoded ID or scalar
(`createFilm(...): ID`). For those, `@table`-on-input is currently the *only*
signal naming the write target, because the return type is a bare `ID` with no
`@table` to derive from. Per `MutationField.DmlTableField`, INSERT/UPSERT
"carry the `@table` `TableInputArg` that drives the statement directly," while
UPDATE/DELETE already moved to a field-relative walker carrier (R246/R266).

Extend the UPDATE/DELETE field-relative walker pattern to encoded-ID/scalar-return
INSERT/UPSERT, so the write target comes from the consuming mutation field's
resolved target rather than its return type. This is the gate that lets Phase 3
finally narrow the directive scope: until the INSERT/UPSERT arms are
field-relative, `@table`-on-input cannot be removed for them. It is also the gate
R332's carve-out waits on: R332 (`table-on-input-deprecation-signal`) must *not*
flag these inputs as deprecated until this phase lands, and it tracks the
`encodedWriteTargetInputTypes(...)` find-usages anchor that this phase retires
(once the write target is field-relative, encoded INSERT/UPSERT inputs no longer
need `@table`, so the deprecation warning should fire on them too). Plan note:
this phase retires `encodedWriteTargetInputTypes`.

Acceptance: an encoded-ID-return INSERT and UPSERT sakila fixture emit correct
DML with `@table` removed from the input; `encodedWriteTargetInputTypes` is gone;
R332's deprecation warning fires on the previously-carved-out inputs.

### Phase 3: remove the directive declaration

- Narrow `directives.graphqls`'s `@table` directive scope from
  `OBJECT | INTERFACE | INPUT_OBJECT` to `OBJECT | INTERFACE`.
- Remove the `@table`-driven arm in
  `TypeBuilder.buildNonTableInputType` (now exclusively
  `buildInputType` after the consumer-derived-only flip in Phase 2).
- Remove the `@table + @record` shadow rule entirely (R96 takes the
  `@record` half; this phase takes the `@table` half).
- Migrate all sakila fixtures: remove `@table(name: "...")` from
  every `input` declaration. Six in `schema.graphqls` plus any in
  `graphitron/src/test/`.
- Migrate any LSP fixtures that reference `@table` on inputs.
- Update `code-generation-triggers.adoc:112` and any other doc
  references.

Acceptance: directive declaration accepts only `OBJECT | INTERFACE`;
all fixture SDL is migrated; build green.

### Phase 4: housekeeping

- Add a migration note in `changelog.md` naming the SHA where
  `@table`-on-input ships zero scope.
- LSP completion + diagnostics drop `@table` from the
  `INPUT_OBJECT`-applicable directive list.
- `docs/README.adoc` and any other documentation references update
  to remove `@table` as a directive consumers reach for on inputs.

## Out of scope

- Removing `@table` on `OBJECT` or `INTERFACE`. Those scopes carry
  load-bearing semantics (`TableType` / `TableInterfaceType`) that
  drive output emit and that don't have a consumer-derived
  equivalent. R96 + R97 don't generalize to those scopes.
- Adding a new directive for explicit type binding on inputs. The
  whole point is that explicit binding is redundant with
  introspection or `argMapping`. If a future case can't be covered
  by either, it surfaces as `UnclassifiedField` and gets its own
  dedicated classifier signal (not a re-introduction of `@table`).
- Replacing `argMapping` with a different mechanism. R84 already
  invested in `argMapping` path expressions; this item extends
  rather than replaces.

## Risk

- **Consumer-derived table resolution is harder to debug than today's
  declarative form.** Mitigation: classify-time rejection messages
  must name the actual consumer's table, the input field that
  failed to resolve, and the candidate fix (add `@field(name:)`,
  use `argMapping`, change return type). LSP feedback is the
  user-facing surface that makes the inference visible.
- **`argMapping` grouping syntax could become unwieldy for large
  fan-outs.** Mitigation: keep the grouping form simple (one level
  deep), defer multi-level nesting to a follow-up if it shows up
  in real schemas. Most fan-outs in production are 2-3 targets.
- **R94's Layer 2 `Constructed` binding overlaps with this item's
  `argMapping` grouping.** Convention-by-name resolves the simple
  cases (R94 Layer 2); grouping handles the rest (R97 phase 1).
  The overlap is intentional: most cases use convention, edge cases
  reach for `argMapping`. Spec-stage review should confirm the
  boundary is clean.

## Tests

Each phase carries its own test surface; the high-leverage cases:

- Pipeline-tier (Phase 1): `argMapping` grouping → emitted fetcher
  body has correct constructor calls per target.
- Pipeline-tier (Phase 2): SDL with no `@table` on input + a
  `@table`-returning consumer → emitted fetcher resolves columns
  against consumer's table.
- Pipeline-tier (Phase 2): SDL with no `@table` on input + a
  consumer that doesn't carry `@table` on its return → classifier
  rejects with a clear message naming the consumer.
- Execution-tier (Phase 1): a sakila multi-target mutation
  exercising grouping end-to-end.
- LSP-tier (Phase 2): hover on an SDL input type returns the
  resolved table information.
