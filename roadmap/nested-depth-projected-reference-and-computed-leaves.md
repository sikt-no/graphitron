---
id: R645
title: "Admit projected @reference and @externalField leaves at nested depth under NestingField"
status: Backlog
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Admit projected @reference and @externalField leaves at nested depth under NestingField

`GraphitronSchemaValidator.isNestedWireableLeaf` admits exactly four leaf shapes under a
`ChildField.NestingField`: `ColumnBackedField`, `TableField`, `NestingField`, and the
`SourceShape.Table` arm of `BatchedTableField`. Every other leaf is rejected by the sibling
`validateVariantIsSupportedAtNestedDepth` with
`Rejection.deferred("Field '<coord>': <VariantClass> is not yet supported under NestingField")`.
This item is about admitting two more: `ChildField.ColumnBackedReferenceField` (a scalar `@field` +
`@reference` projection) and `ChildField.ComputedField` (an `@externalField` expression leaf). Both
are already *projected* leaves rather than dispatched ones, which is why the gate may be mostly a
validator gate. That is the hypothesis, not the conclusion; see below.

## Why it matters: the downstream evidence

Measured on the fs-plattform `sis` subgraph mid-migration to Graphitron 10 (branch
`sis/upgrade-graphitron`) via `mcp__graphitron__diagnostics_aggregate`: **39 of its 72 deferred
errors are this one gate.**

| Sub-kind | Count | Example coordinate |
|---|---|---|
| `ColumnBackedReferenceField` under nesting | 23 | `Resultatfordeling.antallBestatt` |
| `ComputedField` under nesting | 12 | `ResultatAlleSprak.nb` |
| `ComputedField` under nesting, multi-parent | 4 | `EkskludertEmneIResultatsammendrag.emnenavn` |

The 23 are one uniform authoring shape across 11 wrapper types: a `@table`-less nesting type whose
scalar leaves each carry `@field` + `@reference` hopping to the *same* first table.

```graphql
type EmneIUtdanningsplanPlanlagtVekting {          # no @table
    verdi: BigDecimal @field(name: "VEKTINGSTALL_PLANLAGT")
        @reference(path: [{table: "UTDANNINGSPLAN_EMNE_RESULTAT"}])
    vektingstype: Vektingstype                      # object-typed: classifies ReferenceField, NO error
        @reference(path: [{table: "UTDANNINGSPLAN_EMNE_RESULTAT"}, {key: "…PLANLAGT__VEKTINGSTYPE__FK"}])
    vektingIStudiepoeng: BigDecimal @field(name: "VEKTING_I_STUDIEPOENG_PLANLAGT")
        @reference(path: [{table: "UTDANNINGSPLAN_EMNE_RESULTAT"}])
}
```

Note the asymmetry inside a single type: the *object*-typed `@reference` sibling is accepted, only
the *scalar* projection rejects. `VurderingsenhetTerminperiode` in the same schema does object-typed
`@reference` under a nesting type with no diagnostic.

A schema-side workaround exists for the 23 (hoist the shared first hop onto the parent field, give
the wrapper its own `@table`, drop the hop from each leaf; shape-preserving, and it emits one join
instead of N identical ones). That workaround is not a reason to skip this item: the 16
`ComputedField` errors have no clean schema-side equivalent, because `@externalField` on a leaf
inside an unbound wrapper is not a supported shape at all. The supported lift puts `@externalField`
on the *parent* field returning `Field<Record>`, which means reworking consumer Java, not just SDL.

## The hypothesis to verify first: this may be mostly a validator gate

Both target variants already sit on no-op arms in `TypeFetcherGenerator.generateForType`, whose
comments say the value is projected rather than dispatched:

- `ChildField.ColumnBackedReferenceField`: "inline projection via the type's `$project` unit
  (Direct compaction); the read of that aliased projection is reified by `FetcherEmitter.bind` and
  collected below."
- `ChildField.ComputedField`: "alias-pickup read reified by `FetcherEmitter.bind`; projected via the
  type's `$project` unit."

`ChildField.TableField` sits on the immediately following arm with the *same* disposition, and it is
already admitted at nested depth.

That matters because R23 lifted the multi-parent shared-shape gate for `TableField` on exactly this
argument, with no emitter or wiring change. From `roadmap/changelog.md` (R23, `c38779e`):
"`TableField` is a `PROJECTED_LEAF` whose reified read (`FetcherEmitter.bind`, wrapped in
`LightFetcher`) pulls by field name from the source `Record` without consulting the outer parent
table, so first-parent-wins nested-type registration has no runtime effect for this leaf."

