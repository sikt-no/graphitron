---
id: R859
title: "A dev pass captures the same graph twice, so every save evaluates the register twice"
status: Spec
bucket: dx
priority: 2
theme: dev-loop
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# A dev pass captures the same graph twice, so every save evaluates the register twice

A dev pass runs the generator twice and each run captures. At startup `DevMojo.execute` calls
`runGeneratorPass` and then `buildOutputQuietly`; on every schema save `DevMojo.regenerate` calls
`runGeneratorPass` and then `buildOutput` inside one codegen scope. Both generator entry points reach
`GraphQLRewriteGenerator.captureAndRead`, so both write the graph's whole partition and both end with
`Materializations.refresh` for that graph inside their own transaction. Two captures of one graph,
milliseconds apart, from one `RewriteContext`, and the second writes the rows the first just wrote.

The Backlog draft asked why the second pass exists before proposing to remove it, and named three
candidate shapes. Reading the two entry points against each other answers it, and the answer
collapses the design: **the second pass computes nothing the first does not.** Every product
`buildOutput` returns is either already computed inside `runPipeline` and thrown away, or a
projection over material `runPipeline` already holds. This is not two products that need two passes.
It is one pass that discards half of what it computed and a second pass that recomputes it.

## Vocabulary

A **pass** is one round of the dev loop: what a startup or one debounced schema save runs. An
**entry point** is a public method on `GraphQLRewriteGenerator` that drives the pipeline from parse
to product; there are four today, and a pass calls two of them. A **capture** is one run of
`FactCapture`, which writes the graph's whole partition of the fact store in one transaction and
ends that transaction with a refresh of the materialization register. A **registration** is one row
of `meta_materialize`: a derivation whose rule stays in a view and whose rows live in a table of the
same shape that every reader names. The **register** is all twenty of them, and the **refresh** is
the pass that refills them. R855 and R857 define the last three in more detail and this item takes
them as given.

## What runs twice

Per pass today, with the two entry points read stage by stage:

| Stage | Per pass today | Needed |
| --- | --- | --- |
| Read and parse every schema file, apply the tag / note / federation rewrites (`loadAttributedRegistry`) | 2 | 1 |
| Assemble the document and record the stage verdicts (`assembleAndCaptureVerdicts`) | 2 | 1 |
| Classify (`GraphitronSchemaBuilder.buildBundle`) | 2 | 1 |
| Load the jOOQ generated classes (`new JooqCatalog`) | 2 to 4 | 1 |
| Scan and parse the whole classpath census (`CatalogBuilder.buildExternalReferences`) | 2 | 1 |
| Capture: write the graph's partition, refresh the register, run the detections | 2 | 1 |
| Validate (`GraphitronSchemaValidator`) | 2 | 1 |
| Run the lint engine over the registry (`withLintFindings`) | 2 | 1 |
| Project the completion catalog (`CatalogBuilder.build`) | 1 | 1 |
| Produce the plan, render, write, sweep orphans, project the compile graph | 1 | 1 |

The classpath row is R620's item seen from here, and merging the passes closes it for the dev loop
rather than fixing it: with one entry point per pass there is no second scan to suppress. That item
also asks whether the second scan sees classes the first could not, since `regenerate` recompiles
between them, and the tree answers no: the incremental compiler writes only into
`target/graphitron-classes`, and `AbstractRewriteMojo.resolveCompileClasspath` builds
`classpathRoots` from the compile classpath and the reactor modules' output directories, so the
exclusive directory is not a census root and never was. R620 should be re-read against this item
before either is picked up; its remaining content is the measurement note and the rejected
retained-partition route.

## What the second entry point produces that the first does not

Two things, and neither needs a pass of its own.

**The completion catalog** (`CompletionData`, from `CatalogBuilder.build`) has one arm that is
load-bearing and three that are not. The census arm is what capture writes the classpath families
from, so it is built on both paths already and is in the table above. The tables, scalars and
node-metadata arms have exactly two production readers left, both of them console counts in
`DevMojo`: the startup line reporting how many external references were indexed, and
`rebuildCatalog`'s "catalog refreshed (N tables, M scalars)". Every editor-facing surface that used
to read them resolves the same material out of the store through `StoreAccess`, which is why
`graphitron-lsp` carries no reference to the type outside its test fixtures. That does not make the
projection free to drop, the two lines being worth keeping, and it does mean nothing in the second
pass is waiting on it.

