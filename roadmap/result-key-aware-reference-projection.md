---
id: R500
title: "Aliased duplicate reference selections mint duplicate SQL aliases ($fields projects by field name, not result key)"
status: Spec
bucket: bug
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-07-17
last-updated: 2026-07-17
---

# Aliased duplicate reference selections mint duplicate SQL aliases ($fields projects by field name, not result key)

Selecting the same reference field twice under different aliases with divergent sub-selections (`a: ref { x } b: ref { y }`) produces two entries in `getFieldsGroupedByResultKey()` whose `SelectedField.getName()` is identical, so the generated `$fields` switch (`TypeClassGenerator.emitSelectionSwitch`) fires the same inline arm for both entries and projects two `DSL.multiset(...).as("<fieldName>")` terms with the same SQL alias; the per-field readers in `FetcherEmitter` also read by field name, so even absent the SQL alias collision the two aliases could not be told apart on the read side. The failure is loud today (duplicate-alias jOOQ error, not silent-wrong data), which is why this is filed separately rather than folded into R499: R499's occurrence merge keys on name+arguments *within* one result-key bucket, while this defect spans *distinct* result-key buckets and needs projections aliased by result key (`entry.getKey()`) plus result-key-aware readers (e.g. reading the column by `env.getField().getResultKey()` instead of the schema field name). The two fixes are orthogonal; this one is lower priority because no silent data corruption occurs. Surfaced during the R499 Spec trace.

---

## Mechanism (traced; confirms and extends the Backlog paragraph)

Write side: `TypeClassGenerator.emitSelectionSwitch`
(`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeClassGenerator.java:318-320`)
iterates result-key buckets but dispatches on `sf.getName()`, so two buckets whose occurrences share
a field name fire the same arm twice. Every arm that mints a projection alias uses the
generation-time field name, and all five sites are affected:

- `InlineTableFieldEmitter.java:139`: `fields.add(DSL.multiset(inner).as(tf.name()))`;
- `InlineLookupTableFieldEmitter.java:138` (empty-input `falseCondition` branch) and `:181` (main
  branch): `.as(lf.name())`;
- `InlineColumnReferenceFieldEmitter.java:62` (direct column shape) and `:93` (correlated subquery
  shape): `.as(crf.name())`;
- the `ComputedField` arm, `TypeClassGenerator.java:358`: `.as(cf.name())`.

Read side: the four alias-read families pick the value off the parent record by the same
generation-time name, and all are *source-only* `LightFetcher` bindings:
`FetcherEmitter.columnByAlias` (`FetcherEmitter.java:524`,
`((Record) source).get(DSL.field(name))`) serves list-cardinality `TableField`,
`LookupTableField`, `ComputedField`, and Direct-compaction `ColumnReferenceField`; the
single-cardinality `TableField` unwrap (`FetcherEmitter.java:476`) reads
`source.get(name, Result.class)`.

Fetcher registration is per schema coordinate (`FetcherRegistrationsEmitter`,
`FieldCoordinates.coordinates(type, field)`), so one `DataFetcher` serves every alias of a field
and only the env (`env.getField().getResultKey()`; verified present on `graphql.language.Field` in
graphql-java 25.0) distinguishes invocations. A source-only read cannot, and probing the record for
whichever aliased column is present misreads the mixed case (aliased plus unaliased selection of
the same field in one query: the probe finds the unaliased column and serves it to both). The light
path is therefore unsalvageable for these four families; they move to env-dependent bindings.
Scalar column reads keep `LightFetcher`.

Already correct, and pinned unchanged by this item:

- Scalar `ColumnField` / `CompositeColumnField` arms add the raw `table.COL` instance (the
  `LinkedHashSet` dedupes duplicate adds of the same jOOQ `Field`) and read back through typed
  column constants, which are alias-independent; duplicate aliases of plain scalar columns work
  today.
- DataLoader-backed reference fields: the loader name is the `ResultPath`'s named segments
  (`DataLoaderFetcherEmitter.buildDataLoaderName`), so aliased uses get distinct path segments and
  distinct loaders.
- `ParticipantColumnReferenceField` carries its alias basis on the model (`aliasName()`) and
  projects a scalar column; duplicate aliases read the same value, correctly.

Two observations beyond the Backlog text:

