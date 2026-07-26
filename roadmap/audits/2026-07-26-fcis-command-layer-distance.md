# Distance from the FCIS ideal: measured 2026-07-26

Companion audit to R333 (`coordinate-lowers-to-datafetcher-queryparts`). It answers one question
from the code alone, ignoring every javadoc and prose claim: **how far is the tree from the ideal
where facts are first class, the functional core emits commands, and an imperative shell renders all
code from those commands?**

Read this as a snapshot with a stated method, not a standing truth. Every number below is
re-derivable from the *Re-deriving the numbers* section; treat any figure as stale the moment the
package it counts is edited. The findings anchor on symbols (classes and members), never on line
numbers.

## Where the layers sit today

| layer | LOC | files | note |
|---|---|---|---|
| `rewrite/generators/` (shell candidate) | 29,837 | 73 | `TypeFetcherGenerator` alone is 7,102 |
| `rewrite/model/` (core data) | 12,690 | 124 | |
| `rewrite/` root (classification core) | 30,518 | ~80 | `FieldBuilder`, `TypeBuilder`, `FieldRegistry`, `BuildContext`, the resolvers |
| `rewrite/methodgraph/` (all command infrastructure) | 124 | 2 | `MethodCommand`, `MethodCommandRegistry` |

## The gaps

### 1. There is no command layer; the handoff is the whole model

`GraphQLRewriteGenerator.runPipeline` reads: `GraphitronSchemaBuilder.buildBundle`, validate, then
about thirty direct `XGenerator.generate(schema, …)` calls, then `write`. Twenty-five generator
entry points take `GraphitronSchema` itself. No artifact sits between core and shell, so each
generator re-decides from the god object what it should emit. The orchestrator carries decisions of
its own; the `OneOfDirectiveSdlGenerator` emission is gated on `federationLink &&
OneOfDirectiveSdl.usesOneOf(assembled)` at the call site.

### 2. Commands flow backwards

`MethodCommandRegistry.declareReentryRowsMethod(field, unitFqcn)` is called *by* the shell
(`TypeFetcherGenerator`, `TypeFetcherEmissionContext`) and *returns* the method name for the emitter
to use. Commands are therefore minted during rendering as an audit trail, which
`MethodClosureOracleTest` later joins against the emitted `TypeSpec` set via `EmittedMethodClosure`.
The oracle is genuine and end-to-end, but the direction is shell-asks-core rather than
core-tells-shell, and its coverage is the reentry rows-method family plus a DML variant.

### 3. The shell holds the decisions, densely

Branch density (`if`, ternary, `case`) is 1,641 across 29,837 LOC in `generators/` against 434
across 12,690 in `model/`: 0.055 per line versus 0.034, so the shell is about 1.6x denser in
decisions per line and about 3.8x larger in absolute branch count. Roughly 100 `instanceof` sites in
`generators/` name model leaves, plus about 60 `case` arms on leaf types. Emit dispatches on leaf
identity, which is the dispatch R333 replaces with facts.

### 4. Deciding and rendering are interleaved in the same files

`generators/` holds 1,054 javapoet builder calls (596 `CodeBlock.`, 301 `MethodSpec.`, 88
`FieldSpec.`, 69 `TypeSpec.`) in the same files as those 1,641 branches. Nothing structural stops a
decision from sitting between two `CodeBlock` additions, which is what makes the shell's behaviour
reachable only through the whole pipeline.

### 5. The model speaks the renderer's vocabulary, and in one place holds its output

- `CodeBlock` appears in exactly one model file, `RowsMethodBody`, whose permits each carry an
  opaque body. It is constructed by `SplitRowsMethodEmitter` and `TypeFetcherGenerator` and consumed
  by `RowsMethodSkeleton`: emitters on both ends, so it is a shell-to-shell handoff misfiled as a
  model type, with the boundary inverted (the shell owns declaration scaffolding while a model type
  carries pre-rendered body text).
- `TypeName` is in 20 model files and `ClassName` in 21, plus `ParameterizedTypeName` and
  `ArrayTypeName`. The model also *computes* them: `CallParam.deriveJavaType`,
  `RowsMethodShape.strictPerKeyType`, `RowsMethodShape.standardScalarJavaType`, and
  `RowsMethodShape.outerRowsReturnType` all return javapoet types.
- `BodyParam` is the counter-example proving this is avoidable: a sealed hierarchy of pure records
  with no emit vocabulary at all.

### 6. The core is not pure

About fifteen non-generator files reflect over the consumer's classpath, including `FieldBuilder`,
`TypeBuilder`, `BuildContext`, `ServiceCatalog`, `JooqCatalog`, `ClassAccessorResolver`,
`EnumMappingResolver`, `RecordBindingResolver`, `InputBeanResolver`, `SourceRowDirectiveResolver`,
`ScalarTypeResolver`, `CheckedExceptionMatcher`, `RewriteContext`, and
`walker/internal/HandlerAccessorCheck`. Classification interleaves IO with deciding, where the cut
wants the IO at the edge producing a described Java surface as data.

### 7. The recompile graph is a second derivation of the same relation

The dev loop needs to know which generated units a schema edit invalidates.
`CompileDependencyGraphBuilder.fromModel` answers it in 731 lines that coarsen the classified model
into an FQCN-keyed edge graph through an exhaustive switch over leaf arms, and
`TypeSpecReferenceWalk` walks the emitted specs as a completeness oracle because the model-derived
graph and the real references can disagree. The emit call graph is therefore derived twice: once by
the emitters that emit the calls, once by a hand-maintained switch that predicts them.

