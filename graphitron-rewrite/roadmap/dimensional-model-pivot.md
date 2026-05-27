---
id: R222
title: "Dimensional model pivot: slots over cross-product permits"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-21
last-updated: 2026-05-25
---

# Dimensional model pivot: slots over cross-product permits

R222 is the umbrella for the rewrite's dimensional pivot. Three sealed hierarchies pack multi-dimensional information onto single permit sets — input-side classification (`GraphitronType.InputType` + `TableInputType`), field-side classification (`QueryField` / `MutationField` / `ChildField` with 46 cross-product permits), and classification-failure encoding (`UnclassifiedType` / `UnclassifiedField` riding as permits alongside legitimate carriers). The same disease in three organs. R222 absorbs R164 (field-model three-dimension pivot) and R226 (type-level classification failure pivot) and unifies them as one architectural shift, landing the target architecture stage-by-stage through independent spin-out slices.

## Direction, not contract

The model sketched in this umbrella is the *target direction* — where the rewrite is heading. Specific slot names, carrier shapes, the boundary between walker carriers and dimensional slots, and the vocabulary itself are expected to shift as implementation slices land and surface new understanding. Each spin-out slice gets its own spec item where the specifics for that scope get pinned; the slice is free to redraw the diagram so long as it doesn't break the load-bearing claim (cross-product permits dissolve into dimensional slots; producers read graphql-java primitives directly; validity rides on the wrapper). What's stable is the *shape*: slots on a single unified field type, one per consumer concern, populated by thin layers over the SDL substrate. Read the sketches below as illustrative of that shape, not as a frozen contract.

## What is

Three cross-product encodings, three sets of permit-identity-driven discriminations.

**Input-side classification.** `GraphitronType.InputType` permits four backing-class variants (`JavaRecordInputType`, `PojoInputType`, `JooqRecordInputType`, `JooqTableRecordInputType`); `GraphitronType.TableInputType` is a separate sibling root for table-bound inputs. Nine consumer sites discriminate by permit identity: `GraphitronSchemaValidator`, `MutationInputResolver`, `EnumMappingResolver`, `CatalogBuilder` (four sites), `FieldBuilder`, `TypeBuilder`. `TypeBuilder.findReturnTablesForInput` already proves "table-bound" is a property of the consumer, not the input — derived by O(N) back-scan over schema fields. R215's lift admitted `InputField.UnboundField` into `TableInputType.inputFields()`, collapsing the eager-classification axis ahead of this pivot.

**Field-side classification.** 45 permits across `QueryField` (10), `MutationField` (8), `ChildField` (27). `TypeFetcherGenerator` dispatches per-leaf with one arm per permit. A mixin-interface overlay (`BatchKeyField`, `SqlGeneratingField`, `MethodBackedField`, `LookupField`, `TableTargetField`) carries cross-cutting traits. Each permit name packs several decisions: where source comes from (root, parent-keyed, list-parent), what the fetcher does (no I/O, `@service` invocation, generated jOOQ), the field's output shape (single, list, connection), the jOOQ contribution (none, inlined column, own SELECT, UNION ALL, DML), modifiers (lookup mapping, error channel, splitQuery). `RecordLookupTableField` collapses four of these onto one identifier; `QueryServiceRecordField` collapses three. The cross product is the permit set; adding a value to any axis multiplies the permits below it.

**Classification-failure encoding.** `GraphitronType.UnclassifiedType` and `GraphitronField.UnclassifiedField` ride as permits alongside legitimate types and fields, carrying typed `Rejection` payloads. `GraphitronSchemaValidator.validateUnclassifiedType` / `validateUnclassifiedField` translate-then-project — the validator does a half-job (walk Unclassified carriers; project payloads to ValidationError) on top of its real job (cross-type invariants).

`GraphitronField`'s sealed parent (`permits OutputField, InputField, GraphitronField.UnclassifiedField`) carries the input/output split, and `OutputField` carries a further sub-seal (`permits RootField, ChildField`). All three rationales — the cross-product permits, the input/output sibling split, the failure encoding — dissolve in this umbrella.

