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

## Fix direction (for Spec)

Consolidate onto one resolver (`ClassAccessorResolver`, the complete implementation) and have the classification-path callers ground backing classes and infer names through it rather than through the two loose `RecordBindingResolver` helpers and `FieldBuilder.collectAccessorMatches`. The real design work at Spec is deciding the **single correct rule set**, because the copies disagree on load-bearing points and naive unification changes behavior:

- Is `is<Name>` boolean-only (canonical) or accepted for any return type (the loose copies)?
- Does inference check return-type assignability and parameter shape, or match by name alone?
- Should `inferAccessorName`'s "return the bare name even if no method exists" fallback survive, or become a rejection?

Each answer is a small behavior change on some consumer; the Spec should enumerate which callers shift and pin the chosen semantics with pipeline-tier tests over the divergent class shapes (fluent+bean overload, non-boolean `is`, inherited accessor, missing-member inference).

## Relationship to other items

- **R180** (`record-parent-column-read-helper`, Spec) explicitly **defers this**: its Non-goals name "Carry resolved accessors through `SourceKey`" and state "a separate Backlog item should propose the lift" for pre-resolving a backing-class accessor at classification time instead of synthesizing names by convention. This item is that follow-up, broadened to the full four-way resolution divergence rather than only the row-key synthesis path.
- **R431** (`decompose-sourcekey`, Backlog) is the adjacent structural work through which a pre-resolved accessor handle would most naturally be threaded; sequencing is a Spec question.
- The audit also found a **fifth** implementation on the generator↔LSP axis: `CatalogBuilder.beanAccessorSlot` (LSP), which resolves members under yet narrower rules and raises editor diagnostics from its narrower view (audit finding M19, generator-vs-LSP drift). That is a separate item (the LSP must not re-implement generator classification); this item is scoped to the four generator-internal implementations, but a unified resolver here is the thing the LSP should eventually consume.

Confirmed medium severity by the architecture-trap audit (adversarially verified; the four implementations and their diverging candidate order, is-prefix gating, and assignability rules re-confirmed against current code).
