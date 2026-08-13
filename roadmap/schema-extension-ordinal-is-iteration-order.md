---
id: R653
title: "The extension ordinal records JVM iteration order and calls it a position"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# The extension ordinal records JVM iteration order and calls it a position

`store_graph_schema_extension.ordinal`'s column comment promises "stable position in the
resolved set, for faithful replay", and the value does not deliver it.
`AbstractRewriteMojo.buildSchemaRecipe` builds the recipe with `List.copyOf(extensions)` over the
`Set` that `effectiveSchemaFileExtensions` returns, and the omitted-configuration path returns
`RewriteContext.DEFAULT_SCHEMA_FILE_EXTENSIONS`, a `Set.of(...)` whose iteration order is
salted per JVM. So two runs of the same build write the same extension rows under different
ordinals, and the comment describes a stability the column does not have.

Nothing is currently wrong downstream, which is why this is a Backlog item and not a defect
against a shipped promise to a reader. Extension order does not affect match membership, the
filter being a suffix test, and the recipe round-trip anchor compares an in-run recipe against
its own rows so it passes either way. The cost is a column that reads as meaningful and is not,
in a relation whose whole justification is faithful replay.

Two candidate fixes, and the choice is the item's work. Either sort the extensions canonically
where the recipe is minted, making the ordinal a real and reproducible position; or accept that
the effective extension filter is a set, drop the ordinal, and key the relation on
`(graph_name, extension)` the way `store_graph_lint_disabled_rule` keys its own `Set` half. The
second is the shape the grain rule the config family settled would pick today ("an ordinal only
where the source is genuinely ordered"), so the burden is on the first option. Either way the
column comment is rewritten to match.
