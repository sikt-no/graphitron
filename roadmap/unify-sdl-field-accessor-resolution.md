---
id: R461
title: "Unify the four divergent SDL-field-to-Java-accessor resolution implementations behind one resolver"
status: Backlog
bucket: structural
priority: 4
theme: classification-model
depends-on: []
created: 2026-07-10
last-updated: 2026-07-10
---

# Unify the four divergent SDL-field-to-Java-accessor resolution implementations behind one resolver

## Problem

Resolving an SDL field name to a Java accessor on a backing class (bare `name()`, `get<Name>()`, `is<Name>()`, or a public field) is implemented **four times** with rules that disagree. The classification walk grounds a child field's backing class using one set of rules while emission later resolves the same accessor under another, so the type validated at build time and the member invoked at runtime can differ. A fix applied to one copy silently does not propagate to the others.

The four implementations and how they diverge:

- **`ClassAccessorResolver.resolve`** (`graphitron/src/main/java/no/sikt/graphitron/rewrite/ClassAccessorResolver.java:83`) — the canonical, most complete one. Candidate order is a parameter (`CandidateOrder.RECORD_FIRST` = bare → get → is; otherwise get → is → bare); the `is<Name>` candidate is offered **only when the expected return is boolean**; it checks both parameter shape (`paramsMatch`) and return-type **assignability** (`isAssignable`), emits per-candidate rejection diagnostics, and returns typed `AccessorResolution` arms (`BareName` / `GetterPrefixed` / `FieldRead`). Public-field fallback fires only when the SDL field has no arguments.
- **`RecordBindingResolver.findAccessorReturnType`** (`RecordBindingResolver.java:922`) — fixed order bare → get → is; accepts `is<Name>` for **any** return type (not boolean-gated); performs **no return-type assignability check**; accepts either a zero-arg method or one taking a single `DataFetchingEnvironment`; field fallback regardless of SDL args.
- **`RecordBindingResolver.inferAccessorName`** (`RecordBindingResolver.java:951`) — fixed order bare → get → is; matches **any non-static method by name** with no parameter-shape or return check, `is` ungated; falls back to returning the bare field name even when **no such method exists**.
- **`FieldBuilder.collectAccessorMatches`** (`FieldBuilder.java:6239`) — a fourth candidate-collection variant on the classification path.

`findAccessorReturnType` and `inferAccessorName` feed `propagateResultChildren` (`RecordBindingResolver.java:710-738`), which grounds child backing classes; emission then resolves through `ClassAccessorResolver` under the stricter rules.

## Failure scenario

A POJO parent exposes both a fluent `film()` and a bean `getFilm()` with different return types: the binding walk grounds the child type from the bare accessor (bare-first, always) while `POJO_FIRST` emission resolves the getter, so the child type's fields are validated against one class while the runtime fetcher receives instances of the other, surfacing as nulls, `ClassCastException`, or "no accessor matched" rejections naming a class the author never referenced. Symmetrically, `isActive()` returning `String` satisfies the loose inference probe (is-ungated, assignability-blind) but is invisible to the canonical resolver (is-gated to boolean), so classification and emission disagree about whether the field resolves at all. A sharp runtime break needs an unusual class shape, but drift (a rule fix landing in one copy) and confusing rejections are routine.

## Design: one candidate model, two reductions

The four copies answer two genuinely different questions, and the unification has to respect that or it changes semantics silently:

1. **Property read** (emission-side classification and expectation checks): "which member does this SDL field read, given an expected return type and argument shape?" First match wins in candidate order; the order encodes a naming-convention preference, not a data choice. Today: `ClassAccessorResolver.resolve` and its callers (`resolveRecordAccessor`, `probeErrorsAccessor`, `HandlerAccessorCheck`, `checkErrorTypeSourceAccessors`).
2. **Record-source derivation** (class-backed-parent table-bound fields): "which zero-arg member yields the child rows (TableRecords of table T) for this field?" Collect all matches; more than one is an author-facing ambiguity, because two accessors returning the same table's records can denote *different row sets*, so an order-based silent pick is a wrong-data hazard rather than a convention preference. Today: `FieldBuilder.collectAccessorMatches` and its two reducers (`deriveAccessorRecordParentSource`, `derivePolymorphicHubSource`).

