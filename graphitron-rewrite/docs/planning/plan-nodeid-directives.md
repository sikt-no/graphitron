# `@nodeId` + `@node` directive support

> **Status:** Ready (semantics revised)
>
> This plan captures Relay Global Object Identification (GOI) support. It supersedes earlier revisions that framed the feature as a platform-id replacement and allowed jOOQ metadata alone to promote a GraphQL type to a node type. Platform-id becomes one specific migration case on the way to a GOI-complete rewrite, not the umbrella reason for the work.

## What a nodeId is

A nodeId is a **globally unique, opaque, durable identifier that makes an object refetchable** through the Relay `Query.node(id: ID!): Node` contract (and its Apollo Federation twin, `_entities`). It is not "a wrapped composite key"; wrapping a composite key is how we encode the identifier, not what the identifier *is*. Three properties are load-bearing and every design choice in this plan defers to them:

- **Globally unique, type-discriminating.** Every node carries a `typeId` prefix inside the encoded ID. The prefix is how `Query.node(id)` (and `_entities`) route an opaque string back to the correct type / table without the client specifying which type they wanted.
- **Opaque to clients.** The wire encoding (today: URL-safe base64 of `typeId:csv_values`) is an implementation detail of `NodeIdStrategy`. Clients never parse it; schemas, directives, and diagnostics never invite them to. If we ever need to evolve the encoding, the opacity is what lets us do so without breaking consumers.
- **Durable across schema evolution.** An ID issued today must still resolve tomorrow. This rules out silent reassignments (column-order changes in the encoding key, typeId rename on SDL rename, etc.) unless consumers are explicitly migrated. Durability is what makes nodeIds safe to store in bookmarks, client caches, and external systems.

Every mechanical rule in this plan (typeId wins on disagreement, synthesis requires an affirmative Node signal, collision is a hard error, ordering is pinned) is a consequence of one or more of those three properties.

## What a NodeType is

