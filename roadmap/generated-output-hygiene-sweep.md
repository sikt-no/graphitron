---
id: R521
title: "Generated-output readability and hygiene sweep"
status: Backlog
bucket: cleanup
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Generated-output readability and hygiene sweep

A readability/debuggability audit of the full `graphitron-sakila-example` generated tree (691
files, 54k lines, 2026-07-24) surfaced a cluster of consumer-facing defects that are individually
small but collectively define the reading experience of emitted code. The calibration rule for
this item: **identical methods appearing in multiple files violate DRY and get deduplicated;
identical code inlined within different method bodies is fine and often desirable** (a specialized,
breakpointable frame beats generic indirection). Typed jOOQ row arities (`Row2`/`Row3`/`RowN`
follow from the schema's key shapes) stay specialized per call site; hoisting them into generic
helpers is explicitly out of scope.

## Cross-file identical methods (dedupe)

Verified byte-identical by hashing every copy:

- `graphitronContext(env)` in 57 classes. Natural home: a static `from(env)` on the generated
  `GraphitronContext` itself. Interacts with R85, which wants this helper *emitted into more*
  host kinds; deduplication may subsume that fix. Sequence knowingly.
- `scatterByIdx` (22 files), `scatterSingleByIdx` (18), `parentKeyCellValue` (29). None are
  Table-bound, so the helper-locality convention does not force locality; move to the generated
  `util` package.
- `decode<Type>KeysOrThrow`: the same per-type decoder is copied into every host that needs it
  (`decodeFilmKeysOrThrow` identical in `QueryConditions`, `LanguageFetchers`, and
  `types/Language`; `decodeCustomerKeysOrThrow` in `StoreFetchers` and `types/Store`). One home
  per type, e.g. on `NodeIdEncoder` next to the encoders.
- The 9-line error-mapping cause-chain loop is inlined 13 times in mutation catch blocks. It is
  schema-independent policy, and "why didn't my error mapping fire" deserves one breakpoint
  location: hoist to `ErrorRouter` as e.g. `mapToOutcomeOrRedact(e, mappings, env)`.

## Naming and constants

- Synthetic SQL column names are scattered raw literals: `"__idx__"` (123 uses), `"__typename"`
  (~200), `"__rn__"`, `"__pk0__"`, `"__sort__"`, `"__discriminator__"`, `"__rk_"` (89). Meanwhile
  `GraphitronValues` holds exactly one constant, `GRAPHITRON_INPUT_IDX` (`"graphitron_input_idx"`),
  referenced zero times, and VALUES aliases use a third convention (`"idx"`). Make
  `GraphitronValues` the documented single home for these names, reference the constants from all
  emitters, delete the dead one.
- Mutation input handling emits gensym locals (`cmic1_0`, `_saksaK0`, `grpMap_setKey0_0`,
  `insertKeyAgree0`); these are the variables a consumer inspects when a NodeId-composed insert
  fails. The emitter has the schema at hand; derive names from it (`detailsMap`,
  `decodedInReplyTo`), keeping counter suffixes only for disambiguation.
- Polymorphic dispatch passes `new Object[]{outerIdx, pks}` through
  `Map<String, List<Object[]>>` with positional casts on read-back
  (`EntityFetcherDispatch` adds a third slot for the environment). Emit a small record
  (output targets Java 17) so debugger views and casts are self-describing.
- The two-stage union dispatch names its intermediates `stage1`/`stage1_<Type>` with no statement
  of what the stages are. One emitted comment line per dispatch method describing the protocol
  (member-key union, per-type row fetch, index reassembly) orients every future reader.
- `FilmEndorsementNode`'s customized numeric typeId surfaces as a bare `"920534"` literal in
  `NodeIdEncoder`, `EntityFetcherDispatch`, and `QueryConditions`; nothing maps it back to the
  type. Emit a named constant or trailing type-name comment at each site.
- `QueryType` emits `appliedDirective_0/1/2` helpers; number them by field and directive instead
  (`appliedDirective_customers_auth`).

## Emission hygiene

- No `@Generated` annotation, no generated-file header, and (outside `inputs/`, `ErrorRouter`,
  `SelectionOccurrences`, `Outcome`) no javadoc anywhere: root fetcher classes are 2.5-3.2k lines
  with zero orientation. Emit `@Generated` plus a one-line javadoc per fetcher naming the GraphQL
  coordinate it serves, and a class-level on-ramp per category.
- 13 dangling `{@link}`s to generator-internal classes (`EntityFetcherDispatchClassGenerator`,
  `NodeIdEncoderClassGenerator`, `QueryNodeFetcherClassGenerator`) ship in generated javadoc;
  they are unresolvable on the consumer classpath, and one leaks into introspection via the
  `Query.nodes` description. Reference only symbols that exist in the generated artifact; use
  `{@code}` or prose for provenance.
- `$fields` switch arms splice pre-formatted blocks at a fixed indent column, so nested bodies
  render flush-left of their own `case` label (e.g. `types/Film.java`); the highest-traffic file
  a consumer opens looks malformed. Build arms with proper control-flow emission or post-format.
- `import java.lang.String;` etc. tops all ~375 files (javapoet `skipJavaLangImports` is off).
- `.where(DSL.noCondition())` appears 38 times, plus condition methods whose whole body is
  `return DSL.noCondition();` and `noCondition().and(x)` chains. Elide the no-ops so every
  emitted condition is load-bearing.
- Inconsistent utility-class hygiene: some util classes are `final` with private constructors,
  others (`ConnectionHelper`, `ConnectionResult`, `OrderByResult`, `LightFetcher`) are open with
  implicit public constructors. Pick one convention.
- `EntityFetcherDispatch` carries dead generality: `groups.computeIfAbsent(0, ...)` with a
  `switch (altIdx)` that only ever has `case 0`, and per-representation environments built but
  discarded (only the first is used). Emit the degenerate shape directly when one alternative
  exists. `typenameForTypeId` also rebuilds `Map.ofEntries` per call in a local named `MAP`;
  emit a `static final` field.
- NodeId rejection messages span three quality tiers, from value + expected/actual type
  (excellent) down to "did not match the expected type for this argument" in methods with several
  NodeId inputs. Standardize on the rich form everywhere.
- One emitted exception message uses an em dash and a Unicode "less than or equal" sign; reword
  in ASCII for log pipelines.

## Util template quality

The schema-independent `util` classes are static templates stamped per schema (verified
byte-identical across `util`, `federated/util`, `multischema/util`, `multischemamutation/util`),
so the generated-code duplication calibration does not excuse their internal quality; hold them to
hand-written standards:

- `ConnectionHelper.PageRequest`/`Edge`, `ConnectionResult` (11-arg constructor plus telescoping
  `null, null, null` overloads), and `OrderByResult` are hand-rolled pre-record carriers with no
  `toString`; emit records and named factories.
- `ConnectionHelper`'s public entry points have no javadoc, and nothing documents that
  `PageRequest.limit` is `pageSize + 1` (the hasNextPage look-ahead) while `trimmedResult()`
  silently relies on it. Document the cursor wire format (`NUL`-joined, `\u0001` for SQL NULL,
  base64) on `encodeCursor`.
- `ConnectionResult.beforeCursor` is stored but never read; `hasNextPage`/`hasPreviousPage`
  consult only `afterCursor` in both directions. Drop the field or document the intended
  backward-paging semantics.
- The `multitenant` variant's `ConnectionResult`/`ConnectionHelper` differ in shape (extra
  `DSLContext` field, different `totalCount`/`facets` resolution) while keeping the same class
  names as the other four copies; the same name must always mean the same code. Merge behind the
  context lookup or rename the variant.

Statement-form defects (nested instanceof-ternary chains, insert-value ternaries, repeated inline
discriminator expressions) are tracked by R334, whose scope this audit expands; they are out of
scope here. Coverage stays at the compile/execution tier; pipeline tests must not assert on
generated method bodies. The item is sliceable at Spec time (dedup, naming, hygiene, util quality
are independently landable).
