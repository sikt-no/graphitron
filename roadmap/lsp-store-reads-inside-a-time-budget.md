---
id: R773
title: "The LSP's store reads answer inside a time budget, or fail"
status: Spec
bucket: architecture
priority: 3
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# The LSP's store reads answer inside a time budget, or fail

The language server answers every question an editor asks it by querying the fact store, and no
query it issues has a time limit. There is no query timeout anywhere in the reactor: no jOOQ
`Settings.queryTimeout`, no `Statement.setQueryTimeout`, no H2 `SET QUERY_TIMEOUT`. The only time
bounds the store has are *lock* budgets, which answer a different question (how long a writer waits
for a row another writer holds): `GraphitronModelStore.FILE_LOCK_MILLIS` on the connection URL, and
`FactCapture.ANCHOR_LOCK_MILLIS` narrowing it around the anchor upsert. A read that runs long runs
until it finishes, however long that is.

Two properties of the read path turn one slow query into a server that has stopped responding.
`StoreReader.read` is `synchronized` over a single connection, deliberately ("Reads serialize... the
honest cost of the single connection"), so a query that is still running is head-of-line blocking
every hover, completion and diagnostic queued behind it, not only its own request. And no handler in
`GraphitronTextDocumentService` can abandon the wait: all five are a bare
`CompletableFuture.supplyAsync`, with no deadline, and `CancelChecker` appears nowhere in the module,
so an editor's `$/cancelRequest` does not reach the work it is trying to cancel.

This is not a hypothetical shape. The `intent_class_assignable` relation took seventeen seconds on a
census holding no duplicates at all and did not terminate at all when one class name appeared under
two classpath entries, and `intent_authored_field_claim` carried the same defect under the same
`UNION ALL`. Both were found by hand and fixed by deletion and by deduplication. What the store
lacked in both cases, and still lacks, is any mechanism that would have turned an unbounded query
into a bounded failure: the discipline we do have is statement *counting* (the `*StatementCountTest`
tier, explicitly "an enforcer, not a benchmark: no timing, no fixture scale, nothing that could fail
for being slow"), which pins how many round trips a feature costs and says nothing about what any one
of them may spend.

## What changes when this lands

A developer editing a schema under `mvn graphitron:dev` cannot get a language server that has
silently stopped answering. Today one pathological relation is enough: the query runs, the reader's
single connection stays occupied, and every hover and completion behind it waits on a statement that
may never return, with nothing in the editor to say so. After this item the worst that relation can do
is cost one feature one answer. The store aborts the statement when its budget runs out, the affected
surface keeps whatever it was already showing rather than replacing it with a claim about the schema,
the developer gets one warning naming the statement, and the next request is served normally.

The second thing that changes is that we find out. An unbounded query today is invisible until
somebody notices the editor has gone quiet and goes looking by hand, which is how both known cases
were found. A bounded one announces itself with the statement it died on, which turns a diagnosis that
took a spike into a log line.

## Measured, not assumed

Four claims about H2 2.4.240, verified directly against the version the reactor pins rather than taken
from documentation. The probe ran the shape that actually hurt us: a recursive term whose duplicate
output rows re-enter under `UNION ALL`, which `docs/architecture/explanation/fact-model.adoc` records
as "a hang with no diagnostic".

| Claim | Result |
|---|---|
| The runaway query is unbounded under the store's real URL shape (`LOCK_TIMEOUT=60000`) | still running at 3000 ms, killed by the probe rather than by H2 |
| `SET QUERY_TIMEOUT 500` aborts it | `JdbcSQLTimeoutException` at 505 ms |
| The setting is session-scoped, so one connection's budget is not another's | a second connection on the same database was bounded only by its own limit, never by the first's |
| The budget survives the reader's transaction cycle | two successive `setAutoCommit(false)` / `rollback` rounds each aborted at ~400 ms, so a budget set once at mint holds for every later read |

Two facts about the failure's shape are load-bearing below. It arrives as
`org.h2.jdbc.JdbcSQLTimeoutException`, a `java.sql.SQLTimeoutException`, which jOOQ wraps in a
`DataAccessException`. And it is distinguishable from a *lock* timeout only by vendor code, both being
`SQLTimeoutException`: the lock case reports 50200 / `HYT00` ("Timeout trying to lock table"), the
expired statement 57014 / `57014` ("Statement was canceled or the session timed out").

## Design

### The budget is a session command at reader mint

`StoreReader`'s constructor already executes `SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION
LEVEL SNAPSHOT`, for exactly this reason: a property the reader must hold for its whole life belongs
to the session rather than to each caller. `SET QUERY_TIMEOUT <ms>` joins it, extending the mechanism
already in the tree (`FactCapture`'s `SET LOCK_TIMEOUT` bracket) rather than standing a second timeout
mechanism beside it. jOOQ's `Settings.queryTimeout` is the alternative and is rejected on granularity:
it is JDBC's `setQueryTimeout`, whole seconds, which cannot express an editor's budget.

A per-request deadline in the handlers is the other alternative, and it does not solve the problem. It
would bound the *request* while leaving the SQL running on a serialized connection, so the head-of-line
blocking survives untouched. Only the session command actually stops the statement.

### One reader per latency contract, not one per consumer

The LSP has two read grains behind one `StoreAccess` today, and they want different budgets. A
cursor-keyed request answers a keystroke. The whole-workspace diagnostics drain is triggered by a
completed build, runs inline on the notification thread, and is the read that most wants headroom and
least wants to fail. Handing both the same budget would give the drain the interactive number.

So the LSP mints two readers, one per grain, and states a budget on each; the turn-based MCP server
mints one with a generous budget. `StoreReader`'s own javadoc already blesses this ("the remedy if it
ever bites is a second reader per thread, which `reader()` mints as readily as the first"), and it
buys a second thing for free: a build-triggered drain and a keystroke stop serializing behind each
other, which is half the head-of-line problem gone without a separate mechanism.

Note against a shape this item deliberately does *not* take: `FactCapture` narrows `LOCK_TIMEOUT`
around one row and restores it afterwards, on the stated grounds that "the two rows deserve different
answers". Bracketing each read the same way is implementable, and it is rejected because it costs two
extra statements per answer, which the `*StatementCountTest` tier's "one statement per graph per
recalculation" contract would have to absorb. Minting per grain gets the same discrimination for zero
statements.

### The budget value has two arms, not a magic number

`ReadBudget` is sealed: `Bounded(long millis)` and `Unbounded`. The reason is the test harnesses. The
production mints are two lines and the compiler can force them to state a number, but every fixture
that opens a store (`StoreFixture`, `CapturedStore`, `BuiltStore`, and the MCP siblings) also has to
say something, and the honest value there is "no budget". As a bare `long` that is either H2's magic
`0` or a number picked large enough to be safe, which is a wall-clock threshold smuggled into the test
tier under another name and the thing that fails on a loaded CI box. With an arm the harnesses name
`Unbounded` structurally and the tier's guarantee holds by construction.

### An expired budget is its own arm, never an absence

`StoreReader.read` returns a sealed `StoreAnswer<T>`: `Answered(T)` and `OutOfBudget`. It threads
through `StoreAccess.answering` and `answeringAll`, and each surface states its posture in an
exhaustive switch, so the compiler is the enforcer and a surface added later cannot forget.

The alternative, collapsing an expired budget into the absent answer the read path already has, is
what this item was first drafted as and it is wrong twice. `Workspace.answering`'s javadoc is the
argument: it folds three absences into one shape "because a handler's response to all three is the
same". A read that did not finish is a fourth outcome whose response is *not* the same, and the store
has consistently made that kind of distinction an arm rather than a silence (`SourceGraph.Uncaptured`
exists so that "no graph has read this file" is not indistinguishable from an empty result;
`ClasspathClasses.Presence.NO_CENSUS` is the same move one level down). Concretely:

* **Diagnostics must leave the previous publish standing.** Publishing `List.of()` for a file whose
  drain ran out of budget does not withhold an answer, it actively erases the warnings the last drain
  published, so a timeout would clear the developer's squiggles. An `Optional`-shaped degrade cannot
  express "publish nothing at all"; the arm can.
* **The vocabulary keeps its last good value.** `Workspace` loads `LspVocabulary` on `setStore` and on
  `markAllForRecalculation`, and the no-store path there is `LspVocabulary.empty()`, which by
  `Workspace`'s own comment "resolves no cursor to any coordinate". That read is session state, not
  one answer: degrading it to empty on a timeout would silence every surface for every file until the
  next build, quietly. It is also the read on the build-trigger path, so both an escaping exception
  and a silent empty are bad there.
* **Interactive surfaces do degrade to what they have** (no hover, no hints, the completion list
  unchanged), which is the same visible outcome as absence. The distinction absence does not carry is
  the warning: absence is silent and normal, an expired budget names the statement it died on. Nothing
  in the returned value carries the difference and nothing should, because a caller that could branch
  on it would be deciding policy the handler already decided.

### Its own predicate, in its own module

The expired-statement check does not reuse `FactCapture.timedOutOnALock`, for two reasons. The
dependency direction forbids it outright: `graphitron-model`, where `StoreReader` lives, declares no
dependency on `graphitron`. And it would make one predicate answer two questions with opposite
remedies, since that predicate keys on `SQLTimeoutException` in general (to catch a lock timeout while
deliberately letting a deadlock keep its retry) and an expired statement budget is the same type. The
new predicate lives beside the reader and keys on the statement-cancelled condition, vendor code
57014, not on the exception type.

`timedOutOnALock` is not wrong today, because no writer session carries a query budget and this item
does not give one. It becomes wrong the moment a writer does, silently misclassifying an expired
statement as lock contention and demoting the store to memory for it. So its javadoc gains that
boundary in this item rather than leaving a trap for whoever sets the next budget.

### Why a statement budget is enough to bound a request

`SET QUERY_TIMEOUT` bounds a statement, and a read transaction may issue several, so the budget alone
is not a request bound. It does not need to be. The `*StatementCountTest` tier already pins each
feature at O(1) statements per recalculation and asserts the count does not track the document's size,
so the product of the two enforcers is what bounds a request's store time and neither is sufficient
alone. Stating that in both directions also says what this item must not do: it must not add a
wall-clock assertion to the statement-count tier, whose refusal to fail for slowness is the property
that makes it trustworthy.

## Implementation

**`graphitron-model`, `no.sikt.graphitron.model.boot`**

* New `ReadBudget`, sealed, two arms: `Bounded(long millis)` (rejecting non-positive values, since
  H2 reads `0` as no limit and a caller that meant unbounded has an arm for it) and `Unbounded`.
  Carries the `SET QUERY_TIMEOUT` rendering so no caller writes the statement.
* New `StoreAnswer<T>`, sealed: `Answered(T value)` and `OutOfBudget(String sql)`, the statement text
  being what makes the warning diagnostic rather than merely present.
* New expired-statement predicate beside the reader, walking the cause chain for a `SQLException`
  whose vendor code is 57014. Named for what it detects, not for `SQLTimeoutException`.
* `StoreReader` takes a `ReadBudget` at construction and issues its `SET QUERY_TIMEOUT` beside the
  existing `ISOLATION` statement. `read` returns `StoreAnswer<T>`, catching `DataAccessException`,
  testing the predicate, and rethrowing anything that is not an expired statement unchanged: a
  genuine query error is still a defect and must not become an `OutOfBudget`. The rollback that ends
  every read runs on both paths, which the probe confirms leaves the session usable for the next one.
* `GraphitronModelStore.reader()` becomes `reader(ReadBudget)`, with no defaulted overload.

**`graphitron` (`FactCapture`)**

* `timedOutOnALock`'s javadoc gains the boundary: it keys on the exception type, which is sound only
  while no writer session carries a statement budget, and what to key on instead if one ever does.
  No behaviour change.

**`graphitron-maven-plugin` (`DevMojo`)**

* The `lspStore` mint becomes two readers, one per grain, with named budget constants rather than
  literals at the call site so the two cannot drift: an interactive budget in the low seconds (well
  above anything `LspTrace`'s 100 ms `SLOW` threshold would ever tag, because the target is unbounded
  pathology and not latency policing) and a larger drain budget.
* The `mcpStore` mint states a generous turn-scale budget.

**`graphitron-lsp`**

* `StoreAccess.answering` / `answeringAll` propagate `StoreAnswer` rather than unwrapping it, and
  `Workspace`'s two doors of the same name do the same.
* `Workspace` holds the last good `LspVocabulary` and keeps it on `OutOfBudget` at both load sites
  (`setStore` and `markAllForRecalculation`).
* Each surface states its posture in an exhaustive switch: `Hovers`, `Completions`, `Definitions`,
  `InlayHints`, `CodeActions` return what they have;
  `GraphitronTextDocumentService.publishDiagnosticsForRecalculate` skips the publish for an affected
  URI entirely rather than publishing an empty list.
* One warn-level log per expired budget, at the boundary that owns the decision rather than at each
  surface, naming the statement and the budget.

## Tests

The shape of the suite follows from the tiers, not from the clock. Nothing here asserts a duration.

* **The pathological shape, in `graphitron-model`.** A fixture relation carrying the duplicate-row
  recursion `fact-model.adoc` documents ("a recursive term that joins on one column and projects
  another turns two rows differing only in the projected column into identical output rows; `UNION
  ALL` keeps both"), read through a `Bounded` reader, asserting the `OutOfBudget` arm. The shape is
  non-terminating by construction, so the assertion is about an arm and never about how long anything
  took; `fact-model.adoc` also records that "forty stated rows reproduce the hang", so this needs no
  census-scale fixture. The fixture must build the shape from a relation of its own: the two
  relations that carried the defect are respectively deleted and fixed, and naming either would pin
  a shape that is gone.
* **The session command reaches the session.** An `ExecuteListener`-derived handle in the shape of
  `DiagnosticsStatementCountTest.counting` observes that a `Bounded` mint issues its `SET
  QUERY_TIMEOUT` and an `Unbounded` mint issues none. Asserts a statement, not a time.
* **A genuine query error is not an `OutOfBudget`.** A malformed statement through a `Bounded` reader
  still throws, so the arm cannot become a catch-all that swallows defects.
* **The reader survives an expired budget.** A read that runs out of budget, followed by an ordinary
  read on the same reader that answers correctly. This is the property the probe observed and the one
  a rollback bug would break.
* **Diagnostics leave the previous publish standing.** A drain that runs out of budget publishes
  *nothing* for the affected URI, asserted against the test client's recorded publishes: the failure
  this catches is a second publish carrying an empty list, which would clear the developer's
  squiggles. This is the case that justifies the arm over an `Optional`, so it is the case that must
  exist.
* **The vocabulary keeps its last good value.** A reload that runs out of budget leaves the previously
  loaded vocabulary resolving coordinates, rather than the empty one that resolves none.
* **Exhaustiveness is the compiler's.** The sealed `StoreAnswer` and the undefaulted mint mean a new
  surface or a new caller cannot silently inherit a posture, so no meta-test is needed for it.

**A disclosed gap.** Nothing above pins that a real query stays inside a real budget. That is a
benchmark, the project refuses benchmarks in this tier for good reason, and the absence is deliberate
rather than an oversight. What is pinned is that an unbounded query becomes a bounded, typed,
diagnosable failure.

## Developer documentation (first-client check)

`docs/manual/how-to/dev-loop.adoc` already carries the store-contention symptom as a developer meets
it, and this adds the sibling symptom. Draft:

> **A surface goes quiet after a build.** If hovers or inlay hints stop appearing for a schema that
> was fine a moment ago, and the build log carries a warning about a store read that ran out of its
> budget, a relation the language server reads has become too expensive to answer. The editor keeps
> whatever it was already showing rather than telling you the schema changed, so nothing you see is
> wrong; it is just no longer being refreshed for that surface. The warning names the statement, which
> is what to bring to a bug report. Diagnostics you can already see stay on screen: a drain that ran
> out of budget publishes nothing rather than clearing them.

No user-facing surface beyond that: no new goal, directive, output format or wire-protocol change, and
the budgets are constants rather than configuration. If a consumer ever needs to raise one, that is a
follow-up with a real request behind it and not a knob added on speculation.

## Retired vocabulary

* `GraphitronModelStore.reader()`, the no-argument mint. Every reader states a budget.

## Out of scope

* **`$/cancelRequest` handling.** No `CancelChecker` exists in the module, so an editor's cancellation
  reaches nothing. That bounds *wasted* work where this item bounds *unbounded* work, and the two are
  separable; it wants its own item.
* **A per-request deadline in the handlers.** Rejected above on mechanism: it cannot stop the SQL.
* **Budgets on the write path.** A build's capture legitimately takes seconds and reasons about
  `FILE_LOCK_MILLIS` already. Giving a writer a statement budget is what would make
  `timedOutOnALock` wrong, which is why this item documents that boundary instead of crossing it.
* **A reader per thread.** Minting per latency contract removes the drain-versus-keystroke coupling,
  which is the case we can name today. Whether concurrent interactive requests still contend enough
  to want a third reader is a question for a measurement, not for this item.

## Reviewer findings (Spec → Ready gate, 2026-08-21)

Independent reviewer session, status stays `Spec`. The first gate question passes without
reservation, and it was checked rather than trusted: the four H2 claims in `## Measured, not assumed`
were re-probed from scratch against `h2-2.4.240` and all four reproduce, exact down to the vendor
code (unbounded under the real `LOCK_TIMEOUT=60000` URL shape, still running at 3000 ms;
`SET QUERY_TIMEOUT 500` aborting with `org.h2.jdbc.JdbcSQLTimeoutException` at 501 ms, vendor 57014,
state 57014, an instance of `SQLTimeoutException`; a second connection on the same database still
running at 2000 ms while the first was bounded at 300 ms; two `setAutoCommit(false)` / `rollback`
rounds aborting at 401 and 409 ms with the session usable for an ordinary read afterwards). The
57014-against-50200 distinction the design leans on is real.

What blocks is the second question, and it is confined to `## Implementation`. The design itself
fits: the session command beside the existing `ISOLATION` statement, the sealed arm rather than an
absence, the two-arm budget instead of a millis `long`, and both rejected alternatives rejected on
mechanism. None of that would be redesigned by an implementer. But the plan makes two breaking
changes to a shared API, `StoreReader.read`'s return type and `reader()`'s signature, and the
implementation list does not follow either through its blast radius. Three places, each of which
leaves the implementer designing rather than implementing:

1. **`graphitron-mcp` has no heading at all, and its posture is undecided.** Four production sites
   call `reader.read(...)`: `CatalogCorpus:45`, `CatalogQueries:238`, `SchemaQueries:369`,
   `CodeQueries:164`, and `StoreReader` is threaded through about a dozen `GraphitronMcpServer`
   signatures. Returning `StoreAnswer<T>` forces a posture at each of the four, and the only word on
   MCP anywhere in the plan is that its mint gets a generous budget. The LSP postures do not
   generalise to it: "keep the last good value" and "leave the previous publish standing" have no
   meaning for a turn-based server with no prior state to keep. `## An expired budget is its own arm`
   argues at length that this posture is a decision and must not be defaulted, so leaving it
   unassigned in one of the two consuming modules is a hole by the plan's own standard. One sentence
   naming it closes this.

2. **`StoreAccess.readingSessionGraph` is never named, and it is the door the vocabulary uses.** The
   `graphitron-lsp` bullet lists `answering` / `answeringAll` and "`Workspace`'s two doors of the
   same name". The vocabulary loads at `Workspace:181` and `Workspace:286` both go through
   `readingSessionGraph`, the third door. The vocabulary is the plan's most heavily argued
   behavioural requirement, and the door that has to carry it is missing from the propagation list.

3. **`## One reader per latency contract` has no plumbing bullet, and the grains are not separable at
   that door as written.** Its only implementation line is in `DevMojo` ("the `lspStore` mint becomes
   two readers"), but `StoreAccess` holds a single `StoreReader` whose lifetime it owns, `Workspace`
   holds a single volatile `StoreAccess` with null checks in three methods, and
   `StoreAccess.answering` is *implemented as* `answeringAll(List.of(sourceName), ...)` at line 62.
   So the implementer has to choose between two `StoreAccess` instances in `Workspace` (touching
   those null checks and the concurrency comment at `Workspace:44`) and one instance holding two
   readers and routing by door (which means splitting that internal delegation). Those are different
   shapes with different consequences, and the plan picks neither.

Three smaller points, none blocking:

- **A checkable claim that is false as stated.** `## Why a statement budget is enough to bound a
  request` says the statement-count tier "already pins each feature at O(1) statements per
  recalculation". Four such tests exist (`InlayHintStatementCountTest`,
  `DeclarationDefinitionStatementCountTest`, `DeclarationHoverStatementCountTest`,
  `DiagnosticsStatementCountTest`). `Completions` and `CodeActions` both read the store through
  `workspace.answering` and are pinned by none of them, so the product-of-two-enforcers argument
  holds for four of six store-reading surfaces rather than all. One honest clause fixes it, or those
  two surfaces earn a named gap.
- `StoreReader`'s class javadoc carries three `{@link GraphitronModelStore#reader()}` references that
  the retirement breaks. The Javadoc reference gate catches this and the retirement sweep covers it,
  so it is a heads-up rather than a finding.
- The plan presents the drain as build-triggered. Per `Workspace`'s own javadoc the recalculation
  queue fills from two events, a build and a file being opened, so `didOpen` reaches
  `answeringAll` too and takes the drain budget. Probably the right outcome, but the grain
  description reads as though the drain is build-only.

The rest checks out against the tree. Every symbol the plan names exists as named, checked
FQN-aware: `GraphitronModelStore.FILE_LOCK_MILLIS`, `FactCapture.ANCHOR_LOCK_MILLIS` and its
`SET LOCK_TIMEOUT` bracket, `StoreReader`'s `ISOLATION` constant and its `synchronized read`, the
five bare `supplyAsync` handlers, `CancelChecker` genuinely absent from the whole reactor,
`publishDiagnosticsForRecalculate`, all five surface classes, `SourceGraph.Uncaptured`,
`ClasspathClasses.Presence.NO_CENSUS`, `LspVocabulary.empty()`, `DiagnosticsStatementCountTest.counting`
as an `ExecuteListener`-derived handle, and the four fixtures including the MCP sibling.
`FactCapture.timedOutOnALock` does key on `SQLTimeoutException` with 50200 / `HYT00` while letting a
deadlock keep its retry, so the misclassification argument holds; `graphitron-model`'s pom declares
no `graphitron` dependency, so the dependency-direction argument holds independently, and
`timedOutOnALock` is package-private besides. `intent_class_assignable` is gone from code, which is
why the fixture instruction not to name either relation is right. Every quotation from
`fact-model.adoc`, the statement-count tier, and `Workspace.answering`'s javadoc is verbatim.

The reviewer session that landed this block is disqualified from approving the resulting revision.