A GraphQL type is a node type if and only if it satisfies the Relay contract: it `implements Node` (the `interface Node { id: ID! }` declared in SDL, see [graphitron-java-codegen/README.md#node](../../../graphitron-codegen-parent/graphitron-java-codegen/README.md)) **and** carries `@node` (either declared or, transitionally, via the platform-id migration shim). The `@table` requirement is separate: `@node` without `@table` is meaningless because there is no storage to encode.

The rewrite classifies `NodeType` as a subtype of `TableType`, carrying `(typeId, nodeKeyColumns)`. The *only* legal sources for those fields are:

1. **SDL-declared `@node`** on a type that implements `Node`. `typeId` defaults to the SDL type name; `keyColumns` defaults to the jOOQ primary key. Either may be overridden in the directive. This is the canonical form.
2. **Platform-id migration shim.** A pre-pivot consumer with no `@node` in SDL, but whose jOOQ table carries `__NODE_TYPE_ID` + `__NODE_KEY_COLUMNS` metadata, is treated as a NodeType for one release cycle, emitting a deprecation diagnostic that points at adding `implements Node @node` in SDL. This shim is narrow and time-boxed (see **Migration**).

Metadata *alone* does not promote a GraphQL type to a NodeType. The inverse would make `implements Node`, a visible schema-level contract, a consequence of jOOQ generator output, which is backwards. The current trunk code unconditionally synthesizes on metadata presence; **R1 below narrows this.**

## Directive vocabulary

### `@node` on types

```graphql
directive @node(typeId: String, keyColumns: [String!]) on OBJECT
```

- `typeId`: the stable wire-format discriminator embedded in every ID of this type. Defaults to the SDL type name. The author owns it; once published, changing it invalidates every ID already in circulation. Treat as a public API.
- `keyColumns`: ordered SQL column list that composes the key. Defaults to the primary key of the `@table`. Order is load-bearing (encode / decode positional equivalence).
- **Required:** the type carries `@table` **and** declares `implements Node`. The schema validator enforces both.

### `@nodeId` on fields / arguments

```graphql
directive @nodeId(typeName: String) on FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION
```

- **Bare `@nodeId`.** The annotated `ID` encodes *the containing type's* node identity. Containing type must be a NodeType.
- **`@nodeId(typeName: "X")`.** The annotated `ID` encodes *`X`'s* node identity, reached from the containing type / input via a joinPath or FK mirror. `X` must be a NodeType.
- Per the legacy docs, `@nodeId` is required on every node-ID field in hand-written SDL. **Synthesis (classifying an undecorated scalar `ID` on a NodeType parent) is a platform-id migration affordance, not the steady state.** It emits the same classifier output as declared-`@nodeId` but logs a one-time deprecation per site pointing at adding the directive.

## Collision rule (semantics-preserving)

When both SDL `@node` and jOOQ metadata are present, the rule is not "error on disagreement" but "SDL wins, metadata fills blanks":

| SDL declares | Metadata present | Result |
|---|---|---|
| `@node` omitted (or type lacks `@node`) | yes, `implements Node` present | NodeType via migration shim + deprecation diagnostic |
| `@node` omitted, type lacks `implements Node` | yes | `TableType` (not promoted); migration shim does not fire |
| `@node(typeId:)` given, disagrees with metadata | yes | **SDL value wins.** `typeId` is a wire-format contract; the entire point of the directive argument is to decouple the ID from the jOOQ table name. |
| `@node(keyColumns:)` given, disagrees with metadata | yes | **SDL value wins.** Set-equal but order-different → WARN (not error); set-unequal → ERROR (one side is wrong about the schema). |
| Both sides silent on an axis | yes | Use metadata. |
| `@node` given, metadata absent | no | Use SDL; resolve primary-key fallback for omitted `keyColumns`. |

The current trunk code (`TypeBuilder.buildTableType:298-311`) errors on any disagreement. **R1 inverts this.** The author's intent, expressed in SDL, is the authoritative source for wire format.

## `Query.node(id:)` dispatch is core

The `typeId` prefix exists so that `Query.node(id: ID!): Node` can route an opaque string to the correct type without a type hint. This plan treats the resolver as first-class, not "a terminal consumer to wire up later":

- A per-schema **`NodeTypeRegistry`** maps `typeId → (NodeType, TableRef, keyColumns)`. Built once by the classifier from the set of NodeTypes; uniqueness validated at build time.
- **Ambiguity is an error.** If two NodeTypes in a schema share a `typeId` (explicit or defaulted), `QueryNodeField` dispatch is nondeterministic; the classifier surfaces `UnclassifiedType` on the losing side with a pointer to the collision.
- **`QueryField.QueryNodeField` emission** (R4 below): decode via `NodeIdStrategy.unpackIdValues` → extract `typeId` prefix (without full column binding) → registry lookup → `SELECT <target.$fields> FROM <target> WHERE hasId(typeId, id, keyColumns)` → wrap the record in the correct polymorphic branch.
- **Unknown `typeId` at runtime ⇒ `null`**, per the Relay spec's "if no such object exists, the field returns null." Not an exception.

Apollo Federation's `_entities` resolver follows the same contract. `QueryField.QueryEntityField` shares the registry and helpers with `QueryNodeField`: one router, two entry points.

## Durability and opacity (binding invariants)

Two invariants that future changes in this area are measured against:

1. **No silent rotation.** An ID produced by release N must decode and resolve under release N+k for any k, unless consumers are given a versioned migration. Column-order changes in `__NODE_KEY_COLUMNS`, tweaks to `NodeIdStrategy` encoding, and SDL `typeId` renames all fall under this. KjerneJooqGenerator's column-order guarantee (Open point below) is one corollary; another is the rule that `typeId:` once published is an external contract.
2. **Clients never parse.** The encoding format is an implementation detail. Directives, diagnostics, tests, and documentation must not encourage clients to destructure IDs. If we ever need a version byte or a rotation mechanism, we add it inside `NodeIdStrategy`; we never ask clients to decode.

Both invariants motivate specific test coverage (see **Compile-tier coverage**).

---

## Model

All node-identity classification terminates in one of four variants, symmetric across output and input sides. Every trigger below requires the containing type to be a **NodeType** (per the contract above) — no variant is reachable from jOOQ metadata alone without either `implements Node @node` in SDL or the migration shim firing.

| Side | Variant | Trigger | Emission |
|---|---|---|---|
| Output | `ChildField.NodeIdField` | scalar `ID` on a NodeType parent, with `@nodeId` (declared) or synthesized via migration shim | project `nodeKeyColumns`; encode via `NodeIdEncoder.encode(typeId, …)` |
| Output | `ChildField.NodeIdReferenceField` | scalar `ID` with `@nodeId(typeName:)` pointing at another NodeType; `joinPath` resolved to that type's table | project target's `nodeKeyColumns` through `joinPath`; encode via `NodeIdEncoder.encode(typeId, …)` |
| Input | `InputField.NodeIdField` | scalar `ID` on a `@table` input whose own table is a NodeType, with `@nodeId` (declared) or via migration shim | decode via `NodeIdStrategy.unpackIdValues` / `hasIds` / `hasId`; each unpacked value binds to its own-table column |
| Input | `InputField.NodeIdReferenceField` | scalar `ID` on a `@table` input with `@nodeId(typeName:)` pointing at another NodeType; `joinPath` resolved to that type's table | decode via `NodeIdStrategy.unpackIdValues`; each unpacked value binds to the reachable column on the target table, reached through `joinPath` (direct FK-bind when the input's own table mirrors the target's key columns) |

Shared runtime helpers: `NodeIdStrategy` (decode / `hasIds` / `hasId` / `setId` / `createId`) and `NodeIdEncoder` (encode). Both input variants carry the same shape fields as their output counterparts plus `nonNull`, `list`, and the optional `ArgConditionRef condition` every `InputField` already carries — except `list`, which is fixed `false` by the classifier (the scalar gate is load-bearing on both sides).

`PlatformIdField` variants on both `ChildField` and `InputField` are deleted at R6. Every former platform-id classification site routes through one of the four rows above; every former `PlatformIdField` emission site routes through `NodeIdStrategy` + `NodeIdEncoder`. Apollo Federation `_entities` and `Query.node(id:)` share the same dispatch machinery via `NodeTypeRegistry` — no separate emission path.

---

## KjerneJooqGenerator contract

On every table class where it currently emits `getId()` / `getPersonId()` / `hasId` / `hasIds`, KjerneJooqGenerator additionally emits two public constants:

```java
public static final String __NODE_TYPE_ID = "Customer";
public static final Field<?>[] __NODE_KEY_COLUMNS = { STORE_ID, CUSTOMER_ID };
```

- **`__NODE_TYPE_ID`** — the value used today when encoding the composite ID's base64 prefix. Stable across regens; matches whatever a consumer would write as `@node(typeId: "Customer")` in SDL for the same table.
- **`__NODE_KEY_COLUMNS`** — the underlying `Field<?>` references in positional order. `NodeIdStrategy.unpackIdValues(typeId, base64Id, fields)` pairs the CSV-decoded values positionally with this array, and `NodeIdStrategy.createId(typeId, keyFields)` encodes in the same order. Any reordering between `createId` (encode) and `hasIds` (decode) produces silently-wrong composite keys, so this order is load-bearing.

Static finals, not instance fields — accessible as `Customer.__NODE_TYPE_ID` / `Customer.__NODE_KEY_COLUMNS` without needing an instance. Reflection lookup is trivial.

The existing method emissions (`getId()`, `hasId`, `hasIds`) can stay — harmless for non-graphitron callers. The rewrite stops detecting and calling them.

---

## Classification

### Type-level synthesis and output-side `NodeIdField` (shipped; revised at R1–R3)

**Synthesis gate (revised — R2).** `TypeBuilder.buildTableType` synthesizes `NodeType` when **either** condition holds:

- SDL declares `@node` on a type that `implements Node` and carries `@table`. This is the canonical path.
- Migration shim: SDL lacks `@node` **and** lacks `implements Node`, but jOOQ metadata is present. Emits a deprecation diagnostic tied to the release cut-over (see **Migration**). This shim is removed at R6.

Metadata-only tables whose SDL types carry no `@node` *and* no `implements Node` classify as `TableType`, not `NodeType`. The current code's "unconditional synthesis" (`TypeBuilder.java:247-266`) collapses into a single gated branch.

**Collision rule (revised — R1).** When SDL `@node` and metadata both speak to an axis, SDL wins:

- `typeId` disagreement → SDL value wins; no diagnostic. `@node(typeId:)` exists precisely to pin the wire format independent of generator output.
- `keyColumns` disagreement → SDL value wins; WARN if the column sets are equal but order differs (a legitimate pin of a historical order, but worth surfacing); ERROR (`UnclassifiedType`) if the sets are unequal (the SDL and the jOOQ schema disagree about which columns form the key — a genuine bug to fix).

Malformed metadata (`nodeIdMetadataDiagnostic`) remains a hard error, independent of `@node` — an unparseable constants pair means we can't trust the generator output.

**Primary-key fallback for `keyColumns:` (R3).** When `@node` is declared without `keyColumns:` and metadata is absent, resolve from the jOOQ primary key via `JooqCatalog`. The current code records an empty list (`TypeBuilder.java:276-287`) and the legacy docs promise PK fallback; the rewrite must honour it or reject the directive at classify time.

**Validate `implements Node` on `@node` (R1).** `@node` without `implements Node` on the type is a schema error (per the canonical legacy docs). Enforced in `GraphitronSchemaValidator`, not `TypeBuilder`, so the diagnostic travels with other schema validation output.

**Output-side `NodeIdField` classifier (shipped).** Populated either by the declared `@nodeId` path (`FieldBuilder.java:1911-1939`) or by the synthesized Path-2 trigger (`FieldBuilder.java:1968-1977`) — scalar non-list `ID` on a NodeType parent with no `@nodeId`/`@reference`/`@field` and no real column. The Path-2 synthesis emits the same deprecation diagnostic as the type-level migration shim, pointing at adding `@nodeId` to the field. `TypeClassGenerator.$fields` projects the raw key columns; `TypeFetcherGenerator` wires a lambda calling `NodeIdEncoder.encode(typeId, …)` with URL-safe base64 no-pad + `,`→`%2C` encoding so IDs round-trip against `NodeIdStrategy.unpackIdValues` / `hasIds`.

### Output-side `NodeIdReferenceField` (classifier shipped; emission stubbed)

`ChildField.NodeIdReferenceField` carries `(typeName, targetType, parentTable, nodeTypeId, nodeKeyColumns, joinPath)` and is populated by `FieldBuilder` when a scalar `ID` field declares `@nodeId(typeName: "X")` where `X` resolves to a `NodeType`. The classifier resolves `joinPath` to reach that type's table and pulls `nodeTypeId` + `nodeKeyColumns` off the resolved `NodeType`.

Emission is currently stubbed (tracked as Generator stub #8; the stub reason lives at `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` for `NodeIdReferenceField`). R5 lands it: `TypeClassGenerator.$fields` JOINs through `joinPath` and projects the target's `nodeKeyColumns` under an alias; `TypeFetcherGenerator` wires a lambda that reads the aliased values off the record and calls the same `NodeIdEncoder.encode(typeId, …)` that `NodeIdField` uses.

### Input-side `NodeIdField` (shipped)

`InputField` now permits `ColumnField`, `ColumnReferenceField`, `PlatformIdField` (pending R6), `NestingField`, `NodeIdField`, `NodeIdReferenceField`:

```java
record NodeIdField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String nodeTypeId,
    List<ColumnRef> nodeKeyColumns
) implements InputField {}
```

`TypeBuilder.resolveInputField` (`TypeBuilder.java:632-649` is where `PlatformIdField` currently classifies) becomes: scalar `ID`, not a list; if the backing table resolves to a synthesized-or-declared `NodeType`, classify as `InputField.NodeIdField` carrying the same `typeId` + `keyColumns` pair.

**`InputField.NodeIdField` is the first input-side NodeId classifier.** Today the `@nodeId` directive is only read on *output* fields (`FieldBuilder.java:1559-1586`); on the input side, `TypeBuilder.java:638` uses `!hasDirective(@nodeId)` purely as an exclusion gate for the platform-id fallback — there is no existing input-side `@nodeId` classifier path. This variant covers both SDL shapes uniformly:

- `input FooLookup { id: ID! }` on an `@table` whose backing jOOQ table carries the metadata constants → synthesized-route `NodeIdField`.
- `input FooLookup { id: ID! @nodeId }` (declared) → same `NodeIdField` classification.

Both paths produce the same variant carrying `(typeId, keyColumns)`. Shipped in Step 4. **Revised semantics (R2):** the undecorated `input FooLookup { id: ID! }` path is the migration shim — it fires with a deprecation diagnostic pointing at adding `@nodeId` to the field. The declared `@nodeId` form is canonical.

### Input-side `NodeIdReferenceField` (shipped)

Parallel shape on the input side, added to the `InputField` sealed permits alongside `NodeIdField`:

```java
record NodeIdReferenceField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String typeName,
    boolean nonNull,
    TableRef parentTable,
    String nodeTypeId,
    List<ColumnRef> nodeKeyColumns,
    List<JoinStep> joinPath,
    Optional<ArgConditionRef> condition
) implements InputField {}
```

Classified when a scalar `ID` field on a `@table` input declares `@nodeId(typeName: "X")` where `X` is a `NodeType` reachable from the input's own table via `joinPath` (resolved by the same reference-path parser `InputField.ColumnReferenceField` uses). `list` is fixed `false` by the classifier — list inputs collapse the containing `TableInputType` to `UnclassifiedType` per the existing list-gate rule.

Emission (Step 5): decode via `NodeIdStrategy.unpackIdValues`, then bind each unpacked value to its column on the reachable target table. In WHERE contexts (filter / lookup), the bind sits inside a JOIN-through `joinPath` before `hasIds` / `hasId` fires; in FK-mirror cases where the input's own table carries columns congruent with the target's key columns, the bind collapses to a direct same-table column assignment and no JOIN is needed. The dispatch between the two shapes lives in the same emitter arm as `InputField.NodeIdField`.

### Argument side — `NodeIdArg`

`FieldBuilder.classifyArgument` (`FieldBuilder.java:634-end-of-method`) currently binds scalar args via `findColumn` — entry at `:669` ("Scalar arg: bind to column"), `findColumn` call at `:671`, `UnboundArg` fallback at `:673-676`. The classifier emits `ArgumentRef.ScalarArg.NodeIdArg(name, typeName, nonNull, list, nodeTypeId, keyColumns, extraction, argCondition, isLookupKey)` when `typeName == "ID"`, the target table is a NodeType, and the arg either declares `@nodeId` or falls through the migration shim. This is the unified replacement for the previously-proposed `PlatformIdArg`. Shipped in Step 4.

### `LookupMapping` becomes a sum type

```java
sealed interface LookupMapping permits ColumnMapping, NodeIdMapping {
    TableRef targetTable();
}

record ColumnMapping(List<LookupColumn> columns, TableRef targetTable) implements LookupMapping {}

record NodeIdMapping(String nodeTypeId, List<ColumnRef> nodeKeyColumns,
                     CallSiteExtraction extraction, TableRef targetTable) implements LookupMapping {}
```

`projectForLookup` produces `NodeIdMapping` when the single lookup arg is `NodeIdArg`; otherwise `ColumnMapping` as today.

**Refactor scope (historical, shipped in Step 4).** The original `LookupMapping` concrete record became a sealed interface with `ColumnMapping` and `NodeIdMapping` permits. Nested `LookupMapping.LookupColumn` stays on `ColumnMapping` unchanged; callers reference `ColumnMapping.LookupColumn`.

**Why the variants are asymmetric.** `ColumnMapping` carries per-column `CallSiteExtraction` inside each `LookupColumn` because each lookup key is an independent argument (or input-type field) extracted separately. `NodeIdMapping` carries *one* `extraction` at the top level because a NodeId is a single argument (a base64 string or list of strings) that decodes into N target columns — the extraction happens once, then `NodeIdStrategy.hasIds` expands it across `keyColumns`. Pushing `extraction` into each `nodeKeyColumn` would lie about independence that doesn't exist.

**`CallSiteExtraction` for `NodeIdArg`.** Four argument shapes feed a `NodeIdArg`: scalar `ID` arg, list `[ID!]`, nested input-type field, nested list-of-inputs with an ID-bearing field. Scalar and list cases use the existing `Direct` permit; the two nested shapes use the `NestedInputId` permit added in Step 4:

```java
record NestedInputId(String argName, List<String> path, int listDepth) implements CallSiteExtraction {}
```

`argName` is the outer argument name; `path` walks named fields through successive input types to reach the ID-bearing leaf; `listDepth` counts list wrappers encountered along the path (`0` = flat scalar, `1` = outer list of inputs, `≥2` reserved for deeper shapes if they surface later). The `LookupValuesJoinEmitter` `NodeIdMapping` arm reads these to assemble a `Set<String>` before the `hasIds` call — iterating `listDepth` list levels plus `path.size()` field accesses with null-guards per legacy's `_nit_in != null ? _nit_in.getId() : null` pattern.

This permit is intentionally narrow and independent of argres Phase 3's `InputColumnBinding`. Phase 3 lands a richer input-to-column binding mechanism that `NestedInputId` is a strict subset of; once shipped, this permit collapses into `InputColumnBinding` as a mechanical follow-up. Keeping it small was what let Step 4 ship without waiting on Phase 3.

**Block-or-land trigger (historical).** Step 4 shipped with `NestedInputId` because argres Phase 3 was still Unplanned at kickoff. The collapse into `InputColumnBinding` is tracked under Open points.

---

## Generators

Platform-id stops existing as a separate emission path. Projection and read-back shipped in Step 3 (raw key-column projection + `NodeIdEncoder.encode` in the DataFetcher). Input-side lookup / filter emission shipped in Steps 4–5. Remaining emission lands under R4–R6 below; what follows describes the emission shapes (shipped and remaining).

### Filter — lookup path

`LookupValuesJoinEmitter` dispatches on `LookupMapping` variant.

`NodeIdMapping` arm skips VALUES+JOIN entirely:

```java
Set<String> ids = /* extraction from env per CallSiteExtraction */;
return dsl
    .select(<typeFieldsCall>)
    .from(table)
    .where(ids.isEmpty()
        ? DSL.noCondition()
        : nodeIdStrategy.hasIds(
            "Customer", ids,
            new Field<?>[] { table.STORE_ID, table.CUSTOMER_ID }))
    .fetch();
```

Input-order preservation (`orderBy(input.idx)`) does not apply — `hasIds` is a set membership check, not a positional join. Legacy accepts this; we match.

### Filter — non-lookup path

Field-level `@condition` / plain-WHERE emission for a `NodeIdArg`:

```java
condition = id != null
    ? nodeIdStrategy.hasId("Customer", id, keyFields)
    : DSL.noCondition();
```

List variant uses `hasIds` with empty-list guard.

### Mutation input binding

`InputColumnBinding` (the shared type argres Phase 3 populates on `TableInputArg.fieldBindings`) includes a `NodeIdField` variant. The mutation generator dispatches:

```
NodeIdBinding  →  nodeIdStrategy.setId(record, input.getId(), typeId, keyFields)
```

Uses `NodeIdStrategy.setId(UpdatableRecordImpl, String, String, Field<?>...)` — already lives in `graphitron-common`. Same helper for migration-shim inputs and declared `@nodeId` inputs.

`InputColumnBinding` carries one `NodeIdBinding` sum variant that serves both `InputField.NodeIdField` (direct own-table bind) and `InputField.NodeIdReferenceField` (bind via `joinPath` or FK-mirror). The mutation generator dispatches on the binding variant, not on the `InputField` subtype, so the two classifier outputs converge on one emission path. Blocked on argres Phase 3.

### Output reference — `ChildField.NodeIdReferenceField` (R5)

`TypeClassGenerator.$fields` emits a JOIN-through `joinPath` and projects the target's `nodeKeyColumns` under an alias per step of the join. `TypeFetcherGenerator` wires a lambda that reads the aliased values off the record and calls `NodeIdEncoder.encode(typeId, …)` — the same encoder `NodeIdField` uses. Closes the `NodeIdReferenceField` slot in Generator stub #8; the other leaves in stub #8 (`ColumnReferenceField`, `ComputedField`, etc.) stay out-of-scope for this plan.

### Input reference — `InputField.NodeIdReferenceField`

Filter / lookup bind: decode via `NodeIdStrategy.unpackIdValues`, emit a JOIN through `joinPath` if the target isn't the input's own table (or collapse to direct column assignment when the own-table mirrors the target's key columns), then bind each unpacked value to its target column inside the JOIN. Both shapes reuse the `hasIds` / `hasId` helpers introduced for `InputField.NodeIdField`; the `LookupValuesJoinEmitter` `NodeIdMapping` arm extends to carry a `joinPath` alongside `nodeKeyColumns` for the reference variant.

The FK-mirror collapse is the *common case*, not an optimization: mutations and lookups that carry a reference via the owning table's foreign-key columns reach the target without any join. The emitter treats "collapse" as the default and emits the JOIN form only when `joinPath` cannot be satisfied by own-table columns.

Mutation bind for the reference variant lands through the same argres Phase 3 `InputColumnBinding` channel as `InputField.NodeIdField`; the `NodeIdBinding` variant covers both dispatch arms.

### `Query.node(id:)` and `_entities` dispatch (R4)

Both resolvers share one emission path, driven by a **`NodeTypeRegistry`** built at classify time from the set of classified NodeTypes:

```java
record NodeTypeRegistry(Map<String, NodeTypeEntry> byTypeId) {
    record NodeTypeEntry(String gqlTypeName, TableRef table, List<ColumnRef> keyColumns) {}
}
```

Built from `ctx.types.values()` filtered to `NodeType`. Uniqueness is validated at build time: if two NodeTypes share a `typeId`, *both* are demoted to `UnclassifiedType` with a collision message ("`typeId 'X' declared on both Foo and Bar`"). The rule is symmetric — we cannot pick a winner because the loser's IDs would thereafter be unrouteable.

**`QueryField.QueryNodeField` emission.** `TypeFetcherGenerator` wires a resolver that:

1. Reads the `id: ID!` argument from the `DataFetchingEnvironment`.
2. Calls `NodeIdStrategy.peekTypeId(id)` — a thin helper that decodes the base64, splits on the first `:`, and returns the prefix without full column unpacking. (Added to `graphitron-common` alongside `unpackIdValues`.)
3. Looks up the prefix in the registry. Unknown prefix ⇒ `return null` (Relay spec: "if no such object exists, the field returns null"). Registry hit ⇒ `SELECT <target.$fields> FROM <target.table> WHERE hasId(typeId, id, keyColumns) LIMIT 1`.
4. Returns the fetched row wrapped in a polymorphic Node branch, using the `ReturnTypeRef.PolymorphicReturnType` the classifier already produces for `QueryNodeField`.

**`QueryField.QueryEntityField` emission.** The Federation `_entities` resolver dispatches on representation shape. For representations of the form `{ __typename, id }` that match a NodeType, it uses the same registry + `hasId` path as `QueryNodeField`. Representations of other federation entities (non-node) route to their existing resolver implementations. Legacy's `FetchEntityImplementationDBMethodGenerator` — which dispatched on `processedSchema.isNodeIdField(field)` — is subsumed.

**Removed stubs after R4.** `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` entries for `QueryNodeField` and (for the `{__typename, id}` shape) `QueryEntityField`; the corresponding `stub(f)` dispatch arms.

---

## Pipeline-test fixture

Synthetic fixture shipped: `Bar` carries `__NODE_TYPE_ID="Bar"` + `__NODE_KEY_COLUMNS={BAR.ID_1, BAR.ID_2}`; `Qux` carries none. These cover the mechanics of reading metadata but assume the old "metadata alone promotes" semantics. Revised test coverage lands alongside the R-steps below.

**Type-level cases** (`TypeBuilder.buildTableType`; revised R1 + R2):

| SDL on `bar` (which carries metadata) | Expected outcome |
|-----|-----------------|
| `type Foo implements Node @table(name: "bar") @node` | `NodeType(typeId="Bar", keyColumns=[ID_1, ID_2])` — canonical path, metadata fills in defaults |
| `type Foo implements Node @table(name: "bar") @node(typeId: "Foo")` | `NodeType(typeId="Foo", ...)` — **SDL wins on typeId disagreement** (R1 change) |
| `type Foo implements Node @table(name: "bar") @node(keyColumns: ["ID_2", "ID_1"])` | `NodeType(keyColumns=[ID_2, ID_1], ...)` + **WARN** "order differs from metadata" (R1 change) |
| `type Foo implements Node @table(name: "bar") @node(keyColumns: ["ID_1"])` | `UnclassifiedType` — key column *set* differs from metadata (R1: error is reserved for set-level disagreement) |
| `type Foo @table(name: "bar") @node` (missing `implements Node`) | `UnclassifiedType` — schema contract violation (R1 validator) |
| `type Foo @table(name: "bar")` (no `@node`, no `implements Node`) | `TableType` — no promotion; migration shim does not fire (R2) |
| `type Foo @table(name: "bar") { … }` during migration window | `NodeType` + deprecation diagnostic — shim fires *because* the table has metadata (R2 + migration window) |
| `type Foo implements Node @table(name: "qux") @node` (no metadata) | `NodeType(typeId="Foo", keyColumns=[<qux PK>])` — PK fallback (R3 change; today records empty list) |
| `type Foo implements Node @table(name: "qux") @node(keyColumns: ["ID"])` | `NodeType(typeId="Foo", keyColumns=[QUX.ID])` — SDL-only path already worked |
| `type Foo implements Node @table(name: "bar") @node` + **another type** `type Baz implements Node @table(name: "baz") @node(typeId: "Bar")` | Both types → `UnclassifiedType` with collision diagnostic (R4 registry uniqueness check) |

**Input side** (`TypeBuilder.resolveInputField`; same SDL-wins and list-gate rules as output):

| SDL | Expected outcome |
|-----|-----------------|
| `input FooLookup @table(name: "bar") { id: ID! @nodeId }` | `InputField.NodeIdField(nodeTypeId="Bar", nodeKeyColumns=[ID_1, ID_2])` — canonical declared form |
| `input FooLookup @table(name: "bar") { id: ID! }` | `InputField.NodeIdField` + deprecation diagnostic — migration shim (R2) |
| `input FooLookup @table(name: "bar") { id: [ID!]! @nodeId }` | `UnclassifiedType` — one unresolved field collapses the `TableInputType` |
| `input FooLookup @table(name: "qux") { id: ID! @nodeId }` | `UnclassifiedField` on the id field — `qux` has no `@node` / metadata, so the input's own table is not a NodeType |

**Input reference side** (R-step follow-ups to shipped Step 5):

| SDL | Expected outcome |
|-----|-----------------|
| `input FooLookup @table(name: "bar") { relatedId: ID! @nodeId(typeName: "Qux") }` with `bar → qux` reference resolvable and `Qux` declared as NodeType | `InputField.NodeIdReferenceField(nodeTypeId="Qux", nodeKeyColumns=[QUX.ID], joinPath=[bar→qux])` |
| same as above but `Qux` not a NodeType | Classifier error — "referenced type is not a NodeType" |
| same but `[ID!]!` | `UnclassifiedType` — list-gate applies |
| same but no resolvable FK path from `bar` to `qux` | `UnclassifiedType` — reference-path parser rejects |

**`Query.node(id:)` execution cases** (R4; real-jOOQ test-spec on Sakila):

| Query | Expected outcome |
|-----|-----------------|
| `node(id: <Customer/8 base64>)` | resolves to `Customer { id: "<same base64>" }` — round-trip identity |
| `node(id: <Film/1 base64>)` | resolves to `Film { id: … }` — second NodeType in same schema; registry correctly routes |
| `node(id: <unknown-typeId base64>)` | `null` — per Relay spec |
| `node(id: "garbage-not-base64")` | `null` — opacity: decoding errors become not-found, not exposed to client |
| `node(id: <Customer/999999 base64>)` (valid prefix, no such row) | `null` — registered typeId but empty result |

**Output reference side** (R5 execution case): at least one real-jOOQ execution case covering projection + encoding through a single-hop FK path on the test-spec Sakila schema (e.g. `Film.languageNodeId: ID! @nodeId(typeName: "Language")` resolving through `film.language_id → language.language_id`).

**Durability regression test.** One pipeline case covers the R1 inversion directly: classify a type with `@node(typeId: "Foo")` against metadata `__NODE_TYPE_ID="bar"`, assert `typeId == "Foo"`. A second asserts that ID strings produced with one `keyColumns` order still decode under a fixture that pins the order — i.e. we don't accidentally regress the durability invariant through future refactors.

---

## Migration (from platform-id to declared `@node` / `@nodeId`)

The design that makes this migration cheap is simple: **the `__NODE_TYPE_ID` + `__NODE_KEY_COLUMNS` constants give the rewrite everything the legacy reflection-based platform-id detection used to compute at classify time, plus a direct path to the same runtime helpers (`NodeIdStrategy.createId` / `hasIds` / `setId`).** Reflection-based detection (`hasPlatformIdAccessors`, method-name scanning on jOOQ record classes) is redundant from the moment the generator ships the constants. That redundancy is what lets us cut legacy dead code aggressively instead of carrying it through a deprecation window.

KjerneJooqGenerator X.Y is live; Sikt's production schema (`alf/graphitron-rewrite`) already declares `implements Node @node` on every type that targets a metadata-carrying table. So the two migrations are at different points:

**Generator migration (done).** X.Y shipped. Consumers that haven't regenerated jOOQ still work via unmigrated builds; consumers that have regenerated see the constants.

**Reflection deletion (R6 — this plan, immediate).** The `PlatformIdField` sum-type variants, the `platformIdOutputMethodNames` / `hasPlatformIdAccessors` / `recordHasPlatformIdAccessors` catalog methods, the FieldBuilder / TypeBuilder reflection fallbacks, and the platform-id-specific validation tests are deleted. The rewrite classifies every platform-id table through the metadata path (synthesis shim + canonical `@node` + `@nodeId`). No deprecation window — the reflection path is dead code the moment the constants are present.

**Schema migration (R2 shim, soft).** The metadata-only synthesis shim promotes a metadata-carrying table without `implements Node @node` in SDL to `NodeType` with a deprecation diagnostic. This is the bridge that let R6 delete reflection without breaking consumers whose SDL hasn't caught up. Retired at R7 once the deprecation window closes.

**Why not wait.** Keeping reflection alive alongside metadata synthesis would force every change in the NodeId code path to be written twice (once for the reflection path, once for the metadata path), and every test to assert both outcomes. The cost compounds. Deleting reflection early is the single action that most reduces the ongoing maintenance surface.

---

## Scheduling

Commits, in order. Each lands independently and green. Steps 1–5 shipped on trunk under the earlier (platform-id-centric) framing; steps R1–R4 revise the shipped code to match the GOI semantics above before continuing to the remaining phase-2 work.

### Phase 1 (shipped)

1. Metadata probe in `JooqCatalog.nodeIdMetadata` / `nodeIdMetadataDiagnostic`.
2. `NodeType` synthesis (**revised at R1 + R2**: currently unconditional on metadata; narrows to require an affirmative Node signal).
3. `ChildField.NodeIdField` projection + `NodeIdEncoder.encode` in DataFetcher.
4. `InputField.NodeIdField` + `NodeIdArg` + `LookupMapping` sum type + lookup / filter emitter branches. `NestedInputId` permit added.
5. `InputField.NodeIdReferenceField` classifier.

### Phase 2 (remaining; R1–R6)

**R1. Collision-rule inversion + `implements Node` validator.** `TypeBuilder.buildTableType:298-311`: replace "error on any disagreement" with "SDL typeId wins; SDL keyColumns wins (WARN on order-only, ERROR on set-level disagreement)." `GraphitronSchemaValidator`: add a rule that `@node` requires `implements Node` on the same type (per the canonical legacy docs). Migrate the collision-case pipeline fixtures to the new expectations, and add the durability regression test.

**R2. Synthesis gate + migration deprecation diagnostic.** `TypeBuilder.buildTableType:247-266`: require `@node` *or* `implements Node` *or* a metadata-driven shim promotion, with the shim emitting the documented deprecation diagnostic. `FieldBuilder` Path-2 synthesized-`@nodeId` (`FieldBuilder.java:1968-1977`) carries the same diagnostic on the field level. Fixture updates: split the `bar` / `qux` pipeline cases into a "canonical" group (with `implements Node @node`) and a "migration shim" group (without). The input-side cases in `PlatformIdFieldValidationTest` (being renamed to `NodeIdFieldValidationTest`) get the same split.

**R3. Primary-key fallback for `@node` keyColumns.** `TypeBuilder.buildTableType:276-287` currently stores an empty `keyColumns` list when `@node` is declared without `keyColumns:` and no metadata. Resolve from `JooqCatalog.primaryKeyColumnsOf(tableName)` instead. Add `JooqCatalog.primaryKeyColumnsOf` if not already present. Pipeline case: `type Foo implements Node @table(name: "qux") @node` where `qux` has a single-column PK.

**R4. `Query.node(id:)` + Federation `_entities` + `NodeTypeRegistry`.** Build `NodeTypeRegistry` in the classifier, validate typeId uniqueness (collision demotes both types), land `QueryField.QueryNodeField` emission in `TypeFetcherGenerator`, extend `QueryField.QueryEntityField` emission to route `{__typename,id}` representations through the same registry. Add `NodeIdStrategy.peekTypeId(base64)` helper in `graphitron-common`. Remove `NOT_IMPLEMENTED_REASONS` for `QueryNodeField` at `TypeFetcherGenerator.java:213-214` + the `stub(f)` dispatch arm at `:347`. Execution coverage per the table above.

**R5. `ChildField.NodeIdReferenceField` emission.** `TypeClassGenerator.$fields` projects target-table key columns via the resolved JOIN; `TypeFetcherGenerator` wires the encode lambda. Remove the `NOT_IMPLEMENTED_REASONS` entry at `TypeFetcherGenerator.java:247-248` and the `stub(f)` dispatch arm at `:358`. Closes the `NodeIdReferenceField` slot in Generator stub #8. Execution-test coverage against the real-jOOQ test-spec.

**R6. Delete platform-id reflection (immediate; can land ahead of R1–R5).** The `__NODE_TYPE_ID` + `__NODE_KEY_COLUMNS` constants cover every classification case the reflection fallbacks handled. Sikt's production schema declares `implements Node @node` on every metadata-carrying type, so the metadata synthesis path (which is live, unconditional on trunk today) already classifies every node type. Reflection is dead code. Delete:

- `ChildField.PlatformIdField` record + its `permits` entry + the `$fields` arm in `TypeClassGenerator`.
- `InputField.PlatformIdField` record + its `permits` entry.
- `FieldBuilder` output-side platform-id fallback (`FieldBuilder.java:1978-1985`) + input-side platform-id fallback in `TypeBuilder.resolveInputField` (`TypeBuilder.java:632-649`).
- `JooqCatalog.platformIdOutputMethodNames`, `hasPlatformIdAccessors`, `recordHasPlatformIdAccessors`, and `sqlToAccessorSuffix` if no other caller survives.
- `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` / `IMPLEMENTED_LEAVES` entries + switch arms for `PlatformIdField`.
- `ChildPlatformIdFieldValidationTest`, `PlatformIdFieldValidationTest`, and the `PlatformIdField` assertions in `PlatformIdPipelineTest`. The pipeline test stays but asserts `NodeIdField` outcomes.
- Variant-coverage Phase-1 partition entries for `PlatformIdField`.
- `codereferences/dummyreferences/PlatformIdRecord` if unused elsewhere.

R6 depends on: KjerneJooqGenerator X.Y having shipped (done), and the current unconditional-metadata-synthesis behaviour on trunk (live since shipped Step 2). It does **not** depend on R1–R5; land it first to stop the dual-implementation maintenance cost immediately.

**R7. Retire the migration shim.** Once consumers have finished migrating SDL to declared `implements Node @node` + `@nodeId`, delete the metadata-only NodeType promotion branch in `TypeBuilder.buildTableType` (R2-introduced) and the synthesized Path-2 `@nodeId` branch in `FieldBuilder`. From here on, the directives are mandatory for node-identity semantics; the deprecation diagnostic becomes a terminal error. Timing: one release cycle after R1–R6 ship.

Mutation binding (input-side `setId` / column-bind) remains gated on argres Phase 3 and lands via that plan — as a unified `NodeIdBinding` in `InputColumnBinding`, with one dispatch arm serving both `InputField.NodeIdField` and `InputField.NodeIdReferenceField`.

---

## Compile-tier coverage

`graphitron-rewrite-test` compiles against a real jOOQ catalog, so emitted code should type-check against a real jOOQ table class: shipped Step 3's `DataFetcher` body (`r.get(Tables.X.COL)` + `NodeIdEncoder.encode(...)`) on the output side; `hasIds(...)` / `setId(...)` on the shipped Step 4–5 input side; the JOIN-projection + encode pair on the R5 output-reference side; and the `NodeIdStrategy.peekTypeId` + registry-dispatched `SELECT … WHERE hasId(...)` on the R4 `Query.node` side.

The shipped Step 1 took the do-nothing fallback — the test-spec has no metadata-carrying table today, so real-jOOQ compile/execution coverage for the migration-shim path waits on KjerneJooqGenerator X.Y shipping. The synthetic `platformidfixture` covers pipeline-level classification in the interim. If a pre-X.Y stopgap becomes worth the cost, the archived recipe is copy-and-edit: copy a leaf-table jOOQ class into `src/main/java/...`, add a jOOQ-plugin `<excludes>` entry, hand-edit the constants, revert post-X.Y. Rejected initially because non-leaf tables drag `Keys.java` into scope and the one-file estimate tends to grow.

The **opacity / durability invariants** get dedicated coverage outside the test-spec:

- *Opacity round-trip test.* Build a classifier fixture with a schema, encode a handful of IDs via `NodeIdStrategy.createId`, then refactor the fixture (rename the SDL type without changing `@node(typeId:)`, change the jOOQ table name without changing metadata `__NODE_TYPE_ID`), and assert the original IDs still resolve to the same rows. Guards R1 and the "SDL typeId wins" rule directly.
- *Unknown-typeId null test.* `Query.node(id:)` on a base64 decoding to `"NotARegisteredType:1"` returns null, not an error. Guards the Relay-spec conformance and the opacity posture (no internal leakage).

## Open points

- **`NestedInputId` → `InputColumnBinding` collapse.** Once argres Phase 3 lands `InputColumnBinding`, the `NestedInputId` permit shipped in Step 4 collapses into it — same path-walking semantics, wider binding type. Track as a mechanical cleanup commit after Phase 3; not a blocker. The permit is narrow enough that the collapse is local to two files (`CallSiteExtraction.java` + `LookupValuesJoinEmitter`).
- **`__NODE_KEY_COLUMNS` ordering stability guarantee.** An instance of the durability invariant. A silent reorder between KjerneJooqGenerator releases would re-encode new IDs in a different column order than decoded IDs produced pre-upgrade, and `hasIds` would match nothing — silently wrong results across the cut-over. Pin the ordering rule in the KjerneJooqGenerator design doc when the X.Y constants are drafted (e.g. declared-order in the composite-key DDL, then primary-key order, then alphabetical as a tiebreak). The rewrite treats the order as opaque but stable. Release-notes for X.Y+N must call out any ordering change as breaking.
- **Registry `typeId` uniqueness across federated subgraphs.** R4's registry validates uniqueness within a single schema. Under Apollo Federation, two subgraphs could independently declare the same `typeId` for *different* types, and the gateway would see two Node interfaces fighting over one prefix. This is a federation-composition concern rather than a single-service concern, but we should surface it in release notes and possibly as a lint-check in `_entities` emission.
- **`NodeIdStrategy` versioning / rotation.** Honouring the no-silent-rotation invariant permanently means either (a) never changing the encoding, or (b) adding a version discriminator now so we can evolve. Option (b) is cheap if we do it before release Y.Z and free if any existing IDs fit the default version. Track as a separate spec item; the plan does not require it, but the invariant does imply we eventually need it.
- **`NodeType` audit for null/empty guards.** After R1 + R3, `NodeType.typeId()` is guaranteed non-null and `NodeType.nodeKeyColumns()` is guaranteed non-empty (`implements Node @node` + SDL-wins + PK fallback eliminate all three ways those fields used to become null/empty). Audit every dereference and remove dead null-guards. Not a blocker; a follow-up cleanup commit.
- **What about `@node` on an interface?** Relay's `Node` interface is itself the archetype; consumers sometimes want a *custom* interface like `interface Timestamped implements Node { … }`. Out of scope for this plan — the classifier treats `@node` on interfaces as undefined behaviour today, and that stays true until a dedicated plan tackles abstract-type node identity.

