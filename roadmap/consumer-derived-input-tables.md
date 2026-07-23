---
id: R97
title: "Deprecate @table on input types; consumer-derived tables + argMapping grouping"
status: Backlog
bucket: architecture
priority: 6
theme: classification-model
depends-on: [insert-write-target-from-payload]
---

# Deprecate `@table` on input types; consumer-derived tables + `argMapping` grouping

The `@table` directive on input types declares "this input maps to columns
of table X". The classifier consumes it to produce
`GraphitronType.TableInputType` (`TypeBuilder.buildTableInputType`), and
downstream `FieldBuilder.classifyArgument` (the `TableInputType` arm feeding
`EnumMappingResolver.buildLookupBindings`), the UPDATE walker classifiers,
and `GraphitronSchemaValidator.validateTableInputType` all switch on that
variant. The directive is the structural signal that drives the remaining
non-field-relative DML write targets, arg-level `@lookupKey` resolution, and
the global input-classification verdict.

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

## What has already shipped toward this goal (respec 2026-07-23)

This item predates a line of work that delivered large parts of its original
Phase 2; the respec re-baselines against it.

- **Query-side plain-input resolution is already field-relative.** A non-`@table`
  input argument routes through `FieldBuilder.classifyArgument`'s plain-input
  path into `InputFieldResolver.resolve(typeName, rt, enclosingOverride)`,
  resolving every field against the *consuming field's* return table (R205
  restored plain/`@table` filter symmetry; R215 lifted column-miss to
  `UnboundField` uniformly). The `override` axis is threaded field-relative
  through the same call (`enclosingOverride`), and the semantics are pinned by
  the six-row truth table in `argument-resolution.adoc` §Truth table, the R205
  pipeline test `plainInput_resolvedColumnWithoutCondition_emitsImplicitBodyParam`,
  and the execution-tier
  `projectNotesByPlainFilter_plainInputCompositeFkTargetOverride_filtersByForeignTable`
  (+ `…Connection` sibling) in `GraphQLQueryTest` (the R330 / opptak shape).
  The original Phase 2's "validator-mirror obligation" 2x2 is therefore
  discharged for the filter path; only the `@lookupKey` disposition survives
  (see Phase 2 below).
