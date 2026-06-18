---
id: R327
title: "Field-relative input classification (retire findReturnTablesForInput; add @mutation(table:) and deprecate @table-on-input)"
status: Spec
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-18
last-updated: 2026-06-18
---

# Field-relative input classification (retire findReturnTablesForInput; add @mutation(table:) and deprecate @table-on-input)

Split out of R317 slice 4 (the classify-and-emit collapse), which deferred this as the one
non-byte-identical change so the collapse could stay a pure structural delta. R317 left the question
explicit: *"whether a shared input type needs a global (parent-independent) registry entry at all, or
is purely field-level, is the one input-specific question slice 4's implementation must answer."*
This item answers it: purely field-level.

> **The Backlog stub said "retire @table-on-input"; here is the migration target that makes it real.**
> Field-relative derivation covers `@table`-on-input's query role (the table comes from the field's
> return target), but a DML mutation whose return is a bare scalar
> (`deleteFilm(input: FilmInput): Boolean`) has no table-derivable target, so something must still name
> the table at the use site. R327 adds an **optional `table:` argument to `@mutation`** as that use-site
> source, which is the migration target for **deprecating `@table`-on-input**. With both halves in place
> the directive has a complete replacement story (query side: automatic field-relative derivation; DML
> side: `@mutation(table:)`), so `@table`-on-input is deprecated (warned) by this item; physically
> removing the directive location is the follow-on (see Out of scope). R327 also *retires* the
> `findReturnTablesForInput` aggregate, the `isUsedWithOverrideCondition` global scan, and
> `TableInputType`-the-registry-verdict.

## Problem

An input type's table-boundness is decided **globally**, at the wrong altitude. `TypeBuilder.buildInputType`
(`TypeBuilder.java:1333`) classifies an input as table-bound by, in order:

1. `@table` on the input → bound to the named table (`buildTableInputType`);
2. else `isUsedWithOverrideCondition(name)` (`:1582`) — a whole-schema scan for `@condition(override:true)`
   on any consuming field / arg / input-field → non-table;
3. else `findReturnTablesForInput(name)` (`:1545`) — a whole-schema aggregate that walks **every** object
   field accepting the input (excluding `@service` / `@tableMethod` / `@mutation`), collects the distinct
   `@table` of each consuming field's **return** type, and binds the input table-bound iff exactly one
   table results; bails to non-table on zero **or `> 1`**.

Whether an input is table-bound is a function of the **field's target table at the use site**, not a
global property of the input type. The aggregate is the textbook shape the project's "Generation-thinking"
principle names as under-specified: it re-derives, per input type, a fact (`this field's target table`)
that the field edge already resolves registry-free via `TypeBuilder.lookAheadVerdict` (`:354`). The
decision was never resolved, only its inputs aggregated, and aggregated at the wrong altitude.

Two concrete symptoms:

- **The `> 1` bail is a latent bug.** An input reused across two `@table`-returning fields silently
  classifies non-table *everywhere*, losing the table binding on both edges, where each edge has an
  unambiguous answer.
- **`@table`-on-input papers over the missing derivation.** Because the aggregate excludes `@mutation`
  fields, a mutation-only input gets its table *solely* from `@table`-on-input; the directive is doing
  the work the field-relative derivation should do for the common case.

The verdict is a registry entry — `TableInputType` (carrying `table()` + table-resolved `inputFields()`)
versus a non-table `InputType`. But **no consumer forks on that distinction differently**: `InputTypeGenerator`
emits the same SDL schema-type spec for both, and the record shape is table-agnostic. The table-bound
fact is consumed only at the **field resolver** level (query lookup bindings; DML SET/WHERE partition).
By the sub-taxonomy audit rule ("a variant no consumer forks on is a field, not a variant"),
`TableInputType`-the-registry-verdict is a collapse candidate, not a load-bearing type.

Three sites read the verdict, and they do not agree on *how*:

- `FieldBuilder` query-arg edge (`:966`) — via `lookAheadVerdict(typeName) instanceof TableInputType`.
- `FieldBuilder.resolveDmlWalkerInputArg` (`:3712`) — via `lookAheadVerdict`.
- `MutationInputResolver` (`:372`) — via **`ctx.types.get(argTypeName)`**, a direct in-progress-registry
  read that the R317 read-free-walk invariant wants converted regardless.

All three then turn the verdict into the edge-level `ArgumentRef.InputTypeArg.TableInputArg` that already
exists. The global verdict is a detour: the edge is where the table is known and where the answer is used.