## What's to be: dimensional slots, walker-driven, failure at the wrapper

Three changes, all instances of the same principle.

**Surface axes as dimensional slots, not as permits.** Each consumer concern lives in its own slot on the field; the field permits flatten to one record per emit-relevant identity (or stay sub-sealed only where authoring scope justifies it). Consumers read the slot they care about. Impossible combinations are excluded at production time, not by permit cross-product. Adding a new axis is additive: new slot, new family, new production logic.

**Producers read graphql-java primitives directly.** The walker abstraction (`Walker<S, C>`) — a pure function over an SDL substrate `S` returning a sealed `WalkerResult<C>` — is *one* implementation shape; slices may pick another. The load-bearing claim is that producers are thin layers over `GraphQLFieldDefinition`, `GraphQLArgument`, `GraphQLInputObjectField` — no graphitron-internal substrate model intermediating between the SDL and the carrier. The unit-testability claim (parse a fragment, run the producer, assert on the sealed result) falls out of this shape; the test of correctness is the slot's shape, not the producer's.

**Validity rides on the wrapper, not the carrier.** Every classification step returns a sealed `Ok(carrier, diagnostics) | Err(errors, diagnostics)`. Carriers have only "happy" arms — valid or the explicit absent arm (`No<Family>`). Structural failure surfaces through `Err`; the orchestrator collects errors across the whole pass and blocks downstream generation. Classification runs to completion regardless; the LSP consumes the partial classification output independent of whether generation ran.

### Destination sketch

`GraphitronField` becomes a single field namespace (the renamed `OutputField` after the input/output split dissolves and `UnclassifiedField` retires). Each carrier slot lives on the narrowest existing interface that names its property, not as a universal accessor on `GraphitronField`. The walker is universal across that interface's implementers; slot presence is interface-gated, and consumers reading the slot through the interface always get a populated value. R238 (the foundation slice) pins this for the service `MethodCall` family: `ServiceMethodCall` (sealed `Static` / `Instance`) lands on a fresh `ServiceField` interface that sits sibling to `MethodBackedField`, not as a sub-interface of it. The earlier umbrella draft anticipated one unified `MethodCall` carrier on `MethodBackedField`, with per-directive markers as pure sub-interfaces; R238 surfaced that the call shapes across `@service` / `@condition` / `@tableMethod` / `@externalField` differ enough (ctor vs static, multiple-DSLContext rules, return-type relationships) that one unified carrier would carry a kitchen-sink of optional fields. Per-directive sibling interfaces let each slice ship a tight carrier scoped to its call shape; `MethodBackedField` retires only once every per-directive sibling has landed.

Subsequent slices for `Pagination`, `Ordering`, `PredicateCarrier`, `ValidationShape`, `InsertRows`, `UpdateRows` follow the same pattern: find (or introduce) the narrow interface that names the property, put the slot there, and add a marker sub-interface if a consumer subset needs polymorphic dispatch. Interface names land per slice:

| Carrier | Slot home | Status |
|---|---|---|
| `ServiceMethodCall` | `ServiceField` (new sibling of `MethodBackedField`) | R238 (Spec) |
| `ConditionCall` / `TableMethodCall` / `ExternalFieldCall` | per-directive siblings | future slices; collectively retire `MethodBackedField` |
| `ValidationShape` | TBD (narrow interface or existing marker) | future slice |
| `Pagination` | TBD | future slice |
| `Ordering` | TBD | future slice |
| `PredicateCarrier` | TBD | future slice |
| `InsertRows` | TBD | future slice (R122 partner) |
| `UpdateRows` | TBD | future slice |

A two-layer composition still holds where dimensional slots add real composition over multiple walker carriers: a `QueryBuilder` for an UPDATE field composes `predicate()` (WHERE), `updateRows()` (SET), and the field's return-type table; a `DataFetcherBuilder.Service` composes `serviceMethodCall()` and a class accessor; a `ValidationBuilder` composes `validation()`. Where a consumer's needs are simpler than full composition, a *shared emitter* parameterised on the carrier itself is the lighter tool: R238 introduces `ServiceMethodCallEmitter(ServiceMethodCall) -> List<CodeBlock>`. Not every carrier needs a dimensional slot; choose per consumer's need. Consumers attach at whichever layer (carrier, shared emitter, or dimensional slot) matches their concern; the layers compose without re-walking SDL behind them.

