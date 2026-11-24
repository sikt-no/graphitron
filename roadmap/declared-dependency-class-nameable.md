---
id: R1206
title: "A class in a declared dependency is nameable where the schema names it"
status: Spec
bucket: feature
priority: 3
theme: service
depends-on: []
created: 2026-11-24
last-updated: 2026-11-24
---

# A class in a declared dependency is nameable where the schema names it

## Goal

An author can name a class from a dependency the module declares in `@service`, `@condition` or
`@externalField`, as `docs/manual/how-to/external-code.adoc` ("Make the class nameable") promises.
Today such a class is refused `CODE_REFERENCE_METHOD_NOT_FOUND`, because the code gatherer reads
members (`code_method`) only of the build's own entries, `PROJECT`, `REACTOR` and `SIBLING`
(`CodeCapture.REACTOR_ORIGINS`). When this lands, a declared jar's class is read exactly where the
schema names it, and nowhere else. A class only another dependency drags in (`TRANSITIVE`) stays
refused, as the manual says.

## Why not read every declared jar

C measured the blanket widening on opptak: 5 s more capture and 62% more store, about 29k methods,
nearly all of them jOOQ and graphql-java API that no schema names. Alf ruled that a declared jar's
class is read only where a code reference in the graph names it.

## Model

**A class's members stay facts about its classfile.** They are written into the source-keyed `code_`
family (`code_method`, `code_method_parameter`, the candidate arms, `code_type`, the slots), owned
by the code gatherer, at the grain those relations already have. Only the population follows the
schema: which classes of a declared entry get member rows is decided by the code references that
name them. Every reader of `code_method`, and its 9 foreign keys, stays as it is.

**The schema's code references are read before the classpath members.** Today the code gatherer
runs before the schema is read (`ModelCapture`), and the `graphitron` gatherer that records the
written references also resolves them, after `code`. So:

- The as-written half of the `graphitron` gatherer, the `graphitron_ast_` relations
  (`GraphitronAstCapture`'s entry capture), becomes a gatherer of its own, `graphitron-ast`. None of
  its writers reads a `code_`, `sql_` or `store_class` relation, and `fact-model.adoc`, "Ownership",
  already names it the as-written half.
- `meta_gatherer_dependency` gains `('graphitron-ast', 'graphql-ast')`, `('graphitron',
  'graphitron-ast')` and `('code', 'graphitron-ast')`. The `meta_relation` owners of the
  `graphitron_ast_` relations move to the new gatherer.
- `ModelCapture` runs `CodeCapture` after the transcription. Nothing between today's code capture and
  the transcription reads `code_`. A document that fails to parse now stops the capture before the
  code gatherer, so the code rows keep their last clean reading and the entries go unvouched, to be
  read again on the next run.

**The demand is read where it already is.** References are written fully qualified, so
`graphitron_ast_code_reference_entry` already holds every `(graph, class_name)` a schema names. No
demand relation is added: a relation that nearly holds a fact is extended rather than shadowed
(`modeling-discipline.adoc`, Alf 2026-11-14).

**The new step.** The code gatherer's referenced-classes step reads:

- each class some graph's references name;
- that a `DECLARED` entry the reading opened holds;
- and that no `PROJECT`, `REACTOR` or `SIBLING` entry declares.

Each such class is read whole through the member writers the reactor's classes go through, as
`handleSlots` and `readWhole` read the session handle class. It also joins the `nestedClasses` walk, so
a jar service's nested result records are read too. `TRANSITIVE` entries are never opened, so they add
nothing, and the step adds no origin predicate of its own beyond reading the entries the reading opens.

**Sweep.** A demand-read class's rows in an entry go when no graph's reference names that class any
longer. A jar entry the reading skipped as unchanged is opened again when the demand on it grows.

## Cost

On opptak (FS 946fc4aa20), 383 code references name 125 classes, all `REACTOR`, and `code_method`
holds 4081 rows, all `REACTOR`. The step reads no extra class there. Its own cost is one distinct
select over the references, and moving the gatherer adds no work. A cold opptak pair, trunk against
this, measures it with load noted.

## Tests

- **A fixture for a declared and a transitive jar.** `ClasspathFixtures` gains
  `corpus-and-library-declared` and `corpus-and-library-transitive`: the classpath corpus with the
  existing fixture library (`graphitron-sakila-service/src/fixture/library`, compiled with
  `-parameters`), as a `DECLARED` and as a `TRANSITIVE` entry. Today the library is only a `REACTOR`
  entry (`library-named`).
- **A scenario document, read over `corpus-and-library-declared`.**
  `@service(service: {className: "lib.HolderService", method: "assign"})` resolves:
  - `graphitron_code_reference` names the method;
  - `code_method` holds `lib.HolderService`'s methods under the library's entry;
  - no other class of the library has member rows.
- **A defects document, the near misses:**
  - over `corpus-and-library-declared`, the same class with a misspelt method draws
    `CODE_REFERENCE_METHOD_NOT_FOUND`;
  - over `corpus-and-library-transitive`, the class draws `CODE_REFERENCE_CLASS_NOT_READ`.
- **The sweep:** a stepped document drops the reference, and the class's member rows go.
- **The gatherer split:** `MetaDeclarationGateTest` and the stage roster pass with the new gatherer.
  `CaptureCorpusIsolationTest` keeps the transcription free of the catalog.

## Open questions

- **Shape.** Alf's ruling as relayed was "a per-graph relation beside `code_method`". This plan keeps
  the members source-keyed and lets only their population follow the schema, which leaves every reader
  and foreign key of `code_method` unchanged. A per-graph mirror would state a classfile's members a
  second time, at a grain they do not have (`fact-model.adoc`, "Grain", Alf 2026-11-13), and would put
  a union at every reader. Alf decides.
- **The rule this bends.** A crawler's rows about its own corpus must not vary with another corpus
  (`fact-model.adoc`, "Gatherers"). Here a jar entry's member rows vary with the schema in which
  classes have rows, never in what a row says. `('code', 'store')` with `handleSlots` already does the
  same. If Alf and Filip accept the population-versus-content distinction, it is written into
  `fact-model.adoc`, "Gatherers".
