---
id: R243
title: "Per-field direction in @order/@defaultOrder via FieldSort.direction"
status: Spec
bucket: feature
depends-on: []
created: 2026-05-26
last-updated: 2026-05-26
---

# Per-field direction in @order/@defaultOrder via FieldSort.direction

> Add `direction: SortDirection` to the `FieldSort` input so a single `@order` or
> `@defaultOrder` spec can express heterogeneous ordering
> (e.g. `[{name: "year", direction: DESC}, {name: "key", direction: ASC}]`).
> Lift `OrderBySpec.Fixed.direction: String` (whole-spec scalar) down onto
> `ColumnOrderEntry.direction: SortDirection` (per-entry typed enum). The
> resolver pushes the directive-level `direction:` default (or the implicit ASC
> on `@order`) down per-entry at build time; per-entry direction wins. Schema
> change is purely additive; no consumer migration.

This is a surface-only widening: the runtime emission pattern, the
`@orderBy` argument plumbing, and the helper-method shape stay where they are.
The work is one model rewrite, two resolver edits, two emitter call-site edits.

---

## Motivation

`FieldSort` (`directives.graphqls:263`) currently has no `direction:` surface,
and `OrderBySpec.Fixed` (`OrderBySpec.java:60`) carries `direction: String` at
the spec level — every entry shares one direction. `@defaultOrder` has a
single top-level `direction: SortDirection = ASC` that paints every entry; `@order`
(ENUM_VALUE) has no direction surface at all and
`OrderByResolver.resolveEnumValueOrderSpec` (`OrderByResolver.java:191`)
hardcodes `"ASC"` on every fixed-spec it emits.

A schema with heterogeneous fixed ordering — recent-year first then natural
key within the year, `ARSTALL DESC, SORTERINGSNOKKEL ASC` — has no way to say
this today. The fallback is a runtime `@orderBy` input, which is the wrong
tool: the ordering is fixed by the field's contract, not chosen by the client.

The underlying jOOQ emission already supports the call (`col.desc()` /
`col.asc()` per column); the gap is purely how the spec is *modelled* and
*authored*.

---

## Design

### Schema surface

Add `direction: SortDirection` to `FieldSort`:

```graphql
input FieldSort {
  """Database field name (as defined in the jOOQ table)"""
  name: String!
  """Collation to apply (e.g., "xdanish_ai"). Database-specific."""
  collate: String
  """
  Sort direction for this entry. Absent → falls back to the directive-level
  `direction:` argument on `@defaultOrder` (default ASC), or to ASC on `@order`
  (which has no directive-level direction surface).
  """
  direction: SortDirection
}
```

No new surface on `@order` itself — the directive remains:

```graphql
directive @order(
  index: String
  fields: [FieldSort!]
  primaryKey: Boolean = false
) on ENUM_VALUE
```

`@defaultOrder` keeps its existing top-level `direction: SortDirection = ASC`
as the per-spec default that resolution pushes down onto entries that omit
their own direction.

### Model: lift direction onto the entry and type it

Today:

```java
record ColumnOrderEntry(ColumnRef column, String collation) {}
record Fixed(List<ColumnOrderEntry> columns, String direction) implements OrderBySpec {
    public String jooqMethodName() { return "ASC".equalsIgnoreCase(direction) ? "asc" : "desc"; }
}
```

After:

```java
/** Per-entry direction, typed. Decoupled from the SDL `SortDirection` enum
 *  on purpose: this is the resolved truth the emitter consumes, not the
 *  directive-argument value the resolver reads. */
public enum SortDirection {
    ASC, DESC;

    /** jOOQ sort-direction method name: "asc" or "desc". */
    public String jooqMethodName() { return this == ASC ? "asc" : "desc"; }

    /** Sibling direction. Used by ASC-uniform `Fixed` emission to flip a
     *  whole spec when the runtime `@orderBy` direction arg is `DESC`. */
    public SortDirection flipped() { return this == ASC ? DESC : ASC; }
}

record ColumnOrderEntry(ColumnRef column, String collation, SortDirection direction) {}

record Fixed(
    List<ColumnOrderEntry> columns,
    /** True iff every entry carries `SortDirection.ASC`. Computed once at
     *  resolution time; consumed by the `@orderBy` helper emitter to decide
     *  whether the runtime direction arg flips the whole spec (uniform-ASC
     *  case, today's only case) or is ignored because the spec is
     *  direction-locked (any non-ASC entry). See "Runtime direction
     *  interaction" below. Harmless when this `Fixed` is consumed outside
     *  the `@orderBy` helper path (`@defaultOrder` standalone, PK fallback). */
    boolean uniformAsc
) implements OrderBySpec {}
```