The two loose `RecordBindingResolver` helpers are degenerate copies of question 1 in the discovery direction (the return type is the output, not an input). Notably, `inferAccessorName`'s result flows only into `ProducerBinding.ParentAccessor.accessorName`, which is consumed solely by `describe()` (diagnostic text); its looseness is a diagnostics defect, not a live wrong-code path. `findAccessorReturnType` is the load-bearing one: it grounds child backing classes on both the result and input axes.

Consolidation:

- **`ClassAccessorResolver` remains the single home** and grows a shared **candidate enumeration**: given `(backingClass, accessorBaseName, order)`, the ordered stream of name-candidate members under the unified rule set below. The name rules, the `is`-gate, and the member filter live only here.
- **`resolve(...)` keeps its signature and reduction** (first name+shape+return match), now consuming the shared enumeration. It thereby picks up the member filters `collectAccessorMatches` already applies and `resolve` today lacks: skip bridge and synthetic methods and members declared by `Object`. (A bridge method's erased return type can currently win the name match in `resolve`; that is a latent bug in the canonical copy, not just cross-copy drift.)
- **A new probe entry point** covers the discovery direction, replacing both `findAccessorReturnType` and `inferAccessorName`: same candidate enumeration and parameter-shape rule, no expected-return input, returns a typed result carrying the resolved `Method` / `Field`, its real member name, and its generic return type. `propagateResultChildren` / `propagateInputChildren` (`RecordBindingResolver.java:731/766`) call it once per field and use the single result for both the child-class grounding and `ParentAccessor.accessorName`. The two `RecordBindingResolver` helpers are deleted.
- **`collectAccessorMatches` keeps its reduction** (collect all, then `Ambiguous` / `CardinalityMismatch`) and its table-identity and cardinality classification; those are jOOQ-catalog concerns and stay in `FieldBuilder`. Its per-member name matching and filtering move onto the shared candidate enumeration so the name rules cannot drift. The item title says "one resolver"; the accurate statement is one candidate model with two reductions, and the ambiguity reduction is deliberately preserved.

### Candidate order at walk time

`resolve` callers derive `CandidateOrder` from the parent's `ResultType` variant (`JavaRecordType` gives `RECORD_FIRST`, otherwise `POJO_FIRST`). The binding walk runs before any `ResultType` exists, so the probe cannot be handed the variant. It derives the order from `parentClass.isRecord()`, which is provably the same fork: `TypeBuilder.buildResultTypeFromClass` (`TypeBuilder.java:1460`) produces `JavaRecordType` exactly when `cls.isRecord()`. The probe's javadoc records this equivalence; `resolve` itself keeps not introspecting `isRecord()`, so on the emission side the type variant stays the single source of truth per the existing `CandidateOrder` javadoc.

## Unified rule set

Answers to the three questions the Backlog body left open, plus two the investigation surfaced:

- **`is<Name>` is boolean-only, everywhere.** A method named `is<Name>` is a candidate only when its return type is `boolean` / `Boolean`. This matches graphql-java's `PropertyDataFetcher` (the convention `resolve`'s javadoc already pins) and closes the classification/emission split for non-boolean `is` members. Consequence for the record-source question: an `is<Name>` accessor returning TableRecords stops matching (behavior change B4); the author renames it to `<name>` / `get<Name>`.
- **Shape is always checked; assignability is checked where an expectation exists.** Every candidate must pass the parameter-shape rule (per-argument assignable parameters, or a single `DataFetchingEnvironment`); the expectation-checked direction additionally requires return-type assignability. Name-only matching (the `inferAccessorName` rule) does not survive.
- **The missing-member fallback dies.** `inferAccessorName`'s "return the bare name even when no member exists" arm is deleted. The probe returns a typed no-match result and the walk creates no observation, which is what `findAccessorReturnType == null` already causes today; the only observable change is that `ParentAccessor.describe()` now always names a real member.
- **Member filter:** public, non-static, non-bridge, non-synthetic, not declared by `Object`. Public-field fallback only when the SDL field has no arguments.
- **The probe uses the SDL field's real argument shape and the directive-resolved base name.** Today the walk probes only zero-arg / single-`DataFetchingEnvironment` members under the raw SDL field name, while emission resolves under `@field(name:)` with the full per-argument shape. Both inputs unify on the emission side's values (behavior changes B2, B3).

## Behavior changes

Each is deliberate and gets a pipeline-tier fixture:

- **B1, walk candidate order.** Grounding was always bare-first; it becomes variant-dependent (getter-first for POJO parents). A POJO parent with `film(): X` and `getFilm(): Y` grounds the child from `Y`, which is what emission resolves. This is the headline fix for the failure scenario above.
- **B2, fields with arguments.** An SDL field with arguments whose parent member is zero-arg no longer grounds through it (the shape rule requires matching arity); conversely, a member taking per-argument parameters can now ground a binding the old probe missed.
- **B3, `@field(name:)`.** The walk probes the directive-resolved accessor base name rather than the raw SDL field name.
- **B4, non-boolean `is`.** `is<Name>` members with non-boolean returns stop grounding bindings and stop matching record-source derivation.
- **B5, public-field fallback.** Walk-side field fallback now applies only to no-argument SDL fields; previously unconditional.
- **B6, bridge/synthetic in `resolve`.** The canonical resolver stops matching bridge, synthetic, and `Object`-declared members. Latent-bug fix; covariant-return hierarchies are the observable case.
- **B7, diagnostics.** `ParentAccessor.accessorName` always names the actually-resolved member (was: convention-synthesised, possibly nonexistent). Diagnostics-only; the one `GraphitronSchemaBuilderTest` assertion touching it moves with the rule.

Not a behavior change: `collectAccessorMatches`' ambiguity rejection stays. Bare name and getter both matching on the record-source question remains `Ambiguous` with the `@sourceRow` disambiguation hint, per the two-reductions rationale above.

## Tests

Pipeline tier (fixture schema plus backing classes, asserting classification verdicts and the emitted accessor choice), per the tier rules in `docs/architecture/explanation/development-principles.adoc`:

- fluent + bean overload on a POJO parent (B1) and on a Java-record parent (order per variant)
- SDL field with arguments against a zero-arg member and against a per-argument member (B2)
- `@field(name:)` rename grounding through the renamed accessor (B3)
- non-boolean `is<Name>` member: no grounding, and the property-read rejection names the boolean gate (B4)
- public-field fallback with and without SDL arguments (B5)
- a covariant-return hierarchy producing a bridge method (B6)
- an inherited accessor (declared on a superclass: still matched; `Object` members: never matched)
- record-source ambiguity (bare + getter both returning the expected table's records) still rejects with the `@sourceRow` hint

Unit tier: the candidate enumeration's order and filter table driven directly against small synthetic classes; it is a pure reflection function and an ideal unit surface.

## Non-goals

- The LSP's `CatalogBuilder.beanAccessorSlot` (audit finding M19) stays as-is; a follow-up item should route the LSP through the unified resolver rather than re-implementing it. This item creates the thing the LSP can consume.
- Input-side record *mapping* emission (setter / wither conventions, `InputBeanResolver`) is untouched; only the input-axis binding walk's probe migrates.
- No `SourceKey` shape changes, and no carrying of resolved accessor handles from classification into emission. This item makes all four call sites agree on rules; deduplicating the resolve calls across phases (the handle-threading lift R180 deferred) belongs with or after R431's decomposition, where the handle has a natural home. Keeping that out makes this item dependency-free.

## Relationship to other items

- **R180** (`record-parent-column-read-helper`, Spec) explicitly **defers this**: its Non-goals name "Carry resolved accessors through `SourceKey`" and state "a separate Backlog item should propose the lift" for pre-resolving a backing-class accessor at classification time instead of synthesizing names by convention. This item is that follow-up, broadened to the full four-way resolution divergence rather than only the row-key synthesis path. The handle-threading half of the lift is explicitly re-deferred (see Non-goals); what this item delivers is the rule unification that makes any later threading carry one truth instead of four.
- **R431** (`decompose-sourcekey`, Backlog) is the adjacent structural work through which a pre-resolved accessor handle would most naturally be threaded. Sequencing answer: independent. This item changes no `SourceKey` shape, so it neither blocks nor is blocked by R431; the handle threading lands with or after R431.
- The audit also found a **fifth** implementation on the generator↔LSP axis: `CatalogBuilder.beanAccessorSlot` (LSP), which resolves members under yet narrower rules and raises editor diagnostics from its narrower view (audit finding M19, generator-vs-LSP drift). That is a separate item (the LSP must not re-implement generator classification); this item is scoped to the four generator-internal implementations, but a unified resolver here is the thing the LSP should eventually consume.

Confirmed medium severity by the architecture-trap audit (adversarially verified; the four implementations and their diverging candidate order, is-prefix gating, and assignability rules re-confirmed against current code).