**The two diagnostics lists**, `walkErrors` and `warnings`, are the products with a live consumer:
`DevMojo.writeReportFacts` writes them to the store's diagnostics stratum, which is where the
language server reads the developer's squiggles. `runPipeline` computes both. It computes
`walkErrors` inside `validateAndLogErrors` and then throws them as an exception or drops them, and it
computes `warnings` in `withLintFindings` to log them and drops the list. So the pass that emits the
tree has already produced the diagnostics the pass after it exists to produce.

## What it costs, and why no new measurement gates this plan

The Backlog draft asked for a measurement before any design, on R620's discipline that the fix for a
pass costing milliseconds differs from the fix for one costing minutes. Two things answer it.

**The floor is one register refresh, and that is already measured.** Every capture ends with
`Materializations.refresh`, so the second capture pays one full evaluation of all twenty
registrations. Two records price it, at the two scales that matter.

At fixture scale, the register's own reason column carries per-registration refresh figures measured
against the sakila example schema: about 321 ms for `intent_mutation_write_payload_live`, about
170 ms for `intent_carrier_data_field_live`, 77 ms for `intent_field_scope_table_live`, 58 ms for
`intent_input_field_filter_role_live`, down to a millisecond or two for the cheapest. Those were
taken one registration at a time, against different trees as each was argued, so adding them gives an
order of magnitude and not a total: the refresh alone is around a second on that schema, before the
parse, the classification, the census and the partition write.

At consumer scale, R856's price list is the record, and it wants quoting precisely, its own reviewer
having just corrected R857 for reading it loosely. That table prices positions 1 to 14 of the refresh
order at 199 seconds against a settled store captured from a real consumer schema, marks positions 15
and 16 unmeasured, and fences itself as a price list rather than a statement about where the hour in
its title goes. Fourteen registrations were what that store held; twenty ship today. So the honest
form is a lower bound: one pass over that register costs at least 199 seconds on that schema, and the
tail is unmeasured rather than small.

Either scale answers the "milliseconds or minutes" question the same way, and neither needed a new
instrument.

**No fork in this plan turns on a finer number.** A measurement earns its keep when it chooses
between fixes, and the shape below does not tune anything: it removes a duplicate. Which of parse,
classification, census or partition write dominates the second pass would change what to optimise
next, and would change nothing here, because the whole second pass goes rather than a part of it.
The one number worth recording at pickup is a count rather than a clock, and R855 supplies it: with
that item landed the refresh announces each pass, so a dev start prints its refresh passes and the
before / after count is read off the console. Record it in `changelog.md` at Done so the next reader
has an observation rather than this argument.

The arithmetic across the three items, since each is credited with part of it:

| Landed | Register evaluations at start | Per save |
| --- | --- | --- |
| Today | 3 | 2 |
| R857 alone | 2 | 2 |
| This item alone | 2 | 1 |
| Both | 1 | 1 |

## What changes for a consumer

A developer running `graphitron:dev` pays one pass per save instead of two: one read of the schema,
one assembly, one classification, one classpath census, one capture, one register refresh, one
validator run, one lint run. Startup is the same halving, and with R857 landed a start over a warm
store evaluates the register once rather than three times.

A broken schema also stops reporting itself twice. Today a save that fails to parse prints the
attributed one-line parse error from the first entry point and then "catalog refresh after save
failed; keeping previous" from the second, which names no schema problem and reads as a second,
unrelated fault; at startup the second message is "initial catalog build failed; starting with empty
catalog". After the merge there is one failure and one message.

One correctness edge closes as a side effect. The two entry points each read the schema files from
disk, so a save landing between them leaves the emitted tree describing one schema and the captured
facts describing the next. It is transient, the following pass settling it, and while it lasts the
language server answers about a document the generated sources do not match. One read per round
removes the window.

Generated output does not change, and neither do the store's rows. The merge changes how many times
the same rows are written, never which rows: both captures pass the same registry, the same assembly,
the same verdicts, the same census and the same classified run, so the second is idempotent by
construction, which is exactly why nothing flagged it.

## The decision: one pipeline body, four projections of it

`GraphQLRewriteGenerator` today has two pipeline bodies that duplicate their whole front half in
source: `runPipeline` and `buildOutput`. That duplication is the defect's home, and it is also why
the two can drift: a stage added to one is silently missing from the other. Collapse them into one
private body and make each public entry point a projection of it.

The body runs the stages in the table above, in the order it runs them today, and hands back
everything it computed. Three things stay conditional, because a caller that does not want them
should not pay for them: whether the emission runs (the plan, the renderers, the writer, the orphan
sweep, the SDL resource), whether the compile graph is projected, and whether the catalog projection
is built. Emission and the compile graph are already conditional in the tree; the catalog projection
becomes so.