Three deliberate decisions, each citing a principle:

1. **`direction` lives on the entry, not on `Fixed`.** Per
   *Generation-thinking* (`rewrite-design-principles.adoc`): the model carries
   the resolved truth the emitter dispatches on, not the directive-level surface
   the resolver read. Once per-entry direction is expressible in the SDL, the
   "shared direction" of `Fixed` is a resolution-time computation, not a
   property of the resolved spec.
2. **`SortDirection` is an enum, not a `String`.** Per *Sealed hierarchies over
   enums for typed information* and the cited adjacency in
   `rewrite-design-principles.adoc`. The directive layer already gives a closed
   `SortDirection {ASC, DESC}`; the existing `Fixed.direction: String` was a
   pre-resolution shape that has been re-interpreted at every emission site
   via `"ASC".equalsIgnoreCase(direction) ? "asc" : "desc"`. R243 is the moment
   we lift; the `jooqMethodName()` algebra moves onto the enum so it has one
   home.
3. **`uniformAsc: boolean` is precomputed on `Fixed`.** Per *Classifier
   guarantees shape emitter assumptions*. The emitter needs to dispatch on the
   homogeneous-vs-mixed case (see "Runtime direction interaction" below).
   Rather than recompute `cols.stream().allMatch(c -> c.direction() == ASC)`
   at each emitter site, the resolver computes it once and stamps it on the
   record. Trivial-but-load-bearing, so it lives at the classifier site, not
   replicated across emitters.

### Resolver: push directive-level direction down per-entry