- **Mutation write targets are going field-relative, one verb per item.**
  R457 shipped DELETE (`@mutation(table:)` on the field, precedence over the
  input's `@table` bridge, `FieldBuilder.resolveDeleteWriteTarget`,
  `TypeBuilder.resolveInputFields` shared with `buildTableInputType`, the
  validator mirror via `GraphitronSchemaValidator.collectInputFieldRejections`).
  R514 (`dml-emitted-mutation-table-grounding`) grounds
  `ProducerBinding.DmlEmitted` from `@mutation(table:)` so payload carriers
  survive `@table`-on-input removal. R515 (`insert-write-target-from-payload`)
  gives INSERT the return-derived rung (direct `@table` return or the payload
  scan's `DmlElementKind.Table`), admits `@mutation(table:)` on INSERT, retires
  `MutationInputResolver.resolveInput` and the
  `encodedWriteTargetInputTypes` carve-out.
- **The sibling directive retirements landed.** R96 shipped the
  reflection-driven `RecordBindingResolver` (`@record` deprecated and ignored;
  the old `@table + @record` shadow rule survives only as the
  "Shadowed by `@table`" arm of the directive-ignored warning). R94 shipped the
  emit-input-records carrier (scoped to class-not-record). R332 shipped the
  `@table`-on-input deprecation warning, so the user-facing announce is live.

## What `@table` on input still drives today

| Consumer                                                | What it does                                                                    |
|---------------------------------------------------------|---------------------------------------------------------------------------------|
| `FieldBuilder.classifyUpdateTableField` / `classifyUpdatePayloadField` | UPDATE write target: a non-`@table` input arg is translated back to the legacy "only accept @table input arguments" rejection (`rawArgUpdateRejection`); UPDATE has no field-relative write-target path |
| `FieldBuilder.classifyArgument` (`TableInputType` arm)  | Arg-level `@lookupKey` composite lookup: `EnumMappingResolver.buildLookupBindings` runs only for a `TableInputType` arg, so the lookup shape (e.g. `FilmActorKey`) requires `@table` on the input |
| `TypeBuilder.buildInputType`                            | The global classification verdict: explicit `@table` wins; otherwise the `isUsedWithOverrideCondition` routing gate and the `findReturnTablesForInput` auto-promotion aggregate decide `TableInputType` vs non-table |
| `GraphitronSchemaValidator.validateTableInputType`      | Registry-walk validation invariants on table-bound inputs                        |

(INSERT's `MutationInputResolver.resolveInput` consumer is being retired by
R515 and is deliberately absent from this table; if R515 has not shipped when
this item is picked up, treat it as a blocking dependency, not scope.)

## Why `@table` on input is redundant

For each fixture pattern in sakila:

- **INSERT/UPDATE/DELETE mutations.** `Mutation.createFilm(in:
  FilmCreateInput!) @mutation(typeName: INSERT)` returns `Film @table(name:
  "film")`. The mutation's table is derivable from the return type. The
  input's `@table(name: "film")` says the same thing. (DELETE cannot use the
  return rung, R287, and instead names the table on the field, R457; INSERT
  ships the return rung in R515; UPDATE is this item's remaining verb.)
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

- **R94 (`emit-input-records`, shipped)** — settled Layer 1 (graphitron emits
  the per-input carrier, scoped down to class-not-record) and Layer 2
  (`Constructed` binding from carrier components into service params). R97
  builds on Layer 2: instead of by-name resolution against the consumer's
  domain type, the resolution can be `argMapping`-driven for the cases that
  need it. The "R94 lands first" gate is satisfied.
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
- **R332 (`table-on-input-deprecation-signal`, shipped)** — the user-facing
  announce of the deprecation this item performs. R332 announced; R97 removes.
  R332's encoded-ID INSERT/UPSERT carve-out (`encodedWriteTargetInputTypes`) is
  retired by R515, which also extends the warning's replacement wording; this
  item's Phase 2b does the same for UPDATE-consumed inputs.
- **R457 (shipped) / R514 / R515** — the mutation write-target slices of this
  item's axis, carved out and landed (or landing) item-by-item: R457 the DELETE
  field-relative write target, R514 the `DmlEmitted` grounding from
  `@mutation(table:)`, R515 the INSERT return-derived write target plus
  `resolveInput` retirement. Phase 2b below is the UPDATE residual of that
  series and should reuse R515's precedence machinery.
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

Ordered so each phase is independently shippable. Phase 1 (`argMapping`
grouping) is orthogonal to the rest and can be scheduled freely; Phases 2 and
2b can land in either order; Phase 3 requires both plus R515.

### Phase 1: extend `argMapping` with grouping syntax

- Parser change in the `argMapping` value parser (`PathExpr`, the R84
  path-expression parser, is the existing precedent).
- Resolver change in the `argMapping` consumers
  (`ServiceDirectiveResolver` / `ArgBindingMap`; the old
  `EnumMappingResolver.enrichArgExtractions` home is retired).
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

### Phase 2: retire the global input-classification machinery

The original Phase 2 asked for consumer-derived column resolution at the call
site; that shipped (R205/R215/R330, see "What has already shipped"). What
remains is making the *last* `TableInputType`-gated call site consumer-derived
and then deleting the global machinery that only exists to feed it.

- **Route arg-level `@lookupKey` through the consumer-derived table.**
  `FieldBuilder.classifyArgument` runs `EnumMappingResolver.buildLookupBindings`
  only on the `TableInputType` arm, so the composite-key lookup shape
  (`FilmActorKey`) still requires `@table` on the input. Re-derive: when the
  arg carries `@lookupKey` and the input is plain, resolve the input's fields
  against the consuming field's `rt` (the `TypeBuilder.resolveInputFields`
  factoring R457 extracted for exactly this dual use) and build the binding
  set from that. `@lookupKey` on an input that fails to resolve against `rt`
  is a classify-time rejection naming the consumer's table, never a silent
  no-op. (No current fixture exercises a plain-input `@lookupKey` arg, so this
  is additive, not a regression risk.)
- **Retire the `findReturnTablesForInput` global aggregate**
  (`TypeBuilder.buildInputType`, the third of its three ordered steps).
  It is the right idea (derive the table from the consumer) at the wrong
  altitude: a global aggregate over *every* consuming field that bails to
  non-table on more than one distinct table (the `> 1` bail), so an input
  reused across two tables silently classifies non-table everywhere (pinned
  by the two-call-site `PlainFilter` cases in `GraphitronSchemaBuilderTest`).
  The consuming field's resolved target (`rt`) is already computed at the call
  site (`lookAheadVerdict` resolves a field's target registry-free at the
  edge since R317), so the aggregate recomputes what each call site already
  knows, worse. With the `@lookupKey` arm consumer-derived and the mutation
  verbs field-relative (R457/R515 + Phase 2b), nothing needs the aggregate's
  auto-promotion; the `> 1` case stops being a silent demotion and becomes
  per-consumer resolution (the "reuse across consumers" row above).