The verdict stops being control flow. `runPipeline` throws `ValidationFailedException` from inside
the capture window today, which is what makes a validation-rejected schema unable to produce the
catalog and the diagnostics in the same pass. In the merged body the validator's errors are a value:
the continuation returns either the plan it produced or the errors that stopped it, and the public
entry point decides what to do with them. The two build entry points throw exactly the exception they
throw today, so `GenerateMojo` and `ValidateMojo` are untouched; the dev entry point returns them.
Nothing about the store depends on where the throw happens, the capture transaction having committed
before the continuation runs at all.

The four projections after the change:

- `generate()`. Emission, no compile graph, no catalog projection. Throws on errors. `GenerateMojo`.
- `validate()`. No emission, no graph, no catalog. Throws on errors. `ValidateMojo`.
- `buildOutput()`. No emission, no graph, catalog and diagnostics. Never throws on a verdict.
  `DevMojo.rebuildCatalog` on a consumer `.class` change, `DevMojo.buildOutputQuietly` under
  `-Dgraphitron.dev.skipInitial`, and three test callers (`BuildOutputReportPipelineTest`,
  `LintSuppressionPipelineTest`, and the `BuiltStore` fixture helper).
- `runPass()`, new. Emission, compile graph, catalog and diagnostics, no verdict throw. The dev
  loop's pass, and the only projection that unions the emitting and the reporting halves.

`generateIncremental()` retires: `DevMojo.runGeneratorPass` is its only caller in the reactor, and
`runPass` is what that caller wants. `IncrementalGeneration` stays, being the product rather than the
entry point.

`runPass` returns one record pairing the two halves:

```java
public record Pass(BuildOutput output, Optional<IncrementalGeneration> generation) {}
```

`BuildOutput` is reused rather than restated, so `DevMojo`'s existing consumption of
`output.walkErrors()`, `output.warnings()` and `output.catalog()` is unchanged. The generation is
absent exactly when `output.report().errors()` is non-empty, which is the invariant the javadoc
states: a rejected schema emits nothing and reports everything.

Naming: `runPass` rather than `devPass` because the core is not named after a goal, on the precedent
that the entry point the language server drives is `buildOutput` and not `lspOutput`. "Pass" is
already this loop's word for the unit, in `DevMojo.runGeneratorPass`, in R855's pass-boundary events
and in this item's own title.

## Why not the other candidate shapes

**Capture recognising that its inputs have not moved.** The draft's third candidate, and it was
filed expecting R857's fill record to reach it. It does not. That record's soundness rests on a rule
R857 states outright: a writer never consults it, because a writer that has just rewritten a
partition's inputs knows they changed, and only a reader has a question. The second pass's capture is
a writer, so making it consult the fill row would break the argument that makes the reader-side skip
safe, and the row it would consult is the one its own capture is about to rewrite. That holds whatever
key the fill record ends up carrying, which matters because that key is what R857's review rounds are
still open on; the argument here is about who may ask the question, not about what the answer is keyed
on.

Deciding independently that the inputs have not moved means hashing the whole input set (registry,
assembly, verdicts, attribution, jOOQ catalog, census, classified run), which is new machinery whose
inputs are themselves the product of the front half of the pass. So it would save the partition write
and the refresh while still paying the parse, the classification and the census twice. Less saved,
more built.

**A condition at the second call site.** Skipping the second capture when the first one ran is the
one-line fix, and it fails for the reason R857 gives for the same shape: the mojo cannot answer the
question. Capture demotes to a private in-memory store on an unopenable cache and on a graph name
already held against another base directory, and neither demotion is reported back through the
generator, so a caller-side condition would skip a refresh that never happened. It also leaves both
front halves running, which is most of the cost.

**Reading the catalog off the store.** The direction the fact model is heading, and much larger than
this item: it waits on the consumer migrations that give the store its production readers. It is also
not needed here, the projection's only remaining readers being two console counts.

## The four round outcomes

| The round | Today | After |
| --- | --- | --- |
| Clean schema | Two captures. Sources emitted by the first, catalog and diagnostics by the second. | One capture. Both products from one pass. |
| Validation errors | Two captures. First throws after capturing, emitting nothing; second produces catalog and diagnostics. | One capture. No emission, no orphan sweep, catalog and diagnostics produced, errors returned as a value. |
| Parse or assembly refusal | Two captures, both writing the surviving declarations and the stage verdicts, both failing. Two unrelated-looking console messages. | One capture, same rows, one message. Previous catalog kept, workspace marked for recalculation, as today. |
| Renderer or writer crash after a clean validation | First entry point fails; second still refreshes catalog and diagnostics. | The round's diagnostics are not refreshed. See below. |

