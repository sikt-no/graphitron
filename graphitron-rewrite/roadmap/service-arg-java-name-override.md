---
id: R41
title: "@field(name:) on @service method args"
status: Backlog
bucket: architecture
priority: 3
theme: service
depends-on: []
---

# `@field(name:)` on `@service` method args

## Problem

`ServiceCatalog.reflectServiceMethod` matches Java parameters to GraphQL arguments by exact name. The Java parameter name (compiled with `-parameters`) must equal the GraphQL argument name. There is no override, so a schema author who follows one naming convention (e.g. singular `input`) and a service author who follows another (e.g. plural `inputs` for a `List<…>`) cannot both keep their preferred name. The only options today are renaming one side or the other.

`@field(name:)` already reads as "the underlying name this GraphQL slot binds to" in the column-mapping arm. Extending it to service-method arguments lands the same idea on a Java parameter and reuses a directive users already know.

## Proposal

Allow `@field(name: "<paramName>")` on a GraphQL argument of an `@service`-bound field to override the Java parameter name the argument binds to:

```graphql
opprettRegelverksamling(
  input: [OpprettRegelverksamlingInput!]! @field(name: "inputs")
): OpprettRegelverksamlingPayload!
  @service(service: { className: "…RegelverksamlingMutations", method: "opprettRegelverksamling" })
```

Reads as: bind the GraphQL `input` arg to the Java `inputs` parameter of `opprettRegelverksamling`. Absent the directive, the existing name-equality rule applies.

## Plan sketch

1. `FieldBuilder.resolveServiceField` already collects `argNames` from `fieldDef.getArguments()`. Build a second structure alongside it: `Map<String,String> javaNameToArgName` populated from `@field(name:)` on each arg, defaulting to identity when absent.
2. Extend `ServiceCatalog.reflectServiceMethod`'s signature to accept the map. Inside the per-parameter loop, resolve `pName` to its GraphQL arg name through the map before the `argNames.contains(...)` check; the rest of the classification (extraction, typing) is unchanged.
3. Validation in `FieldBuilder.resolveServiceField` (before reflecting):
   - reject when the same Java name is targeted by two args (collision);
   - reject when `@field(name:)` references a parameter of the service method that doesn't exist (typo guard, leveraging the reflection step).
4. `@tableMethod` follows the same pattern via `reflectTableMethod`; do both for symmetry — the change is mechanical once the map plumbing exists.
5. Context arguments (`@service(contextArguments: [...])`) are out of scope for this item: they're keyed by name into the runtime context and the override would have to propagate to the registry. File a follow-up if a real case appears.

## Tests

- `ServiceCatalogTest`: param `inputs` with `argNames={input}` and override `{inputs→input}` classifies as `ParamSource.Arg` with the GraphQL name `input`.
- `ServiceCatalogTest`: collision (two args target `inputs`) → clear error.
- `ServiceCatalogTest`: `@field(name:)` references unknown Java param → clear error naming both the directive site and the available parameter names.
- `GraphitronSchemaBuilderTest`: SDL-driven happy path verifying the directive is read off the argument.
- Pipeline-tier coverage piggybacks on existing `@service` mutation fixtures by adding one schema fixture that uses the override.

## Notes

- `@field(name:)` is overloaded by argument context, not by directive: on a filter argument in a `@table`-backed return type it names a column (existing behaviour); on an `@service` / `@tableMethod` argument it names a Java parameter. The two contexts are mutually exclusive — column binding fires only for filter args inside table-backed return types and never for `@service` parameters, so a single argument site never sees both meanings. The directive-spec change is to allow `name:` at the argument location for service-bound fields and document the per-context resolution.
- Co-considered with `R1` (`BatchKey` lifter directive): both extend the binding language for service method parameters, but they're orthogonal — this item is about *naming*, R1 is about *DTO-to-key conversion*.
