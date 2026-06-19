---
id: R337
title: "Input-side nesting-projection classification (NestingType mirror)"
status: Backlog
bucket: architecture
priority: 4
theme: model-cleanup
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# Input-side nesting-projection classification (NestingType mirror)

A directiveless SDL `input` type that is nested under a table-bound parent (a `@table` input, or a jOOQ-record `@service` param once R336 lands) is semantically a *projection of columns on the parent's table*: its fields resolve against the parent's `TableRef`, it has no table of its own and no Java backing. Today such a type classifies as `GraphitronType.PojoInputType` with `fqClassName = null`. That is a misnomer. `PojoInputType` is overloaded: with a non-null `fqClassName` it is a genuine POJO backing (a `@service` param typed as a plain Java class), but the `null`-backed branch in `TypeBuilder.buildNonTableInputType` (`TypeBuilder.java:1419-1421`) is a catch-all "no backing class could be reflected" bucket. A column-grouping projection is not a POJO; surfacing it as `PojoInput` in the model and in the LSP (hover + inlay) leaks a reflection fallback into what reads like a semantic category.

The output side already models this correctly. `GraphitronType.NestingType` (`GraphitronType.java:388-414`) is exactly "a directiveless SDL type embedded under a `@table`-bound parent … inherits the parent's `@table` and maps to the same database row; its fields are columns on the embedding table." It is assigned **only at the embedding edge** (when the field walk builds a `ChildField.NestingField`), because the type pass visiting a directiveless type standalone cannot know whether anything nests it. The input side has the field-level half of this already, `InputField.NestingField` built by `classifyInputField` (`BuildContext.java:1857-1899`), which resolves the nested fields against the same `resolvedTable`, but it never gives the nested *type* a matching classification, so the type falls to `PojoInput(null)`.

This item introduces the input mirror of `NestingType`, an input-side nesting-projection classification assigned at the embedding edge, so a nested grouping input surfaces honestly in the model, the `TypeClassification` projection, and the LSP. It is pre-existing and broader than any one consumption path: the same `PojoInput(null)` mislabel applies to nested groupings under `@table` inputs today and to the R336 jOOQ-record flatten once it lands, so the honest classification should land once and cover both edges.

## Scope sketch (firm up in Spec)

* Add the input nesting-projection variant to `GraphitronType` (under the `InputType` sealed parent, or alongside it) and decide its `HasInputRecordShape` participation. Note the embedding-edge assignment pattern from `GraphitronSchemaBuilder.registerNestingTypesIn` (the output `NestingType` precedent): leave the directiveless input unclassified in the type pass, assign the projection at the edge.
* Wire the assignment at both embedding edges: the `@table`-input `NestingField` path and the R336 jOOQ-record flatten path.
* Project it through `CatalogBuilder.projectTypeClassification` into a new `TypeClassification` variant, and render it in `LspClassificationLabels` + `DeclarationHovers` (R217: projection-record simple names are user-visible strings).
* Decide whether a still-unbound directiveless input (one that nests nothing and binds nothing, e.g. the `NO_CLASS` case and the R205 plain-filter case) stays `PojoInput(null)` or also moves off it. The narrow scope is just the *nesting projection*; the broader "split the `PojoInput(null)` catch-all" is a judgment call for Spec.
* Tests: classification cases in `GraphitronSchemaBuilderTest`, variant/projection coverage, LSP label coverage.

## Relations

Sibling to **R336** (the functional flatten of nested input fields in jOOQ-record `@service` params). R336 does **not** depend on this: its flatten reads the nested type's SDL fields directly, the way the `@table`-input path recurses inline, so codegen works regardless of the nested type's classification. This item is purely model/LSP honesty. Relate to **R171** (`input-like-type-sealed-parent`): if the input-like types gain a sealed parent, the nesting-projection variant should sit in that hierarchy.

**Out of scope:** the functional flatten (that is R336); changing how nested fields resolve to columns; the output-side `NestingType`.
