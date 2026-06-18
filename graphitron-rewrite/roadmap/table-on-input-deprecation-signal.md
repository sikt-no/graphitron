---
id: R332
title: "Mark @table on input types as deprecated (signal ahead of R97 removal)"
status: Backlog
bucket: cleanup
priority: 6
theme: model-cleanup
depends-on: []
created: 2026-06-18
last-updated: 2026-06-18
---

# Mark @table on input types as deprecated (signal ahead of R97 removal)

The `@table` directive is declared on three scopes
(`directives.graphqls:13`: `directive @table(name: String) on OBJECT |
INPUT_OBJECT | INTERFACE`). The `INPUT_OBJECT` scope is slated for removal: its
information is redundant with the consuming field's return-type table, and R97
owns the replacement (consumer-derived tables + `argMapping` grouping) and the
eventual scope-narrowing to `OBJECT | INTERFACE`. But R97 is a large
architecture item gated behind R94, so the removal is some distance out, and
there is no user-facing signal today telling consumers that `@table`-on-input is
on its way out. This item is the **deprecation announcement only**: surface, now
and cheaply, that `@table` on an input type is deprecated, so consumers stop
adding new usages ahead of R97's removal. It deliberately does **not** change
classification behavior (that is R97's job); it makes the existing behavior
loudly deprecated.

## Origin and the R315 note

R315 (Done, `0bb7161` + rework `0d4acca`; bind FK-reference `@nodeId` onto
jOOQ-record `@service` params) already took the first bite out of
`@table`-on-input, but only on one path. Its "convergence by rejection" decision
(D2) added a narrower `isTableRecord` reject arm in `InputBeanResolver`: a
`@table`-present record param now fails honestly with "drop `@table`; the service
owns the DML" instead of silently falling to the bean path's misleading "has no
fields matching" error. Crucially, R315 scoped that to the jOOQ-record `@service`
case and was explicit that it does **not** deprecate `@table` generally, that the
general deprecation is R97's, and that R315's targeted rejection is
forward-compatible with it. So today the picture is uneven: one path hard-rejects
`@table`-on-input, every other path silently accepts it, and nothing announces
the intended direction. This item closes that gap with a uniform deprecation
signal across all input-type usages.

## Why a separate item from R97

R97 couples the deprecation to its replacement mechanism: its Phase 2 build
warning ("`@table` on input is redundant; consumer-derived table resolution is
in effect") can only fire once consumer-derived resolution exists, which depends
on R97 Phase 1 + R94. A pure "deprecated, will be removed; see R97" signal needs
none of that machinery and can ship immediately. Shipping the announcement early
is the point: it tells consumers to stop adding new `@table`-on-input usages
while the replacement is still being built, and it shrinks the eventual R97
migration. R97 remains the owner of the actual removal, the consumer-derived
table resolution, and the `argMapping` grouping; this item is its signaling
precursor and should be folded into R97 (or retired) if R97 lands its Phase 2
warning first.

## Candidate signal surfaces (to be settled at Spec)

The detailed plan belongs at Spec; the rough shape is a deprecation signal in
some combination of:

- the `@table` directive description in `directives.graphqls` (note the
  `INPUT_OBJECT` scope as deprecated, point at R97);
- a build-time deprecation warning emitted when `@table` is seen on an
  `INPUT_OBJECT` (distinct from R315's hard reject on the narrow service-record
  path, and distinct from R97's "redundant; consumer-derived resolution in
  effect" warning that presumes the replacement is live);
- an LSP deprecation diagnostic / hint on `@table` applied to an input type;
- user-facing docs (`code-generation-triggers.adoc`, `docs/README.adoc`)
  marking `@table`-on-input as deprecated.

Spec should decide which of these constitute "marked as deprecated" for this
item versus what is left to R97's removal phases, and whether the build warning
is unconditional or suppressible.

## Out of scope

- The `OBJECT` and `INTERFACE` scopes of `@table`. Those carry load-bearing
  output-emit semantics (`TableType` / `TableInterfaceType`) with no
  consumer-derived equivalent, and are not being deprecated.
- Changing input classification or removing the directive scope. That is R97
  (Phases 2 and 3). This item only adds the deprecation signal over today's
  behavior.
- The `argMapping` grouping / consumer-derived table mechanism. Owned by R97.
