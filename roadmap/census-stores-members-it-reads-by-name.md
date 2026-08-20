---
id: R762
title: "The census stores every class member to answer questions only ever asked by name"
status: Backlog
bucket: architecture
priority: 2
theme: dev-loop
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# The census stores every class member to answer questions only ever asked by name

The classpath census stores nine relations per class: its methods, their parameters, the type
references those carry, its record components and its supertypes. Two of the nine are read
enumeratively, to answer a question asked before the author has typed anything. The other seven are
only ever read for a class whose name is already written in the document, and they are **97% of the
census**. A name already written can be resolved from its classfile on demand in **0.1 ms**, which is
what makes the depth a choice rather than a requirement. This item stores class names and resolves
members on demand. It is a second, independent lever on the same population R685 narrows by width,
and the two compose.

## The split, measured

Row counts from the persisted workspace store a full reactor build leaves behind. The classification
is not a guess about intent; it is what every read site in `graphitron-lsp`, `graphitron-mcp` and
`graphitron` actually asks for.

| Relation | Rows | How it is read |
|---|---|---|
| `jvm_class` | 11,782 | **enumerative**: every class offered at once |
| `jvm_scalar_type_field` | 69 | **enumerative**: every scalar constant offered at once |
| `jvm_method` | 94,429 | anchored to a written class name |
| `jvm_method_parameter` | 119,832 | anchored to a written class and method |
| `jvm_method_parameter_type_ref` | 106,090 | anchored, through `intent_type_backing_seed` |
| `jvm_method_return_type_ref` | 75,219 | anchored, through `intent_type_backing_seed` |
| `jvm_record_component` | 426 | anchored to a written class name |
| `jvm_record_component_type_ref` | 561 | anchored, through `intent_type_backing_seed` |
| `jvm_class_supertype` | 8,817 | **not read at all**, see below |

So **11,851 rows of 417,225, 2.8%, carry the enumerative load**, and 405,374 rows exist to answer
questions that arrive with a class name attached.

The read sites, so the classification can be checked rather than taken:

* `ClassNameCompletions` selects `jvm_class` joined to `store_source`, grouped by name. No member
  relation, no prefix filter, no limit: it offers all 11,782 classes.
* `ClasspathClasses.presenceOf` reads `jvm_class` alone, twice, for its three-way answer.
* `ScalarTypeCompletions` reads `jvm_scalar_type_field` whole. 69 rows.
* `ClasspathMethods.read`, which backs both method completion and hover's method arm, always pins
  `JVM_METHOD.CLASS_NAME.eq(classFqn)`. `MethodCompletions`' own javadoc states the contract: the
  class name "is the user's previous edit; this provider only acts once that has resolved".
* `ArgMappingCompletions` pins class, method and non-null parameter name.
* `Hovers` pins `CLASS_NAME.eq(fqn)`. `DiagnosticFacts` pins `CLASS_NAME.in(questions.classNames)`,
  the names in the document, and its parameter arm pins a disjunction of written class/method pairs.
* Every generator-side read reaches the census through a join on a `graphitron_` or `graphql_`
  relation, so the build is anchored throughout. That was measured separately and recorded in R733.
* `ClassMemberSlots`, `DeclarationFacts` and the MCP's `SchemaQueries` arms all anchor on a class
  name or on `intent_type_backing`.

### The one exception, which is a requirement and not a counterexample

The MCP's `CodeQueries.classes` browses classes by kind, and its `declaring(kind)` predicate is an
`EXISTS` over `jvm_method` (any method; or a method with `returns_condition`) and over
`jvm_record_component`. That predicate spans every class, so it is enumerative. What it needs from
each class is one bit, not the class's members: has-any-method, has-condition-method,
has-record-component. Three booleans on `jvm_class`, computable by the scanner at the moment it
already has the classfile parsed. The method and parameter rows that call projects are bounded by
the page's `limit`, so they are on-demand by construction.

Any depth cut has to carry those three bits or that surface breaks. Naming them is most of the
design.

### `jvm_class_supertype` is written every capture and read by nothing

8,817 rows per capture with no reader in production, and not because a reader was lost. Its only
consumer is `intent_class_assignable`, which nothing reads and which does not terminate on a real
census (R760). The view's own comment says the dependency runs the other way, that the closure "is
also the whole reason `jvm_class_supertype` records what it records". So the chain is dead end to
end: a relation captured for a view with no reader that could not serve one anyway. R760 asks
whether the view should exist; this is the other half of that question and the two should be settled
together.

Note what this does *not* say. R685 uses supertype edges in its own evidence, counting reactor
classes whose supertype resolves in a third-party jar. That is analysis over the captured rows, not a
consumer, and it stays possible either way; it is an argument for capturing supertypes if and only if
something reads them.

## Why on demand is viable here, and the number that decides it

Resolving one class's members at query time means: open the entry, seek the classfile, inflate it,
parse it. Measured over jars spanning 17 KB to 84 MB, re-opening the `ZipFile` on every call with no
caching at all:

| Jar | Size | Classes | One class resolved |
|---|---|---|---|
| `duckdb_jdbc-1.5.2.1` | 83.8 MB | 83 | 0.128 ms |
| `jetty-io-12.0.36` | 340 KB | 209 | 0.084 ms |
| `plexus-utils-4.0.2` | 193 KB | 89 | 0.073 ms |
| `doxia-site-renderer-2.0.0` | 44 KB | 12 | 0.293 ms |
| `doxia-module-xhtml5-1.11.1` | 18 KB | 6 | 0.032 ms |