## What this gives up

**A round whose emission crashes no longer refreshes that round's diagnostics.** The store keeps the
previous round's rows rather than writing new ones, which is the posture every other failure path in
the dev loop already takes, and the developer has a stack trace naming a graphitron defect rather
than a schema problem. The alternative is to turn the emission's exceptions into values so the report
survives them, which is a larger change to the pipeline's contract for a path that only opens when
graphitron itself is broken. Stated here because it is a real property being traded rather than an
oversight.

**The dev console stops printing validation errors twice.** Today the pipeline logs every error in
`file:line:col` form on its way to the throw and `DevMojo` then prints `WatchErrorFormatter`'s
grouped tree, so a dev save with errors prints both. Once the logging moves out of the shared body
and into the entry points that want it, `runPass` can decline the line-by-line emission and leave the
tree, which is what `ValidationFailedException`'s javadoc already claims happens. Deliberate, and
listed here rather than under improvements because it changes output a developer sees. Keeping both
emissions is the alternative and costs one line at the new call site.

**With `-Dgraphitron.dev.compile=false` the pass projects a compile graph nobody reads.** A pure
in-memory projection over a plan already in hand, on a mode that has given up compilation. The
alternatives are a second dev entry point or a public boolean parameter, both of which cost more than
the projection does.

## Implementation

**`GraphQLRewriteGenerator`.** One private pipeline body, reached by all four public entry points.
`runPipeline` grows the two switches it does not have (emission, catalog projection) beside the
compile-graph switch it already has, and its capture continuation returns the plan or the validator's
errors instead of throwing. `buildOutput`'s body becomes a projection of the same call and its
duplicated front half goes. Hoist the jOOQ catalog and the census to the top of the body so both are
built once and handed to the capture, to the catalog projection and to the detections; both
`captureFactsAndDetect` overloads and the four-argument `captureAndRead` then have no callers, which
leaves the six-argument `captureAndRead` as the class's one capture seam. Move `logWarnings` and the
error logging out of the body to the entry
points that want them, which is what lets `buildOutput` stay silent and `runPass` choose the tree.
New public `runPass()` and `Pass`; `generateIncremental()` deleted. The class javadoc gains a
paragraph naming the four projections and the one body, since a fifth entry point that grows its own
front half is the regression this collapse exists to prevent.

**`CatalogBuilder`.** New `build(JooqCatalog, GraphQLSchema, RewriteContext, List<ExternalReference>)`
overload taking the census the caller already scanned. The existing three-argument form scans its
own and stays for the four tests that use it; production moves to the new one, which is what makes
"one census per pass" structural instead of incidental. Javadoc says which is which and why.

**`ValidationFailedException`.** The javadoc paragraph claiming the dev formatter replaces the
line-by-line emission becomes true rather than aspirational; correct it to say which entry points
log and which return.

**`DevMojo`.** `runGeneratorPass` calls `runPass`, assigns `lastGeneration` from
`pass.generation()`, and handles a rejected round as a branch rather than a catch arm: the
`WatchErrorFormatter` tree is built from `pass.output().report().errors()`, and `previousErrorKeys`
is set on the same three paths it is set on today (empty on success, the round's keys on a
validation rejection, null on a parse or infrastructure failure). Its `ValidationFailedException`
catch arm goes, nothing on this path throwing it any more; the `SchemaParseException` and
`RuntimeException` arms stay. `regenerate` loses its second generator call and its
"catalog refresh after save failed" arm, publishing the round's facts from the one pass; `execute`
keeps `buildOutputQuietly` for the `skipInitial` arm, which emits nothing by design, and takes
`runPass` for the generating one. `rebuildCatalog` is unchanged, a consumer `.class` change being a
catalog question and not a generation one.

## Tests

- **Agreement, in the pipeline tier.** Over one fixture context, `runPass(ctx).output()` equals
  `buildOutput()` on that same context, component by component: catalog, report errors, warnings.
  Both entry points survive the change, so this is an executable comparison rather than a comparison
  against a deleted method, and it is the item's claim ("the second pass bought nothing") turned into
  an assertion. It is the gate that fails if the merge drops a product or computes one differently on
  the two paths. Assert the emitted half against the other survivor beside it: the sources
  `runPass` writes are the sources `generate()` writes for the same context.