## Goal

Classify the input arg **after** its field's target, deriving table-boundness from the target's table,
at the edge, with a single producer.

- **One edge producer of "what table does this arg bind against," from one ordered set of use-site
  sources.** Given the field's edge, the table resolves as: (1) the explicit `@mutation(table:)` argument
  if present (DML fields); else (2) the field's return-derivable table (`lookAheadVerdict` of the return:
  the `@table` return / carrier / payload-data-field `@table`); else (3) for a deprecated `@table`-on-input,
  that table, with a deprecation warning; else (4) no table → plain input (query) or a validate-time
  rejection (DML). The arg's input fields then resolve against that table via the existing
  `BuildContext.classifyInputField` (`:1374`), yielding a `TableInputArg`. Every source is a use-site
  fact feeding one producer; none is a competing global verdict.
- **Add `@mutation(table:)` and deprecate `@table`-on-input.** The optional `table:` argument on
  `@mutation` (`directives.graphqls:225`) is the use-site table source for DML fields whose return does
  not carry the table. With it in place, `@table`-on-input has a full migration target and is deprecated:
  its use emits a deprecation warning naming the replacement (field-relative derivation for query args,
  `@mutation(table:)` for DML), via the existing classify-time directive-warning channel
  (`emitDirectiveIgnoredWarning` / `BuildWarning`).
- **Retire the global machinery.** `findReturnTablesForInput`, `isUsedWithOverrideCondition` (override
  becomes the use-site flag already partly threaded as `enclosingOverride` at `FieldBuilder:1016`), and
  `TableInputType`-the-registry-verdict are deleted. Every input's global registry entry is uniformly the
  table-agnostic shape (`PojoInputType` / `JavaRecordInputType` / `JooqTableRecordInputType` /
  `JooqRecordInputType`). `buildInputType` collapses to what `buildNonTableInputType` is today.
- **Read-free, per R317.** `MutationInputResolver`'s `ctx.types.get` read is converted to the edge
  resolution, so no input-classification edge reads the in-progress registry.

## Design decisions

These are the resolved forks (a `principles-architect` pass on the draft, 2026-06-18, settled them).

