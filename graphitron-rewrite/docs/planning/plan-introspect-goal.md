# Plan: Replace legacy `introspect` goal

> **Status:** Spec
>
> Replaces `graphitron-maven-plugin:introspect`, which produces
> `graphitron-lsp-config.json` for the Rust-based LSP. The plan
> documents the contract and inputs in language-neutral terms; the
> implementation language (Java port into `graphitron-rewrite-maven`
> vs. Rust port into the LSP itself) is a deliberate open question
> resolved before moving to Ready.
>
> The plan also covers a near-term contract extension: the LSP will
> grow `@service` and `@condition` method help (parameter completion,
> return-type validation, method-existence checks). That requires
> richer per-method information than the legacy goal emits, so the
> new producer is designed for the extended contract from day one
> rather than shipping a 1:1 port and then revisiting.

## Goal

Stand up a replacement producer for `graphitron-lsp-config.json` so the
legacy `graphitron-maven-plugin` can be retired without dropping the
LSP's catalog feed, and extend its contract so the LSP can offer
help on `@service` and `@condition` method references in addition to
the existing table catalog. The replacement reads the same conceptual
inputs (jOOQ-generated catalog, GraphQL scalar definitions, external
reference classes), emits a superset of the JSON contract the LSP
already parses, and runs from a single invocation in the consumer's
build (or LSP session) without depending on any other graphitron
goal.

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

