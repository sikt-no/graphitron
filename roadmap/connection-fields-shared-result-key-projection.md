---
id: R499
title: "Relay connection $fields projects only the first occurrence of a shared result key (edges.node vs nodes divergence)"
status: In Progress
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-07-17
last-updated: 2026-07-17
---

# Relay connection $fields projects only the first occurrence of a shared result key (edges.node vs nodes divergence)

The generated `<Node>.$fields(...)` projection builder discards every occurrence of a shared GraphQL result key except the first. `TypeClassGenerator.emitSelectionSwitch` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeClassGenerator.java:318-319`) iterates `sel.getFieldsGroupedByResultKey().entrySet()` and binds `SelectedField sf = entry.getValue().get(0)`. `entry.getValue()` is a `List<SelectedField>` precisely because one result key can carry several selected fields with divergent sub-selections. In a Relay connection (`@asConnection`) the `edges { node { <ref> } }` and `nodes { <ref> }` selections collapse to the same result key `<ref>`, so the list holds both occurrences; the descend-into-nested arms (`NestingField`, `TableField`, `LookupTableField`, `ColumnReferenceField` at `TypeClassGenerator.java:336-355`) recurse over `sf.getSelectionSet()`, i.e. only `get(0)`'s (the `edges.node`) sub-selection. Any reference sub-field requested under `nodes` but absent from `edges.node` is therefore never added to the SELECT, and the `nodes` mapper reads a column absent from the row type. This surfaced during runtime testing of the opptak subgraph against graphitron 10.0.0-RC27: selecting a reference/FK-joined field under both `edges { node { ... } }` and `nodes { ... }` with different sub-selections yields per-row `Exception while fetching data ...: Field "organisasjon" is not contained in row type (...)` errors and silent `null` data on the diverging side (a jOOQ "not contained in row type" failure, not a hard 500). It reproduces at both first-level and deep nesting, in every direction where one side is not a subset of the other (edges ⊂ nodes, nodes ⊂ edges, disjoint), and is symmetric across query roots (`kvotetyperV2`, `poengformlerV2`). Scalar columns of the node's own table are unaffected, because the scalar arms (`ColumnField`/`CompositeColumnField`, `TypeClassGenerator.java:323-335`) never read the sub-selection and the base row is always fully projected; identical-on-both-sides and superset/subset-favouring-`edges` selections also pass today. Affects every `@asConnection` query for any reference field (~20 `*V2` roots in opptak), and is realistically triggered when independent client fragments merge into one operation or a federation router composes across `edges`/`nodes`.

Note: this is distinct from R481 (batched polymorphic parent-holds-FK correlation), which shares the jOOQ "not contained in row type" symptom string but is a different code path.

---

## Mechanism (traced; corrects two Backlog claims)

How the two occurrences end up in one list: the connection fetcher
(`TypeFetcherGenerator.buildQueryConnectionFetcher`, the `pageRequest` emit around
`TypeFetcherGenerator.java:5179`) passes the raw `env.getSelectionSet()` into
`<Node>.$fields(...)`. graphql-java's `DataFetchingFieldSelectionSet.getFieldsGroupedByResultKey()`
flattens the *whole* subtree and groups by leaf result key, not by path, so
`edges { node { <ref> {a} } }` and `nodes { <ref> {b} }` contribute two `SelectedField`s to the
`<ref>` entry. The connection wrapper keys (`edges`, `node`, `nodes`, `pageInfo`, `cursor`,
`totalCount`) fall through the switch's `default -> { }` arm; the flattened substrate itself is
long-standing behaviour and stays unchanged by this item. Polymorphic connections route through the
generated `PolymorphicSelectionSet.restrictTo(...)` view, which preserves full occurrence lists per
key (`PolymorphicSelectionSetClassGenerator.java:68-86`) before feeding the same `$fields` loop, so
one fix at the loop covers plain and polymorphic connections; `TypeClassGenerator.java:319`
(`entry.getValue().get(0)`) is the single defect site in the reactor.

Two corrections to the Backlog paragraph above:

1. The scalar `ColumnReferenceField` arm does *not* descend and reads nothing off `sf`:
   `InlineColumnReferenceFieldEmitter` materialises its table expressions with
   `ArgumentValueSource.Env` and keeps the `sfName` parameter only for signature symmetry. The
   occurrence-sensitive arms are `TableField` and `LookupTableField` (both descend via
   `sf.getSelectionSet()` into `Target.$fields(...)` and read runtime arguments off `sf` via
   `ArgumentValueSource.FromSelectedField`: join-path routine args, filter args, JooqConvert key
   lifts, pagination `first`), plus `NestingField`'s inline recursion (descent only, no argument
   reads).
2. "Unioning into the `LinkedHashSet`" cannot be done by re-emitting an arm per occurrence: the
   inline arms mint one `DSL.multiset(...).as(<fieldName>)` per arm, `.as(...)` produces a fresh
   jOOQ `Field` every call (the set dedupes raw `table.X` adds only, by jOOQ per-alias `TableField`
   caching), so per-occurrence re-emission would put duplicate SQL aliases in the SELECT. The union
   must happen on the *selection* side, merging all occurrences' sub-selections into a single arm
   emission.

## Contract

1. **Union descent.** For a result-key entry with N occurrences, every nested descent (inline
   `TableField` / `LookupTableField` projection, `NestingField` recursion) operates on the union of
   all N occurrences' sub-selections, recursively at every depth. Each arm still emits exactly one
   SELECT term per result key. Any sub-field requested under either `edges.node` or `nodes` is
   projected; the mapper on each side reads only what it asked for, and extra columns in the row are
   harmless.
2. **Fail loud on unrepresentable divergence.** Occurrences under one result key that disagree on
   `getName()` or on `getArguments()` throw a descriptive runtime exception naming the field and the
   conflicting values, surfacing as a GraphQL field error. Rationale: the occurrence set is a
   runtime query artifact with no build-time home, so a runtime guard is the only available
   enforcer; today's behaviour (silently serving `get(0)`'s arguments for both paths) is silent
   wrong data. Both halves of the check are reachable, not dead code: `edges.node.<ref>` and
   `nodes.<ref>` live in sibling selection sets that GraphQL field-merging validation never merges,
   so divergent arguments *and* divergent underlying field names (two fields aliased to one result
   key, `x: a` under one path and `x: b` under the other) are legal at the GraphQL layer and only
   graphitron's flattening collapses them into one bucket. The two halves have different scopes,
   which the Design pins precisely: the `getName()` half is **universal** (a single-name `switch`
   dispatch over a mixed-name bucket is unrepresentable for *every* arm, arg-consuming or not),
   while the `getArguments()` half is **arm-scoped** (only an arm that consumes `sf` for runtime
   state can serve wrong arguments).
3. **Scalar arms unchanged.** `ColumnField` / `CompositeColumnField` / `ComputedField` /
   `ColumnReferenceField` read nothing beyond the switch key.
4. **Pure emit-layer fix.** No classification, model, or validator change; query shape is runtime,
   so no validator mirror is owed.

## Design

Generated-code shape:

- The public `$fields(DataFetchingFieldSelectionSet sel, T table, env)` entry keeps its signature
  (all existing fetcher call sites unchanged) and delegates into the switch loop over
  `Map<String, List<SelectedField>>`.
- Each type class additionally exposes `$fields(List<SelectedField> occurrences, T table, env)`:
  it merges `occ.getSelectionSet().getFieldsGroupedByResultKey()` across the occurrences
  (concatenating lists per key, insertion-ordered) and runs the same switch loop. The inline
  `TableField` / `LookupTableField` arms call `Target.$fields(entry.getValue(), alias, env)`
  instead of `Target.$fields(sf.getSelectionSet(), alias, env)`; the `NestingField` arm's inline
  recursion iterates the merged map of `entryN.getValue()` instead of
  `sfN.getSelectionSet().getFieldsGroupedByResultKey()`.
- The merge and the divergence guard are schema-independent graphql-java manipulation (identical
  bytecode for every type), so they live as statics on a util-singleton scaffold registered in
  `UtilSingleton.ALL` (the sanctioned pruning-safe pattern; a blanket edge into a frozen-ABI
  singleton is a harmless superset per `UtilSingleton.java:28-33`), *not* as per-class private
  helpers, which would be a copy maintained apart from its source at generated-code scale.
  Design fork on the host: extend the existing selection-focused scaffold
  (`PolymorphicSelectionSet`) versus mint a small sibling (e.g. `SelectionOccurrences`).
  Recommendation: a new sibling, so the existing scaffold's frozen ABI is not touched; the
  implementer confirms against `UtilSingleton`'s freeze semantics.
- The guard is two checks with two scopes; conflating them reopens the bug:
  - **Name check (universal).** Emitted once per merged bucket, before the `switch` dispatch, for
    *every* arm. The switch routes on `get(0).getName()` and the union descent then merges all
    occurrences' sub-selections under that one name; if the occurrences disagree on `getName()`
    (two distinct fields aliased to one result key) the merge would run one field's arm over
    another field's sub-selection, silently dropping the diverging side, exactly the bug class this
    item fixes. This is unrepresentable regardless of whether the arm reads `sf`, so a
    `NestingField` / `ColumnReferenceField` / scalar bucket must fail loud too, not just the
    arg-consuming arms. The check is schema-independent, so it lives on the merge scaffold and runs
    for free per bucket.
  - **Argument check (arm-scoped, not a hardcoded `{TableField, LookupTableField}` list).** Whether
    an arm's emission consumes `sf` for runtime state is a structural fact already carried by its
    `ArgumentValueSource.FromSelectedField` usage (join-path args, filters, key lifts, pagination),
    so the argument-guard emission derives from that same predicate. A future variant that emits a
    `FromSelectedField` read then gets the argument guard for free instead of silently reverting to
    first-occurrence argument picking. Gating this half on the predicate (rather than guarding
    arguments universally) avoids a false-positive fail-loud on an argument some arm carries but
    never consumes; the comparison is over the full `getArguments()` map, which is conservative but
    errs toward fail-loud on the arms that do consume it (their consumable args *are* the field's
    args).
- After the guard passes, `get(0)` serves as the canonical occurrence for argument reads, now
  provably equivalent to every other occurrence. Singleton lists (the overwhelmingly common case)
  short-circuit both guard and merge.

## Implementation plan

1. **Scaffold.** Add the occurrence-merge and consistency-guard statics to the chosen util
   singleton (design fork above); register in `UtilSingleton.ALL`; emit test beside it (the
   `PolymorphicSelectionSetClassEmitTest` pattern). The merge groups by result key, so duplicate
   leaf selections across the two sides collapse into one bucket and each arm still emits exactly
   one SELECT term per key; no separate dedup pass is owed. The universal name check lives here and
   runs once per bucket.
2. **`TypeClassGenerator`.** Restructure `build$FieldsMethod` / `emitSelectionSwitch`: entry-method
   delegation, the `List<SelectedField>` overload, merged-map iteration in the `NestingField` arm,
   the universal name-consistency check per bucket (before the `switch`), and the arm-scoped
   argument-consistency check driven by the `FromSelectedField` predicate.
3. **Inline emitters.** `InlineTableFieldEmitter` / `InlineLookupTableFieldEmitter`: swap the
   `sf.getSelectionSet()` descent for the occurrence-list overload; argument reads keep `sf`
   (post-guard canonical).
4. **Compile graph.** `CompileDependencyGraphBuilder`'s projection mirror
   (`addProjectionChildEdges`) should need no new per-type edges (nested arms already depend on the
   target type class; the scaffold edge is blanket); verify the mirror stays in sync.
5. **Pinned-output refresh.** Existing unit/pipeline pins of `$fields` emission
   (`TypeClassGeneratorTest` and friends) update with the restructure.

## Implementation notes (In Progress)

- Design fork resolved as recommended: a new sibling scaffold `SelectionOccurrences`
  (`SelectionOccurrencesClassGenerator`, registered as `FrozenScaffold` in `UtilSingleton.ALL`).
  Freeze-semantics confirmation: the class is schema-independent graphql-java manipulation, so its
  public ABI never moves on a schema edit; the `FrozenScaffold` variant (blanket-edge-safe) is the
  correct classification, and `PolymorphicSelectionSet`'s frozen ABI stays untouched.
- Generated shape: two public `$fields` entries (`DataFetchingFieldSelectionSet` and
  `List<SelectedField>`) delegate into one private `$fieldsGrouped(Map<String, List<SelectedField>>,
  T, env)` switch loop; the `@SuppressWarnings("unchecked")` stamp moves to `$fieldsGrouped` (the
  narrowest enclosing member holding the casts).
- The universal name check is `SelectionOccurrences.canonical(key, occurrences)`, which replaces the
  loop's `entry.getValue().get(0)` binding, so it runs per bucket before every switch dispatch by
  construction. The arm-scoped argument guard is emitted by the inline emitters themselves
  (`InlineTableFieldEmitter.readsSelectedFieldArguments` mirrors the arm's
  `FromSelectedField`-consumption sites clause for clause; the lookup arm guards unconditionally
  because its input-rows helper always reads the `@lookupKey` argument), keeping the predicate in
  the same file as the emission it tracks.
- Contract refinement discovered at the execution tier: a plain runtime exception thrown from the
  guards is redacted by `ErrorRouter` to "An error occurred. Reference: <uuid>", defeating the
  "descriptive" half of contract point 2. The guards therefore throw the generated
  `GraphitronClientException` (the client-mistake marker `ErrorRouter.surfaceClientErrorOrRedact`
  surfaces raw), the same disposition as `ConnectionHelper`'s malformed-cursor guard; the
  divergence genuinely is a client query mistake. Scaffold-to-scaffold references need no compile
  graph edge (frozen scaffolds are exempt as reference-walk oracle sources).
- Compile graph: one new blanket frozen-scaffold edge per type class
  (`addTypeProjectionEdges` → `util.SelectionOccurrences`), beside the existing
  `GraphitronClientException` blanket; `addProjectionChildEdges` needed no per-type changes, as the
  Spec predicted.

## Test plan (tiers per `development-principles.adoc`)

- **Unit:** emit-shape tests beside `TypeClassGenerator` pinning that the overload exists with the
  right signature and that guard emission tracks the `FromSelectedField` predicate; scaffold emit
  test for the merge/guard statics. No code-string assertions on method bodies (banned at every
  tier).
- **Pipeline:** pin that generated type classes expose both `$fields` entries and that fetcher call
  sites still target the `DataFetchingFieldSelectionSet` entry; signature-level TypeSpec assertions
  only.
- **Compilation:** `graphitron-sakila-example` compiles the restructured output (automatic).
- **Execution** (`GraphQLQueryTest` or a new `@ExecutionTier` companion in
  `graphitron-sakila-example`; existing `@asConnection` fixtures such as `stores` / `filmsFaceted`
  suffice for the shape):
  - a connection query selecting the same reference field under both `edges { node { ... } }` and
    `nodes { ... }` in all four directions (edges ⊂ nodes, nodes ⊂ edges, disjoint, identical),
    asserting both sides resolve with correct data and no "not contained in row type" error;
  - a deep-nesting variant (the divergence one level down);
  - a polymorphic-connection variant (e.g. `searchConnection`);
  - an argument-divergence query asserting the descriptive field error (add a fixture if no
    argument-bearing inline field currently sits under a connection node);
  - a name-divergence query on a non-arg-consuming arm: the same result key aliased to two distinct
    fields across `edges { node { x: a { ... } } }` and `nodes { x: b { ... } }` (a `NestingField` /
    reference bucket, not a `TableField`), asserting the descriptive fail-loud rather than the silent
    drop that first-occurrence dispatch would produce, proving the name check is universal and not
    gated on the argument-consumption predicate;
  - a non-connection control query, proving behaviour parity on the restructured shared path for
    single-occurrence selections.
- **Determinism:** `GeneratorDeterminismTest` covers the new emission cross-cuttingly; no dedicated
  work unless it fires.

Acceptance-criterion correction versus the Backlog paragraph: "identical-on-both-sides selections
leave generated output byte-unchanged" is unsatisfiable as written, because the restructure changes
every type class's generated source regardless of query shape (generation is per-schema; the
projection choice is runtime). The criterion this Spec pins instead: identical and single-occurrence
selections are behaviourally identical to pre-change at the execution tier, and generated-source
determinism stays guarded by `GeneratorDeterminismTest`.

## Out of scope

- **R500** (`result-key-aware-reference-projection`, filed from this trace): two different aliases
  of the same reference field (`a: ref { x } b: ref { y }`) occupy *distinct* result-key buckets
  with the same `getName()`, so they never reach this item's within-bucket guard; both arms project
  `.as(<fieldName>)` and collide on the SQL alias. Fails loud today (jOOQ duplicate-alias error, no
  silent wrong data) and needs result-key-based aliasing plus result-key-aware readers; orthogonal
  to this fix.
- The whole-subtree-flattening substrate itself (wrapper keys and foreign sub-field names swept
  into the map and tolerated by the `default` arm) stays as-is.

## Cross-references

- **R481** (`batched-polymorphic-parent-holds-fk-correlation`) shares the jOOQ "not contained in
  row type" symptom string on a different code path; neither item depends on the other.
- **R500** (above) is the sibling defect on the aliased-duplicate axis.
- Surfaced during runtime testing of the opptak subgraph against graphitron 10.0.0-RC27; the
  reproduction matrix in the problem statement above is the field evidence.