Within each sub-seal, R164's permit consolidation collapses the cross-product permits to one record per emit-relevant identity. `RootField` as the intermediate between `OutputField` and `QueryField` / `MutationField` retires alongside the parent collapse.

### The unified diagnostic surface

`Diagnostic` is an LSP-aligned sealed family — `severity` (`Error` / `Warning` / `Information` / `Hint`, mirroring LSP `DiagnosticSeverity`), `code` (stable string id), `source` (`"graphitron"`), `message`, `tags` (`Unnecessary`, `Deprecated`), `relatedInformation`. Arms keep type-safe pattern matching on the producer side; the LSP wire-format adapter reads the LSP fields and projects mechanically. `AuthorError` (the existing `Rejection.AuthorError` sealed family) carries on `WalkerResult.Err.errors`; the wire-format adapter projects each leaf to severity=Error LSP `Diagnostic` records with a code derived per leaf type.

`ValidationReport` carries `errors: List<ValidationError>` and `warnings: List<BuildWarning>` today; the foundation slice adds a `walkerDiagnostics: List<Diagnostic>` slot alongside them, and once every producer migrates the three slots collapse into one diagnostic stream. From the foundation slice forward, walker output reaches the editor through the same channel today's validator output does — `Workspace.setBuildOutput(BuildArtifacts, ValidationReport)` is the seam, the rest of the wire (`recalculateListener` → `Diagnostics.compute` → `LanguageClient.publishDiagnostics`) is already live, and `Diagnostics.validatorDiagnostics` gains an arm projecting the walker `Diagnostic` family.

### Single field namespace, no failure permit

After the pivot, `GraphitronField`'s sealed parent and the `UnclassifiedField` permit are both gone:

- `InputField` (and its sub-permits) retires as input-side carriers move to slots on the unified field.
- `UnclassifiedField` retires as classification failure moves to `WalkerResult.Err`.
- `OutputField` and `RootField` retire as redundant intermediate sub-seals — there is no "Input" half left to contrast with, and the `RootField` between `OutputField` and `QueryField` / `MutationField` carries nothing distinctive.
- The surviving permits live directly under `GraphitronField`: `QueryField`, `MutationField`, `ChildField`.

## Architectural principle this codifies

The rewrite-internal disease is encoding multiple independent axes through one permit set; the cure is dimensional slots populated by independent producers, each producer a thin layer over graphql-java primitives, validity riding on the wrapper.

- **Cross-product encodings hide axes.** Per-axis encodings surface them. Adding an axis becomes adding a slot; adding a value to an axis becomes adding an arm to that slot's sealed family. No multiplication. Impossible combinations are excluded at production time, not by permit cross-product.
- **The walker abstraction is one implementation shape, not the load-bearing claim.** The load-bearing claim is that producers read graphql-java primitives directly and return typed sealed results. Slices may share an abstraction or roll their own; the test of correctness is the slot shape, not the producer shape.
- **Absence encoding follows the slot's home.** When a slot is field-universal (lives on `GraphitronField` or a sub-seal), absence is encoded by a `No<Family>` arm: the producer runs unconditionally, the carrier has a no-signal arm, consumers pattern-match exhaustively. When a slot is directive-gated and lives on a narrow interface (R238's pattern: `ServiceMethodCall` on `ServiceField`, sibling of `MethodBackedField`), absence is encoded by interface non-membership: the producer runs only for implementers, consumers reading the slot through the interface always get a populated value. Both forms make absence first-class; neither uses `Optional`.
- **Validity lives at the wrapper, not inside the carrier.** Encoding failure inside the carrier family would force every downstream consumer to either filter or handle the failure arm. Encoding it at the wrapper plus a classification/generation phase split lets downstream consumers assume `Ok`-only inputs while classification runs to completion for the LSP's benefit.
- **LSP-aligned diagnostics from day one.** Every diagnostic carries the LSP-shape fields (severity, code, message, tags, relatedInformation) so the wire-format adapter is a mechanical projection rather than a translation layer. R226's reframing of validator output as walker diagnostics, and R222's walker output, share one wire format.
- **Each axis is independently testable.** A producer is a pure function: SDL fragment in, sealed result out. Tests don't need a graphitron classification context.