The duplication produces a recurring bug class, not occasional bugs. R455 fixed
`TypeSpecReferenceWalk` blind spots that silently falsified the oracle; R459 added a missing node for
fetcher-owning plain-object nesting types; R462 is open (bucket `bug`) for missing outgoing per-field
edges and names the cause exactly, that `addFieldEdges` never sees fields absent from
`schema.fields()` and that `schema.fieldsOf(nestedType)` is empty for a coordinate-less nesting type.
One shape underneath all three: the graph is derived from coordinates, the emit contains methods no
coordinate exposes, and such a method is invisible by construction.

This is the clearest cost-removing argument for the command layer, as opposed to the
capability-adding one in the testing thread. A command exists per emitted method, so the dependency
graph becomes a projection (nodes grouped by `unitFqcn`, edges projected to unit granularity) rather
than a prediction, the switch collapses toward a group-by, and the two walks over emitted specs
unify. `AbiSignature.hash` fingerprints the rendered unit today, so signature-carrying commands would
additionally make the recompile set computable before rendering. Caveats: javac's unit is still the
file, the frozen-scaffold blanket edges remain, and the dev loop is user-visible, so any re-basing
must hold `IncrementalCompileHarnessTest` green (superset oracle plus clauses (a) and (b)).

## What is already built toward the ideal

- **Fact relations are an established pattern, not a novelty.** `ArrivalIndex(Map<String, Arrival>)`,
  `ErrorIndex(Map<String, ErrorType>)` and `ArgBindingMap(Map<String, PathExpr>)` are keyed
  relations, and `GraphitronSchema` carries `arrivals` and `reachableSourceShapes` keyed by
  coordinate, outside the leaves.
- **Commands-in-waiting already live in the model**: `BodyParam`, `MappingEntry`, `DefaultedSlot`,
  `CallSiteExtraction`, `RowsMethodShape`, `InputRecordShape`, `MethodRef`, `HelperRef`, `LifterRef`.
  These are emit-shaped data records; most need only de-javapoeting.
- **Name authority is mostly regime 1 already**: 12 string-built name sites and 16 capitalize calls
  across the whole 29.8k-LOC shell, which matches R333's thread-J claim rather than contradicting it.
- **The FCIS invariant already has a working harness.** `MethodClosureOracleTest` generates over a
  schema exercising the load-bearing seam families, walks the emitted units, and asserts every
  qualified callee resolves to an emitted method. The ratchet for every later family already exists.

## Sequence to close the gap

Ordered so each step unblocks the next and none needs a big-bang edit.

| # | step | why here |
|---|---|---|
| 1 | A pure `JavaTypeRef` record replaces `TypeName` / `ClassName` across the model; the javapoet-producing helpers move to the shell or return the record | precondition for everything: until facts are comparable data with no emit dependency, commands cannot be asserted and files cannot move between layers |
| 2 | `RowsMethodBody` moves to `generators/` as the shell-internal handoff it already is | one file, no semantic change, and worth landing before real commands exist so it is not read as the template for one |
| 3 | Invert the flow for the family that already has commands: the core produces the reentry rows-method command, `TypeFetcherGenerator` consumes it instead of asking for a name, the oracle stays green across the flip | smallest possible proof of the direction, on ground the oracle already covers |
| 4 | Extend family by family, oracle as ratchet | R541's root query unit is the natural next family: already in Spec, already spending the registry |
| 5 | Push reflection to the edge: one IO pass produces a described-Java-surface fact set, the classifier consumes it purely | biggest single purity win, and independent of the command work, so it can run in parallel with 1 to 4 |
| 6 | The corpus's command half and R333's projection re-source | both want facts to be the thing read; they land naturally once 1 to 4 are done |

Steps 1 and 2 are filed together (the model owns no emit vocabulary); step 3 is filed on its own.
R543's fact half depends on none of this and can proceed immediately; its command half sits behind
steps 3 and 4.

## Re-deriving the numbers

Run from the repo root. Figures above were produced by these, on 2026-07-26.

```bash
cd graphitron/src/main/java/no/sikt/graphitron/rewrite

# layer sizes
for d in generators model catalog schema compile lint walker selection session methodgraph; do
  echo "$(cat $(find $d -name '*.java') | wc -l) LOC  $(find $d -name '*.java' | wc -l) files  $d"
done

# leaf-identity dispatch in the shell
grep -roh "instanceof \(ChildField\|QueryField\|MutationField\|InputField\|OutputField\|GraphitronType\|GraphitronField\)[.A-Za-z]*" generators/ | sort | uniq -c | sort -rn

# branch density
for p in generators model; do
  echo "$p: $(grep -rc 'if (\|? .* :\|case ' $p --include=*.java | awk -F: '{s+=$2} END {print s}') branches / $(cat $(find $p -name '*.java') | wc -l) LOC"
done

# rendering work in the shell
grep -roh "CodeBlock\.\|MethodSpec\.\|TypeSpec\.\|FieldSpec\.\|ParameterSpec\." generators/ | sort | uniq -c | sort -rn

# emit vocabulary in the model
grep -rh "^import no.sikt.graphitron.javapoet" model/ | sort | uniq -c | sort -rn

# IO in the core
grep -rln "Class.forName\|getDeclaredMethods\|getMethods()\|\.getClassLoader\|URLClassLoader" --include=*.java . | grep -v generators
```

Branch and dispatch counts are proxies: a `case` arm in a rendering switch is not the same kind of
decision as an `instanceof` selecting an emit strategy, and the grep cannot tell them apart. They are
useful for magnitude and for tracking direction across a migration, not as precise measures of
misplaced logic.