So the spec's first job is to determine whether `isNestedWireableLeaf` tracks a **real emitter hole**
or is merely **conservative**. Do not assume conservative. The predicate's own javadoc explicitly
claims that expanding it "requires the corresponding generator-side change", and names the three
seams that would have to agree: `FetcherEmitter.bind`, `FetcherEmitter.nestedTypeOwnsFetchers`
(delegating to `NestingReach.ownsFetchers`), and `FetcherRegistrationsEmitter.nestedBody`, with
`TypeFetcherGenerator` emitting the nested `<Type>Fetchers` class. Repeat R23's emitter-safe
verification rather than reasoning by analogy.

The concrete question for `ColumnBackedReferenceField`: a nested plain-object type shares the
*parent's* table context, and the leaf's value needs its `joinPath` hop applied. Establish whether
the nested type's `$project` unit actually carries that join, or whether the join is emitted only
when the leaf hangs off a table-backed parent. `FetcherEmitter.bind`'s Direct-compaction
`ColumnBackedReferenceField` branch (which reads the value back out of the parent `Record` by alias)
is one place to look; `JoinedTableReprojection` is an adjacent precedent worth reading, since it
already projects a participant type's `ColumnBackedReferenceField` into a *borrowed* projection root
and defers the non-`Direct` compactions there.

`GraphitronSchemaValidator.validateColumnBackedReferenceField` already rejects the `NodeIdEncodeKeys`
compaction and the malformed reference-path shapes ahead of generation, so those need not be handled
here, but confirm those pre-gates still fire at nested depth once the blanket gate stops shadowing
them.

For `ComputedField`, weigh the note in `FetcherEmitter.resolvesViaPropertyDataFetcher`'s javadoc: a
`ComputedField` "needs a SELECT-projected parent". Decide whether a nesting parent satisfies that.

## Scoping decisions the spec must make

1. **One item or two?** `ColumnBackedReferenceField` and `ComputedField` may have materially
   different verification burdens: the former is a join-carrying column projection, the latter a
   user-supplied `Field<T>` expression. Split if the evidence diverges.
2. **Overlap with R323** (`nestingfield-multiparent-batchkey-leaves`). The 4 multi-parent
   `ComputedField` errors come from a *different* gate, `compareNestedFieldsShape`'s catch-all
   ("not yet supported across multiple parents"), reached via `validateNestingParentCompat`, not from
   `isNestedWireableLeaf`. R323 covers that gate but scopes itself to BatchKey leaves. Decide whether
   the multi-parent `ComputedField` arm belongs here, in R323, or in its own item; either way
   cross-link. Admitting a variant at nested depth does *not* by itself admit it across shared
   parents.
3. **`LookupTableField`** is the open re-scoping question R323 carries. It looks answered by
   attrition rather than by analysis: the leaf no longer exists in the model (R432 folded the lookup
   pair onto the source-gated `BatchedTableField`, and the inline `LookupTableField` folded onto
   `TableField` with a `lookup()` facet), and both survivors are already admitted at nested depth
   ("lookup-keyed or not", per the predicate's javadoc). Confirm that reading and say so explicitly
   in R323 rather than letting the question sit; do not absorb it silently.
4. **Coverage shape.** Follow R23's pattern: a pipeline test in `GraphitronSchemaBuilderTest`
   (classifies, then validates clean) plus an execution test in `GraphQLQueryTest` pinning the
   projected value against a direct navigation of the same row.
5. **The existing rejection tests do not simply invert.** `NestingFieldValidationTest`'s two
   `ColumnBackedReferenceField` cases (`DEFERRED_NESTED_COMPOSITE_REFERENCE` and
   `DEFERRED_NESTED_COMPOSITE_INSIDE_NESTED_NESTING`) both build a *composite NodeId*
   reference, so their compaction is `NodeIdEncodeKeys`, which
   `validateColumnBackedReferenceField` rejects on its own account. Lifting the nested-depth gate
   should move those cases onto the `NodeIdEncodeKeys` deferral message, not onto "no error"; the
   clean-admit case needs a new `Direct`-compaction fixture alongside them. Call this out in the
   diff so it is visible in review.
6. **Census bookkeeping.** `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`
   enforces an exhaustive disjoint partition over `TypeFetcherGenerator.IMPLEMENTED_LEAVES`,
   `STUBBED_VARIANTS.keySet()`, `NOT_DISPATCHED_LEAVES`, and the derived projected bucket. A reading
   taken while filing this item: `ColumnBackedReferenceField` is in
   `ProjectionCommands.CONTRIBUTION_MINTING_LEAVES` and absent from `IMPLEMENTED_LEAVES`, so it lands
   in the derived projected bucket, exactly where the already-admitted `TableField` sits;
   `ComputedField` is in both sets, so it is one of the dual-arm kinds the census pins explicitly.
   Neither set is keyed on nested-depth wireability, so admitting these leaves under `NestingField`
   should move no census entry. Verify that rather than trusting it.