1. **Bare result keys would hand the SQL alias namespace to clients.** Today minted SQL aliases are
   schema-controlled. `.as(entry.getKey())` verbatim would let a client-chosen alias collide with a
   base-column projection (jOOQ resolves ambiguous name reads to the first match: silent wrong
   data, the failure class this codebase refuses), with the reserved `__src_<col>__` full-row
   aliases (GraphQL reserves `__` names for schema definitions only; document aliases are
   unrestricted, so `__src_actor_id__: ref` is a legal query), or with the polymorphic
   `__discriminator__` projection. The fix must mint aliases through a reserved prefix.
2. **Divergent arguments across aliases become correct for free.** Each result-key bucket emits its
   own arm with its own `SelectedField`, so `a: ref(f: 1) { x } b: ref(f: 2) { y }` renders two
   independent correlated subqueries, each reading its own arguments. This complements R499's
   within-bucket guard, which throws on argument divergence *inside* one bucket where
   per-occurrence emission is impossible.

## Contract

1. **Result-key-distinct projection.** Aliased duplicate selections of the same inline reference or
   computed field produce one SELECT term per result-key bucket, each under a distinct SQL alias
   derived from the runtime result key. Each bucket's arm descends its own sub-selection and reads
   its own arguments; divergent sub-selections and divergent arguments across aliases both resolve
   independently and correctly.
2. **Reserved alias namespace.** Minted projection aliases are `"__rk_" + resultKey`, with the
   prefix single-homed as a generation-time constant in `GeneratorUtils` beside
   `reservedSourceAlias` (the existing `__src_` precedent). Client-chosen aliases can then never
   collide with base-column projections, `__src_<col>__`, `__discriminator__`, or each other
   (result keys are unique per flattened map by construction; an adversarial `__rk_foo` alias mints
   `__rk___rk_foo`, still distinct).
3. **Result-key-aware reads.** The four alias-read families (list and single `TableField`,
   `LookupTableField`, `ComputedField`, Direct `ColumnReferenceField`) become env-dependent
   bindings reading by `"__rk_" + env.getField().getResultKey()`.
4. **Scalar arms unchanged.** `ColumnField` / `CompositeColumnField` projection and typed-constant
   reads stay as-is; they are alias-correct today.
5. **Unaliased parity.** For selections without aliases, resultKey equals the field name; behaviour
   at the execution tier is identical to pre-change (SQL alias strings gain the prefix, a cosmetic
   change).
6. **Pure emit-layer fix, execution-tier enforcer.** No classification, model, or validator change
   (query shape is runtime; no new stubbed variant appears, so no validator mirror is owed). The
   failure class is a jOOQ runtime error invisible to pipeline-tier `TypeSpec` assertions, so the
   enforcer for this fix is the named execution-tier tests below.

## Design

- **Prefix constant, not a runtime scaffold.** The alias transform is a bare prefix concatenation
  of a GraphQL Name (no sanitization needed for a jOOQ alias), so it does not earn an emitted
  runtime helper: the write side emits `.as("__rk_" + <entry>.getKey())` and the read side
  `DSL.field("__rk_" + env.getField().getResultKey())`, both driving off one
  `GeneratorUtils` constant (`RESERVED_RK_ALIAS_PREFIX` or similar). This mirrors
  `reservedSourceAlias`, keeps the generated code legible inline, and avoids a `UtilSingleton`
  classification plus coupling to R499's scaffold landing order. The prefix reaches generated code
  only inside string literals, the same habitat as `__src_` / `__discriminator__`, so the
  dunder-identifier lint is untouched.
- **Write side.** `emitSelectionSwitch` threads the per-depth entry variable name into the inline
  emitters (they currently receive only `sfName`); each of the five `.as($S)` sites becomes the
  runtime alias expression. Polymorphic types route through `PolymorphicSelectionSet.restrictTo`
  into the same loop, so one fix covers plain and polymorphic paths.
- **Read side.** `columnByAlias` and the single-cardinality unwrap switch from `sourceOnly` to
  `envDependent` bindings (both registration shapes already exist; the registration value changes
  from `new LightFetcher<>(Fetchers::x)` to `Fetchers::x`). `LightFetcher` itself is untouched
  (frozen scaffold; scalar reads remain its consumers).
