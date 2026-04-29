---
id: R40
title: "@field(javaName:) on @service method args"
status: Backlog
bucket: architecture
priority: 3
theme: service
depends-on: []
---

# `@field(javaName:)` on `@service` method args

## Problem

`ServiceCatalog.reflectServiceMethod` matches Java parameters to GraphQL arguments by exact name. The Java parameter name (compiled with `-parameters`) must equal the GraphQL argument name. There is no override, so a schema author who follows one naming convention (e.g. singular `input`) and a service author who follows another (e.g. plural `inputs` for a `List<…>`) cannot both keep their preferred name. The only options today are renaming one side or the other.

`@field(javaName:)` already exists for column-bound usages and reads naturally as "this GraphQL slot binds to that Java identifier". Reusing it for `@service` arguments closes the gap with a directive users already know.

## Proposal

Allow `@field(javaName: "<paramName>")` on a GraphQL argument of an `@service`-bound field to override the Java parameter name the argument binds to:

```graphql
opprettRegelverksamling(
  input: [OpprettRegelverksamlingInput!]! @field(javaName: "inputs")
): OpprettRegelverksamlingPayload!
  @service(service: { className: "…RegelverksamlingMutations", method: "opprettRegelverksamling" })
```

Reads as: bind the GraphQL `input` arg to the Java `inputs` parameter of `opprettRegelverksamling`. Absent the directive, the existing name-equality rule applies.

## Plan sketch

1. `FieldBuilder.resolveServiceField` already collects `argNames` from `fieldDef.getArguments()`. Build a second structure alongside it: `Map<String,String> javaNameToArgName` populated from `@field(javaName:)` on each arg, defaulting to identity when absent.
2. Extend `ServiceCatalog.reflectServiceMethod`'s signature to accept the map. Inside the per-parameter loop, resolve `pName` to its GraphQL arg name through the map before the `argNames.contains(...)` check; the rest of the classification (extraction, typing) is unchanged.
3. Validation in `FieldBuilder.resolveServiceField` (before reflecting):
   - reject when the same Java name is targeted by two args (collision);
   - reject when `javaName` doesn't reference a parameter of the service method (typo guard, leveraging the reflection step).
4. `@tableMethod` follows the same pattern via `reflectTableMethod`; do both for symmetry — the change is mechanical once the map plumbing exists.
5. Context arguments (`@service(contextArguments: [...])`) are out of scope for this item: they're keyed by name into the runtime context and the override would have to propagate to the registry. File a follow-up if a real case appears.

## Tests

- `ServiceCatalogTest`: param `inputs` with `argNames={input}` and override `{inputs→input}` classifies as `ParamSource.Arg` with the GraphQL name `input`.
- `ServiceCatalogTest`: collision (two args target `inputs`) → clear error.
- `ServiceCatalogTest`: `javaName` references unknown Java param → clear error naming both the directive site and the available parameter names.
- `GraphitronSchemaBuilderTest`: SDL-driven happy path verifying the directive is read off the argument.
- Pipeline-tier coverage piggybacks on existing `@service` mutation fixtures by adding one schema fixture that uses the override.

## Notes

- Existing `@field(name:)` on column-bound args is a separate axis (GraphQL → SQL/jOOQ column); the two arms don't overlap because column binding only fires for filter args inside table-backed return types, not for `@service` parameters. No directive-spec change needed beyond confirming `javaName` is allowed at the argument location.
- Co-considered with `R1` (`BatchKey` lifter directive): both extend the binding language for service method parameters, but they're orthogonal — this item is about *naming*, R1 is about *DTO-to-key conversion*.