The LSP's planned `@service` / `@condition` work also wants more than
the legacy goal emits today. The current `external_references` entry
is a flat list of method names per class: enough to autocomplete the
`method:` argument, not enough to validate that the chosen method's
signature matches what Graphitron expects (parameter sources, return
type, parameter list). The rewrite generator already extracts that
richer view internally (`MethodRef`, `ParamSource`, `ServiceCatalog`),
so the cost of teaching the LSP is mostly about exposing what the
generator already knows rather than computing anything new. Doing
that exposure during the introspect-replacement work avoids paying
the producer-and-consumer migration cost twice.

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
   - Method *signatures* are not emitted today: each method is just
     a string. That is the gap the `@service` / `@condition` LSP
     work needs closed.

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
    {
      "name": <short-name>,
      "class_name": <fqcn>,
      "methods": [
        {
          "name": <method-name>,
          "return_type": <fqcn-or-erased-type-name>,
          "params": [
            {"name": <param-name-or-null>, "type": <fqcn>, "source": <source-tag>}
          ]
        }
      ]
    }
  ]
}
```

The `methods` element is the contract extension. The legacy plugin
emits each method as a bare string (`"createCustomer"`); the
replacement emits an object per method with the signature data the
LSP needs for `@service` / `@condition` help. `source` is one of the
classifications the rewrite pipeline already uses (`Arg`, `Context`,
`Sources`, `DslContext`, `Table`, `SourceTable`, see
`MethodRef.ParamSource`); when the consumer hasn't compiled with
`-parameters`, `params[].name` is `null` and the LSP falls back to
positional naming. Overloads are emitted as separate entries (one
object per signature) so the LSP can match by arity and argument
types; the producer does not collapse them. Open question 9
revisits this if a different strategy is preferred.

LSP back-compat: the existing field name `methods` is reused, but
its element type changes from `string` to `object`. That is a
breaking change for any LSP build still on the legacy parser.
Mitigations:

(a) emit both shapes during a transition window (`methods` as the
    new objects, `method_names` as the legacy strings), and drop
    `method_names` once the LSP releases the parser update; or
(b) flip the parser first, then the producer.

Pick one once the LSP team confirms a release window. (a) is the
default unless the LSP can hard-cut.

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

   For each method, emit:

   - method name,
   - return type (fully qualified, erased),
   - parameter list in declaration order, each parameter carrying
     `(name, type, source)` where `source` is the rewrite-pipeline
     classification (`Arg`, `Context`, `Sources`, `DslContext`,
     `Table`, `SourceTable`).

   When the class was compiled without `-parameters`, parameter
   names are unavailable; emit `null` and let the LSP decide whether
   to surface a fix-it hint pointing at maven-compiler-plugin's
   `<compilerArgs>`. The rewrite generator already emits a one-shot
   warning for this case (`ServiceCatalog.emitParametersWarning`);
   the producer should match that behaviour.

   The classification rules for `source` are the same ones rewrite
   uses today; a producer that lives inside the rewrite codebase
   reuses `ServiceCatalog` directly, a producer that lives outside
   it must mirror the same rules to avoid contract drift.

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
- The `@service` / `@condition` extension reuses
  `ServiceCatalog` / `MethodRef` directly: same parameter
  classification, same `-parameters` handling, same overload
  resolution as the generator. No second source of truth.

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
  bytecode-reader code path. For the `@service` / `@condition`
  extension this code path also has to recover parameter types
  (Signature attribute), parameter names (MethodParameters
  attribute, only present if `-parameters` was set), erased return
  type (descriptor parse), and the rewrite-side `ParamSource`
  classification (which depends on parameter types and on whether
  the surrounding field is a service / condition / table-method
  context). Each of those is a separate bytecode-reading concern.
- The contract drifts away from "what the generator believes" toward
  "what the LSP can deduce". Risk of subtle disagreement (e.g. on a
  custom-scalar Java type, or on parameter-source classification
  for `@service` methods) that doesn't manifest until a consumer
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
- Like (A), the `@service` / `@condition` extension reuses
  `ServiceCatalog` / `MethodRef` and stays in lockstep with the
  generator's classification rules.

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
   tail, (B) wins on long-term simplicity. If not, (C). The
   `@service` / `@condition` extension significantly raises the (B)
   cost: parameter / signature / annotation parsing on top of the
   class-discovery work. Re-evaluate (B) with that scope in mind,
   not the catalog-only baseline.
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
   rewrite codebase before settling. (`MethodRef` and `ServiceCatalog`
   are the obvious reuse seam for the `@service` / `@condition`
   extension; their visibility is currently package-private and
   would need to be lifted.)

6. **Cut-over plan for the `methods` field.** Picking (a) "emit
   both shapes" vs. (b) "flip parser then producer" depends on the
   LSP's release rhythm and how widely the legacy LSP is deployed.
   Confirm with the LSP team before promoting Spec to Ready.

7. **Methods to include in the extended emission.** The legacy
   external-references walk lists every public method on a scanned
   class. For `@service` / `@condition` help, only methods that
   could plausibly be referenced from a directive matter; methods
   that obviously can't (return type incompatible, parameters
   nonsensical) are noise. Decide whether to filter at producer
   time or let the LSP filter on display. Default: emit
   everything; the producer doesn't know schema-side context.

8. **Return-type representation.** Rewrite's `MethodRef.returnType`
   is a structured `javapoet.TypeName` (preserves generics);
   the legacy goal would have emitted only the erased FQCN. Decide
   whether the JSON's `return_type` is a flat FQCN string (cheap,
   loses generics) or a structured object (richer, but the LSP has
   to learn a TypeName-like grammar). Recommend the flat string
   plus an optional `return_type_generics` array for the parameter
   types when present.

9. **Overload handling.** Java permits same-name methods with
   different signatures. The legacy goal already collapses these
   by name. The rewrite generator rejects directive references
   that resolve to multiple overloads. Decide whether the producer
   should: emit one method object per overload (LSP picks), emit
   one merged entry with a list of signatures, or keep the
   collapse-by-name behaviour and drop the duplicates with a
   warning. Recommend "one object per overload" so the LSP can
   match by arity / argument types.

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

- `@service` / `@condition` signature emission: a fixture class with
  one method per `ParamSource` variant (`Arg`, `Context`, `Sources`,
  `DslContext`, `Table`, `SourceTable`), one overloaded method, and
  one method on a class compiled without `-parameters`. Assert that
  the JSON's `params[].source` matches the rewrite-side
  classification, that overloads round-trip, and that
  unavailable parameter names are emitted as `null` (not as
  positional placeholders generated by the producer).

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
