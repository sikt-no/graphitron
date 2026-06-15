---
id: R311
title: "Bind a jOOQ TableRecord @service input param: column-axis @field + @nodeId scalar-key decode"
status: Backlog
bucket: feature
priority: 5
theme: service
depends-on: []
created: 2026-06-15
last-updated: 2026-06-15
---

# Bind a jOOQ TableRecord @service input param: column-axis @field + @nodeId scalar-key decode

A top-level `@service` parameter whose Java type **is** a generated jOOQ `TableRecord`
(the `EndreUtdanningsspesifikasjonsstatusInput @table` -> `UtdanningsspesifikasjonsstatusRecord`
shape) is unsupported on the rewrite. Its SDL input fields name jOOQ **columns** via
`@field(name: "DATO_FRA")` and decode `@nodeId` ids into scalar key columns, but
`InputBeanResolver` only knows how to bind a consumer-authored bean/record by Java-member
name (R200, shipped). The live consumer error is a hard Author rejection:

    parameter 'inputs' ... bean class '...tables.records.UtdanningsspesifikasjonsstatusRecord'
    has no fields matching the SDL input type 'EndreUtdanningsspesifikasjonsstatusInput'

That message **misdiagnoses**: the cause is not "no matching fields", it is "this is a jOOQ
table record, bound on the wrong axis". The build correctly stops (no broken code is emitted),
but the shape needs real support, and on a coincidental property-name match the same path
silently partial-populates or throws an R150-family `ClassCastException` at execution instead
of rejecting.

**The seam, and a correction to R200's framing.** R200's spec claimed this case "never enters
`buildInputBean` at all" because `looksLikeBeanCandidate` rejects `org.jooq.*` classes. That
premise is false for *generated* records: `looksLikeBeanCandidate`
(`InputBeanResolver.java:637-646`) is package-based, and generated records live in the
consumer's `*.tables.records` package, so they pass the gate at `:171`, land on the JavaBean
arm, and reach `bindJavaBean` (which then comes up empty). The decisive classification seam is
in `ServiceCatalog`, not `InputBeanResolver`: `classifySourcesType` (`ServiceCatalog.java:794-832`)
already holds raw `org.jooq.TableRecord` and classifies the *child*-coordinate `List<TableRecord>`
case into `SourceKey.Wrap.TableRecord`, while the *root* coordinate deliberately falls through
to `InputBeanResolver` as `Direct` (the comment at `ServiceCatalog.java:260-267` names this the
"canonical InputBeanResolver shape"). The structural detector `isJooqRecord`
(`InputBeanResolver.java:559-566`) already exists and is wired on the R195 member-leaf path
(`bindField`, `:464`); the root-param path never got it.

**Direction (chosen: support it).** Classify the top-level jOOQ-record param at the
`ServiceCatalog` parse boundary into a dedicated `ParamSource` / `CallSiteExtraction` variant
(sibling to the child `SourceKey.Wrap.TableRecord` arm it already produces), carrying the record
`ClassName`, so `InputBeanResolver` never sees this param as a bean candidate (per "Classification
belongs at the parse boundary"). Bind its columns on the column axis via `@field(name:)` (the
R97 axis read, but at the param position), and decode `@nodeId` fields into scalar key columns by
lifting R195's `NodeIdDecodeRecord` / `buildJooqRecordLeaf` machinery (`:506-549`, including the
record-type-mismatch gate at `:537-545`) from the **member** position to the **param** position.
Mirror the new classifier branch with a validate-time rejection (per "Validator mirrors classifier
invariants"), replacing today's misdiagnosing "has no fields matching" outcome with an honest one.

**Scope boundary (disjoint producer seams, so this is a new item rather than folded into R97).**
R97 (`consumer-derived-input-tables.md`) owns `@table`-on-the-input-*type* -> `TableInputType`
(the `TypeBuilder` column-deprecation axis); none of its consumers is `ServiceCatalog` or
`InputBeanResolver`, and its redesign produces column bindings, not `NodeIdDecodeRecord`-on-a-
record-param, so it neither subsumes nor closes this case. R195 (shipped) handled jOOQ-record
`@nodeId` decode for *members*; this is that mechanism lifted to the *param* position, plus the
column-axis read. R195's changelog deferred exactly this ("top-level `@service` param is a jOOQ
record ... deferred, tangled with R97"); the deferral rationale ("gated out anyway") is now void.
This item owns only the param-IS-a-jOOQ-record half: `ServiceCatalog` root classification, the
`@field`-names-a-column read at the param position, and the `@nodeId` scalar-key decode at the
param position. Filed off the R200 In Review -> Done review (2026-06-15), which surfaced the false
`looksLikeBeanCandidate` gating premise.
