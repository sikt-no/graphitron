---
id: R110
title: "Replace @batchKeyLifter with @sourceRow composing with @reference"
status: Spec
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

Both cases collapse to "the lifter's `RowN` matches a unique key on the parent side, materialised into the parent_values CTE." Stating the uniqueness invariant explicitly: **the parent-side tuple must represent a unique key on whatever it reads from** — trivially satisfied by leaf-PK and by FK-paired columns (which are unique by FK constraint), and the seam where any future non-PK unique-key extension would have to prove itself.

The two cases route through different resolver paths and `BuildContext.parsePath` is **not** extended for this work. With `@reference`, the resolver delegates to `parsePath` exactly as today (its gate stays unchanged: catalog connectivity between known endpoints). Without `@reference`, the resolver bypasses `parsePath` and reads the leaf target's PK columns directly from `JooqCatalog.primaryKeyColumns(...)`. Routing the leaf-PK case through `parsePath` would conflate two unrelated triggers ("catalog can connect endpoints" vs. "directive present, derive parent-side") and push classification work out of the resolver into a generic path parser; the principles call for the directive-shaped fork to live in the resolver.

## Resolver

`SourceRowDirectiveResolver` (renamed from `BatchKeyLifterDirectiveResolver`):

1. Validate parent shape: still POJO / JavaRecord with declared backing class. Same rejection messages as today, with the new directive name.
2. Reflect the lifter method, validate single arg assignable from parent class, validate return is `Row1..Row22<...>`. Logic from `BatchKeyLifterDirectiveResolver.java:212-300` survives unchanged except for the directive-name strings.
3. Derive the expected parent-side column tuple by directive shape:
   - **`@reference` present.** Delegate to `BuildContext.parsePath` with the field's element type as the leaf target; expected tuple is `firstHop.sourceSideColumns()`. `parsePath`'s existing contract is untouched.
   - **`@reference` absent.** Bypass `parsePath`; read the leaf target's PK columns directly via `JooqCatalog.primaryKeyColumns(...)`.
   - `@reference` parse failure: surface the parse error directly; do not double-validate against the lifter.
4. Validate `RowN` arity and per-position erasure against the derived tuple. Two diagnostic messages distinguish the cases — exact wording is anchored to the howto's "Rejection messages" section (see Documentation below) so user-facing text and SDL author guidance share one source. Templates: `"@sourceRow on '<parent>.<field>': lifter '<method>' RowN type at position <i> ('<actual>') does not match first-hop source-side column '<col>' of FK '<fk>' (Java type '<expected>')"` for the `@reference` case; `"@sourceRow on '<parent>.<field>': lifter '<method>' RowN type at position <i> ('<actual>') does not match primary key column '<col>' of '<leaf>' (Java type '<expected>')"` for the leaf-PK case.
5. Construct the appropriate `BatchKey` permit (see "Model" below): `LifterPathKeyed` for the `@reference` path, `LifterLeafKeyed` for the leaf-PK case. Each permit carries its own `joinPath` shape; classifier and emitters dispatch on permit identity, no per-instance branching.

The `targetColumns` arg is removed entirely. Existing fixtures using `targetColumns` must migrate; since the directive has not been adopted outside the test suite, the migration is internal-only.

## Model

`BatchKey.LifterRowKeyed` (`BatchKey.java:337-362`) today holds a single `JoinStep.LiftedHop`, which encodes "no parent table, source-side == target-side, single column-equality JOIN against the leaf." That representation only fits the no-`@reference` case after the redesign. With `@reference`, the path is normal `FkJoin` steps and the lifter's tuple stands in for the (conceptual) source table at SQL generation time but the slot pairs are normal FK orientations.

`LifterRowKeyed` splits into two permits, mirroring the variant-identity-tracks-shape rule applied four times already (R61 / R70 / R71 / R74):

- **`LifterLeafKeyed(JoinStep.LiftedHop hop, LifterRef lifter)`** — no `@reference`. Holds the existing single-`LiftedHop` shape. The hop's slots fold source-side and target-side onto the same column (leaf-PK column-equality).
- **`LifterPathKeyed(List<JoinStep> path, LifterRef lifter, List<ColumnRef> parentSideColumns)`** — `@reference` resolved to a non-empty path. `path` is the resolved `FkJoin` chain; `parentSideColumns` is `path.first().sourceSideColumns()`, materialised on the permit so the prelude reads it without re-walking the path. The compact constructor enforces the equality (`if (!parentSideColumns.equals(path.getFirst().sourceSideColumns())) throw ...`) so a future caller cannot construct an inconsistent permit.

The split puts the directive-shape fork in the type system and lets four distinct consumers (resolver step 5, prelude projection, validator diagnostics, emitter) dispatch on permit identity rather than re-deriving "is this the leaf-PK case?" from `path.first() instanceof LiftedHop`. Single-permit polymorphism was considered and rejected: the two shapes' downstream branches are independent, so collapsing them onto one record with conditional fields recreates the per-instance branching the encoding rule exists to eliminate.

