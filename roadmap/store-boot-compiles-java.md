---
id: R759
title: "No stored column spells a file as a URI, so no store boot compiles Java"
status: Ready
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# No stored column spells a file as a URI, so no store boot compiles Java

A file has two spellings in this system. The store's is `source_name`, the path as the reader spelled
it, anchored by `store_source` and used as half of the site key in dozens of relations. The editor's is
a `file://` URI, because that is how the language server protocol names a document. Four base-table
columns hold the second spelling, so the same rule that converts between them is applied at three
different times: at the loader for those four columns, at read time in SQL for three arms of the
`diagnostic` view that kept raw source names, and in Java at the consumer wherever a query holds one
spelling and the column carries the other.

The read-time application is the expensive one. It is a `CREATE ALIAS` carrying Java source in a string
literal, which H2 compiles with javac every time the schema is executed, so opening a fact store runs
the Java compiler. Nothing caches it; the cost is per store, not per process.

This item removes the second spelling from storage. Every stored column holds a path, the view derives
from paths, and the two boundaries that need a URI produce one at the edge. The alias goes away as a
consequence rather than as the goal.

## What changes for a consumer

Nothing on any wire. The language server publishes the same URIs, and the MCP diagnostics tools return
the same `uri`, `file` and `directory` values they return today. What changes is the clock: the build
drops 42.7 seconds of 410, and every store boot drops about 30%, which every consumer pays once per
`graphitron:generate`, once per language-server session, and once per MCP server start.

## What the store holds today

| Column | Spelling | Written by |
|---|---|---|
| `rejection_validation_error.file` | `file://` URI | `RejectionFacts` |
| `lint_finding.file` | `file://` URI | `BuildWarningFacts` |
| `build_warning_no_rule.file` | `file://` URI | `BuildWarningFacts` |
| `javac_diagnostic.file` | `file://` URI, or the `(no source)` sentinel | `CompileDiagnostic` |
| `java_file.file`, `java_class_declaration.file`, `java_method_declaration.file`, `java_field_declaration.file` | absolute normalised path | the JVM scan |
| every `source_name` column | path as the reader spelled it | capture |

The JVM scan family is the precedent this item follows, and its own comment on `java_file.file` states
the convention outright: "Path form, as `store_source` spells a schema file". Those columns are named
`file`, carry paths, and are the partition dimension of their family. The four diagnostics columns are
the outliers, and the three view arms with no stored file column at all are where the alias got its job.

`ModelCodegenDriver` already tells jOOQ the schema declares no routines: "The schema declares no
routines and none are planned; the H2-functions spike left the scalar-alias surface as a documented
contingency, not a mechanism." That comment is false today by exactly one statement. This item makes it
true.

## What it costs

All figures on one 4 vCPU, 15 GB sandbox, taken by executing the DDL statement by statement against a
private in-memory H2 and timing each. Re-measure at pickup; the recipe is at the bottom.

The schema is 1894 statements and boots in about 0.21 to 0.38 seconds. The alias is **64.5ms of a
212ms boot**, which is 30% of the boot and seven and a half times the next most expensive statement (a
`CREATE TABLE` at 8.5ms). The cost does not amortise, each store getting its own compilation: four
consecutive alias-only boots in one JVM, after a cold first at 721ms that pays H2's class loading,
measured 85.9, 65.9, 62.9 and 51.1ms.

The boot count is what turns 60 milliseconds into a minute. `graphitron-model`'s tests alone open a
store 152 times, once per `withSeededStore` call.

Measured end to end with the alias bound to a compiled method as a stand-in for its removal,
`mvn install -Plocal-db` green on all 14 modules and 5970 tests:

| | Build | `graphitron` | `graphitron-model` | `graphitron-mcp` | `graphitron-lsp` | `docs` |
|---|---|---|---|---|---|---|
| trunk | 410.1s | 85.0s | 68.3s | 37.2s | 50.6s | 20.3s |
| alias gone | **367.4s** | 70.7s | 55.7s | 29.0s | 42.7s | 14.6s |

