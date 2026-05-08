---
id: R110
title: "Replace @batchKeyLifter with @sourceRow composing with @reference"
status: Backlog
bucket: architecture
priority: 7
theme: service
depends-on: []
---

# Replace @batchKeyLifter with @sourceRow composing with @reference

`@batchKeyLifter` (`directives.graphqls:152-167`) bridges DTO parents with no jOOQ FK metadata to table-bound child fields by extracting a join-key tuple from the parent. The directive has not yet been adopted; before it does, three properties of its current design need fixing. First, the name reads as builder vocabulary (`BatchKey` + "lift"), not user vocabulary; schema authors don't think "lift a batch key," they think "the parent doesn't sit in a table; here's where the join key lives." Second, the directive does not compose with `@reference`: the lifter's `RowN` must equal the *leaf target table's* columns, leaving the schema author no way to express "lifter produces the source-side of the first hop, then `@reference` navigates the rest." A schema author who needs that today is forced to re-implement the FK chain inside the lifter method, which defeats the directive's whole purpose. Third, the `targetColumns` arg lets users pick any column subset on the target table and the resolver does not check that the picked columns form a unique key; arbitrary non-key columns silently produce wrong fan-out at runtime.

We replace the directive with `@sourceRow`, which (a) drops `targetColumns`, deriving the expected parent-side tuple from `@reference`'s first hop or the leaf target's PK; (b) composes with `@reference` so multi-hop paths from a DTO parent become expressible for the first time; and (c) renames to user-facing vocabulary that survives composition.

## User stories

The directive applies when the parent is a `@record` with a non-jOOQ backing class (POJO / Java record) and the schema author **cannot or will not modify the backing class**. Two stories carry the design:

**Story 1 — third-party generated DTO.** Schema author grafts a database-backed field onto a foreign DTO whose class is regenerated from an OpenAPI / Protobuf / JAXB schema.

```graphql
type CustomerSummary @record(record: {className: "com.billing.CustomerSummary"}) {
    customerId: Int!
    recentRentals: [Rental!]
        @sourceRow(className: "no.example.SourceRows", method: "customerIdOf")
        @reference(path: [{key: "RENTAL_CUSTOMER_ID_FKEY"}])
}
```

The `customer.customer_id` PK is what the DTO carries; the FK chain `customer → rental` lives in the catalog. The lifter (in the graphitron-using project, not in the billing module) returns `Row1<Integer>` matching `customer.customer_id`. Without `@sourceRow`, the only options are to rewrite `recentRentals` as a hand-rolled `@service` (loses pagination / order / filter codegen) or pre-fetch inside the billing call (over-fetches for clients that did not ask for `recentRentals`). Adding a typed accessor to `CustomerSummary` is structurally unavailable: the class is regenerated.

**Story 2 — shared cross-team DTO.** A platform module exposes `SubscriptionDTO` consumed across many services. Modifying it to expose a jOOQ-typed accessor would couple the platform module to jOOQ — non-starter for teams that do not use it.

```graphql
type Subscription @record(record: {className: "no.sikt.platform.SubscriptionDTO"}) {
    customerId: Int!
    invoices: [Invoice!]
        @sourceRow(className: "no.example.SourceRows", method: "customerIdOf")
        @reference(path: [{key: "INVOICE_CUSTOMER_ID_FKEY"}])
}
```

The lifter is a one-line bridge that calls `SubscriptionDTO.getCustomerId()`. The directive is exactly the lever that lets that bridge be schema-side, not DTO-side.

The shared property both stories test: **the join key already lives on the parent DTO somewhere; the path from "DTO instance" to "the value" must be expressible without modifying the DTO.**

A composite-key story (e.g. analytics DTO with `(year, region)` joined to `monthly_stats`) is held out of scope for this iteration but the design accepts it without modification (lifter returns `Row2<Integer, String>` against a 2-column first-hop key).

## Directive shape

```graphql
"""
Identifies the static Java method that extracts the parent-side join-key tuple
when the parent is a @record-backed DTO with no jOOQ FK metadata in the catalog.
The method takes the parent's backing class and returns Row1..Row22<...>.
The returned tuple must equal the columns the field's first JOIN ON predicate
consumes on the parent side: the first hop's source-side columns when @reference
is present, or the leaf target table's primary key columns when @reference is
absent.
"""
directive @sourceRow(
    """Fully-qualified Java class name carrying the static lifter method."""
    className: String!
    """Static method name; must take a single arg assignable from the parent's backing class and return Row1..Row22<...>."""
    method: String!
) on FIELD_DEFINITION
```

Flat `className` / `method` args, no `ExternalCodeReference` wrapper. The wrapper is used by older directives because of the `name:` legacy deprecation that R93 is migrating away (`directives.graphqls:251-252`); for a brand-new directive the wrapper carries no value, the flat shape is shorter at the use site, and there is no LSP migration to keep alive.

## Composition rule

