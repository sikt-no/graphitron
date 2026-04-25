# Plan: Replace legacy `introspect` goal

> **Status:** Spec
>
> Replaces `graphitron-maven-plugin:introspect`, which produces
> `graphitron-lsp-config.json` for the Rust-based LSP. The plan
> documents the contract and inputs in language-neutral terms; the
> implementation language (Java port into `graphitron-rewrite-maven`
> vs. Rust port into the LSP itself) is a deliberate open question
> resolved before moving to Ready.

## Goal

Stand up a replacement producer for `graphitron-lsp-config.json` so the
legacy `graphitron-maven-plugin` can be retired without dropping the
LSP's catalog feed. The replacement reads the same conceptual inputs
(jOOQ-generated catalog, GraphQL scalar definitions, external
reference classes), emits the same JSON contract the LSP already
parses, and runs from a single invocation in the consumer's build (or
LSP session) without depending on any other graphitron goal.

## Motivation

`graphitron-maven-plugin:introspect` is the last piece of the legacy
plugin keeping consumers on it; every other goal has a rewrite-side
replacement (see the umbrella roadmap entry "Retire
`graphitron-maven-plugin`"). Until introspect has a successor,
consumers who want LSP support cannot fully migrate, and the legacy
plugin cannot be deleted.

The introspection job is also the one piece of legacy work that does
not belong to schema transformation: it walks compiled jOOQ output,
not GraphQL SDL. That makes it a natural candidate to live somewhere
other than `graphitron-rewrite-maven`. The Rust LSP already needs to
understand jOOQ-shaped data; if it grew its own catalog reader, the
LSP would stop depending on a separate Maven invocation for the
freshness of its catalog and the introspect goal would disappear
entirely instead of being ported. That trade is worth deciding
deliberately rather than by default.

## What the legacy goal does today

`IntrospectMojo` (`graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/IntrospectMojo.java`,
~280 LOC) writes a single JSON file (default
`target/graphitron-lsp-config.json`). Inputs:

1. **jOOQ catalog**, via `TableReflection` reflection on the
   compiled `<jooqGeneratedPackage>.tables.*` and `*.Keys` classes:
   - Per schema's `Tables` class: each public static `Table<?>` field
     contributes a table entry. The Java field name (e.g. `FILM`) is
     the table key in the JSON.
   - Per table: `getReferences()` for outgoing FKs and a
     cross-product over all other tables' `getReferencesTo(table)`
     for incoming FKs.
   - Per table: every public `TableField<?, ?>` field contributes a
     column entry. `nullable` comes from `DataType.nullable()`;
     `field_type` is the column's Java type mapped to a GraphQL scalar
     name where possible.

2. **Scalar definitions**, via `ScalarUtils.getScalarTypeNameMapping()`:
   - Built-in: `graphql.Scalars`, `graphql.scalars.ExtendedScalars`,
     federation-jvm scalars (`_Any`, `_FieldSet`, `link__Import`),
     and `no.sikt.graphql.schema.CustomScalars`.
   - User-supplied: classes named in `<scalars>` config containing
     public static `GraphQLScalarType` fields.
   - Each `GraphQLScalarType` field carries the GraphQL name and a
     parameterised Java coercing class; the introspect goal inverts
     that to a Java-class-canonical-name → GraphQL-name lookup so it
     can label `TableField` columns.

3. **External references**, when
   `includeExternalReferencesForLSP=true`:
   - Explicit `<externalReferences>` `<name, class>` pairs.
   - Package scans from `<externalReferenceImports>` (recursively
     listing `.class` entries on the consumer's compile classpath,
     across both directory and JAR sources).
   - Per resolved class: list of distinct public method names.

The output is consumed by the Rust LSP. Two fields in the current
output are stub data and not authored by the legacy goal:
`description` (always `""`) and `definition` (always
`{"file": "file:///tables/<NAME>" or "file:///builtin", "line": 1, "col": 1}`).
The LSP either ignores them or treats the URI as opaque; either way no
real source coordinate is produced today.

## Output contract

The replacement must produce output the current LSP can read without
modification. The schema (matching `LspConfig` in the legacy plugin)
is:

```
{
  "tables": [
    {
      "table_name": <java-field-name, e.g. "FILM">,
      "description": <string, "">,
      "definition": {"file": <uri-string>, "line": <int>, "col": <int>},
      "references": [
        {"table": <other-java-field-name>, "key": <fk-key-name>, "inverse": <bool>}
      ],
      "fields": [
        {"field_name": <java-field-name>, "field_type": <graphql-or-java-type>, "nullable": <bool>}
      ]
    }
  ],
  "types": [
    {"name": <graphql-scalar-name>, "aliases": [], "description": "",
     "definition": {"file": "file:///builtin", "line": 1, "col": 1}}
  ],
  "external_references": [
    {"name": <short-name>, "class_name": <fqcn>, "methods": [<method-name>, ...]}
  ]
}
```

`external_references` is omitted (not emitted as `[]`) when disabled
via configuration. `inverse: false` means the table that owns the
entry holds the FK; `inverse: true` means another table holds it
pointing here. `key` is the FK's Java field name in the jOOQ `Keys`
class, formatted as `<TABLE>__<FK_NAME>` (both upper-cased).
`field_type` is the GraphQL scalar name when the column's Java type
maps to one; otherwise the simple Java class name.

Two fields in the current contract are unused-by-fact (`description`,
`definition`). The replacement either:

(a) emits the same stubs to keep parser back-compat, or
(b) emits real source coordinates if the producer can compute them.

(b) is only feasible if the producer has access to the GraphQL SDL
files (for `tables` definitions tied to `@table` types) or to scalar
declaration sites; the legacy goal has neither. An LSP-side producer
plausibly has both. Decide once the language is chosen.

## Required behaviours (language-neutral)

The replacement, regardless of where it lives, must:

1. Enumerate every jOOQ table reachable from the configured catalog
   under a single root package (the consumer-supplied
   `<jooqGeneratedPackage>` or its rewrite equivalent). Output uses
   the Java field name from `Tables.<NAME>` as the table identifier;
   the underlying SQL name is not emitted.

2. For each table, enumerate columns as `(java-field-name,
   graphql-or-java-type-name, nullable)` triples. The type-name
   resolution must consult the same GraphQL scalar registry the
   generator uses, so a column whose Java type is e.g.
   `java.time.OffsetDateTime` shows up as `DateTime` rather than
   `OffsetDateTime`.

3. For each table, enumerate FK relationships as `(other-table,
   fk-key-name, inverse)` triples covering both directions. The
   key-name format must match what the LSP cross-references
   elsewhere; current format is `<TABLE>__<FK_NAME>` upper-cased.

4. Enumerate every GraphQL scalar known to the generator (built-in
   plus any consumer-declared scalar definition class) and emit it as
   a `types` entry.

5. Optionally enumerate external reference classes (resolver services
   and similar) with their public methods. Inputs are explicit
   `(short-name, fqcn)` entries plus package paths to scan
   recursively. Toggleable from configuration; off-state omits the
   field entirely.

6. Be invokable on demand without going through `generate` or
   `validate`: the LSP does not want to wait on schema codegen to
   refresh its catalog.

7. Produce deterministic output for the same inputs (stable ordering
   of tables, columns, references, types, external references) so the
   file is content-stable across invocations and round-trips through
   git diffs cleanly.

## Implementation options

The deliberate open question. Three options on the table; pick one
before moving Spec → Ready.

### Option A: port to `graphitron-rewrite-maven`

A new `IntrospectMojo` in the rewrite plugin, structurally similar to
the legacy mojo but built on rewrite-side equivalents of
`TableReflection` and `ScalarUtils`. Goal name `introspect`,
invocation `mvn graphitron-rewrite:introspect`.

Pros:
- Closest to status quo; the consumer build surface is unchanged
  except for the plugin coordinates.
- jOOQ catalog read is live JVM reflection on classes already on the
  Maven plugin's compile classpath: simplest possible reader.
- Scalar resolution reuses the rewrite generator's own scalar wiring;
  no risk of drift between what the generator emits and what the LSP
  is told about.
- External reference scanning has direct classloader access.

Cons:
- Adds ~280 LOC to a module the umbrella plan (`Retire
  graphitron-maven-plugin`) is otherwise trying to keep small.
- Refresh model stays Maven-bound: the LSP cannot re-introspect
  without spawning a Maven invocation, which is slow and noisy in an
  IDE session.
- Ships a JSON file as the integration boundary even though both
  producer and consumer are owned by Sikt; the file is a build
  artefact whose freshness the consumer has to manage.

### Option B: reimplement inside the Rust LSP

The LSP grows a catalog reader: it walks the consumer's resolved
classpath, parses the jOOQ-generated `.class` files for `Tables`,
`Keys`, and per-table column constants, and parses `GraphQLScalarType`
field declarations from the configured scalar definition classes.
Output is consumed in-process; the JSON file may be retained only as
a debugging artefact, or dropped entirely.

Pros:
- The introspect goal disappears: one fewer Maven goal to ship,
  document, and version. Closes the umbrella roadmap sub-item by
  deletion rather than migration.
- LSP can refresh on demand (e.g. when the consumer rebuilds jOOQ),
  with no Maven round-trip. Turnaround drops from "rerun
  `mvn graphitron-rewrite:introspect`" to "the LSP noticed the
  classfile mtime changed".
- The LSP already has the consumer's project context (Maven layout,
  classpath, source files); it is the natural place to know what
  jOOQ classes look like on disk.

