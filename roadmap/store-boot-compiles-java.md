---
id: R759
title: "Every fact-store boot compiles Java source, because one CREATE ALIAS carries its body inline"
status: Backlog
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# Every fact-store boot compiles Java source, because one CREATE ALIAS carries its body inline

The fact schema declares one SQL function, and it declares it by writing Java source into a string
literal. H2 compiles that source with javac every time the schema is executed, so opening a fact store
runs the Java compiler. Nothing caches it: the cost is paid per store, not per process. Repointing the
declaration at the compiled method it already restates removes it, and the same edit removes a third
spelling of a rule that already has a declared single home. Measured, it is 42.7 seconds and 10% of a
410 second build, green.

## What the declaration is

At the tail of the diagnostics section, `graphitron-model.sql` carries:

```sql
CREATE ALIAS canonical_uri AS 'String canonicalUri(String sourceName) { ... }';
```

The function turns a stored `source_name` into its `file://` URI form. One view needs it, the
`diagnostic` view, whose arms over `intent_authored_claim_conflict`, `graphql_syntax_error` and
`graphql_schema_error` read capture's raw source names, which no loader normalises.

H2 offers two forms of `CREATE ALIAS`. The `AS '<source>'` form takes Java source and compiles it. The
`FOR "<class>.<method>"` form binds a compiled static method by name. The schema uses the first, and
its own comment says why: "Inline source, so the DDL stays self-contained."

## What it costs

All figures on one 4 vCPU, 15 GB sandbox, taken by executing the DDL statement by statement against a
private in-memory H2 and timing each.

The schema is 1894 statements and boots in about 0.21 to 0.38 seconds. The alias is **64.5ms of a
212ms boot**, which is 30% of the boot and seven and a half times the next most expensive statement
(a `CREATE TABLE` at 8.5ms). The cost does not amortise, each store getting its own compilation: four
consecutive alias-only boots in one JVM, after a cold first at 721ms that pays H2's class loading,
measured 85.9, 65.9, 62.9 and 51.1ms. The same alias bound to a compiled method measured 1.7 to
8.4ms, and returned identical values on a path and on null.

The boot count is what turns 60 milliseconds into a minute. `graphitron-model`'s tests alone open a
store 152 times, once per `withSeededStore` call, and the module's summed test class time is 41.3
seconds; with the alias repointed it is 30.7. Across the reactor every module that touches the store
pays it, and so does every consumer: one boot per `graphitron:generate`, one per language-server
session, one per MCP server start.

Measured end to end, `mvn install -Plocal-db` green on all 14 modules and 5970 tests:

| | Build | `graphitron` | `graphitron-model` | `graphitron-mcp` | `graphitron-lsp` | `docs` |
|---|---|---|---|---|---|---|
| trunk | 410.1s | 85.0s | 68.3s | 37.2s | 50.6s | 20.3s |
| alias repointed | **367.4s** | 70.7s | 55.7s | 29.0s | 42.7s | 14.6s |

It lands where the theory says: every module that boots stores drops, and the classes that move most
are the store-boot-heavy ones (`WarmStartRefreshTest` −6.6s, `PersistentStoreTest` −5.0s,
`FactCaptureAgreementTest` −4.4s, `FactSchemaGateTest` −3.7s, `GraphitronMcpServerTest` −3.6s).

## The design half, which is the better half

The inline body is not the rule's home. `no.sikt.graphitron.model.read.SourceUri.of` is, in this same
module, and `ValidationReport.canonicalUri` already delegates to it. So the value has three spellings:
the method, the delegating method, and the SQL string literal. The DDL comment says as much, naming
`ValidationReport.canonicalUri` "the declared single home" and calling the alias "its verbatim
restatement", pinned by a parity assertion in a test.

That is the shape this schema warns about everywhere else. A rule stated twice and held together by a
test is what the fact model's own comments call two readings of one population; the schema's whole
argument for deriving in views is that a rule lives in exactly one place. `CREATE ALIAS ... FOR`
collapses the three spellings to one and makes the parity assertion a tautology rather than a
guard, which is what you want: a guard over two spellings is a worse outcome than one spelling.

Against that, the sentence the change gives up is real and should be stated rather than waved past:
the DDL stops being self-contained. Executing the schema then requires `graphitron-model`'s own classes
on the classpath. Both existing boot paths already have them (the runtime bootstrap is in that module,
and jOOQ codegen drives the same entry point), so nothing in the tree is affected, but a future reader
executing the file against a bare H2 would meet a missing-function error. Whether that is a cost worth
paying, and whether the header should say so, is the Spec pass's call.

## What a Spec pass has to settle

* **Null handling, which is a correctness constraint and not a detail.** H2 passes a SQL NULL straight
  through to a `String` parameter of a compiled-method alias; it does not short-circuit. The inline
  body returns null for null. `SourceUri.of(null)` throws `NullPointerException`, `Path.of` rejecting
  null before the `InvalidPathException` catch can see it. So the change needs a null guard on
  `SourceUri.of`. That method's javadoc already claims "Neither direction throws", so the guard is a
  correction the documentation asserts and the code does not make, and it is worth fixing whether or
  not the alias moves.
* **What happens to the parity test.** `DiagnosticFactsTest`'s case pinning the alias against the Java
  site passes unchanged after the move, because the two are then the same code. A test that cannot
  fail is not a test; decide whether it is retired, or narrowed to what still has content (that the
  alias resolves at all, and that H2's null passing behaves as assumed).
* **Whether the other statement-kind costs are worth anything.** With the alias gone the boot is
  `CREATE TABLE` 182ms over 145 statements, `COMMENT ON` 72ms over 1688, `CREATE VIEW` 47ms over 59.
  Nothing there is one outlier; it is the schema's actual size. Worth recording as the floor so nobody
  hunts it twice.
* **Whether boot count is the next question.** This item makes a boot 30% cheaper. It does not ask why
  `graphitron-model` opens 152 of them, or whether a suite could share one store and clear rows between
  cases through the mechanism `StoreRefresh` already has. That is a separate item and a larger one,
  and it should not be folded in here: this change is one line and needs no fixture reasoning at all.

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

Two cautions. Time the alias in isolation as well as in the whole boot: a full-boot A/B is noisy
enough on a loaded machine (94 to 461ms across seven runs) to hide a 60ms effect, while an alias-only
boot measures it cleanly. And do not A/B by deleting the statement, which fails: the `diagnostic` view
references the function and H2 rejects the view definition.