**0.03 to 0.29 ms**, and flat in jar size because a zip central directory is a seek and not a scan.
A completion, hover or diagnostic needs one class, occasionally the handful a document names. Against
an editor round-trip budget of tens of milliseconds that is free, and the enumerative surface that
genuinely cannot wait for a name, class-name completion, is precisely the one this item keeps
resident.

What makes it work at all is that `jvm_class` stays: it maps a class name to the classpath entry that
declares it, so an on-demand read knows which entry to open without searching. The resident 2.8% is
the index that makes the other 97% resolvable.

## What it would save

The write side is where the census costs, and R733's per-statement instrument measured it: the
`jvm_` family's deletes and merges are about **8.1 seconds of 13.3** across one module's five
`graphitron:generate` executions, 62%, and the top nine statements by total time in both runs.

The write cost tracks row count, which was checked rather than assumed. Replaying the real census
into a fresh copy of the same tables, class-level rows against full depth, warm:

| Arm | Rows | Time |
|---|---|---|
| class-level only | 11,851 | 0.02 s |
| full depth | 417,225 | 0.70 s |

A 35-fold row ratio and a 35-fold time ratio. Be careful what that licenses: it is a bare batched
`INSERT` into unindexed in-memory tables, not the capture's `DELETE` plus `MERGE` against a
file-backed store carrying 260 indexes, which is why the real figure is 8.1 s and not 0.7 s. It
establishes proportionality, not magnitude. On that proportionality a depth cut points at most of the
8.1 seconds, and the honest form of the claim is that the magnitude has to be measured after the
change, not projected from here.

Disk is the other surface, and it is the one a consumer notices: the store is **858 MB** for those
417,225 rows, and the user cache held 6.5 GB across nine DDL-hash segments.

## Where this contradicts R685, which matters because R685 is in Spec

R685's "Rejected: extract less per dependency class" section rules out reading less per class. Its
reasoning is right about the alternative it considered and does not reach this one, and a Spec pass on
either item should reconcile them explicitly rather than letting both stand.

The rejected alternative was to keep almost nothing per class, "only what has a required signature,
which in practice means the `public static GraphQLScalarType` fields `@scalarType` binds", leaving
about 42,000 rows. That drops class *names* for everything without a scalar constant, so it does fail
on completion, and the rejection is correct: "a census exists to answer *before* the author has
written anything". This item keeps every class name and drops only what sits beneath one, which the
rejection's own argument permits, since below a class name nothing is ever asked before the name
exists.

The rejection then generalises one step too far, in a sentence that is true of the scan and not of
the store: "reading fewer members per class buys about 30%, because decompression rather than parsing
is the cost." That governs the 648 ms scan, where the bytes must be inflated whatever is kept. It
does not govern the write, which is ~1.6 s per execution against that 648 ms and scales with rows
rather than with bytes inflated. Fewer members buys 30% of the scan and something much larger than
30% of the write.

One asymmetry to settle rather than gloss: on demand shifts work from capture to query. Under R685's
width cut the transitive jars are not opened at all, whereas here they are opened rarely and lazily.
The two cuts compose, and they are not alternatives, but their interaction on the scan is worth one
sentence in whichever specs second.

## What a Spec pass has to settle

* **Where the on-demand read lives, and whether the store stays the only fact surface.** The LSP
  currently answers every member question with SQL. On demand means some answers come from a
  classfile instead, and a second retrieval path through the same façade is a real cost against the
  fact model's argument that one store serves every reader. A `LOCAL TEMPORARY` per-session cache
  populated on first ask is the shape that keeps reads SQL-shaped; whether that is worth it, or
  whether `ClasspathMethods` simply stops being a query, is the design fork.
* **What happens when the classfile is gone.** A resident row survives a jar being rebuilt or
  deleted; an on-demand read does not. Today a stale census answers from the last scan, which is
  sometimes wrong and never absent. Decide what a missing entry means at query time, and note it is
  a *new* silence rather than a widened one.
* **The three bits, and whether they generalise.** Has-any-method, has-condition-method,
  has-record-component are what MCP browsing needs. Whether they belong as columns on `jvm_class` or
  as a summary relation, and whether any other surface wants a fourth, is the part to get right
  before anything is deleted.
* **Whether `jvm_class_supertype` is captured at all**, jointly with R760.
* **Whether the 11,782-item completion list is itself the bug.** `ClassNameCompletions` offers every
  class with no prefix filter and no limit. If it grew a prefix filter, the enumerative case would
  weaken and even `jvm_class` could in principle be resolved on demand, at which point this item's
  premise changes shape. Worth an explicit verdict, because the enumerative-completion argument is
  what both this item and R685 rest on. My reading is that it should stay enumerative (a client
  re-sorts and re-filters the list locally, and a server-side prefix filter makes completion depend
  on keystroke timing) but the question deserves stating.

## How to re-measure

```bash
# The split: for each jvm_ relation, find its read sites and check whether every one
# pins a class name. Writers to exclude are FactSink, StoreRefresh, CatalogFactCapture.
grep -rl "JVM_METHOD\b" --include=*.java graphitron*/src/main/java

# On-demand latency: open a jar, seek one .class entry, inflate, parse with ClassFile.of().
# Re-open the ZipFile per iteration so the number carries no cache credit.

# Write proportionality: read the real census out of a persisted store, replay it into a
# fresh in-memory copy of the same tables, class-level arm against full-depth arm.
# Alternate the arms; the first arm pays JIT and reads high.
```

Row counts come from any persisted store under `~/.cache/graphitron/model/`, which is the population
that matters; the graph it was captured for does not affect the census.