It lands where the theory says: every module that boots stores drops, and the classes that move most
are the store-boot-heavy ones (`WarmStartRefreshTest` −6.6s, `PersistentStoreTest` −5.0s,
`FactCaptureAgreementTest` −4.4s, `FactSchemaGateTest` −3.7s, `GraphitronMcpServerTest` −3.6s).

With the alias gone the boot floor is `CREATE TABLE` 182ms over 145 statements, `COMMENT ON` 72ms over
1688, `CREATE VIEW` 47ms over 59. Nothing there is one outlier; it is the schema's actual size.
Recorded here so nobody hunts it twice.

## Why the URI leaves storage rather than the alias getting a compiled body

`CREATE ALIAS ... FOR "<class>.<method>"` binds a compiled static method and would buy the same 42.7
seconds for a one-line diff. It is the wrong trade, and three of its costs are the reason:

* It keeps the fork. The rule still has three spellings (`SourceUri.of`, its delegate
  `ValidationReport.canonicalUri`, and the alias), and a rule stated twice and held together by a test
  is what this schema's own comments argue against everywhere else.
* It makes every store boot depend on `graphitron-model`'s classes being on the classpath, which is
  new coupling bought for nothing.
* It needs a null guard on `SourceUri.of` as a correctness constraint, because H2 passes a SQL NULL
  straight through to a `String` parameter and `Path.of(null)` throws `NullPointerException` before the
  `InvalidPathException` catch can see it.

Deriving the URI from a stored per-source column and joining for it is better than that and still worse
than this. It adds a column, and the three arms that would need the join reach `store_source` through
no foreign key, so the join's totality would be convention rather than constraint. It also cannot cover
the javac arm at all, whose `file` names a generated `.java` with no `store_source` row.

Removing the URI from storage dissolves all of it. No alias, so no javac at boot and no classpath
coupling. No function applied to a NULL `source_name`, so the null question stops being load-bearing.
No join, so no missing foreign key. The javac arm is covered by the same rule as every other arm.

## Implementation

**The three write sites stop converting.** `RejectionFacts` (at its `row.setFile` call),
`BuildWarningFacts` (at the `file.accept` call, which serves both `lint_finding` and
`build_warning_no_rule`), and `CompileDiagnostic` (at its record construction) write
`location.getSourceName()` as read. `CompileDiagnostic` keeps its `(no source)` sentinel.

**The column names do not change, only their spelling.** `file` stays `file` on all four tables and on
the view. `java_file.file` is the precedent: a path-form column named `file`. Renaming to `source_name`
would be wrong for the javac arm, whose file is a generated `.java` and not a captured source at all,
and would churn every jOOQ-generated reference for no gain. The four column comments and the view's
`file` and `directory` comments are rewritten to state path form and to stop claiming a canonical URI
normalised at the loader.

**The view drops the function and keeps its shape.** The three arms over
`intent_authored_claim_conflict`, `graphql_syntax_error` and `graphql_schema_error` project
`source_name` where they called `canonical_uri(...)`. The `directory` column stays
`REGEXP_REPLACE(<file>, '/[^/]*$', '')` in all seven arms, now over paths. The `CREATE ALIAS` statement
is deleted.

**The language server converts inbound only.** `DiagnosticFacts.Questions` collects the batch's
document URIs into `replayFiles`, which feeds `DIAGNOSTIC.FILE.in(uris)`; that one collection point
converts through `StoreAccess.sourceNameOf`, and `Answers.replayFor` takes a source name. Nothing
outbound changes, because `ReplayRow.file` has exactly one reader, `replayFor`'s in-memory equality
filter: the URI a published diagnostic carries is the document's own, never the row's. A URI that
resolves to no local file yields no source name and so matches no row, which is the same answer it gets
today.

`LintFixes` drops its `SourceUri.of` call and compares `LINT_FINDING.FILE` against the source name it
already holds.