The lifter's `RowN` must equal the columns the field's first JOIN ON predicate consumes on the parent side. Two derivations, no third case:

- **With `@reference`.** First-hop source-side columns. `BuildContext.parsePath` (`BuildContext.java:524-591`) already orients each `FkJoin` slot at synthesis time so `slot.sourceSide()` is the column on the source table of the hop. The expected tuple is `firstHop.sourceSideColumns()`.
- **Without `@reference`.** Leaf target table's PK columns from `JooqCatalog.primaryKeyColumns(...)`. Single degenerate hop, parent_values → leaf, no FK involved.

Both cases collapse to "the lifter's `RowN` matches the columns the JOIN's parent side reads from the parent_values CTE." The implicit-FK inference in `parsePath` — which today fires only when both endpoint tables are catalog-known — extends to fire whenever `@sourceRow` is present, because the lifter resolves the parent-side tuple's shape independently of catalog FK metadata.

## Resolver

`SourceRowDirectiveResolver` (renamed from `BatchKeyLifterDirectiveResolver`):

1. Validate parent shape: still POJO / JavaRecord with declared backing class. Same rejection messages as today, with the new directive name.
2. Reflect the lifter method, validate single arg assignable from parent class, validate return is `Row1..Row22<...>`. Logic from `BatchKeyLifterDirectiveResolver.java:212-300` survives unchanged except for the directive-name strings.
3. Parse `@reference` if present (call `BuildContext.parsePath` with the field's element type as the leaf target). Derive the expected parent-side column tuple:
   - `@reference` resolved to a non-empty path: `firstHop.sourceSideColumns()`.
   - `@reference` absent or empty: leaf target's PK columns.
   - `@reference` parse failure: surface the parse error directly; do not double-validate against the lifter.
4. Validate `RowN` arity and per-position erasure against the derived tuple. Two diagnostic messages distinguish the cases ("first-hop source-side columns of FK '..." vs. "primary key of '<leaf>'").
5. Construct `BatchKey.LifterRowKeyed`. The `joinPath` published to the classifier is the resolved `@reference` path (with `LifterRowKeyed.lifter` carrying the parent-side extraction) or `[LiftedHop]` for the no-`@reference` leaf-PK case.

The `targetColumns` arg is removed entirely. Existing fixtures using `targetColumns` must migrate; since the directive has not been adopted outside the test suite, the migration is internal-only.

## Model considerations

`BatchKey.LifterRowKeyed` (`BatchKey.java:337-362`) today holds a single `JoinStep.LiftedHop`, which encodes "no parent table, source-side == target-side, single column-equality JOIN against the leaf." That representation only fits the no-`@reference` case after the redesign. With `@reference`, the path is normal `FkJoin` steps and the lifter's tuple stands in for the (conceptual) source table at SQL generation time but the slot pairs are normal FK orientations.

Two viable shapes for `LifterRowKeyed` after redesign, deferred to implementation:

- **Single permit, polymorphic over path shape.** `LifterRowKeyed(List<JoinStep> path, LifterRef lifter, List<ColumnRef> parentSideColumns)`. The path's first step is `LiftedHop` (no `@reference`) or `FkJoin` (with). Prelude reads `parentSideColumns` directly; emitters consume `path` uniformly.
- **Two permits.** `LifterLeafKeyed` (no `@reference`, holds `LiftedHop`) and `LifterPathKeyed` (with `@reference`, holds non-empty `FkJoin` chain). Mirrors the variant-identity-tracks-shape rule applied four times already (R61 / R70 / R71 / R74).

The two-permit shape is more in-line with rewrite design principles; the single-permit shape is simpler to land. Pick during In Progress.

## Scope kept unchanged

- Parent shape gating: `@sourceRow` only applies to `@record` parents whose backing class is a non-jOOQ POJO / Java record. The directive is rejected on `@table` parents (use `@reference` directly), on jOOQ-backed `@record` parents (the catalog record drives batching), and on `@record` parents with no declared backing class.
- Rejection on `@asConnection` fields (Invariant #9).
- Rejection on non-`@table`-bound returns.
- Lifter return type: `Row1..Row22<...>` only. `Record1..Record22<...>` symmetry is the sibling concern of [R71 (`recordn-key-parity-lifter-and-non-jooq-record-parents.md`)](recordn-key-parity-lifter-and-non-jooq-record-parents.md) and lands separately; nothing in R110 forecloses it.

## Out of scope

- Composite-key stories (lifter returning `RowN` for `N ≥ 2`) are admitted by the design but not driving it. Test fixtures cover `Row1` and `Row2` to exercise the path; we do not enumerate the full `Row1..Row22` matrix.
- Per-parent arbitrary `Condition` joins (the "user method takes `(List<Record>, Table)` and returns `Condition`" alternative) are deferred. They would unlock range / function predicates the column-equality shape cannot express, but raise an unsolved result-ordering problem (DataLoader cannot align result rows to input keys without a column-equality projection or an explicit alignment column). The `@sourceRow` design does not foreclose that direction; it lives orthogonally.
- Non-PK unique-key lookups on the leaf target (e.g. lifter returns `tmdb_id`, joins on `film.tmdb_id` UNIQUE constraint that is not the PK). Held out of this iteration; if the need arises, it likely lives as an extension to `@reference`'s first path element rather than as a new `@sourceRow` arg.
- Auto-derive accessor parity. The auto-derive accessor path on jOOQ-backed `@record` parents is governed by R74; that work and R110 are independent and can ship in either order.

## Tests

- **L1 (model).** `BatchKey` test pins `keyElementType()` / `javaTypeName()` / `dispatch()` / `preludeKeyColumns()` for the redesigned `LifterRowKeyed` (or its split permits). Both no-`@reference` and with-`@reference` shapes covered.
- **L3 (validator).** `SourceRowClassificationTest` (renamed from existing `BatchKeyLifterCase` in `GraphitronSchemaBuilderTest.java:1753-2272`) covers:
  - Story 1 SDL (`@sourceRow` + `@reference` single-hop) classifies as `RecordTableField` with the expected `LifterRowKeyed`.
  - Story 2 SDL (`@sourceRow` + `@reference` single-hop, different FK) same shape.
  - `@sourceRow` alone (no `@reference`) classifies as `RecordTableField` with a leaf-PK `LiftedHop`.
  - `@sourceRow` + `@reference` multi-hop chain (≥ 2 hops): classifier accepts; first hop's source-side validates against the lifter's RowN.
  - Composite-key shape (`Row2`) on a 2-column first-hop key.
  - Rejection: lifter `RowN` arity / per-position type does not match the derived tuple. Two diagnostic messages, distinguishing the `@reference` and leaf-PK cases.
  - Rejection: `@reference` parse failure surfaces directly (no double-validation against the lifter).
  - All existing parent-shape rejections from `BatchKeyLifterCase` (asConnection, jOOQ-backed parent, missing backing class, scalar return) preserved with the new directive name.
- **L4 (pipeline).** Pipeline test for at least one `@sourceRow + @reference` field: parent VALUES emission and JOIN ON predicate render as expected. Pipeline test for one `@sourceRow` no-`@reference` leaf-PK field, mirroring the existing lifter pipeline coverage.
- **L5 (compile spec).** `graphitron-sakila-example` carries Story 1 / Story 2-shape fixtures; `mvn compile -pl :graphitron-sakila-example -Plocal-db` passes.
- **L6 (execution).** Sakila execution test exercises one `@sourceRow + @reference` end-to-end and confirms DataLoader alignment.

## Acceptance criteria

- `@batchKeyLifter` is removed from `directives.graphqls`. `@sourceRow(className, method)` replaces it; flat args, no `ExternalCodeReference` wrapper.
- `BatchKeyLifterDirectiveResolver` → `SourceRowDirectiveResolver`. `targetColumns` validation removed; first-hop / leaf-PK derivation added.
- `BuildContext.parsePath` extends to accept a no-table-known parent when `@sourceRow` is present, deriving the path's source endpoint from the lifter's tuple shape.
- `BatchKey.LifterRowKeyed` (or its split-permit successor) carries enough to project the parent-side tuple in the prelude regardless of whether the path is leaf-PK or FK-chain.
- `FieldBuilder` updates: directive lookup string, classifier integration, error message strings (the user-facing messages naming `@batchKeyLifter` switch to `@sourceRow`).
- All existing `BatchKeyLifterCase` test scenarios pass under the new directive name with the new resolver. New cases above are added.
- `@sourceRow` works composed with `@reference` for ≥ 1-hop paths; rows-method emitter and parent VALUES projection work end-to-end on Sakila.
- Documentation: rewrite-design-principles.adoc updated; `BatchKey` class-level Javadoc updated to reflect the redesigned `LifterRowKeyed`.

## Roadmap entries (siblings / dependencies)

- **Replaces** the current `@batchKeyLifter` directive (`directives.graphqls:152-167`). The directive has not been adopted outside test fixtures, so removal is internal-only and does not require a schema-side migration.
- **Sibling of** [R71 (`recordn-key-parity-lifter-and-non-jooq-record-parents.md`)](recordn-key-parity-lifter-and-non-jooq-record-parents.md), which extends the lifter return type from `Row1..Row22` to also accept `Record1..Record22`. R110 redefines the directive's *expected tuple shape* (parent-side, derived); R71 extends the *return type carrier* (Row vs. Record). Independent; can ship in either order. If R71 lands first, R110 inherits the dual return-type acceptance. If R110 lands first, R71 narrows to extending `SourceRowDirectiveResolver`'s return-type validation.
- **Adjacent to** [R74 (`accessor-row-record-shapes.md`)](accessor-row-record-shapes.md), the auto-derive accessor path on jOOQ-backed `@record` parents. R110 does not change that path; the two designs sit on opposite sides of the "is the parent jOOQ-backed?" fork.
