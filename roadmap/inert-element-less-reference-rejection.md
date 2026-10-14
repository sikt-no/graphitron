---
id: R692
title: "An element-less @reference is an author error at every site"
status: Backlog
bucket: architecture
priority: 7
theme: diagnostics
depends-on: []
created: 2026-08-17
last-updated: 2026-10-14
---

# An element-less @reference is an author error at every site

## Goal

`@reference(path: [])` is refused wherever it is written, so an application of the directive always
states at least one hop. Implicit navigation is spelled by writing no directive; an application
exists to state a path, and one stating none says nothing.

## Why

A missing `path:` is already impossible: the directive declares `path: [ReferenceElement!]!`, so the
schema validation graphql-java runs refuses it. The empty list is legal SDL, and no site gives it a
meaning that leaving the directive out does not:

- **On a field definition** it is the same as no directive. `BuildContext.parsePath` gathers the
  elements of every `@reference` on the field, and an empty gathering is what triggers the inference
  of the single foreign key between the field's start and target tables, whether it came from no
  application or from one that wrote `[]`.
- **On an argument or an input field** it is inert. There is no target table to infer against (both
  sites call `parsePath` with a null `targetSqlTableName`), so the empty path stays empty, the column
  resolves against the field's own table, and the carrier behaves as if the author had written no
  directive. That is an authoring trap: it looks like it constrains the filter and does nothing.

Two neighbouring cases already reject it. An element-less application inside a multi-application
chain is a structural rejection ("an @reference application composing a table chain must carry at
least one path element"), and `@referenceFor` refuses an element-less list outright, a
`@referenceFor` application stating a complete path. The single-application `@reference` is the one
place the empty list is still accepted.

Acceptance today is deliberate as of the FK-target `@nodeId` translated-filter work, which fixed a
crash on this shape by binding it `Local` on the argument side to match the input-field side and
left the question of accepting it open. This item closes it.

## Plan

One rule over all three sites, not one per site: rejecting at the argument alone re-created the
asymmetry that produced the original crash.

- A classifier arm refusing an element-less `@reference` at a field, an argument and an input field,
  and its mirror in `GraphitronSchemaValidator`.
- The two pipeline cases in `ReferenceFilterRemoteColumnPipelineTest` that pin the inert binding
  become rejection cases.
- The `@reference` manual page (`join-with-references.adoc`) states the rule where it now says the
  empty path is legal and inert, and says that inference is written by leaving the directive out.

It is a breaking change for a schema that writes `path: []`, generated SDL that always emits the
argument among them. No consumer schema we hold writes one, and such a schema gets a clear error
rather than a directive that does nothing.

Until this lands, the fact store treats `[]` as equivalent to no directive, which is what the
generator does on a field definition and in effect on the other two sites.

## Notes

**2026-10-14.** No consumer schema writes `@reference(path: [])`, and none writes `path: {}`.
Checked across the seven Graphitron subgraphs in fs-plattform at `origin/main` `4d2f79280b`: sis,
opptak, tilgangsstyring, utdanningsregisteret, kompetanse, kreg and fskode, about 1,400
`@reference` applications between them. The breaking change this item makes reaches none of them.
`roadmap/audits/2026-10-14-reference-keys-in-consumer-schemas.md`.