## Stages

Each stage is a work-stream; spin-out roadmap items file as slices get picked up. The order below names dependency edges, not the schedule. Stages 1–4 are mostly parallelizable across slices; Stages 5–7 are sync points.

### Stage 1 — Foundation slice

One vertical slice, end-to-end: one slot on a carrier-bearing interface, one producer that fills it, one consumer migration, one LSP wire arm. The slot's home (existing narrow interface vs. introduced one), the producer's implementation shape (sealed `Walker<S, C>` vs another), and the first consumer to migrate are the foundation slice's call. The point is that the slot pattern lands once, in tree, demonstrating the pivot's structural shift end-to-end. The foundation slice fixes the wire-format conventions (source attribution, code namespace, per-walker `AuthorError` sub-seal) and the slot-home convention (narrow interface, not universal parent; interface-gated absence rather than `No<Family>` when directive-gated) that subsequent slices inherit. R238 ships the foundation slice as `ServiceMethodCall` on a new `ServiceField` sibling of `MethodBackedField`.

### Stage 2 — Walker carrier slots

Each remaining walker-output carrier ships as an independent slice: its sealed family or record, its slot on a carrier-bearing interface (existing or introduced), the consumer migrations that read it, the `Diagnostic` arms it surfaces. Whether the carrier needs a `No<Family>` arm depends on the slot's home: field-universal slots do, directive-gated slots on narrow interfaces don't. Candidates (minus whichever Stage 1 ships): `ValidationShape`, `Pagination`, `Ordering`, `PredicateCarrier`, `MethodCall`, `InsertRows`, `UpdateRows`. Slices are parallelizable; no ordering between them.

### Stage 3 — Field dimensional slots

R164's content. `DataFetcherBuilder`, `QueryBuilder`, `ValidationBuilder` dimensional slots land per sub-seal, composing walker carriers and reflection-driven information into emit-ready form. Each dimension's sealed family lands once; the sub-seal's cross-product permits flatten under it. Each dimension is a spin-out slice; runs in parallel with Stage 2 once the foundation lands.

### Stage 4 — Failure at the wrapper everywhere

R226's content. `UnclassifiedType` and `UnclassifiedField` retire. `GraphitronSchemaValidator.validateUnclassifiedType` / `validateUnclassifiedField` retire. Type-level classification (`GraphitronSchemaBuilder`'s type-classification step) lifts into `WalkerResult<C>`. The validator's surface narrows to cross-type invariant checks. `ValidationReport`'s `errors` / `warnings` slots collapse into the unified `Diagnostic` stream.

### Stage 5 — Legacy permit deletion

