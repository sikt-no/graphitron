---
id: R930
title: "Every SDL fact names the document it was read from"
status: Backlog
bucket: architecture
priority: 2
theme: tooling
depends-on: []
created: 2026-09-07
last-updated: 2026-09-07
---

# Every SDL fact names the document it was read from

## Goal

Every row the SDL walk writes says which `.graphqls` document it came from, and says it in a column
the schema declares `NOT NULL`. Today 37 of the 54 source-owned `graphql_` and `graphitron_` relations
declare `source_name` nullable, and rows really do carry NULL there, so for those relations the store
cannot answer which file a fact came from. When this lands it can, for every fact, which is what lets
anything downstream refresh, invalidate or attribute per document rather than per graph.

Nullability at this scale is the symptom rather than the defect, and the premise this item works
from is that there is no legitimate NULL to accommodate: **everything in the merged registry came
from a document, so everything in it can name one.** The defect is that
`SdlFactCapture.capture` walks a **merged** `TypeDefinitionRegistry` and recovers the document
afterwards from graphql-java's per-node `SourceLocation`, and `SdlFactCapture.setPosition` returns
without setting the column wherever that location is absent. The information is not missing from the
world, it is discarded upstream: `SchemaLoader.parsePerSource` already reads the documents one at a
time, and the walk that consumes the merge cannot see what the parse knew.

The two populations that produce a NULL today are dealt with rather than modelled. A
programmatically built registry is a shape we no longer support, so it is retired rather than given
an attribution; that retirement is part of this item and its blast radius is what Spec has to size.
The bundled `directives.graphqls` is an input the store read, which makes it a source like any other,
and `store_source.stamp`'s comment already records it as a resource name, so it gets a row and its
definitions get attributed to it.

The class's own javadoc already claims the property this item makes true. It says capture is
"type-local: every row's content is a function of its own type's declaration sites and nothing else
... which is what keeps a single file the unit of an incremental refresh". A single file is not the
unit of anything while a third of the relations cannot name their file.

## Why this is worth its own item

R872 hit it from the other side and stopped. That item roots each family's facts at the row they hang
off so a delete is complete by construction, and its SDL phase wanted a `(graph_name, source_name)`
foreign key into `store_graph_source`. A NULL matches no foreign key, so on 37 relations the edge
would be declared, would satisfy a structural gate that checks declared edges, and would delete
nothing. Its Spec gate withheld on exactly that, and R872 now stops at the families whose attribution
is already sound.

The tree argues against that edge twice, and both statements are about this defect rather than about
the edge being wrong in principle. `store_graph`'s table comment: "the graph is ambient before the
walk begins and NOT NULL on every row, while the source rows are a summary collected last and
nullable at schema-level sites, so the FK doctrine admits one and not the other."
`FactCaptureAgreementTest.schemaFilesAreRecordedAsSources`'s javadoc: "a schema-level row can carry a
null source name, and the FK doctrine puts one only where the walk writes the child while standing on
the parent." Both describe a walk that is not standing on the parent. Make it stand there and the
doctrine admits the edge.

## What Spec has to settle

**Where attribution comes from.** The candidate is the walk's own position rather than the AST node's:
drive capture from the per-source parse so the walk always knows which document it is reading, and
write that. `SourceLocation` stays the source of line and column, which it answers well.

**What retiring programmatic input costs.** The premise above settles what a fact with no file is
attributed to by removing the case rather than answering it, and the open question is the blast
radius: which callers and which tests build a registry rather than reading files, and whether the
bundled resource needs a `source_kind` of its own or rides as a `SCHEMA_FILE` under a resource name.

**Which document owns a schema-level row.** `graphql_schema_directive` and `graphql_root_operation`
are keyed at schema level, and a `schema` block may be extended from several files. Per-node
attribution from the walk's position gives the file that wrote the row, which is probably right and is
not obviously right; the alternative is that these relations are functions of more than one document
and belong in a re-aggregated set instead. R872's taxonomy would move with that answer.

**What the two spellings of absence become.** `GraphSourceMembership.note` normalises a null source to
the empty string while these columns use NULL, so the store currently spells "no source" two ways. One
of them should survive.

## Also in scope, once the premise holds

The SDL cascade and the coordinate re-aggregation, which were R872's phase three and moved here with
the defect that blocked them: the `(graph_name, source_name)` edges into `store_graph_source` from the
54 source-owned relations, an index on that pair for each, the re-aggregation of the fifteen relations
that are a function of more than one document, and the schema-file-belongs-to-one-graph rule with its
`store_source.graph_name` column. None of them is sound until the column is `NOT NULL`, which is why
they are here rather than there. R872 keeps the three families whose attribution already holds.

## Out of scope

The `sql_`, `jvm_` and `java_` families, which are R872's and need nothing from this item.

## Provenance

Found at R872's Spec gate, round 1, by an independent reviewer checking whether the proposed foreign
key reaches the rows it exists to delete. It does not, on 37 of 54 relations. Filed here rather than
absorbed because the fix is a change to how capture reads its corpus, with its own open questions,
and because every fact naming its document is worth having whether or not the cascade follows it.