**MCP converts outbound, through the seam that already exists.** `DiagnosticsTool` renders
`location.uri` through `SourceUri.of`, skipping the absent bucket it already skips.
`DiagnosticFacets.Spelling` is the existing declaration of how a wire spelling relates to a stored one;
it gains a URI value, and `FILE` and `DIRECTORY` declare it. Its `normalise` already carries the
inbound direction (wire to stored, here `SourceUri.sourceNameOf`), so a caller filtering by a URI still
filters exactly. The outbound direction is new: today every spelling stores what it publishes, so
`Dimension` puts the stored value on the wire raw, and it needs a render alongside `normalise` for the
group key, the `files` list and the ordering.

**The directory render has a trap, and the rule is convert-then-strip.** `Path.toUri()` appends a
trailing slash to a directory that exists on disk, so converting the stored parent path gives
`file:///a/b/` where the view publishes `file:///a/b` today. The rendered directory URI is therefore the
rendered file URI with its last segment removed, never a converted directory path. The same mechanism
is visible in the measurement probe, where `Path.of("")` came back as a trailing-slash URI of the
working directory.

**`SourceUri` gains the guard its javadoc already promises.** `of(null)` throws today while the class
comment says "Neither direction throws". Under this design no SQL NULL reaches it, so the guard is a
documentation correction rather than a correctness constraint, and it is worth making either way.
`McpWire.uri`, a fourth private restatement of the same three lines, delegates to `SourceUri.of`.

**`ValidationReport.sourceUris` retires rather than converts.** Its javadoc calls it a precomputed set
for the LSP short-circuit in `Diagnostics.compute`, but that consumer is gone: diagnostics went
store-based, no main source in `graphitron-lsp` references `ValidationReport` at all, and nothing in
production calls `sourceUris()`; its only readers are its own unit tests. A dead URI-keyed set is not
worth respelling. The component retires with its `addCanonical` builder, `ValidationReport.from` stops
computing it, `ValidationReportTest`'s `sourceUris` cases go with it, and `ValidationReport.canonicalUri`
retires with its last caller.

## Tests

**The invariant the parity assertion becomes.** A `graphitron` test-tier case asserting that no value in
`rejection_validation_error.file`, `lint_finding.file`, `build_warning_no_rule.file`,
`javac_diagnostic.file` or the view's `file` and `directory` begins with `file:`, over a store seeded by
a build that produces rows in every arm. This is the load-bearing new test: it states the property the
item delivers, and unlike the assertion it replaces it can fail.

**The parity case retires.** `DiagnosticFactsTest`'s case pinning the alias against the Java site (its
`@DisplayName` reads "the canonical_uri alias restates ValidationReport.canonicalUri, spelling
included") has no subject once neither exists. Delete it rather than narrowing it. Its sibling cases in
the same class that use `canonicalUri` to compute an expected stored value now expect the raw path.

**The wire is pinned by tests that must not move.** The evidence that no consumer sees this is that the
existing published-URI assertions pass untouched: `TextDocumentServiceTest`, `ValidatorDiagnosticsTest`,
`LintSuppressionDiagnosticsParityTest`, `RejectionSeverityCoverageTest` and `StoreAccessTest` on the LSP
side, and the MCP tools' `location.uri` assertions. Where those tests compute an expected URI they call
`SourceUri.of` directly instead of the retired delegate. A diff that changes what any of them expects is
a diff that moved the wire, and should be read as a defect in this item rather than a test update.

**The spelling pin becomes a round trip where a render exists.** `DiagnosticsAggregateTest`'s
`everyStoredDimensionValueIsAlreadyInItsDeclaredSpelling` asserts each declared spelling is the identity
on the values the store holds, which a URI spelling deliberately is not: it stores a path and renders a
URI. On a dimension declaring a render the pinned property is `normalise(render(stored))` giving back
the stored value; identity stays the assertion for the rest.

**The directory trap gets its own case.** An MCP-side assertion that the published `directory` for a
diagnostic whose parent directory exists on disk is byte-identical to what trunk publishes, which is the
one thing convert-then-strip and strip-then-convert disagree about.