- **The rejected round, in the pipeline tier.** A schema with a validation error: `runPass` returns
  a report carrying that error, an absent generation, a catalog whose tables and scalars are
  populated, and the diagnostics lists the store's stratum is written from. Assert the output
  directory is untouched, which is what pins that a rejection skips the plan, the renderers and the
  orphan sweep rather than half-emitting.
- **The invariant.** Generation present if and only if `report().errors()` is empty, asserted on both
  fixtures above so a future arm that returns a generation beside errors fails here.
- **The unchanged build paths.** `generate()` and `validate()` still throw `ValidationFailedException`
  carrying the same error list on the rejecting fixture, and `generate()` still emits on the clean
  one. Existing coverage may already carry this; add only what it does not.
- **`DevMojoTest`.** A `regenerate` round publishes the round's rejection and warning facts and marks
  the workspace for recalculation, driven through the merged pass. The parse-failure discrimination
  that test already drives through `runGeneratorPass` keeps working, one message now rather than two.
- **`BuildOutputReportPipelineTest`, `LintSuppressionPipelineTest`, `BuiltStore`.** Unchanged callers
  of `buildOutput`, which is the regression surface for the projection that keeps its contract.

No new tier. The one property no test in the tree can state exactly is the capture count per pass,
there being no per-pass capture counter to assert on and no case for inventing a production seam to
carry one; the agreement test plus one call site in the merged body is what stands in for it, and
R855's pass lines are the console-level observation.

## Documentation

`docs/architecture/how-to/dev-loop-internals.adoc`, the "Generator dispatch" bullet: one sentence
saying a pass is one generator run producing both the emitted tree and the editor-facing products,
and that a second entry point per pass would capture the graph twice. The bullet already describes
the single-pass shape, so this states the invariant rather than correcting a claim, which is what a
reader adding a surface to the dev loop needs to not reintroduce the doubling.

No user-manual change. The dev goal's user-facing surface, its parameters and its console contract
apart from the removed duplicate messages, is unchanged.

## Retired vocabulary

- `generateIncremental`, the entry point `runPass` replaces. It is named in prose outside its own
  declaration, in `IncrementalCompiler`'s class javadoc, which is the reference a token grep at the
  Done gate has to reach.
- The private convenience overloads of `captureAndRead` and `captureFactsAndDetect` that build their
  own jOOQ catalog and census, both of which lose their callers when the pipeline hoists those two
  above the capture. Private, so the sweep is over comments and javadoc rather than call sites.
- "The second pass", as a description of how a dev round works, in any comment or doc sentence that
  survives the change. `DevMojo.regenerate`'s and `execute`'s comments are the ones to read.

## Sequencing against R855 and R857

Independent of both in the tree: this item's edits are in `GraphQLRewriteGenerator`,
`CatalogBuilder` and `DevMojo`'s pass methods, and neither sibling touches those. R855 rewrites
`Materializations` and `FactCapture.capture`; R857 rewrites `Materializations` and the comment at
`DevMojo.execute`'s `refreshAll` call, a line this item does not move. So there is no ordering
requirement and `depends-on` stays empty.

Two soft preferences. R855 first, because its pass lines are what make this item's before / after
count readable off a console rather than argued from source. And R857 either way, since only both
items together take a dev start to one register evaluation; whichever lands second should update the
other's arithmetic if it quotes it.

## Out of scope

- **Making any single pass cheaper.** This item removes a duplicate pass. The cost of the one that
  remains is R856 (the capture-cadence refresh), R848 (whether the register needs to be this large)
  and R620 (the census read), and nothing here changes any of them.
- **The reader-side `refreshAll` after startup**, which is R857's third evaluation.
- **Whether `rebuildCatalog` should regenerate.** A consumer `.class` change can change what
  generation produces, and today that path refreshes the catalog and recompiles the cached tree
  without regenerating. Possibly wrong, and not this item's question; the merge leaves that path
  exactly as it is.
- **Retiring the completion catalog's projection.** Its three non-census arms reach two console
  counts, which is a finding this item records and does not act on. Whether the counts should come
  from the store instead is its own item, and it wants writing only if someone wants the type gone.
- **The store-backed catalog**, which waits on the consumer migrations.

## Related

R857 is the third register evaluation at startup and the item this one was told to follow; its rule
that a writer never consults the fill record is what rules out the capture-side idempotence shape
above. R855 is the instrument that makes both items' claims visible on a console. R856 is the
capture-cadence cost that makes the doubling expensive rather than merely untidy. R620 is the same
doubling seen through the classpath census; this item's merge closes its motivating symptom, and it
should be re-read rather than picked up as filed. R848 asks whether the register needs to be this
large at all.
