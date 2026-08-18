---
id: R713
title: "The graphitron decodes read captured rows instead of the AST"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: [three-tiers-capture-derive-query]
created: 2026-08-18
last-updated: 2026-08-18
---

# The graphitron decodes read captured rows instead of the AST

`GraphitronFactCapture` is 1050 lines that read the SDL AST (`graphql.language.Directive`,
`Argument`, `Value`, `AstPrinter`) and write the 63-relation `graphitron_` family. Beside it,
`SdlFactCapture` reads the same AST and writes the generic directive model into `graphql_`:
definitions in `graphql_directive` / `_argument` / `_location`, and applications with their argument
values at all five locations (`graphql_type_directive` + `_arg`, and the schema, field, argument and
enum-value pairs).

So one corpus is transcribed twice, and the second pass's rows are a function of the first pass's
rows. `graphitron_field_reference_step` decomposes a `@reference` path argument;
`graphitron_service_arg_mapping_sigil` extracts a sigil from an argument value; `graphitron_table`
reads a `name:`. Every one of them is computable from what `graphql_` already holds, which makes the
family a derivation and makes this pass a derivation running in the transcription tier.

The tree already has the right word for it. `MacroCapture`'s javadoc distinguishes what "transcribes
into `graphql_type_directive`" from what "decodes into `graphitron_federation_key`", in one sentence.

## What this is not

Not a demand that the decoding become SQL. A tier is decided by what a relation's rows are computed
from, not by what computes them, so a Java-computed derivation over captured rows is tier two.
`GraphQLSelectionParser` parses selection-set syntax out of a string argument and will stay a parser
in Java under any arrangement.

Not a reason to separate `graphitron_` from `graphql_`. The 48 foreign keys between them are a
derivation's edges to its inputs, correct and permanent.

## The obstacle, and the two moves

`graphql_type_directive_arg.value_sdl` is one VARCHAR, "the value as written, rendered from the AST".
The transcription therefore stores argument values as printed text, which is why the decoder needs
`AstPrinter` and why a decode over rows would have to parse SDL value syntax.

**Move one, required: the decode's input becomes the rows.** `GraphitronFactCapture` stops taking the
registry and takes the transcribed rows, parsing `value_sdl` where it needs structure. This is
mechanical, contained to one class's input, and it is what makes the family tier two: the pass no
longer reads a corpus, so it no longer has to run at capture cadence, and the independence gate the
sibling nodehood item introduces applies to it unchanged.

**Move two, optional and later: structure the transcribed value.** One row per value node carrying a
kind (string, int, enum, boolean, null, list, object, variable), a parent link and an ordinal, so a
list or object value is a tree of rows rather than a printed string. An AST is a tree and a faithful
transcription of a tree is rows. `value_sdl` stays beside it as the written spelling, which the
"two spellings of one value are two base columns" rule in `fact-model.adoc` already licenses, and
which the round-trip emitter reads. This is what turns the shallow decodes into views; the real
parsers stay programs either way.

Move one is worth landing without move two. Whether move two pays is a question about how many
decodes are shallow enough to become views, and that count is worth measuring before committing:
`graphitron_table`, `graphitron_enum`, `graphitron_scalar_type` and the binding relations look
shallow, while the reference, condition, service and order families do not.

## Move three, measured: decode in a view through an H2 function

Preferring views over Java derivations is the standing preference (a view is always current), and H2
can be extended with functions, so a third option exists beside the two above: leave `value_sdl` as
text and decode it inside a view. Measured against H2 2.4.240 and graphql-java 25 rather than read
off documentation.

**What works.** `CREATE ALIAS <name> DETERMINISTIC FOR '<Class>.<method>'` works, and a scalar Java
function reads correctly from inside a `CREATE VIEW`. Alias and view both persist in a file database
and survive reopening.

**The limit.** H2 cannot correlate a FROM-clause table function, or `UNNEST`, with an outer column.
`FROM arg t, steps(t.value_sdl) s`, the same as a `JOIN ... ON 1=1`, and
`FROM arg t, UNNEST(arr(t.value_sdl)) WITH ORDINALITY u` all fail with `Column "T.VALUE_SDL" not
found`, and `LATERAL` is not available (`Function "LATERAL" not found`). A `ResultSet`-returning
function works standalone but cannot be driven by a table's rows, so the natural shape for a
multi-row decode is unavailable.