Sync point on Stages 2 + 3 (every consumer reads via slots; every cross-product permit's dimensional slots have ingressed). Retirements:

- `GraphitronType.InputType` 4-arm permit + `TableInputType` sibling root
- `ArgumentRef.InputTypeArg.TableInputArg`, `PlainInputArg`
- `InputField` sealed family (`ColumnField`, `ColumnReferenceField`, `CompositeColumnField`, `CompositeColumnReferenceField`, `NestingField`, `UnboundField`)
- `HasInputRecordShape` capability marker
- `RootField` intermediate sub-seal between `OutputField` and `QueryField` / `MutationField`
- Cross-product field permits per R164's consolidation (`RecordLookupTableField`, `QueryServiceRecordField`, etc.)
- `TypeBuilder.findReturnTablesForInput` back-scan

### Stage 6 — Namespace collapse

Sync point on Stages 4 + 5. After Stages 4 + 5, `GraphitronField`'s `permits OutputField, InputField, UnclassifiedField` reduces to `permits OutputField`. Delete the sealed parent; rename `OutputField` → `GraphitronField`. Re-flatten field signatures across consumers; the `RootField` intermediate retires here if not already.

### Stage 7 — Directive narrowing

`@table`, `@record(class:)`, `@value` drop from `INPUT_OBJECT` scope; the SDL directive declarations narrow; fixture sweep. Closes R97. Lands anywhere after Stage 5.

## What this absorbs

| Item | Absorption mode |
|---|---|
| **R164** (field-model three-dimension pivot) | Stage 3 + Stage 5 (permit consolidation). File discarded |
| **R226** (classification dimensional pivot: diagnostics off the model) | Stage 4 + unified `Diagnostic` family. File discarded |
| R171 (sealed `InputLikeType` parent) | Dissolves; no per-input model record survives |
| R97 (deprecate `@table` on input types) | Stage 7 directive narrowing closes the item. `argMapping` grouping (R97 Phase 1) remains separable |
| R213 (rejections at consumer field) | Walker-time `SourceLocation` is the consumer field's own SDL location |
| R209 (FieldRegistry classify-input trace) | Typed `Rejection.AuthorError` at walker time; surfaces through the orchestrator's `WalkerResult.Err.errors` collection |
| R221 (validator walks `PlainInputArg.fields()` for `UnboundField` rejection) | Dissolves; per-permit dispatch retires |
| R144 (lookup-key / set-field partition stored on `TableInputArg`; `@value` directive marker) | Two reversals. (1) Partition lives in `PredicateCarrier`'s `Condition` / `LookupRows` arm choice. (2) `@value` retires (catalog-derived from PK membership). R144's cardinality-safety surface (`multiRow: true` opt-in, PK-coverage check) survives unchanged |
| R215 (column-binding at classification, not usage) | Subsumed; column binding happens inside each SQL-emitting producer at its leaf-resolution step. R215's `UnboundField` admit set translates per-walker into the producer's own "unresolved" arm |
| R98 (multi-source input validation) | Dissolves structurally. Per-output-field validation makes "different consumers, different POJOs" the default behaviour, not a structural extension |

Adjacent but not absorbed:

- **R220 / R193** (`ServiceCatalog` predicate consolidation, sealed `UnresolvedParam`): same disease in a different file. R222 primes the pattern; those items apply it on the consumer-side surface independently.
- **R122** (compound-entity-mutations): contract partner. R122 owns `InsertRowsWalker`'s tree shape and FK threading; R222 names the slot the producer fills.
- **R200 / R195** (honor `@field(name:)` in `InputBeanResolver`): naming binding between SDL fields and Java members, orthogonal to the pivot.
- **R166 Phase 1** (reachability slot): orthogonal; producers run on reachable fields anyway.

## Dependencies and sequencing

- Stage 1 enables Stages 2–4. Slices within those stages are parallel; no inter-slice ordering.
- Stage 5 syncs on Stage 2 + the parts of Stage 3 whose dimensional slots compose Stage 2 carriers.
- Stage 6 syncs on Stages 4 + 5.
- Stage 7 lands anywhere after Stage 5.
- **R215** (Done): unbound-field admit set generalises to producers' "unresolved" arms; no further build-order concern.
- **R94** (shipped): the existing `HasInputRecordShape` marker + `InputRecordShape` record become the `ValidationShape` carrier; the slice that ships this is per-output-field POJO emit in `InputRecordGenerator`. R94's per-input POJO becomes per-(output-field × input-type-typed-arg) POJO; R98's multi-source case becomes default behaviour.

## Vocabulary

The names below are the working vocabulary for the umbrella; slices may rename, narrow, or restructure as their implementation details surface. Treat this as anchor terminology, not a frozen API.

- **`Walker<S, C>`** — a pure function over an SDL substrate `S` returning `WalkerResult<C>`. One implementation shape for producers; slices may pick another. Substrate-parametric for forward-compat with type-level producers.
- **`WalkerResult<C>`** — sealed `Ok<C>(C carrier, List<Diagnostic> diagnostics)` / `Err<C>(List<AuthorError> errors, List<Diagnostic> diagnostics)`. `Ok` rejects Error-severity diagnostics by compact-ctor; `Err.errors` is non-empty by compact-ctor invariant. Classification runs to completion regardless of how many `Err`s; downstream generation is blocked when any `Err` is present.
- **Carriers**: `ValidationShape`, `Pagination`, `Ordering`, `PredicateCarrier`, the `MethodCall` family (per-directive: `ServiceMethodCall`, `ConditionCall`, `TableMethodCall`, `ExternalFieldCall`), `InsertRows`, `UpdateRows`. Each a sealed family or record carrying the reduced output. `No<Family>` arms apply when the slot is field-universal; directive-gated slots on narrow interfaces (R238's pattern) skip the `No<>` arm and use interface non-membership instead. No `Invalid` arm in either case; structural failure rides on `WalkerResult.Err`.
- **Dimensional slots** — `DataFetcherBuilder`, `QueryBuilder`, `ValidationBuilder`. Compose walker carriers + reflection-driven information into emit-ready form.
- **`No<Family>`**: the domain arm naming "the substrate carries no actionable signal for this family." Producer ran, no error, nothing to encode. Applies when the slot is field-universal; directive-gated slots on narrow interfaces use interface non-membership instead. Concrete shapes vary per family (`NoPredicates`, `NoValidationShape`, ...); framing is uniform across the cases that need it.
- **`PredicateCarrier`'s two valid arms** — `Condition` for SQL-emitting *read* fields, `LookupRows` for *mutation* fields. The producer's bailout-restart pattern handles role discovery: sentinel directives (`@lookupKey` on a read field, `@multirows` on a mutation field) trigger an arm flip. Consumers pattern-match the arm at use time.
- **`MethodCall` family**: per-directive records carrying `(target, methodName, bindings, returnShape)`. R238 pins the first instance — `ServiceMethodCall` (sealed `Static` / `Instance`) — and lands its bindings as `MappingEntry` arms (`FromArg`, `FromContext`, `FromDsl`) plus a recursive `ValueShape` family for input-object bindings. Subsequent slices add `ConditionCall`, `TableMethodCall`, `ExternalFieldCall` with their own binding shapes. The earlier umbrella draft named one unified `MethodCall` with `ParamBinding` arms covering every directive (`FromEnvArg`, `FromContextKey`, `FromDslContext`, `FromBatchKeys`, `FromSourceRow`); R238 split it per-directive because call shapes differ enough that one unified carrier would carry many always-absent slots per callsite.
- **Shared emitter**: a static utility parameterised on a carrier-bearing interface that produces emit-ready code fragments (var-decls, expression blocks). R238 introduces `ServiceMethodCallEmitter(ServiceMethodCall) -> List<CodeBlock>`. Lighter than a dimensional slot when the consumer's only need is the carrier's emission, not multi-carrier composition. Slices choose between shared emitter and dimensional slot per consumer's need.
- **Per-directive sibling interface**: a sibling of `MethodBackedField` carrying one directive's call slot. R238 introduces `ServiceField` (carrying `ServiceMethodCall`) as the first instance. The earlier umbrella draft framed this as a pure marker sub-interface of `MethodBackedField`; the sibling shape lets each slice ship narrow without forcing the other six `MethodBackedField` implementers to grow no-op slot accessors. `MethodBackedField` retires once every per-directive sibling has landed.
- **`BackingClass`** — three-arm sealed family (`Pojo`, `JavaRecord`, `JooqTableRecord`); attaches per binding kind where method-call semantics need it.
- **`Diagnostic`** — LSP-aligned sealed family. Carries non-error events on both `Ok` and `Err`. `BuildWarning` migration retires; the channel is one unified stream at the LSP boundary.
- **`AuthorError`** — the existing `Rejection.AuthorError` sealed family. The wire-format adapter projects each leaf to severity=Error LSP `Diagnostic` with a code derived per leaf type (e.g. `AuthorError.UnknownName` → `"graphitron.unknown-name"`).
- **`@table` / `@record(class:)` on input types** — drop entirely. Table-binding collapses to the consumer's `@table` return at production time. `@record(class:)`'s deserialization-target function collapses to the user's declared service-method param type, read by reflection at the `MethodCall` producer's site.
- **`@value` on input fields** — drops as redundant scaffolding. The WHERE-vs-SET partition derives from catalog PK membership inside the SQL-emitting producers.

## Out of scope

- The R164 sub-dimension internals (`DataFetcherBuilder` source-cardinality + action + field-cardinality axes, etc.) — Stage 3 spin-outs own those.
- The R122 `InsertRowsWalker` tree shape and FK threading — R122 owns.
- `ServiceCatalog` predicate consolidation (R220 / R193) — adjacent disease in a different file.
- `argMapping` grouping syntax (R97 Phase 1) — adjacent.
- Reachability pruning across all type kinds (R166 Phase 1) — orthogonal.
- Producer-side unification of method invocation paths (uniform reflection-mapping rules across `@service` / `@externalField` / `@tableMethod` / `@condition`) — separate work that R164's `DataFetcherBuilder` dimension may absorb piecewise; not load-bearing on the umbrella.

## Previous design attempts

Rejected alternatives from earlier R222 drafts, recorded so reviewers don't re-derive the dead ends.

- **Horizontal Phase 1 (all carriers, all slots, all `Diagnostic` arms up front).** Earlier draft committed seven carrier families, seven slot getters, four `Diagnostic` arms in Phase 1 with only `ValidationShape` populated. Rejected: vocabulary that doesn't ride a live producer is a contract committed before any consumer pulls on it. Vertical slices each ship their own vocabulary.
- **R164 + R226 as separate items.** Treated as adjacent contract partners. Rejected because the work overlaps structurally: R164's dimensional slots compose R222's walker carriers; R226's `Unclassified*` retirement uses the same `WalkerResult.Err` wrapper R222 produces. Unifying as one umbrella with parallel spin-outs replaces the coordination tax with explicit stage dependencies.
- **Recursive `InputUsage` carrier scoped to SQL emission.** First pivot folded WHERE construction, DML row-shaping, lookup-key identification, method-param binding, pagination, and ordering into one classified output that consumers re-discriminated by role. Rejected as wrong granularity.
- **`Invalid<Family>` arms inside the carrier families.** Encoded structural failure inside two of the seven families. Rejected because no downstream generator inspects an `Invalid` arm — generation is blocked before any generator runs in either failure mode, so the asymmetry encoded an invariant the wrapper + phase split already enforce.
- **`Input` / `InputFieldDecl` per-input wrapper records.** Pass-through wrappers over `GraphQLInputObjectType` / `GraphQLInputObjectField`. Rejected because per-input identity has no carrier-independent state worth a record, and graphql-java already provides every accessor.
- **`SchemaCoordinate(String)` identity wrapper.** Stringly-typed wrapper conflating `"FilmInput"` and `"FilmInput.title"`. Rejected because plain `String` covers the type-name case and graphql-java's `FieldCoordinates` covers the type+field case.
- **Optional carrier slots on `OutputField` (`Optional<Pagination>`, ...).** Presence-vs-absence at the storage layer. Rejected for field-universal slots because the `No<Family>` arm makes absence a first-class domain state consumers pattern-match exhaustively; Optional re-introduces a present/missing flag the sealed family already encodes. For directive-gated slots, R238 surfaced a third option: interface-gated presence (slot lives on a narrow interface, absent for non-implementers). Optional remains rejected in both cases.
- **`ValidationShape` as a per-input carrier (`Map<String, ValidationShape>` on the classification artifact).** Two-substrate variant: per-output-field carriers on `OutputField`, per-input carriers in a name-keyed map. Rejected because validation fires at the resolver method-arg boundary, which is the output field's seat; the "global common shape across consumers" framing tried to reuse a per-type POJO, but the consumer is the unit of validation.
- **`MethodArguments` as the carrier name.** Earlier vocabulary draft. Replaced by `MethodCall` to align with the rewrite's existing `CallParam` / `CallSiteExtraction` / `MethodRef.Call` naming family.
