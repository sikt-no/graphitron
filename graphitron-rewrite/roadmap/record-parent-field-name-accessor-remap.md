---
id: R191
title: "Honor @field(name:) for accessor lookup on free-form @record parents"
status: Backlog
bucket: bug
priority: 5
theme: service
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Honor @field(name:) for accessor lookup on free-form @record parents

On a free-form `@record` parent (Pojo / JavaRecord backing class), `FieldBuilder.collectAccessorMatches` derives candidate accessor method names exclusively from the GraphQL field name: it tries `<name>`, `get<Name>`, `is<Name>` and nothing else. The `@field(name: …)` directive value is computed into `columnName` at the call site (`FieldBuilder.java:3668-3670`) but never threaded into the accessor matcher; `resolveRecordParentSource(name, …)` is invoked with the raw GraphQL field name (`FieldBuilder.java:3700`). When an author writes a Pojo-backed `@record` whose accessor name diverges from the GraphQL field name and tries to bridge with `@field(name: "<accessorTail>")`, the matcher returns `AccessorDerivation.None` and the field falls through to the three-option AUTHOR_ERROR. The rejection text then advertises "expose a typed accessor on the parent returning List<…Record>, Set<…Record>, or …Record" without mentioning the name-matching requirement, so authors read the suggestion as describing exactly what they already wrote and conclude the validator is broken. Concrete repro shape (from a user report): a Pojo with `getUtdanningsspesifikasjonRecord()` / `getUtdanningsmulighetRecords()` returning typed `TableRecord` shapes, exposed through SDL fields `utdanningsspesifikasjon` / `utdanningsmulighet` with `@field(name: "utdanningsspesifikasjonRecord")` / `@field(name: "utdanningsmulighetRecords")` — both fields land as `UnclassifiedField` despite the typed accessors being present.

Two acceptable Spec resolutions; the In Progress author picks one with reviewer-architect input. (a) Teach `collectAccessorMatches` to honor `@field(name:)` on free-form `@record` parents: when the directive is present, use the directive value as the sole accessor-name target (with the same `<value>` / `get<Value>` / `is<Value>` triple). The column-remap interpretation continues to apply on `JooqTableRecord` parents; the two readings are disjoint by parent shape. (b) If the design intent is that `@field(name:)` is strictly a column remap and accessor names must literally match the GraphQL field, leave the validator alone and rewrite the rejection text to say "the accessor name must match the GraphQL field name (with optional `get`/`is` prefix)" so the misleading "expose a typed accessor returning …" framing stops sending authors in circles. Spec should also add a `GraphitronSchemaBuilderTest` case under the existing `AccessorDerivedSourceCase` enum covering the divergent-accessor-name + `@field(name:)` shape — either as an admit (option a) or as a rejection with the new error text (option b).