**The idiom that does work**, verified reading, joining and aggregating: a scalar function returns an
`ARRAY`, cross joined with a literal-bounded `SYSTEM_RANGE`, filtered on `CARDINALITY`, elements
picked with `ARRAY_GET`. Two properties of it are load-bearing rather than incidental. The range
bound must be a literal, so the view needs a ceiling plus a guard query proving no row reaches it.
And `ARRAY_GET` past the end raises an error rather than returning null, so the `CARDINALITY` filter
is what keeps the view readable at all.

**Cost.** Over 20 000 rows: 1690 ms parsing per call (about 85 microseconds each, essentially all of
it graphql-java constructing an ANTLR parser per invocation), 81 ms with a `ConcurrentHashMap` memo
keyed on the input text (202 actual parses, because directive argument values repeat heavily), against
19 ms for a pure-SQL equivalent. Two further findings shape how such a view is written: H2 caches
whole query results, so an identical second read cost 0 ms and made no calls, but the full cost
returns after any write to the driving table; and `DETERMINISTIC` does not memoize per value, so the
same expression twice in one row costs two calls, which the idiom above incurs by naming the function
in both `ARRAY_GET` and `CARDINALITY`.

**Why the function must not be graphql-java-backed.** `CREATE ALIAS` fails loudly at DDL time when the
class is missing, so every host booting the store would need the class and its dependencies on its
classloader: the LSP, the Maven plugin, MCP, the tests, and the codegen driver, which executes this
same DDL to generate the `Tables` classes. `graphitron-model` depends on jOOQ, H2 and jooq-codegen,
not graphql-java, so this inverts a module dependency to put a large library below the schema module.
`GraphitronModelStore.connect()` already records that these hosts "all hand it a loader the
service-loaded driver was not registered under", so this adds a second class to a known hazard. And
the degradation is asymmetric: a *fresh* boot without the class fails loudly, but reopening an
existing file without it does not. H2 drops the aliases silently and the view becomes unreadable,
and `store_stamp` covers the DDL hash and generator version, not the classpath.

**So the shape, if this move is taken:** an H2 function backed by a value-literal parser this project
owns in `graphitron-model`. The grammar is small (string, int, float, boolean, null, enum, list,
object, variable), it needs no dependency, it memoizes trivially, and it removes both the dependency
inversion and the classloader hazard. graphql-java stays where only it can serve, the document parse
and the assembled schema. The selection-set grammar `GraphQLSelectionParser` handles is a different
and much larger language and stays a Java derivation regardless.

This move and move two are alternatives, not a sequence: move two structures the value at capture so
no decode has to parse, move three leaves it as text and parses in a function. Move three is cheaper
to try and does not change the transcription; move two is the better model if the measurement above
turns out to be optimistic on a real schema's directive population. Move one is required either way.

## Sequencing and blast radius

Depends on the tier-naming item for the vocabulary it uses. Wants the nodehood item ahead of it
rather than the reverse: nodehood is the one decode whose inputs span two corpora, so it is the
smallest instance of the same move and proves the shape on one relation before this item does it on
sixty-three.

Blast radius is the reason this is not one commit. `FactCaptureAgreementTest` pins every relation
against the live pipeline, so the agreement suite is the safety net and also the thing most likely to
need re-anchoring. The pass runs inside `FactCapture.capture`'s transaction today, so moving it also
moves work out of that transaction, which interacts with the per-crawler transaction item.

## Out of scope

- The value-structure model, unless the measurement above says move two pays; it is called out here
  so the decision is recorded rather than rediscovered.
- Retiring the three `graphitron_*_synthesis` provenance relations, which the tier reading predicts
  become unnecessary once synthesis is derived.
- Which producer owns the composed `graphql_` payload. The coordinate keys are the assembled
  schema's own grain, and moving the payload's source from the `TypeDefinitionRegistry` to
  `GraphQLSchema` is its own item. This item benefits from it (a decode over composed rows gets
  directive-argument defaults already applied) but does not require it.
