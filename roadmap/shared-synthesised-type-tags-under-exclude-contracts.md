---
id: R997
title: "Shared pagination types drop out of exclude-based Apollo contracts by inheriting every carrier tag"
status: Backlog
bucket: bug
priority: 2
theme: pagination
depends-on: []
created: 2026-10-07
last-updated: 2026-10-07
---

# Shared pagination types drop out of exclude-based Apollo contracts by inheriting every carrier tag

## Goal

A federated subgraph whose Apollo contract variants are built by *excluding* a tag keeps its shared pagination types in every contract that has a field returning them. Today a type graphitron generates for more than one `@asConnection` field (the shared `PageInfo`, or a Connection and Edge pair two fields reach through the same `connectionName:`) carries the union of every such field's `@tag`s. A contract that excludes `experimental` then drops `PageInfo` even though its `stable` fields return it, so the stable contract either fails its downstream check or loses pagination. Reported on GitHub issue #555 by the tilgangsstyring subgraph in fs-plattform, after the separate regression there was fixed.

The shape, with type names from the report and field names illustrative. A `stable`-tagged feature file and an `experimental`-tagged one each hold an `@asConnection` field (a list field graphitron expands into a Relay connection, minting the Connection, Edge and `PageInfo` types when the author has not declared them), and neither declares `PageInfo`:

```graphql
# features/stable/... (<schemaInput tag>stable)
extend type Query { organisasjoner: [Organisasjon!]! @asConnection }
# features/experimental/... (<schemaInput tag>experimental)
extend type Query { miljoer: [Miljo!]! @asConnection }
```

The minted `PageInfo` is emitted as `type PageInfo @tag(name: "experimental") @tag(name: "stable")`. Under an include-based contract (keep `stable`) that is correct; under an exclude-based one (drop `experimental`) it removes `PageInfo` from the stable contract. Declaring `PageInfo` by hand avoids the tag, since an author-declared type inherits nothing, but it is not a general answer: a shared Connection/Edge has the same problem, and `@shareable` on a minted type also follows the carriers.

## Notes for Spec

- **The rule today.** `docs/manual/reference/directives/asConnection.adoc` states it: "a synthesised one carries the union of the tags from every connection-promoted field". It is implemented twice, by `TypeRegistry.mergeSynthesisedTags` on the walk and by `EmittedRegistry.applyInheritedTags` on the store path, which is what the published schema now comes from. Type-level tags were chosen because Apollo treats a tag on a type definition as applied to all its fields, which is the include-based reading R298 (`rover-graphos-integration`) set out to verify; nothing has considered exclude-based contracts.
- **The fork.** Union suits include-based contracts and fails exclude-based ones. Intersection, or leaving a shared type untagged when its carriers disagree, suits exclude-based and fails include-based (an untagged type is absent from an include filter). The reporter suggests either the untagged-on-disagreement rule or making inheritance configurable. No single fixed rule serves both contract styles, so the spec has to decide whether graphitron picks one, follows a configuration knob (and where it lives: per build or per schema input), or stops tagging shared types and documents what the author declares instead.
- **One rule, not two.** Whatever is chosen should be stated once; the walk's and the store's implementations agreeing today is what `EmittedRegistryAgreementTest` checks, and that test's corpus configures no tags.
- **Evidence.** An exclude-based contract built from emitted SDL is the acceptance check the reporter is running; R298's package 2 (GraphOS contract verification) is the standing version of that and could gain an exclude-based variant.