The split is for the *resolver / validator* dispatch axis. On the *emitter* axis, both permits share the same column-keyed DataLoader path: `SplitRowsMethodEmitter` continues to read both via the existing `BatchKeyField` capability, with the per-permit fork confined to resolver step 5 (constructing the right permit) and the prelude's parent-VALUES projection (reading `parentSideColumns` from the permit). No emitter arm should fork on permit identity; capability uniformity holds at the emission layer even where the seal forks at the resolution layer.

## Scope kept unchanged

- Parent shape gating: `@sourceRow` only applies to `@record` parents whose backing class is a non-jOOQ POJO / Java record. The directive is rejected on `@table` parents (use `@reference` directly), on jOOQ-backed `@record` parents (the catalog record drives batching), and on `@record` parents with no declared backing class.
- Rejection on `@asConnection` fields (Invariant #9).
- Rejection on non-`@table`-bound returns.
- Lifter return type: `Row1..Row22<...>` only. `Record1..Record22<...>` symmetry is the sibling concern of [R71 (`recordn-key-parity-lifter-and-non-jooq-record-parents.md`)](recordn-key-parity-lifter-and-non-jooq-record-parents.md) and lands separately; nothing in R110 forecloses it.

## Out of scope

- Composite-key stories (lifter returning `RowN` for `N ≥ 2`) are admitted by the design but not driving it. Test fixtures cover `Row1` and `Row2` to exercise the path; we do not enumerate the full `Row1..Row22` matrix.
- Per-parent arbitrary `Condition` joins (the "user method takes `(List<Record>, Table)` and returns `Condition`" alternative) are deferred. They would unlock range / function predicates the column-equality shape cannot express, but raise an unsolved result-ordering problem (DataLoader cannot align result rows to input keys without a column-equality projection or an explicit alignment column). The `@sourceRow` design does not foreclose that direction; it lives orthogonally.
- Non-PK unique-key lookups on the leaf target (e.g. lifter returns `tmdb_id`, joins on `film.tmdb_id` UNIQUE constraint that is not the PK). Held out of this iteration; if the need arises, it lives as an extension to `@reference`'s first path element rather than as a new `@sourceRow` arg. The uniqueness invariant stated in the composition rule above is the seam: any future extension must prove its derived tuple represents a unique key; the current shape's `LifterLeafKeyed` arm trivially satisfies that (PK is unique by definition) and `LifterPathKeyed` inherits it from the FK constraint.
- Auto-derive accessor parity. The auto-derive accessor path on jOOQ-backed `@record` parents is governed by R74; that work and R110 are independent and can ship in either order.

## Tests

- **L1 (model).** `BatchKey` test pins `keyElementType()` / `javaTypeName()` / `dispatch()` / `preludeKeyColumns()` for `LifterLeafKeyed` and `LifterPathKeyed` separately. Both permits return `LoaderDispatch.LOAD_ONE` and the same `keyElementType` shape (`Row<N>` over the lifter's tuple), inherited from today's `LifterRowKeyed`; the two permits diverge on `preludeKeyColumns()` (leaf-PK vs. first-hop source-side) and on identity for resolver / validator dispatch. Variant-identity assertions cover the seal: existing exhaustive-`switch(batchKey)` sites must compile after the split. Compact-constructor invariant on `LifterPathKeyed` (parentSideColumns equality with `path.first().sourceSideColumns()`) covered by a unit test that constructs an inconsistent permit and asserts the throw.
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
- **L5 (compile spec).** `graphitron-sakila-example` carries Story 1 / Story 2-shape fixtures plus a no-`@reference` leaf-PK fixture; `mvn compile -pl :graphitron-sakila-example -Plocal-db` passes. The howto article (see Documentation below) draws its three SDL examples from these fixtures so the published examples cannot drift from working code.
- **L6 (execution).** Sakila execution test exercises one `@sourceRow + @reference` end-to-end and confirms DataLoader alignment.

## Documentation

A new howto article ships with the implementation: `docs/manual/how-to/source-row.adoc`, sibling to the existing `external-code.adoc`, `add-custom-conditions.adoc`, and `result-types.adoc` under the same directory. The article walks three end-to-end examples in user voice:

1. **Story 1 — third-party generated DTO with `@reference`.** TMDB-style scenario: DTO carries the internal table's PK, `@reference` navigates a single FK to the leaf target. SDL fragment, lifter Java method, what the generator emits.
2. **Story 2 — shared cross-team DTO with `@reference`.** `SubscriptionDTO`-style scenario: same shape as Story 1 but framed around the cross-team-coupling motivation. Reinforces "the lifter lives in the graphitron-using project, not in the DTO module."
3. **No-`@reference` leaf-PK case.** Smaller fixture where the DTO carries the leaf table's PK directly, no FK chain. Establishes the simplest shape so readers can walk the rule "matches first-hop source-side OR leaf PK" against two anchors.

**No-drift mechanism.** SDL blocks in `source-row.adoc` are pulled via AsciiDoc `include::` directives with `tag::sourcerow-story-1[]` / `tag::sourcerow-story-2[]` / `tag::sourcerow-leafpk[]` markers in the `graphitron-sakila-example` fixture `.graphqls` files. A stale snippet, a renamed tag, or a deleted fixture fails the docs render rather than landing as a quiet drift; the docs build is part of the same `mvn install` pipeline as the L5 compile-spec, so a fixture change that breaks the snippets fails CI in one of two ways without needing both.

The article also documents the rejection messages users will see — invalid lifter signature, arity mismatch, `@reference` parse failure — anchored to the diagnostics emitted by `SourceRowDirectiveResolver` (the resolver section above pins the exact wording, so the howto's "Rejection messages" subsection and the resolver's diagnostic strings cite one source).

The article cross-links to the `@reference` reference page (where users land when they reach the composed examples) and to `external-code.adoc` (where the conventions for static lifter classes match the conventions for `@condition` / `@service` lifter methods).

## Acceptance criteria

- `@batchKeyLifter` is removed from `directives.graphqls`. `@sourceRow(className, method)` replaces it; flat args, no `ExternalCodeReference` wrapper.
- `BatchKeyLifterDirectiveResolver` → `SourceRowDirectiveResolver`. `targetColumns` validation removed; first-hop / leaf-PK derivation added per the resolver section.
- `BuildContext.parsePath`'s gate is **unchanged**. The leaf-PK case bypasses `parsePath` and reads `JooqCatalog.primaryKeyColumns(...)` directly.
- `BatchKey.LifterRowKeyed` is split into `LifterLeafKeyed` and `LifterPathKeyed` permits. Every existing exhaustive `switch(batchKey)` site is walked (the seal makes this `javac`-checked).
- `FieldBuilder` updates: directive lookup string, classifier integration, error message strings (the user-facing messages naming `@batchKeyLifter` switch to `@sourceRow`).
- `@LoadBearingClassifierCheck` keys are audited and renamed: `lifter-classifies-as-record-table-field` → `sourcerow-classifies-as-record-table-field`; `lifter-batchkey-is-lifterrowkeyed` is replaced by per-permit keys (`sourcerow-leafkey-batchkey-is-lifterleafkeyed`, `sourcerow-pathkey-batchkey-is-lifterpathkeyed`). Every `@DependsOnClassifierCheck(reliesOn=...)` consumer of the renamed keys is updated in the same commit; `LoadBearingGuaranteeAuditTest` is green at the end of the work.
- All existing `BatchKeyLifterCase` test scenarios pass under the new directive name with the new resolver. New cases above are added.
- `@sourceRow` works composed with `@reference` for ≥ 1-hop paths; rows-method emitter and parent VALUES projection work end-to-end on Sakila.
- `docs/manual/how-to/source-row.adoc` ships with the three examples from the Documentation section above. SDL blocks are pulled via AsciiDoc `include::` with `tag::sourcerow-story-1[]` / `tag::sourcerow-story-2[]` / `tag::sourcerow-leafpk[]` markers on the matching `graphitron-sakila-example` fixture `.graphqls` files; a stale snippet or renamed tag fails the docs render. The article is wired into the docs site's index (`docs/manual/index.adoc`).
- Internal-docs sweep: `rewrite-design-principles.adoc` updated; `BatchKey` class-level Javadoc updated to reflect the `LifterLeafKeyed` / `LifterPathKeyed` split.

## Roadmap entries (siblings / dependencies)

- **Replaces** the current `@batchKeyLifter` directive (`directives.graphqls:152-167`). The directive has not been adopted outside test fixtures, so removal is internal-only and does not require a schema-side migration.
- **Sibling of** [R71 (`recordn-key-parity-lifter-and-non-jooq-record-parents.md`)](recordn-key-parity-lifter-and-non-jooq-record-parents.md), which extends the lifter return type from `Row1..Row22` to also accept `Record1..Record22`. R110 redefines the directive's *expected tuple shape* (parent-side, derived); R71 extends the *return type carrier* (Row vs. Record). Independent; can ship in either order. If R71 lands first, R110 inherits the dual return-type acceptance. If R110 lands first, R71 narrows to extending `SourceRowDirectiveResolver`'s return-type validation.
- **Adjacent to** [R74 (`accessor-row-record-shapes.md`)](accessor-row-record-shapes.md), the auto-derive accessor path on jOOQ-backed `@record` parents. R110 does not change that path; the two designs sit on opposite sides of the "is the parent jOOQ-backed?" fork.
