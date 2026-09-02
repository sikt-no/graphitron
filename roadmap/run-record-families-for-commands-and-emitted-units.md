---
id: R727
title: "Run-record families: committed command rows and the emitted-unit census land in the store"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Run-record families: committed command rows and the emitted-unit census land in the store

The store answers what the schema means, and after
`roadmap/planners-read-facts-emitters-read-commands.md` it will be what the plan derives commands
from, but nothing records what a run *concluded*: which command rows the plan committed, and which
Java units the render fold emitted for them. Both conclusions exist only in memory during the run,
so every consumer that wants them has to reproduce the tier that produced them. The language server
and the MCP cannot answer "what code did this coordinate produce" without re-deriving planner
logic, and a cross-tier invariant cannot be asked at all: the enforcement gap in
`roadmap/list-ordering-invariant-enforcement.md` is precisely a question of the form "every
coordinate the facts classify as list-shaped has a launcher row carrying an ordering", and its
hardest case is a site that takes *no* launcher row, an absence no in-memory walk over existing
rows can see but a relational anti-join between the fact stratum and a command record answers
directly.

## Shape

A run-record stratum, written *downward* by the tier that owns the rows, at the tier's own cadence:

* **The plan's committed command rows**, written after `EmitPlan.produce` commits them. Keyed by
  coordinate plus the glue keys the relations already declare; javapoet types stay out, exactly as
  the plan itself refuses them.
* **The render's emitted-unit census**, written by the render fold: coordinate to emitted class,
  method, file. The fold's closure invariant already holds this mapping in hand (every emitted
  method is the render output of exactly one committed command), so the writer is a fold over data
  the shell has, not a parse of the generated tree.

The cadence precedent is `javac_`: a post-capture family with its own writer, whose graph partition
capture clears "because its rows describe an emitted tree the run is about to replace"
(`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`, header). The
emitted-unit census is the second family whose corpus the run itself produced; the fact model
(`docs/architecture/explanation/fact-model.adoc`) names `javac_` as the only one today.

## The emitted-unit census is a corpus read, not a written record

This half was specified above as a fold over what the shell already holds, "not a parse of the
generated tree". That was decided before the write-direction boundary existed, and it is the wrong
side of it. Two things settle it.

**Positions.** The use case is jumping from a schema coordinate to a list of candidate sites in
generated code, which needs a target file, line and column, and a description telling the
candidates apart. The render fold cannot know a line or a column: a position exists only once a
`TypeSpec` becomes text, and `JavaFile.writeToPathReporting` renders and writes in one step. Getting
positions from the fold means teaching `graphitron-javapoet` to report per-member offsets as it
emits, a new contract in a library whose value is knowing nothing about meaning. Read from the
emitted tree instead and `SourceWalker` already reports 1-based line and column per declaration.

**The boundary.** A corpus fact can always be gathered; a conclusion can only be reported. The model
can go read any corpus, the emitted tree included, so it should never accept a corpus fact from a
consumer. It cannot go read a decision that existed only in the memory of the tier that reached it.
That sorts this item's two families to opposite sides: the committed command rows are a conclusion
and are reported downward, fenced by the no-upward-read rule below; the emitted-unit census is a
corpus fact and is gathered. This item already half-says so, calling the census "the second family
whose corpus the run itself produced" and then writing it from memory anyway.

### How the census is gathered

* **The generator writes provenance into the file it emits.** `addJavadoc` exists on `TypeSpec`,
  `MethodSpec` and `FieldSpec` and is already used by a dozen generators, so this is established
  practice rather than a new capability. A file is the generator's own output channel, so writing to
  it breaks no rule: the store reads what the generator wrote exactly as it reads what the consumer
  wrote.
* **The marker is `@see "graphitron:<KIND> <Coordinate>"`**, with a human sentence in the prose
  above it. The form was chosen by testing the alternatives against a real javadoc run, because we
  emit into consumer projects and a marker that breaks their build is not available. The results are
  in the table below.

[cols="2,1,1,1"]
|===
| form | default `javadoc` | `-Xdoclint:none` | `-Xdoclint:all`

| a custom block tag | error | warning | error
| `{@link Coordinate}` | error, reference not found | error | error
| `@see "graphitron:..."` | silent | silent | silent
| a prose line | silent | silent | silent
|===

A custom tag is unavailable and cannot be waived: `-Xdoclint:none` downgrades the unknown-tag report
to a warning rather than silencing it, so the check belongs to the standard doclet rather than to
doclint and no flag turns it off. `{@link}` is also wrong on the merits, a schema coordinate not
being a Java program element, and the reference gate this repository runs on its own sources is the
check that catches it. A prose line passes but, placed first, becomes javadoc's summary sentence and
so every generated member's editor tooltip; placed last it is a magic trailing line with nothing for
a decode to anchor on. `@see "quoted"` is javadoc's own defined form for a reference to something
outside Java, which is what a coordinate is, and it renders as a see-also entry rather than
displacing the summary. GraphQL names are `[_A-Za-z][_0-9A-Za-z]*`, so no coordinate can contain a
quote and there is no escaping hazard.