**The measurement is re-taken.** The alias-only boot figure and the end-to-end build, per the recipe
below, recorded in the In Review commit message.

## Retired vocabulary

* `canonical_uri`, the SQL alias, and the DDL comment calling it "its verbatim restatement" of "the
  declared single home".
* `ValidationReport.canonicalUri`, the `sourceUris` component, and its javadoc's story of a
  short-circuit path in `Diagnostics.compute`, whose consumer predates store-based diagnostics.
* "canonical file URI" and "normalised once at the loader" as column-comment prose on the four `file`
  columns and the view.
* `McpWire.uri` as a private restatement, and its comment's appeal to "what the store's own
  `canonical_uri` does for the same reason".

## Done-gate finding, 2026-08-20: the retirement is three prose sites short

The implementation landed at `452c497` and is the change this spec approved. `mvn install -Plocal-db`
is green on all 14 modules and 6059 tests on the rebased head, every piece of named completeness
evidence is present and ran, and the load-bearing invariant is falsifiable: reverting `RejectionFacts`
to convert makes `DiagnosticFactsTest.noFileColumnSpellsAUri` fail on three of its three fixtures. The
store side, the writers, the two boundaries, `SourceUri`'s two directions and the `ValidationReport`
retirement all match this body. Nothing about the design or the tests needs another pass.

What is not discharged is the `Retired vocabulary` section above, which is contract like the rest of
the body. Three prose sites still describe the retired mechanism as live, all of them inside surfaces
the Done gate's retirement sweep names (javadoc, roadmap bodies):

* `SchemaSource`'s type javadoc lists "`ValidationReport`'s canonical URI" as one of four consumers
  whose byte-for-byte agreement `sourceName()` underwrites. That mechanism no longer exists. It is
  `{@code ValidationReport}` rather than a `{@link}` to the member, so the javadoc reference gate
  cannot catch it, and `sourceName()` is the very string the writers now store raw, which makes this
  the one site a reader of the new code is most likely to arrive at.
* R733's "Every store boot compiles Java" section still quotes `CREATE ALIAS canonical_uri` as a live
  64.5ms cost and closes with "See the store-boot item for the design half", a pointer to this file,
  which Done deletes. Its next picker is sent after a statement that is gone.
* R631's body describes `RejectionFacts` and `BuildWarningFacts` as each carrying a
  "location-normalisation block (canonical URI when the source name is non-empty, line and column when
  the line is positive)". Those are two of the three files this item changed; the block no longer
  canonicalises anything, so the one-site refactor R631 proposes is now smaller than it reads.

To satisfy the gate: repoint or drop the `SchemaSource` clause, mark R733's section as shipped here
(with the landing SHA and the re-measured figures, so the wall-clock item keeps the datum without
keeping the task), and correct R631's description of the block to what the two writers now do. Prose
only; no code, test or measurement change is being asked for.

## What this does not do

It does not ask why `graphitron-model` opens 152 stores, or whether a suite could share one and clear
rows between cases through the mechanism `StoreRefresh` already has. That is a separate item and a
larger one; this change makes each boot cheaper and touches no fixture reasoning.

## How to re-measure

```bash
# Statement-by-statement boot cost, no build needed: execute the DDL against H2 directly,
# splitting on top-level semicolons with single-quote state tracked, and time each statement.
# Discard the first two boots (JIT and H2 static init) and read the median of several.
java -cp ~/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar Probe.java \
  graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql

# End to end.
time mvn install -Plocal-db
```

Two cautions. Time the alias in isolation as well as in the whole boot: a full-boot A/B is noisy enough
on a loaded machine (94 to 461ms across seven runs) to hide a 60ms effect, while an alias-only boot
measures it cleanly. And do not A/B by deleting the statement on its own, which fails: the `diagnostic`
view references the function and H2 rejects the view definition. Delete it together with the three arms'
calls, which is what this item does anyway.