Cons:
- Bytecode parsing is a real jump in scope for the LSP. Java class
  files are stable but the work is not free; existing Rust crates
  (`classfile-parser`, `cafebabe`) handle the bytes but the
  jOOQ-specific reflection on top still has to be written.
- Scalar resolution needs the consumer's scalar definition classes.
  Built-in scalars (federation-jvm, graphql-java) live in JARs the
  LSP has to discover via the consumer's POM. Custom scalars likewise.
  This is more classpath-walking machinery than (A).
- External reference enumeration needs to read every `.class` in
  configured packages and extract public method names: another
  bytecode-reader code path.
- The contract drifts away from "what the generator believes" toward
  "what the LSP can deduce". Risk of subtle disagreement (e.g. on a
  custom-scalar Java type) that doesn't manifest until a consumer
  schema breaks.
- Locks in Rust as the LSP's permanent home. If the LSP later moves
  language (e.g. to share more code with rewrite by living in Java),
  this work is rewritten.

### Option C: hybrid (small JVM helper invoked by the LSP)

Keep a JVM-side producer (CLI or in-process helper invoked via a
sidecar JVM the LSP launches), but not as a Maven goal. The LSP spawns
the helper when it needs fresh data, passing classpath and scalar
configuration; the helper returns the same JSON over stdout (or an
RPC channel) without writing a file. The Maven goal disappears, but
the JVM-side code that knows how to read jOOQ catalogs remains in
Java where it is cheap.

