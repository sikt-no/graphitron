---
id: R206
title: Synthesised connection/edge types carry carrier-field source location
status: Spec
bucket: validation
theme: structural-refactor
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Synthesised connection/edge types carry carrier-field source location

`ConnectionPromoter.promote` synthesises `ConnectionType` and `EdgeType` records for every `@asConnection` carrier (and a `PageInfoType` once, when at least one connection is promoted). All three records have a `SourceLocation location` field, but the promoter passes `null` at every synthesis site (`ConnectionPromoter.java:118-135, 141-142`). When `GraphitronSchemaBuilder.rejectCaseInsensitiveTypeCollisions` finds a case-folded collision between two synthesised connection names (e.g. `QueryPoengklasserv2Connection` vs `QueryPoengklasserV2Connection`), it demotes each member to `UnclassifiedType(name, existing.location(), Rejection.caseFoldCollision(...))` (`GraphitronSchemaBuilder.java:337-338`). With `existing.location()` returning `null`, the resulting `ValidationError` has no source position and an LSP/editor consumer can't jump to the field that caused the type to be synthesised. The error message names the colliding type but the user has to grep the SDL to find the carrier.

The root cause is that the synthesised types lack provenance back to the field definition that triggered synthesis. Each unique connection name is produced by at least one `@asConnection` carrier (or a structural connection-shaped field); that carrier's `FieldDefinition` AST node has a `SourceLocation` available via `field.getDefinition().getSourceLocation()` in graphql-java. Threading that location into the `ConnectionType` and `EdgeType` records at synthesis time costs nothing at runtime, makes the `existing.location()` lookup in the collision pass already-correct, and surfaces an actionable position on every diagnostic that flows through `UnclassifiedType.location()`.

## Plan

1. **Capture carrier location at synthesis.** In `ConnectionPromoter.promote` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ConnectionPromoter.java`), thread `fieldDef.getDefinition() != null ? fieldDef.getDefinition().getSourceLocation() : null` into the `new ConnectionType(...)` and `new EdgeType(...)` constructors (currently `null` at lines 119 and 128). The carrier field is already in scope at both call sites. First-write-wins on dedupe (the existing `ctx.types.get(promotion.connectionName()) instanceof ConnectionType` early-`continue` at line 105 already handles this); the `enrich` arm at lines 122-126 / 131-135 should preserve the first-writer's location too. We do not anticipate multiple carriers synthesising the same connection name in practice, and once R??? retires the `@asConnection(connectionName:)` argument that becomes an enforced invariant. For now, document first-write-wins as the dedupe rule in the `promote` javadoc; the rest follows.

2. **Leave `PageInfoType` provenance null.** `PageInfo` collisions are diagnostically different: the colliding party is an SDL-declared type folding to `pageinfo`, and the SDL side already carries its own location. Carrying a synthesised carrier location on `PageInfoType` would point at an arbitrary connection carrier that isn't the actionable site. Keep `PageInfoType.location()` `null` and rely on the SDL-side group member to anchor the diagnostic.

3. **No record-shape changes.** `ConnectionType`, `EdgeType`, and `PageInfoType` already declare a `SourceLocation location` component; we are populating an existing field, not extending the API surface.

4. **Test coverage.** Add a pipeline-tier test in `GraphitronSchemaBuilderTest` (or the nearest existing collision-coverage neighbour) that:
   - feeds an SDL with two `@asConnection` carriers whose default-derived names collide under case-folding (e.g. `poengklasserv2: [Foo!] @asConnection` on one type and `poengklasserV2: [Foo!] @asConnection` on another, or use `@asConnection(connectionName:)` to force the collision while the argument remains supported);
   - asserts that both resulting `ValidationError`s carry a non-null `SourceLocation` whose line/column point at the carrier field definition, not `null`.
   The structural collision arm (SDL-declared `XxxConnection` vs `xxxconnection`) already gets a location because the classifier carries it through from the SDL parse; existing coverage for that arm continues to pass unchanged.

5. **Out of scope.** Repointing the message text away from the synthesised type name (e.g. switching the prose to "the field `Query.poengklasserv2` synthesises a connection that collides with…") is a separate readability tweak. This item only ensures the position attached to the diagnostic is the actionable one; the message stays as is. Likewise, retiring `@asConnection(connectionName:)` is its own item; this change is forward-compatible with that simplification (when the argument is gone, every collision unambiguously points at one field).

## Verification

`mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes with the new test. Spot-check by running the failing schema cited in the originating bug report locally and confirming the validation errors now carry a `SourceLocation` line/column that opens at the `@asConnection` carrier field in an editor.