- **Membership single-home (design fork).** The set of variants that project-under-alias (write
  switch arms) and the set that read-back-by-alias (`FetcherEmitter` chain) are two hand-enumerated
  lists that must agree; this agreement predates R500 (it was already load-bearing under
  name-keying) and R500 changes only the key. Recommendation: reify the axis as a small capability
  interface on `ChildField` (e.g. `ResultKeyAliasedField`, precedent `SqlGeneratingField` /
  `MethodBackedField`), implemented by the four families and consumed by both sides, so a future
  variant that projects under an alias but forgets the read side is a visible omission rather than
  a reincarnation of this bug. If the implementer judges that out of scope for a bug fix, the Spec
  records the split as pre-existing and accepted.
- **Interplay with R499.** Same loop and emitters, orthogonal axes: R499 merges occurrences within
  a bucket, R500 aliases across buckets. Either lands first; the second rebases mechanically. No
  semantic interaction (R499's merge and guard stay within-bucket; R500's per-bucket arms each get
  R499's union descent).

## Implementation plan

1. **Prefix constant.** `GeneratorUtils` gains the reserved result-key alias prefix beside
   `reservedSourceAlias`, javadoc'd as the single home for both emission sides.
2. **`TypeClassGenerator`.** Thread the entry variable into the arm emissions; swap the
   `ComputedField` arm's alias; pass the alias expression to the inline emitters.
3. **Inline emitters.** All five `.as(<name>)` sites (`InlineTableFieldEmitter`,
   `InlineLookupTableFieldEmitter` x2, `InlineColumnReferenceFieldEmitter` x2) take the runtime
   alias expression.
4. **`FetcherEmitter`.** The four families move to env-dependent result-key reads; membership
   capability interface per the design fork (or the recorded acceptance).
5. **Pinned-output refresh.** Unit and pipeline pins of `$fields` emission and fetcher bindings
   update with the restructure.
6. **Execution-tier tests** as below.

## Test plan (tiers per `development-principles.adoc`)

- **Unit:** emit-shape tests pinning that arm emissions alias by the prefixed runtime result key
  and that the four families bind env-dependent readers; no code-string assertions on method
  bodies.
- **Pipeline:** signature-level TypeSpec assertions that the alias-read fetcher methods take
  `DataFetchingEnvironment` and registrations reference them bare.
- **Compilation:** `graphitron-sakila-example` compiles the restructured output (automatic).
- **Execution** (`GraphQLQueryTest` or an `@ExecutionTier` companion; these are the enforcers per
  contract item 6):
  1. `a: ref { x } b: ref { y }`: divergent sub-selections under two aliases resolve independently
     with correct data on both sides, no duplicate-alias error; variants covering inline
     `TableField` (list and single cardinality), `LookupTableField`, Direct
     `ColumnReferenceField`, and `ComputedField`;
  2. mixed aliased plus unaliased selection of the same field in one query (`ref { x } a: ref { y }`),
     the case that kills any source-only or record-probing read;
  3. adversarial client alias: `__rk_foo: ref { x }` alongside `foo: ref { y }`, pinning that the
     prefix separates the namespaces rather than asserting it in prose; a base-column-named alias
     variant (e.g. an alias equal to a projected column's SQL name);
  4. divergent arguments across aliases (`a: ref(f: 1) b: ref(f: 2)`), converting the
     comes-for-free claim into an enforced behaviour;
  5. aliased duplicates nested one level down (`NestingField` recursion) and under an
     `@asConnection` query (both `edges.node` and `nodes` sides);
  6. unaliased control queries asserting behaviour parity on the restructured path.
- **Determinism:** `GeneratorDeterminismTest` covers the emission cross-cuttingly; no dedicated
  work unless it fires.

## Out of scope

- **R499** (within-bucket occurrence merge and argument-divergence guard); same loop, orthogonal
  axis, either landing order works.
- DataLoader-backed reference fields (already alias-safe via path-scoped loader names).
- Scalar `ColumnField` / `CompositeColumnField` and `ParticipantColumnReferenceField` (already
  alias-correct as traced above).
- The whole-subtree-flattening substrate (unchanged, per R499's same boundary).

## Cross-references

- **R499** (`connection-fields-shared-result-key-projection`): sibling defect on the shared-bucket
  axis; its Spec's mechanism section documents the flattening substrate both items sit on.
- Surfaced during the R499 Spec trace; the loud duplicate-alias failure mode is why this item is
  priority 4 to R499's 3.
