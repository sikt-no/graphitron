---
id: R56
title: "Extract `ConnectionPromoter` from `GraphitronSchemaBuilder`"
status: Ready
bucket: architecture
priority: 3
theme: structural-refactor
depends-on: []
---

# Extract `ConnectionPromoter` from `GraphitronSchemaBuilder`

## Premise

`GraphitronSchemaBuilder.java` (670 lines) is mostly orchestration glue, but it bundles ~250 lines of one cohesive sub-concern: turning `@asConnection` carrier fields into proper Connection-typed fields and synthesising the supporting Connection / Edge / PageInfo types. The pieces are spread across `promoteConnectionTypes`, `rebuildAssembledForConnections`, `noSynthesisedTypes`, `rewriteCarrierField`, `promotionFor`, `buildSynthesisedConnection`, `buildSynthesisedEdge`, `buildSynthesisedPageInfo`, `resolveDefaultFirstValue`, `resolveConnectionName`, plus the local `baseTypeName(GraphQLOutputType)` / `capitalize` helpers and the `CarrierRewrite` / `ConnectionPromotion` records. Lifting this into a `ConnectionPromoter` sibling class gives the concern its own file and its own focused test surface (today's coverage is end-to-end through `GraphitronSchemaBuilderTest` and `ConnectionRegistrationsTest`, not the promotion logic itself). R6 (`FieldBuilder` decomposed onto the cross-cutting-concern axis) shipped ten resolver siblings under `graphitron/src/main/java/.../rewrite/`; this is a smaller-scale instance of the same pattern, filed separately because the motivation here is testability and scope clarity, not the cross-arm duplication that drove R6. Not blocking anything; opportunistic refactor (priority 3).

## Shape

**Structural extract-class, not a sealed-result resolver.** Unlike R6's resolvers, Connection promotion has no rejection arms to lift: rejection of malformed `@asConnection` usage happens upstream in `FieldBuilder.classifyField` ("@asConnection on inline (non-@splitQuery) TableField is not supported", "@asConnection on @lookupKey fields is invalid", etc., all already covered by `GraphitronSchemaBuilderTest`). Promotion runs after field classification has already accepted the carrier and only does structural work: register synthesised Connection / Edge / PageInfo types, rewrite carrier fields' return types and arguments, and produce the rebuilt `GraphQLSchema`. So the lift produces a plain stateless utility class, not a `Resolved.{Ok, Rejected}` shape.

The `promote` signature combines a `ctx.typeRegistry` side-effect with a `List<CarrierRewrite>` return value, which is the kind of dual-target output that Principle 7 ("builder-internal sealed hierarchies for multi-target classification") usually pushes toward a sealed projection. It doesn't apply here: this is a single-step structural transformation, not a per-field/per-argument classification fan-out. The two outputs are tightly coupled coordination between `promote` and its single caller `rebuildAssembledForConnections` — one population pass, two consumers, no per-element variant axis. A sealed `Resolved` would be ceremony for a step that has neither rejection arms nor multi-target projection.

## Surface and contract

New file: `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ConnectionPromoter.java`. Public surface (package-private; sibling of `TypeBuilder` / `FieldBuilder`):

- `static List<CarrierRewrite> promote(BuildContext ctx)` — lifted from `promoteConnectionTypes(ctx)`. Mutates `ctx.typeRegistry` (synthesise / enrich the Connection, Edge, PageInfo entries), returns the carrier-rewrite list for the caller to apply via the schema rebuild.
- `static GraphQLSchema rebuildAssembledForConnections(GraphQLSchema original, Map<String, GraphitronType> types, List<CarrierRewrite> rewrites)` — lifted from the same-named private method, signature unchanged.
- `record CarrierRewrite(String parentTypeName, String fieldName, String connectionName, int defaultPageSize, boolean outerNonNull)` — lifted from the private record, becomes package-private so `GraphitronSchemaBuilder.buildSchema` can pass it between the two calls. The `ConnectionPromotion` record stays private to `ConnectionPromoter` (only used internally by `promote` / `promotionFor`).

Everything else in the bullet list (`promotionFor`, `rewriteCarrierField`, `noSynthesisedTypes`, the three `buildSynthesised*` helpers, `resolveDefaultFirstValue`, `resolveConnectionName`, the local `baseTypeName(GraphQLOutputType)` overload, `capitalize`) becomes a private static helper on `ConnectionPromoter`. The `import static no.sikt.graphitron.rewrite.BuildContext.*` carries over.

`GraphitronSchemaBuilder.buildSchema` retains the orchestration call sequence — only the implementation moves:

```java
var rewrites = ConnectionPromoter.promote(ctx);
var rebuiltAssembled = ConnectionPromoter.rebuildAssembledForConnections(ctx.schema, ctx.types, rewrites);
```

## Name-collision note

`GraphitronSchemaBuilder` has a private `baseTypeName(GraphQLOutputType)`; `BuildContext` has a different `baseTypeName(GraphQLFieldDefinition)` that uses `GraphQLTypeUtil.unwrapAll`. They reach the same answer for connection-shaped types but differ subtly (the local form unwraps `NonNull → List → NonNull` in fixed order). Move the local form to `ConnectionPromoter` as a private helper; do **not** attempt to consolidate against `BuildContext.baseTypeName` — that's scope creep, and the unwrap shapes aren't trivially equivalent for arbitrary types. Three other resolvers (`OrderByResolver`, `ExternalFieldDirectiveResolver`, `ServiceDirectiveResolver`) keep their `import static no.sikt.graphitron.rewrite.BuildContext.baseTypeName` and continue to use the `GraphQLFieldDefinition` form, untouched.

## Acceptance criteria

1. `ConnectionPromoter.java` exists; `GraphitronSchemaBuilder.java` no longer references the moved methods/records (verified by `grep -n "promoteConnectionTypes\|rewriteCarrierField\|buildSynthesised\|promotionFor\|noSynthesisedTypes\|resolveConnectionName\|resolveDefaultFirstValue\|ConnectionPromotion" GraphitronSchemaBuilder.java` returning only the two `ConnectionPromoter.*` orchestration call sites).
2. `GraphitronSchemaBuilder.java` shrinks by ≥230 lines (target: down to ~430 lines from 670).
3. `ConnectionPromoter` is `final` with a private constructor (or all-static API), since it carries no per-build mutable state — `BuildContext` is the state holder.
4. Full `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` is clean, including the existing `GraphitronSchemaBuilderTest` `@asConnection` cases and `ConnectionRegistrationsTest`.
5. New focused-unit test class `ConnectionPromoterTest` exists with at least the cases listed under "Test plan" below.

## Test plan

New file: `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/ConnectionPromoterTest.java`, `@UnitTier`, exercising `ConnectionPromoter.promote(ctx)` directly against a hand-crafted `BuildContext` (the existing `GraphitronSchemaBuilder.buildContextForTests` seam exposes the wired `BuildContext` after type classification but before field classification — perfect for this).

Cases (each is a positive assertion on the synthesised type registry plus the returned carrier-rewrite list):

- **Directive-driven bare-list carrier**, `@asConnection` on `[Customer!]!`: emits one `CarrierRewrite(parentTypeName, fieldName, "<Parent><Field>Connection", FieldWrapper.DEFAULT_PAGE_SIZE, true)`; `ctx.types` gains a `ConnectionType`, an `EdgeType`, and a `PageInfoType` (PageInfo synthesised because the SDL doesn't declare one).
- **Directive-driven with explicit `connectionName:`**: returned `connectionName` reflects the override, not the auto-derived `<Parent><Field>Connection`.
- **Directive-driven with explicit `defaultFirstValue:`**: returned `defaultPageSize` reflects the override, not `FieldWrapper.DEFAULT_PAGE_SIZE`.
- **Structural Connection-typed return**: SDL declares the `Connection` / `Edge` types as plain object types, no `@asConnection` directive applied; `promote` returns no carrier rewrite (structural carriers don't need return-type rewriting), but `ctx.types` has the `PlainObjectType` entries replaced with typed `ConnectionType` / `EdgeType` (the enrich path).
- **SDL-declared `PageInfo`**: SDL declares `type PageInfo @shareable { ... }`; promotion doesn't synthesise a fresh PageInfo, instead enriches the existing `PlainObjectType` entry with the SDL form's `shareable` flag preserved.
- **Two carriers pointing at the same connection name** (dedup): both fields share one synthesised `ConnectionType` entry; the second carrier reuses the first, no second synthesis.
- **`@asConnection` on a return type that already names the Connection** (the `currentBaseName.equals(connectionName)` guard in `promoteConnectionTypes`): no `CarrierRewrite` emitted (the carrier doesn't need return-type rewriting).
- **Item-nullability propagation**: `[Customer]` (item-nullable) vs `[Customer!]` (item-non-null) yields `ConnectionType.itemNullable` matching the input shape.

`rebuildAssembledForConnections` is exercised end-to-end by the existing pipeline tests; one focused unit test stays useful as a regression: hand-craft an assembled schema with no rewrites and no synthesised types ⇒ same `GraphQLSchema` instance returned (the `noSynthesisedTypes` short-circuit).

Existing pipeline tests stay unchanged and serve as regression coverage:

- `GraphitronSchemaBuilderTest` (the megafile): every `@asConnection` / `Connection` / `Edge` case continues to pass.
- `ConnectionRegistrationsTest`: generator-tier coverage stays green.
- `ConnectionTypeValidationTest`: validator-tier coverage stays green.

## Phase plan

Single phase. The lift is mechanical and self-contained:

1. Create `ConnectionPromoter.java` with the moved methods and records. Keep them static, package-private API for `promote` / `rebuildAssembledForConnections` / `CarrierRewrite`; everything else private.
2. Replace the two call sites in `GraphitronSchemaBuilder.buildSchema` with the new API.
3. Delete the moved methods and records from `GraphitronSchemaBuilder`. Verify imports — drop now-orphan ones (`GraphQLAppliedDirective`, `GraphQLList`, `GraphQLNonNull`, `GraphQLTypeReference`, `GraphQLTypeVisitorStub`, `SchemaTransformer`, `TraversalControl`, `TraverserContext`, `TreeTransformerUtil`, `TreeTransformerUtil`, `LinkedHashMap`, `List` if no remaining use, `ArrayList`).
4. Add `ConnectionPromoterTest`.
5. Full `mvn install -Plocal-db -P!docs` clean.

No follow-up phase needed. The lift doesn't introduce a sealed-result hierarchy and doesn't unlock further refactors elsewhere.

## Out of scope

- Any change to the Connection promotion behaviour. Pure structural extract-class.
- Reconciling `GraphitronSchemaBuilder.baseTypeName(GraphQLOutputType)` against `BuildContext.baseTypeName(GraphQLFieldDefinition)`. Different signatures, different unwrap semantics; consolidating is a separate decision (file a Backlog item if it ever matters).
- Adding a sealed `Resolved.{Ok, Rejected}` shape. Connection promotion has no rejection arms — rejection is upstream in `FieldBuilder.classifyField`.
- Changes to the carrier classification side (`FieldBuilder`'s `@asConnection` arms), the validator, or the connection emitters.
- Lifting other concerns out of `GraphitronSchemaBuilder` (e.g. `buildRecipeErrors` / `validateDirectiveSchema`). Each is its own opportunistic decision.
- The `DIR_AS_CONNECTION` directive-presence assertion in `GraphitronSchemaBuilder.validateDirectiveSchema` (line ~652) stays put. That method asserts every Graphitron directive's presence in one place; pulling out its `@asConnection` line would couple a directive-presence check to a structural transformer for no gain.

## Reviewer

Last committer of this spec file: Claude (this session). Spec → Ready handoff requires reviewer ≠ Claude; `/srp R56` produces the handoff prompt.
