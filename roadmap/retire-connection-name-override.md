---
id: R208
title: "Retire the @asConnection(connectionName:) deprecated argument"
status: Backlog
bucket: cleanup
theme: pagination
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Retire the @asConnection(connectionName:) deprecated argument

`@asConnection(connectionName:)` is deprecated in `directives.graphqls` (the SDL `@deprecated` marker landed alongside R93's `SdlAction` migration registry; see `directives.graphqls:243`) but still functional: `ConnectionPromoter.resolveConnectionName` honours an explicit override when present and falls back to the `<ParentType><FieldName>Connection` derivation otherwise. The deprecation reason states the architectural concern: sharing one synthesised type across distinct carrier fields conflates parents / filters / orders at the type level, and the override exists "only as a transition mechanism for legacy schemas".

Carrying the deprecated argument has concrete downstream costs:

- **Per-field source-location provenance (R206) cannot become an invariant.** R206 threads the `@asConnection` carrier's `SourceLocation` through into the synthesised `ConnectionType` / `EdgeType` records. R206's plan §1 documents first-write-wins as the dedupe rule because, with `connectionName:` still legal, two carriers can legitimately name the same synthesised type. Once the override is gone, a synthesised connection name maps to exactly one carrier and the "carrier-field source location" becomes well-defined; the validator can promote the assumption to a validator-enforced invariant rather than a documented convention. (R206's plan §1 references that future invariant explicitly.)
- **Two diagnostic phrasings to maintain.** `Rejection.java:243-246` carries two parallel fix hints for the case-fold collision arm: a "rename the source field or set `@asConnection(connectionName:)` to a name that is unique" path and a "rename the connection (source field or `@asConnection(connectionName:)`)" edge-name variant. When the override retires, both messages simplify to the rename-the-source-field path.
- **Test fixtures use the override to force collisions that no realistic schema produces.** `GraphitronSchemaBuilderTest.CaseInsensitiveTypeClashCase.SYNTH_VS_SYNTH` and `SDL_VS_SYNTH` use `@asConnection(connectionName: "FooConnection")` / `(connectionName: "fooConnection")` to manufacture a case-fold collision between synth names. Without the override, the equivalent collision is engineered via two carriers whose default-derived names differ only in case (e.g. `Foo.x` vs `foo.X`, or `Query.fooConn` vs `Query.FooConn`). The arm continues to test what it tests, but the SDL fixture stops being "schema-author writes a duplicate `connectionName:`" and becomes "schema-author writes two collision-prone field names".

The retire is mechanical: drop the argument from `directives.graphqls`, drop `ARG_CONNECTION_NAME` plumbing in `BuildContext.java:134` and the `applied.getArgument(ARG_CONNECTION_NAME)` branch in `ConnectionPromoter.resolveConnectionName`, simplify the two `Rejection` messages, rewrite the affected `CaseInsensitiveTypeClashCase` SDL fixtures to use carrier-name-driven collisions, and run the full `mvn install -Plocal-db` to catch any consumer that still names the argument in test SDL.

**Open question for Spec stage.** R93 wired an `SdlAction` quick-fix at `Member("@asConnection", "connectionName")` that surfaces the deprecation in the LSP. The retire either: (a) waits a deprecation cycle so consumers have time to react, in which case the SdlAction stays and gets a sharper "this will be removed in <next major>" message; or (b) deletes the argument and the SdlAction together, with a migration note in the changelog. Pick a side during Spec drafting; the choice affects whether this item lands solo or behind a version-bump gate.
