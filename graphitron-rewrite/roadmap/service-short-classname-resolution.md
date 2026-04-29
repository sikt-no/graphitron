---
id: R47
title: "Short class-name resolution for `@service` and `@externalField` (legacy parity)"
status: Backlog
bucket: cleanup
priority: 7
theme: legacy-migration
depends-on: []
---

# Short class-name resolution for `@service` and `@externalField` (legacy parity)

## Motivation

`ServiceCatalog.reflectServiceMethod` currently calls `Class.forName(className)` directly, forcing an FQN. Existing schemas carry short class names like `className: "PersonService"` and rely on the legacy Mojo's `externalReferenceImports` list to find them. Without short-name resolution, every legacy schema has to be migrated to FQNs at the same time as it migrates to the rewrite, which is unnecessary friction.

Carved out of the original R31 spec, where it was bundled under "Catalog + classification" but is logically independent of the typed-context-value registry. Also benefits [`computed-field-with-reference.md`](computed-field-with-reference.md) (R48), since `@externalField`'s new `reference: { className: "..." }` argument has the same FQN-only constraint today.

## Design

Port the lookup from `ExternalReferences.getClassFrom` (`graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/configuration/externalreferences/ExternalReferences.java:52-85`):

1. If the name resolves as-is via `Class.forName(name)`, use it.
2. Otherwise, prepend each entry from a configured `externalReferenceImports` list (Mojo parameter) and load the unique match.
3. Reject zero-match and multi-match cases with a classification error.

Apply the same lookup at every consumer:

- `ServiceCatalog.reflectServiceMethod` (`@service`)
- `ServiceCatalog.reflectExternalField` (`@externalField`, when R48 ships)
- `ServiceCatalog.reflectTableMethod` (`@tableMethod`)
- Any other `Class.forName` site that loads a developer-supplied class name from a directive argument.

A single helper `ExternalClassResolver.resolve(String name)` lives next to `ServiceCatalog`; the Mojo wires the `externalReferenceImports` list into it at generator startup.

## Implementation

### Configuration (Mojo)

- `<externalReferenceImports>` Mojo parameter (string list of package prefixes; mirrors the legacy plugin's same-named parameter).

### Generator

- New `ExternalClassResolver` helper, package-private to the classification layer.
- Update each `Class.forName` call site listed above to delegate to the resolver.
- Classification errors for zero-match and multi-match cases include the searched prefixes for diagnostics.

## Tests

- **Unit (L1)** — `ExternalClassResolverTest`: FQN resolves directly; short name resolves through prefixes; zero-match rejects with prefixes listed; multi-match rejects with all matches listed.
- **Classification (L2)** — `ServiceCatalogTest`: extend at least one `reflectServiceMethod_*` case to assert short-name resolution succeeds with a prefix configured.
- **Pipeline (L4)** — fixture-side: at least one schema using a short `className` for `@service` and at least one for `@externalField` (when R48 is in flight).

## Open questions

1. **Naming of the Mojo parameter.** `<externalReferenceImports>` matches the legacy plugin and minimises migration friction. Alternative would be a more explicit `<classNameSearchPrefixes>` but matters less than parity.
2. **Resolver caching.** Resolution runs once per directive site at generate time; caching the lookup is a micro-optimisation. Skip until profiling shows it.

## Roadmap entries (siblings / dependencies)

- **Sibling slice of** [`typed-context-value-registry.md`](typed-context-value-registry.md) (R45) and [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md) (R46). All three carved out of the original R31 spec.
- **Benefits** [`computed-field-with-reference.md`](computed-field-with-reference.md) (R48): the new `reference: { className: ... }` argument should accept short names once this lands.