Pros:
- Reflection stays on the JVM where it is trivial; the LSP gets the
  on-demand refresh model without growing a bytecode reader.
- The introspect output stops being a build artefact; it becomes an
  IPC payload, removing the freshness-management problem.
- Migration story for consumers: drop the introspect Maven execution,
  install the LSP with the helper bundled.

Cons:
- LSP has to manage a JVM child process: cold-start cost, lifecycle,
  classpath discovery. None of this is hard but it is a moving part
  the current LSP does not have.
- Two code-owning languages stay in the introspection path (Rust
  driver + Java reader); we did not collapse the toolchain, we just
  rearranged it.
- Distribution gets harder: the LSP has to ship (or detect) a JRE.

### Decision criteria

Lock the choice on:

1. **LSP refresh model.** If the LSP needs sub-second refresh on
   classpath changes, (A) is too slow; pick (B) or (C). If
   "rerun maven on jOOQ regen" is acceptable UX, (A) is the cheapest.
2. **Appetite for bytecode reading in Rust.** If the LSP team is
   willing to take on `cafebabe`-level code paths and the maintenance
   tail, (B) wins on long-term simplicity. If not, (C).
3. **Consumer migration cost.** (A) is one POM coordinate change.
   (B) and (C) require an LSP install / upgrade and removal of the
   introspect execution. None of these are heavy.
4. **Future of the LSP language.** If Rust is the long-term answer,
   (B) is an investment; if undecided, (C) keeps that question open.