- **Retire the `isUsedWithOverrideCondition` routing gate** (`buildInputType`
  step 2) in the same move. It overloads a per-field validation modifier
  (`override` = "the consumer owns this predicate, skip column-coverage on
  it") into a whole-type routing gate that today only decides whether the
  aggregate may auto-promote. The override flag is already threaded
  field-relative at the call site (`classifyArgument`'s `enclosingOverride`,
  the R330 rework), so once the aggregate goes, the gate has nothing left to
  guard. After this bullet, `buildInputType` is directive-driven only:
  explicit `@table` → `TableInputType` (the deprecated bridge, Phase 3
  deletes it), everything else → the plain-input path.
- `GraphitronSchemaValidator.validateTableInputType` keeps validating the
  bridge-classified inputs until Phase 3; consumer-derived call sites
  discharge the validator-mirror obligation the way R457 did
  (`collectInputFieldRejections` at the field-derived site).
- LSP work: hover on an SDL input type shows the resolved table
  (per-consumer if multiple consumers).

Acceptance: every sakila fixture compiles unchanged; a plain-input arg-level
`@lookupKey` fixture classifies via the consumer's table;
`findReturnTablesForInput` and `isUsedWithOverrideCondition` are gone; LSP
hover shows the resolved table.

#### Validator-mirror status (mostly discharged)

The original Phase 2 carried a 2x2 `override` x `column-resolves` obligation.
That table is now live field-relative: `argument-resolution.adoc` §Truth table
pins the six-row `any-enclosing-override` x `@condition` semantics, enforced by
the R205 pipeline test
(`plainInput_resolvedColumnWithoutCondition_emitsImplicitBodyParam`), the
`UnboundField` lift (R215), and the execution-tier
`projectNotesByPlainFilter_plainInputCompositeFkTargetOverride_filtersByForeignTable`
(+ `…Connection` sibling) in `GraphQLQueryTest` (the override-true /
column-miss row, the R330 opptak shape). Keep those green rather than
authoring anew. The one surviving obligation is the `@lookupKey` disposition
above: its rejection row (plain input, `@lookupKey`, unresolvable against the
consumer's table) must land in the validator-mirror set with a pipeline-tier
test.

### Phase 2b: UPDATE write-target migration (the last verb)

The mutation half of this item has been landing verb-by-verb: DELETE (R457),
the grounding substrate (R514), INSERT (R515). UPDATE is the residual: both
UPDATE classifiers (`classifyUpdateTableField`, `classifyUpdatePayloadField`)
translate a `DmlWalkerInputArgResolution.RawArg` (a plain input) back to the
legacy "@mutation fields only accept @table input arguments" rejection via
`rawArgUpdateRejection`, so after R515 it is the lone verb hard-requiring
`@table` on its input.

Extend R515's precedence ladder to UPDATE, where the return rung is as natural
as INSERT's (an UPDATE returns the updated row's `@table` type or a payload
carrier wrapping it): return-derived table > `@mutation(table:)` (add UPDATE
to `TABLE_ARG_SUPPORTED_VERBS`, needed for the ID/scalar-return UPDATE) > the
input's `@table` bridge, with R515's cross-check semantics carried over
verbatim (same directive pairs must not behave differently across verbs). The
field-derived path re-derives input fields through
`TypeBuilder.resolveInputFields` and feeds `UpdateRowsWalker` exactly as the
`@table`-input path does; the walker itself is already table-driven and needs
no change.

When picked up, carve this out as its own item the way R457/R514/R515 were;
this phase records the scope so the series has a single home. UPSERT stays
deferred upstream (refused before classification) and inherits the ladder when
it un-defers.

Acceptance: an UPDATE sakila fixture (direct-return and payload-returning)
emits byte-identical DML with `@table` removed from the input;
`rawArgUpdateRejection` is gone; the R332 warning's replacement wording covers
UPDATE-consumed inputs.

### Phase 3: remove the directive declaration

- Narrow `directives.graphqls`'s `@table` directive scope from
  `OBJECT | INTERFACE | INPUT_OBJECT` to `OBJECT | INTERFACE`.
- Remove the `@table`-driven arm in `TypeBuilder.buildInputType` (the only
  arm left after Phase 2), collapsing every input to the plain-input path;
  `buildTableInputType`, `TableInputType`, and
  `GraphitronSchemaValidator.validateTableInputType` retire with it (their
  remaining consumers all moved to field-relative resolution in Phase 2/2b).
- Remove the input half of the "Shadowed by `@table`" directive-ignored
  warning (the residue of the old `@table + @record` shadow rule; R96 took
  the `@record` half).
- Retire the R332 deprecation warning itself
  (`emitTableOnInputDeprecationWarnings`): once the scope narrows, an
  `@table`-on-input is a parse error, not a warning.
- Migrate all fixtures: remove `@table(name: "...")` from every `input`
  declaration; `schema.graphqls` carries roughly forty at the time of this
  respec (grep `^input .+@table`), plus inline SDL in `graphitron/src/test/`
  and any LSP fixtures.
- Update `code-generation-triggers.adoc` and any other doc references.

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
- Pipeline-tier (Phase 2): plain input + arg-level `@lookupKey` against a
  `@table`-returning consumer → lookup bindings built from the consumer's
  table; same input against a consumer whose table lacks the columns →
  classify-time rejection naming the consumer.
- Pipeline-tier (Phase 2): the two-call-site reuse shape (today's `> 1`
  aggregate bail) classifies per-consumer instead of demoting; the
  `GraphitronSchemaBuilderTest` `PlainFilter` cases are rewritten from
  pinning the demotion to pinning the per-consumer resolution.
- Pipeline-tier (Phase 2b): UPDATE fixtures byte-identical with and without
  `@table` on the input (the `MutationTableArgClassificationTest` pattern).
- Execution-tier (Phase 1): a sakila multi-target mutation
  exercising grouping end-to-end.
- Execution-tier (Phase 2b): an UPDATE round-trip with `@table` dropped from
  the input.
- LSP-tier (Phase 2): hover on an SDL input type returns the
  resolved table information.