The rest of the shape:

* **`@see` is repeatable, which is the grain.** A shared site serving several coordinates carries one
  tag per coordinate; a coordinate served by several sites is several members carrying the same
  marker. Both directions are rows, which is what makes a list of candidates the natural read.
* **The graph name stays out of the marker.** A generated file is attributed to its graph by joining
  `java_file.source_root` to `store_graph_output.output_directory`. The graph is a fact about where
  the file sits, not about the file, and spelling it twice mints a value that can disagree.
* **The marker reaches the store with no new machinery.** `Trees.getDocComment` returns a doc comment
  with block tags intact and `SourceWalker` only whitespace-strips it, so the marker lands verbatim
  in the `javadoc` column that `java_class_declaration`, `java_method_declaration` and
  `java_field_declaration` already carry. Verified against a probe file rather than assumed. So the
  census is a decode over a transcription that already exists, which is the same shape the
  `graphitron_` relations have over the `graphql_` directive applications, and it is stratum two.
* **The kind is closed, the description is rendered.** A free-text description in the store is
  unqueryable and undiffable. The marker carries a CHECK-constrained kind naming what the site is
  (the projection, the fetcher, the mapper, whatever the emitters actually distinguish) and the
  human sentence is rendered from the kind plus the class and member. That gives the MCP a filter and
  gives a gate something to assert coverage against.
* **The census keys on the coordinate relation** rather than on a rendered coordinate string, per the
  store's key discipline, and takes one foreign key to it rather than three relations or one relation
  with a nullable field-and-argument tail.

### What this costs, knowingly

A documentation tag is a published channel: a consumer who generates and publishes javadoc will
carry `See Also: "graphitron:PROJECTION Film.title"` in their rendered output, and nothing suppresses
that while the marker stays in a comment. The only form that avoids it is an annotation, which costs
a compile-scope dependency on a graphitron artifact plus a walker change plus a schema change, since
`SourceWalker` records no annotations at all. Take the visible tag, as a decision rather than a
discovery.

Adding the marker changes every emitted file once, which collides head-on with the
`graphitron-sakila-example` identical-output check that
`roadmap/capture-without-the-materialization-refresh.md` carries as a delivery criterion. So this
lands after that item, not beside it.

### What a corpus read cannot answer

Absence. A coordinate that emitted nothing leaves nothing to parse, and "emitted nothing" is
indistinguishable from "never generated". So the anti-join this item was surfaced by, every
list-shaped coordinate having a launcher row that carries an ordering, is answered by the command
rows and not by the census. That is not a gap in the census; it is the two families being about
different questions, which is the same reason they sit on opposite sides of the boundary above.

Freshness has the same honest shape either way: a workspace that never generated has no census rows,
exactly as it has no `javac_` rows today.

## The rule that keeps this compatible with the tier doctrine

The seam item bans the store serving plan-shaped views as planner inputs, and that ban is about
*read direction*, not about a record. The charter rule for these families: **no tier reads a
run-record family upward.** Planners read facts, emitters read the in-memory command rows the fold
hands them, and neither ever reads the record back; the record exists for gates, the MCP, the
language server, and cross-tier joins. A planner or emitter importing the record's generated tables
is the violation the family comment states and a boundary test forbids, on the
`StoreClientBoundaryTest` model.

Freshness is inherent and honest: rows describe the last run, so an editor workspace that never ran
the plan has none, the same as `javac_` today. Author-facing rules that must be fresh at edit
cadence (the declared-but-unlowerable rejection in the ordering item) stay fact-derived and do not
move here.

## Sequencing

After or alongside the seam item's per-family conversions, which are what make this cheap: once a
producer derives its command rows from the store by SQL, writing them back beside the facts is one
more statement at a grain the producer already owns. The ordering-invariant item does not block on
this (its honesty half is fact-derived, its enforcement half can start as an in-memory fold gate),
but its enforcement becomes non-regressable and its absent-row blind spot becomes queryable only
once the command record exists.

## Provenance

The emitted-code half was first listed as the "generated code catalog" dimension in
`roadmap/knowledge-base-programme.md` (R117), whose own fact-base note reconciles that the model
store, not the DuckDB projection, is where such a dimension lands. The command half was surfaced by
the ordering-invariant enforcement question (R677) during the spec discussion of the seam item
(R682, `roadmap/planners-read-facts-emitters-read-commands.md`), which deliberately keeps both
records out of its own scope.