The branch this plan lives on (`claude/plan-introspect-goal-uI7le`)
is for plan iteration only; lock the option in a follow-up commit to
this same plan before promoting to Ready.

## Open questions

1. **Are `description` and `definition` actually consumed?** Confirm
   with the LSP code (out of repo). If they are no-ops, drop them
   from the contract instead of porting stubs forward. If they are
   live, decide whether the new producer can populate them with real
   values (only feasible if it has access to the schema SDL or, for
   scalars, to scalar-class source files).

2. **Is the `<TABLE>__<FK_NAME>` key format observed elsewhere in the
   LSP?** If yes, it is part of the contract and the new producer
   has to keep it. If no, simplify to `<FK_NAME>` and save the
   double-uppercase concat.

3. **What about multi-schema jOOQ catalogs?** Today the goal flattens
   tables across all schemas under the catalog root, keying solely on
   Java field name. Are there real consumer setups with name
   collisions across schemas? If so, the JSON needs a schema
   qualifier. Cheap to verify against alf's jOOQ output before
   committing.

4. **Custom-scalar classpath in option (B).** If the LSP grows its
   own catalog reader, can it actually discover the consumer's
   `<scalars>` classes from POM analysis alone, or does it need a
   build-time dump?

5. **Should the rewrite generator publish a typed catalog object
   directly?** The generator already constructs in-memory views of
   the jOOQ catalog and the scalar registry. A small "dump these as
   JSON" helper inside `graphitron-rewrite` (callable from a Maven
   goal or from a CLI) could reduce options (A) and (C) to "wire up
   the producer, do not write a parser". Worth checking for in the
   rewrite codebase before settling.

## Tests

Concrete plan deferred until the language is chosen. Test ideas
common to all options:

- A golden-file fixture: a small jOOQ-generated package (two or three
  tables, one outgoing FK, one incoming FK, a few columns of varied
  types) plus a known scalar-definition class plus a single external
  reference class. Run the producer, snapshot the JSON, compare. The
  legacy plugin has no fixture of this shape; a new one is needed
  regardless of language. The rewrite-fixtures module already
  publishes a small jOOQ catalog (`graphitron-rewrite-fixtures` /
  `graphitron-rewrite-fixtures-codegen`); reuse where possible.

- Determinism: run the producer twice in the same environment, assert
  byte-identical output (closes the ordering requirement above).

- Type-mapping coverage: at least one column per built-in scalar
  category (string, int, float, boolean, date / time, ID) plus one
  column whose Java type is unmapped (verifies the simple-class-name
  fallback).

- External-references toggle: enabled run produces the field with
  expected entries; disabled run omits the field entirely (not `[]`).

Option-specific test work (Maven `verify.groovy` IT, LSP integration
test, JVM-helper protocol test) lands when the option is chosen.

## Documentation

- Roadmap: drop the standalone "Port `introspect` goal to
  `graphitron-rewrite-maven`" sub-bullet under the umbrella; replace
  with a "Replace `introspect` goal" entry that links here. Once a
  language is chosen, edit the entry to reflect the destination
  (rewrite plugin, LSP, or hybrid).
- Legacy plugin README's `### Goal: introspect` section: leave
  untouched until the replacement ships, then add a one-line redirect
  on landing.
- `graphitron-rewrite/docs/getting-started.md`: if the replacement is
  a Maven goal, add a `### LSP catalog` subsection alongside the
  watch-mode docs. If it lives in the LSP, no rewrite-side doc is
  needed; the LSP's own README owns the entry point.

## Roadmap integration

Replaces the existing umbrella sub-item "Port `introspect` goal to
`graphitron-rewrite-maven`" in
`graphitron-rewrite/docs/planning/rewrite-roadmap.md`. New entry
text:

> **Replace `introspect` goal** [Spec] (this plan). Successor to the
> legacy `graphitron-maven-plugin:introspect`. Implementation language
> is an explicit open question in the plan; pick before promoting to
> Ready.

Once a language is locked and the plan is promoted to Ready, the
entry's body updates to name the destination module. On landing,
collapse to a Done line citing the commit (or, if Option B/C, the
LSP commit reference) and the fixture or test location.