`resolveColumnOrderSpec` (`OrderByResolver.java:146`) currently reads
`@defaultOrder`'s `direction:` argument, then constructs `Fixed(entries,
direction)`. After R243 it:

1. Reads the directive-level `direction:` (`ASC` if absent) once.
2. For each entry returned by `resolveOrderEntries`, applies the per-entry
   `FieldSort.direction:` if present; otherwise inherits the directive-level
   value. Both come from the SDL directive value as the `EnumValue` /
   `String` shape; both project onto the model `SortDirection` enum.
3. Computes `uniformAsc = entries.stream().allMatch(e -> e.direction() == ASC)`.
4. Returns `new Fixed(entries, uniformAsc)`.

`resolveOrderEntries` (`OrderByResolver.java:216`) widens to project the
`direction:` map key from each `FieldSort` value, falling back to a
caller-supplied default (the directive-level direction). The `index:` and
`primaryKey:` branches stamp `ASC` on every entry they synthesise (since
those variants don't take per-field direction; see fork (b) below).

`resolveEnumValueOrderSpec` (`OrderByResolver.java:170`) deletes the
hardcoded `"ASC"` at line 191 and instead routes through the same
`resolveOrderEntries` path with default `ASC` (since `@order` has no
directive-level direction surface). The `uniformAsc` flag falls out of the
constructor.

The PK-fallback construction in `resolveDefaultOrderSpec`
(`OrderByResolver.java:133`) explicitly stamps `SortDirection.ASC` on each
synthesised entry and sets `uniformAsc = true`. Per the
*Classifier guarantees shape emitter assumptions* audit the
principles-architect flagged: every `Fixed` producer in the codebase post-R243
must populate per-entry direction *and* `uniformAsc` — there is no implicit
"ASC by default" anywhere in the model after R243.

### Emitter: per-entry direction at every fixed-spec call site

Four call sites consume `Fixed.jooqMethodName()` today:

- `TypeFetcherGenerator.java:3197` and `:3241` (`buildPageRequestSetupCode` /
  `buildOrderByCode` for the `Fixed` arm)
- `TypeFetcherGenerator.java:3321` (the named-order emission inside the
  helper, single-arg shape)
- `InlineTableFieldEmitter.java:163`
- `SplitRowsMethodEmitter.java:949`

Each currently emits:

```
$L.$L.$L()  // alias.JAVANAME.<asc|desc>()
```

with `<asc|desc>` from `fixed.jooqMethodName()`. After R243 each emits per-entry:

```
$L.$L.$L()  // alias.JAVANAME.<col.direction().jooqMethodName()>()
```

This is mechanical — `fixed.jooqMethodName()` → `col.direction().jooqMethodName()`.

### Runtime direction interaction (the `@orderBy` helper)

The helper emitted by `buildSingleArgOrderByBody` (`:3346`) /
`buildListArgOrderByBody` (`:3395`) reads a user-supplied direction value
(`dir`) from the `@orderBy` input and currently dispatches per column:

```java
"DESC".equals(dir) ? col.desc() : col.<base>()
```

where `<base>` is always `asc` today (because every named order
hardcodes ASC at line 191). After R243 the named order may carry
heterogeneous per-column directions, and the question is what the user-supplied
`dir` does relative to that.

**Settled semantics: ASC-uniform → user `dir` flips; mixed → direction-locked.**
The dispatch is on the `Fixed.uniformAsc` flag computed at resolution time:

```java
// Pseudocode for the per-column emit inside the named-order switch arm
if (namedOrder.order().uniformAsc()) {
    // Today's behavior, unchanged.
    emit: "DESC".equals(dir) ? col.desc() : col.asc()
} else {
    // Direction-locked: the SDL author baked in per-column directions;
    // honour them verbatim, ignore the runtime dir argument for this arm.
    emit: col.<entry.direction().jooqMethodName()>()
}
```

This is the principles-architect's "opt-out" alternative, chosen over the
multiplier semantics that the Backlog body left open. Two arguments,
referenced to the principles doc:

1. *Stability through simplicity* (`graphitron-principles.adoc`). The
   multiplier rule would make the user's `DESC` argument do different things
   depending on the parity of `DESC` flags inside whichever enum value the
   user picked: `dir: DESC` on `{year DESC, key ASC}` would emit
   `year ASC, key DESC`, which is genuinely surprising. The SDL author wrote
   `{year DESC, key ASC}` because that *is* the answer; the runtime arg
   should not transmute it.
2. *Generation-thinking*. Under "opt-out" the emitter for the mixed case is
   pure dispatch on per-entry direction — no per-column flip table, no
   `flipped()` calls at the emission site. `SortDirection.flipped()` survives
   on the enum (the runtime-flip helper at
   `ConnectionHelperClassGenerator.java:100-117` for backward pagination
   already exists and is unrelated; it operates on jOOQ `SortField` at
   runtime, not on model `SortDirection` at build time), but the build-time
   emitter does not call it.

The result is also pragmatically conservative: today's call surface
(`fields: [{name: "x"}]`-style entries with no per-field direction) keeps
`uniformAsc = true` and falls through the existing code path verbatim. The
mixed case is the only new emission shape, and it's the simplest possible
shape (no conditional on `dir`, just per-column emission).

### Three settled forks

These match the open forks named in the Backlog body; each is settled here
so the implementer doesn't relitigate.

#### (a) `@order` gains a sibling top-level `direction:`? — *No*

Per *Directives carry only what the SDL author needs to say*
(`rewrite-design-principles.adoc`). A top-level `direction:` on `@order` would
be a second way to say what `FieldSort.direction:` already says. Each enum
value's `@order` is already a discrete spec; adding a top-level direction
introduces a cross-product of failure modes ("directive-level DESC + per-field
ASC on every entry — is that locked or flippable?") for no expressive gain.
Per-field-only is the call.

#### (b) `index:` / `primaryKey:` validator surface — *None new*

`FieldSort.direction:` is structurally inaccessible from the `index:` and
`primaryKey:` variants because those don't take `FieldSort` values; `FieldSort`
is only the element type of `fields:`. No new rejection logic needed in R181
(`validate-order-directive-args.md`). The PK-fallback and named-index
construction paths in the resolver stamp `SortDirection.ASC` explicitly on every
entry they synthesise (Resolver change above) and set `uniformAsc = true`.
This is documented as a non-issue rather than enforced; it falls out of the
schema's own grammar.

#### (c) Redundant directive-level `direction:` mirroring per-entry — *Accept silently*

A schema with `@defaultOrder(direction: DESC, fields: [{name: "x", direction: DESC}])`
is the per-field-wins rule's no-op case (entry direction matches the inherited
default). A schema with `@defaultOrder(direction: DESC, fields: [{name: "x", direction: ASC}])`
is the per-field-wins rule's override case (the point of the feature). Both
resolve to the same model shape (a `Fixed` whose entries carry their final
directions); both emit identical code. There is no semantic difference between
"the author was redundant" and "the author was deliberately explicit", so
warning would be noise. Accept silently.

---

## Implementation sites

A focused delta — three production files, one fixture schema, and tests.

- **`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/OrderBySpec.java`**:
  - New nested `enum SortDirection { ASC, DESC }` with `jooqMethodName()` and
    `flipped()`. (Nested for cohesion with the only consumers; the SDL-side
    `SortDirection` enum stays a separate, distinct namespace.)
  - `ColumnOrderEntry` widens to `(ColumnRef column, String collation, SortDirection direction)`.
  - `Fixed` becomes `(List<ColumnOrderEntry> columns, boolean uniformAsc)`;
    `direction: String` field and `jooqMethodName()` method delete.

- **`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/OrderByResolver.java`**:
  - `resolveColumnOrderSpec` reads directive-level `direction:` as the
    fallback default; passes it into `resolveOrderEntries`; computes
    `uniformAsc` from the resolved entries.
  - `resolveOrderEntries` projects `FieldSort.direction:` per entry (using
    `ARG_DIRECTION`, already in `BuildContext`); the `index:` / `primaryKey:`
    branches stamp `SortDirection.ASC` explicitly.
  - `resolveEnumValueOrderSpec` deletes the hardcoded `"ASC"` at line 191;
    passes `ASC` as the fallback default into the shared path.
  - `resolveDefaultOrderSpec`'s PK-fallback `Fixed` construction stamps
    `SortDirection.ASC` per entry and sets `uniformAsc = true`.

- **`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`**:
  - Four `fixed.jooqMethodName()` call sites (`:3197`, `:3241`, `:3321` named-order
    inner loop, plus the `buildPageRequestSetupCode` arm at `:3187`) switch to
    `col.direction().jooqMethodName()`.
  - The named-order emitter inside `buildSingleArgOrderByBody` (`:3346`) and
    `buildListArgOrderByBody` (`:3395`) dispatches on `namedOrder.order().uniformAsc()`:
    uniform-ASC keeps today's `"DESC".equals(dir) ? col.desc() : col.asc()`
    conditional; mixed emits `col.<direction>()` directly, no `dir`
    conditional.

- **`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/InlineTableFieldEmitter.java`** (`:163`),
  **`SplitRowsMethodEmitter.java`** (`:949`): mechanical `fixed.jooqMethodName()`
  → `col.direction().jooqMethodName()`.

- **`graphitron-rewrite/graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`**:
  Add `direction: SortDirection` to `FieldSort` (no default value; absence is
  the inherit-the-directive-default signal).

- **`graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`**:
  Add one demonstrative use of the new shape — a connection field with a
  `@defaultOrder(fields: [{name: "...", direction: DESC}, {name: "...", direction: ASC}])`
  spec, so the execution-tier test below exercises a real heterogeneous order
  in the running app.

- **Tests** (next section).

Existing test fixtures that construct `OrderBySpec.Fixed(...)` directly
(notably `TableFieldValidationTest`, `LookupTableFieldValidationTest`,
`QueryTableFieldValidationTest`, `RecordLookupTableFieldValidationTest`,
`QueryLookupTableFieldValidationTest`, `TypeFetcherGeneratorTest`, and
`DirectiveSupportReportTest`) need a mechanical update to the new
constructor shape: every `ColumnOrderEntry(col, collation)` site gains a
`SortDirection.ASC` argument, and every `Fixed(entries, "ASC")` site
becomes `Fixed(entries, true /* uniformAsc */)`. No behavioural change in
those tests; the constructor signature widens.

---

## Tests

Three tiers; the pipeline tier is primary per
*Pipeline tests are the primary behavioural tier*
(`rewrite-design-principles.adoc`).

### Unit-tier

- `OrderBySpecTest`: pin the algebra on `SortDirection`. `ASC.jooqMethodName()
  == "asc"`, `DESC.jooqMethodName() == "desc"`, `ASC.flipped() == DESC`,
  `DESC.flipped() == ASC`. Pins the type system can't.

### Pipeline-tier (primary)

- `PerFieldDirectionDefaultOrderResolutionTest`: an SDL fixture with
  `@defaultOrder(fields: [{name: "a"}, {name: "b", direction: DESC}, {name: "c"}])`
  on a list field. Run through `GraphitronSchemaBuilder`; assert the resulting
  `TableField.orderBy()` is `Fixed` with three entries
  `[(a, ASC), (b, DESC), (c, ASC)]` and `uniformAsc == false`.
- `PerFieldDirectionDirectiveLevelDefaultTest`: same fixture but with
  `@defaultOrder(direction: DESC, fields: [{name: "a"}, {name: "b", direction: ASC}])`.
  Assert entries are `[(a, DESC), (b, ASC)]` (directive-level DESC pushed
  down, per-field ASC overriding) and `uniformAsc == false`.
- `PerFieldDirectionEnumOrderResolutionTest`: an `@order` enum value with
  `fields: [{name: "year", direction: DESC}, {name: "key", direction: ASC}]`.
  Assert the named-order's `Fixed` has the expected per-column directions
  and `uniformAsc == false`.
- `UniformAscFallbackTest`: a schema with no per-field directions and no
  directive-level direction. Assert `uniformAsc == true` and every entry's
  direction is `ASC`. Pins the no-regression-on-existing-shape claim.

### Compilation-tier

- The existing `graphitron-sakila-example` compile against real jOOQ classes
  covers per-column `<col>.desc()` / `<col>.asc()` resolution. The new
  heterogeneous-spec example added to `schema.graphqls` ensures both
  branches compile.

### Execution-tier (the proof)

- `PerFieldDirectionExecutionTest`: query the connection field whose
  `@defaultOrder` carries the heterogeneous spec; assert the returned rows
  are ordered `(field-A DESC, field-B ASC)` against fixture data crafted so
  the difference is observable (i.e., the same field-A value paired with
  multiple field-B values).
- `MixedOrderEnumValueExecutionTest`: an `@order` enum value with
  per-field directions, exercised via `@orderBy`. Two sub-cases: client
  sends no `dir` (default), and client sends `dir: DESC`. Per the
  direction-locked rule, both should return the same heterogeneous order;
  asserting both pins the opt-out semantics against accidental future
  regression to multiplier semantics.

---

## Phasing

Single phase. The change is purely additive in the SDL (new optional
`direction:` on `FieldSort`), the model rewrite is mechanical, and the
emitter touches are local. No staged emission, no consumer migration, no
runtime contract surface change.

---

## Settled design notes

Surfaced during drafting so the implementer doesn't relitigate.

1. *Backwards-compatible by construction.* `FieldSort.direction:` is a new
   optional input field; existing schemas without it resolve to the
   `directive-level direction` (or ASC) per the resolver's fallback, which
   produces the same `Fixed` shape they had before (every entry's direction
   matches the old `Fixed.direction` scalar; `uniformAsc` reflects that).
   No consumer recompile required for behaviour preservation; only the
   internal `Fixed` / `ColumnOrderEntry` constructor signatures change, and
   those are internal to the rewrite generator.
2. *Why a nested enum, not a top-level type.* `SortDirection` lives inside
   `OrderBySpec` as a nested enum because every consumer of the typed
   direction is already in the OrderBySpec orbit (resolver, emitters reading
   `Fixed`). Pulling it to a top-level model type would put it on the
   reusability shelf, where nothing else needs it. Per *Narrow component
   types* the nested location is correct for the consumer set.
3. *`SortDirection.flipped()` retained.* It survives on the enum even though
   the build-time emitter doesn't call it post-R243 under the
   direction-locked rule. The single-place ASC↔DESC algebra is cheap to
   keep, and a future R243-adjacent shape (e.g. an explicit
   `@order(reverseAllowed: false)` toggle, or a strict-mode ASC/DESC
   compatibility check in R181) is the obvious consumer. If it turns out
   genuinely dead at code-review time, prune it.
4. *Direction-locked rule is a Spec-time decision, not a runtime check.*
   The decision lives at codegen time (the emitter dispatches on
   `Fixed.uniformAsc`), not as a runtime branch on `dir`. This is
   *Generation-thinking* applied: the resolver classifies the spec's shape
   once, the emitter emits one of two static code shapes per named-order
   arm, and the user-facing semantics are predictable from the SDL alone.

---

## Non-goals

- *Multi-axis runtime direction.* No support for "user supplies one direction
  per column at runtime". The schema author commits at SDL-author time to one
  of: uniform direction (runtime-flippable), or fixed heterogeneous order
  (runtime-locked). A list-shaped `@orderBy` argument carrying per-element
  directions is a different feature (the existing `buildListArgOrderByBody`
  path) and is unaffected.
- *Validator surface for "redundant `direction:` in `FieldSort` matching the
  directive-level default".* Fork (c) settles this as accept-silently.
- *Lifting `Fixed.direction: String` elsewhere in the codebase.* The rewrite
  has no other persistent `direction: String` shape; the lift is local to
  `OrderBySpec`.