- **Fully edge-level; `TableInputType`-the-verdict retired (the architect's arm (b)).** The alternative
  — keep a global `TableInputType` for the `@table`-on-input case and derive only the rest at the edge —
  reintroduces the two-producers-of-one-fact smell through the back door: the edge would branch on "is the
  global verdict already table-bound, or do I derive?". Since no emitter forks on the variant, keeping it
  alive only to short-circuit one input of the edge decision is the smell, not a saving. The per-edge
  reclassification cost this incurs (the same input type hit by N edges classifies N times) is the policy
  the **plain-input path already documents and accepts** (`argument-resolution.adoc` §Classification:
  "Classification is cheap; reclassification is simpler than caching"); arm (b) makes the `@table`-input
  path converge on that policy rather than forking it by input flavour.
- **The DML table override moves from the input type to the mutation directive (`@mutation(table:)`).**
  The original draft kept `@table`-on-input as an edge-consulted override because the bare-scalar-return
  DML case has no derivable target. The architect's open tension was that an override is still a second
  table source the edge must consult. Moving it onto `@mutation(table:)` resolves that: the override is now
  a use-site fact on the *same field* whose edge is doing the resolving, in the same ordered source list
  as the return-derived table, so it is unambiguously an input to the single producer, not a competing
  producer. `@table`-on-input is then a deprecated alias of the same use-site intent, not a structurally
  distinct override. This is the only fork combination where the directive survives R327 without
  reintroducing the two-producers-of-one-fact smell.
- **`@table`-on-input is deprecated, not removed, in this item.** Its uses keep working (resolving to the
  same table they do today) but emit a deprecation warning pointing at the migration target. Removal (the
  `INPUT_OBJECT` directive location, `buildTableInputType` for the `@table`-on-input case, and the
  warning) is the follow-on once consumers migrate. This keeps R327 a non-breaking change for existing
  schemas while making the replacement canonical.
- **Validator mirrors the new failure mode.** Field-relative derivation introduces a rejection the global
  model could not produce: a DML arg whose field has **no `@mutation(table:)` AND no return-derivable
  table AND no (deprecated) `@table`-on-input**. Per "the validator mirrors classifier invariants," this
  must fail at *validate* time as an `UnclassifiedField`, not as a runtime null-table `NoSuchElement` in
  the walker. It reuses the existing `DmlWalkerInputArgResolution.Rejected` arm (`FieldBuilder:3713-3734`);
  the Spec names it rather than leaving it implicit, with a rejection message naming `@mutation(table:)` as
  the fix. The rejection prose distinguishing real-`@table` inputs from promoted-plain inputs
  (`FieldBuilder:1351-1357`) is re-derived from the SDL view, since the registry verdict no longer records
  the distinction.

## The non-byte-identical surface and its gate

Two verdict shifts, each pinned, each at the tier that observes it:

1. **Input reused across `> 1` `@table`-returning field** — today non-table everywhere (aggregate bails on
   `> 1`); tomorrow table-bound *per field*, against each edge's own target. This is a **behaviour** change
   (the per-edge SQL differs and is now correct on both edges), so it earns an **execution-tier** test
   proving each edge generates correct, distinct SQL, asserted via the `ExecuteListener` structural-token
   approach (`argument-resolution.adoc` §Test assertions), never code-string body matching.
2. **Input used on both a `@table`-returning and a non-`@table`-returning field** — today table-bound
   everywhere (global `TableInputType` is read even on the non-table edge); tomorrow table-bound only on
   the edge whose target is a table. This is a **classification** shift observable in the classified model
   before emission, so it is pinned at the **pipeline tier**: assert the `ArgumentRef` each edge produces.

Both fixtures name the flipped input type explicitly so the intentional divergence is greppable (the
discipline `argument-resolution.adoc` uses for its legacy-divergence pins). Every existing single-table
input stays byte-identical across the `GraphitronSchemaBuilderTest` truth table and the sakila pipeline /
compile / execution tiers; that is the non-negotiable correctness gate.

## Out of scope

- **Physically removing `@table`-on-input.** R327 deprecates it (warning + migration target); deleting
  the `INPUT_OBJECT` directive location, the `buildTableInputType` `@table`-on-input branch, and the
  warning is the follow-on once consumer schemas migrate to `@mutation(table:)`. File that as a separate
  Backlog item when R327 lands.
- **LSP / diagnostic-surface changes** beyond the new `UnclassifiedField` rejection the validator mirror
  requires and the `@table`-on-input deprecation warning.
- **The broader input-model redesign** (input record shape, binding reflection) — untouched; R327 changes
  only where table-boundness is decided.

## Slicing

Each slice is gated on the truth table plus the sakila pipeline / compile / execution tiers; no slice is
structure-only (R317's anti-narrative rule). The variant retirement lands with the last consumer.

1. **Field-relative query-filter classification; retire the aggregate.** The query-arg edge
   (`FieldBuilder:966`) derives its table from the field's resolved target instead of reading the global
   `TableInputType`; `findReturnTablesForInput` and the `isUsedWithOverrideCondition` global scan are
   deleted (override becomes the edge flag). The two query-side verdict shifts land and are pinned (the
   reuse-across-tables execution test, the table-vs-non-table pipeline test). `TableInputType` still
   exists, now produced only for `@table`-on-input / DML inputs.
2. **Add `@mutation(table:)`.** Extend the directive (`directives.graphqls:225`) with the optional
   `table: String` argument and resolve it as the first DML table source ahead of return-derivation. Pin a
   pipeline + execution test on a bare-scalar-return DML field (`deleteFilm(input: FilmInput): Boolean`)
   that classifies and generates correct SQL via `@mutation(table:)` with no `@table` anywhere. Additive:
   no existing fixture sets the argument, so byte-identical.
3. **Field-relative DML inputs; retire `TableInputType`-the-verdict.** `resolveDmlWalkerInputArg` and
   `MutationInputResolver` derive the table from the ordered source list (`@mutation(table:)` → return
   shape → deprecated `@table`-on-input); the `MutationInputResolver` `ctx.types.get` registry read is
   converted. `buildInputType` collapses to the table-agnostic shape, `TableInputType` the variant is
   removed, and the validator mirror for the no-table-source case is added. Byte-identical for existing
   `@table`-on-input DML inputs (which now also emit the deprecation warning).
4. **Deprecate `@table`-on-input.** Emit the deprecation `BuildWarning` on use, naming the migration
   target; pin the warning's presence and text. (May fold into slice 3 if near-free.)
5. **Doc sweep.** Update `argument-resolution.adoc`, the directive reference, and any
   classification-pipeline prose to describe the edge-relative model and `@mutation(table:)`; confirm no
   doc names a retired symbol (`findReturnTablesForInput`, `TableInputType`, `isUsedWithOverrideCondition`).
