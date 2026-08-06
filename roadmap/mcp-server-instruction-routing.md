---
id: R584
title: "MCP server instructions route agents to every tool family"
status: In Progress
bucket: feature
priority: 5
theme: tooling
depends-on: []
created: 2026-08-04
last-updated: 2026-08-06
---

# MCP server instructions route agents to every tool family

The shipped server instructions (`graphitron-mcp/src/main/resources/mcp/instructions.txt`) orient an
agent to exactly one tool family out of the twelve tools `GraphitronMcpServer` registers (eleven
unconditionally plus `execute` when a dev database is configured). A full paragraph routes to
`catalog.tables` / `catalog.describe`, naming the discovery keys and the comment caveat. The other
tools are never named: not `schema`, `diagnostics`, `edges`, `services`, `conditions`, `records`,
`docs.search`, `catalog.search`, `execute`, or `status`. Neither is the `directives` resource, which
is the directive-grammar cheat-sheet an agent authoring a schema wants first. The `about` prompt is
named, and its bundled text (`mcp/about.md`) names no tool either, so the one pointer the
instructions do offer for orientation does not route.

The observed consequence is that agents re-derive from text what the wire already carries. Two
episodes from one live session against a consumer schema mid-migration:

- Asked to read diagnostics, the agent called `diagnostics`, then shelled out to `python3` over the
  harness-spilled tool-result JSON to count the entries and list their coordinates. The pre-paging
  total was already in the tool's own summary text (`DiagnosticsTool.diagnosticsResult` composes
  `"diagnostics: N entr(ies) … showing M"`).
- Needing the DELETE mutations and the table each targets, the agent ran
  `grep -rn 'typeName: DELETE'` across the whole monorepo. `schema(type: "Mutation")` already returns
  per-field `dmlKind` and the resolved `tableName`, projected from
  `FieldClassification.DmlMutation` in `SchemaView`.

Why it matters: every tool the server ships is a token-cost saving that pays only when an agent finds
it, and an undiscovered tool is worse than an absent one because the fallback returns weaker data. The
second episode's grep reads author intent out of SDL text rather than the classifier's verdict, gets no
table binding, and sweeps a whole monorepo instead of the schema under generation. The first burns a
subprocess round trip on a number the tool had already handed over.

## What the ambient slot is for

The fix is not "name the other ten tools". It is to decide what the handshake `instructions`
string is *for*, because the file is not one surface among several: it is the only prose the server
ships that reaches the agent without the agent choosing to fetch it.

The `instructions` string is **ambient**. The client receives it once in the `initialize` handshake
and holds it for the session, so its cost is paid on every request and its content is the agent's
standing model of the server. A tool description is **local**: it is read when the agent is already
looking at that tool, and it can only ever speak for that one tool. That split assigns the work:

- **Ambient carries what no single tool description can say.** Which tool answers which *question*
  (a tool description cannot compare itself to a sibling), and the conventions that hold across
  every tool.
- **Local carries that tool's own arguments, result shape, and caveats.** One source of truth per
  fact.

Today's file inverts the split. Its catalog paragraph restates `catalog.describe`'s own description
almost verbatim (the jOOQ-comments caveat is in both, and "SQL names are the discovery keys" is
`catalog.tables`'s "SQL names drive discovery"), so the ambient budget is spent duplicating one
tool's local prose while ten tools and a resource get nothing. Cutting that duplication is what
pays for the routing table: the rewrite adds coverage of every tool without becoming a wall of prose,
because the per-tool detail it would otherwise carry already has a home.

The routing job is worth doing even where the local descriptions are excellent, and they are. Two
independent reasons:

- A description answers "what does this tool do", never "which of your twelve tools answers the
  question I have". The second episode above is a routing failure, not a description failure:
  `schema`'s description does name field classifications, and the agent still grepped, because
  nothing connected the question it had ("which mutations delete, and from what table") to the tool
  that answers it.
- Local descriptions are not reliably in context. Claude Code defers MCP tool schemas: a session sees
  the tool *names* and must make an explicit search call to load a description.

The second reason is evidence about *leverage*, deliberately not the criterion. It is a vendor
behaviour nothing in this reactor can enforce and it expires without notice when a client changes, so
a design resting on it would be a design with no enforcer. Worse, taken as the criterion it argues
for the wrong thing: if the problem were "descriptions may be missing", the fix would be to copy the
descriptions into the ambient slot, which is a derived fact maintained apart from its source and grows
without bound per tool. The criterion is the first reason alone, which holds either way: *the ambient
slot carries only what no single tool description can carry.* The deferral observation is why the slot
is worth spending tokens on at all.

That criterion needs one review tell, because this is the seam that will erode under the next append.
A routing line may name the question, the tool, and the fact the tool carries ("`schema` carries each
mutation's write kind and its resolved table"). It may not restate the tool's argument names, its
result fields, or its caveats. The source-index location cadence that `services` / `conditions` /
`records` describe is the worked example: genuinely useful, genuinely per-tool, and the first thing
this seam routes *out* of the ambient block.

## Design

Rewrite `instructions.txt` as three parts, in this order: the orientation already there (what
graphitron is, what the dev loop does), a question-keyed routing table over every advertised tool
plus the `directives` resource, and the cross-tool conventions. Drop the catalog paragraph's
duplicated per-tool detail.

The **routing table is keyed left-hand-side by the agent's question**, not by the tool name, because
an agent scanning for "what do I call" already has the names; what it lacks is the mapping from its
own question to one of them. Ordering follows the authoring loop (understand the vocabulary, find the
data, read the schema, read what is broken, follow the bindings, run it), not the registration order.

The **three cross-tool conventions** are each drawn from an observed miss or a real cross-tool
invariant, and each is a fact no single description can state:

1. *A paged tool's first line carries the total before paging.* Read it before paging or
   post-processing. This is the first episode's fix, and it holds across every paged tool
   (`catalog.tables`, `diagnostics`, `schema`, and the three code tools), which is why it belongs
   in the ambient slot rather than repeated six times.

   The claim is scoped to paged tools *on purpose*, and the scope is exactly the six tools that call
   `McpWire.page`. The other list-shaped tools are near misses rather than counterexamples:
   `edges`, `docs.search`, and `catalog.search` all do carry a count in their success summaries, so
   the miss is not a missing number. None of them takes a cursor, so "the total before paging" names
   nothing for them; and the degradation arms of `docs.search` and `catalog.search` return
   `WarmState.degradationMessage`, which carries neither a tool prefix nor a number. An ambient
   sentence promising "the first line always carries the total" would send an agent looking for a
   count in a sentence about ONNX. The honest grain is the paged tools, and the pin below asserts
   exactly that grain so the sentence stays true as tools are added.
2. *IDs are stable and shared across tools.* A schema-qualified SQL table name, a `Type.field`
   coordinate, and a `fqcn#method/arity` method ref select the same thing in every tool that takes
   one, so a result's id feeds the next call. `edges` already says this about its own endpoints
   ("the same stable IDs the catalog / schema / code tools accept"); the ambient slot is where the
   claim covers the whole surface, and it is what turns a `catalog.search` hit or an `edges`
   neighbour into a follow-up call rather than a fresh search by name. This is the one convention
   already pinned end to end rather than merely true:
   `GraphitronMcpServerTest.methodRefIdsMatchTheSourceIndexJoinKeys` and
   `catalogSearchReturnsRankedTableIdsWhoseTopFeedsCatalogDescribe` assert the handoff the sentence
   promises.
3. *Every tool reads the live workspace, and the schema-facing ones report the snapshot's
   availability and freshness* (`schema`, `diagnostics`, `edges`, `status`; the catalog and code tools
   read their own live projections and carry no snapshot axis). After a save, re-call. The point is to
   stop an agent reasoning about whether an earlier answer went stale when re-asking is one cheap
   call, and to make `Built/Previous` legible when it appears. The sentence must not generalise into
   "anything missing is staleness a re-call fixes": an absent source location on a code tool is that
   tool's own independent walk cadence, which its description already explains, and one ambient
   sentence fusing the build cadence with the source cadence would tell an agent to re-call for
   something re-calling does not change.

## Draft (first-client check)

The user-visible surface of this item *is* prose an agent reads, so the draft is the design. Written
out below; it moves into `graphitron-mcp/src/main/resources/mcp/instructions.txt` on
implementation. Wording is the implementer's to refine; the shape is what the reviewer is signing off.

> You are connected to a live `mvn graphitron:dev` session for a graphitron project.
>
> graphitron is a code generator: it turns a GraphQL schema plus jOOQ-generated database models into
> Java resolvers. The developer authors `.graphqls` schema files and graphitron regenerates the Java
> sources on every save. Alongside this MCP server, the dev session runs a language server (LSP) on a
> loopback port that backs schema authoring in the editor: diagnostics, hover, completion, and
> go-to-definition between the schema and the generated Java.
>
> Ask these tools before grepping the schema or reading generated sources. They answer off the live
> build, so they carry the classifier's verdict and the resolved database binding, which the schema
> text alone does not.
>
> - What does a directive mean, how do I express something: `docs.search`, and the `directives`
>   resource for the grammar itself.
> - Which tables exist, what is in one: `catalog.tables`, then `catalog.describe`.
> - Which table holds data I can only describe in words: `catalog.search`.
> - What is in my schema, what did graphitron make of a type or field, which table backs it, which
>   mutations write and to what: `schema`. A field reported `Unclassified`, or a snapshot reported
>   `Unavailable` or `Previous`, means graphitron could not read the intent you are asking about: go to
>   `diagnostics` for why, rather than back to the schema text.
> - What is broken right now: `diagnostics`.
> - Which consumer Java the schema binds to: `services`, `conditions`, `records`.
> - What else touches this, what breaks if I change it: `edges`.
> - Is the dev session live, and is its answer current: `status`.
>
> Three things hold across these tools:
>
> - A paged result's first line summarises the whole set, including the total before paging. Read it
>   before you page or post-process: the count you want is usually already there.
> - IDs are stable and shared. A schema-qualified SQL table name, a `Type.field` coordinate, and a
>   `fqcn#method/arity` method ref select the same thing in every tool that takes one, so follow the
>   ids out of one result into the next call instead of searching again by name.
> - Every tool reads the live build, and `schema`, `diagnostics`, `edges`, and `status` report whether
>   the build behind their answer is current. After a save, re-call rather than reasoning about
>   whether an earlier answer went stale.
>
> Run the `about` prompt for an explainer of the project and the dev loop. The graphitron
> documentation site is the reference for the directive vocabulary, the generation pipeline, and the
> schema conventions.

And the conditional tail, appended only when the `execute` tool is registered:

> This project configured a dev database, so `execute` runs a query or mutation against the generated
> resolvers in-process and hands back what it returns. Use it to check that a schema edit actually
> works, rather than reasoning about the generated Java.

### The ambient string is composed, not a single literal resource

That conditional tail is a design decision, not a formatting one, and it is the fork the coverage pin
turns on. `execute` is registered exactly when `executeConfig != null`, while `instructions` is loaded
once from a fixed jar resource ("it is shape, not state", per the class javadoc). A static routing line
for `execute` would advertise an absent tool to every project without a dev database; omitting the line
leaves the highest payoff-per-discovery tool as the one the ambient slot never routes to.

Settled: **compose the ambient string**, base block plus the conditional sentence, mirroring the
conditional registration exactly. `GraphitronMcpServer` already holds `executeConfig` at the point it
calls `loadResource`, so the change is a second bundled resource (`mcp/instructions-execute.txt`,
keeping agent-facing prose out of Java string literals as the existing two resources do) appended when
the tool is registered. The pin then asserts coverage of the advertised surface *per boot*, which is
strictly stronger than a union-across-boots check, and the exempt set can ship genuinely empty rather
than carrying `execute` as an untested-looking placeholder.

`GraphitronMcpServerTest.initializeReturnsBundledInstructions` compares the handshake string to the
resource verbatim. It boots without an execute config, so it keeps passing unchanged and now pins the
base arm; the composed arm wants its own case asserting the tail appears exactly when the tool does.

### What writing it surfaced

- The draft runs 411 words / 2582 characters for the base block, 452 / 2834 composed with the execute
  tail, against today's 195 / 1291. So covering twelve tools, a resource, and three conventions costs a
  little over double. That is the honest price, and it is still small against a session's context. But
  it is the ambient slot, charged per request, so the item ships a size ceiling with the coverage pin
  (below) rather than leaving the next append unbounded.
- `status` is the one tool whose routing line reads thin, because convention 3 already tells the
  agent what `status` is for. Kept anyway: the exempt half of the coverage pin should be empty on
  landing, so the first entry ever added to it has to argue for itself.
- The routing table's own answer to "which tool answers this" is sometimes "read the local
  description next" (`edges` directions, `execute`'s rollback guarantee, `catalog.search`'s warming
  degrade). That is the split working, not a gap.

## Implementation

- `graphitron-mcp/src/main/resources/mcp/instructions.txt`: the rewrite above.
- `graphitron-mcp/src/main/resources/mcp/instructions-execute.txt`: new, the conditional tail.
- `GraphitronMcpServer`: the only main-code change, appending the tail to the loaded instructions when
  `executeConfig != null`. Two or three lines beside the existing `loadResource` calls.
- `graphitron-mcp/src/test/java/no/sikt/graphitron/mcp/ServerInstructionsTest.java`: new, and it holds
  all three assertions this item adds (the advertised-surface coverage pin, the summary-line pin under
  convention 1, and the size ceiling). One class because they share one subject, the bundled prose and
  the claims it makes, and one fixture, a booted server. The name is deliberately not
  `...CoverageTest`: the coverage pin's *partition* shape follows `EdgeCoverageTest`, but two of the
  three assertions are not coverage, and a name that promised coverage would make the size ceiling and
  the summary-line pin look misfiled. `GraphitronMcpServerTest` is the wrong home for any of them: it
  is already 1200+ lines and its subject is the transport contract, not the bundled prose.
- `GraphitronMcpServerTest`: one case for the composed-arm tail. Its existing
  `initializeReturnsBundledInstructions` needs no change.
- The tool descriptions are deliberately untouched. The item cuts duplication out of the ambient file,
  not out of the local descriptions, because the local one is the copy whose reader is looking at that
  tool.

## Tests

One coverage pin over the advertised surface, plus a pin under the convention the ambient prose
asserts. Shape follows `EdgeCoverageTest` for the partition and
`MojoDocCoverageTest.everyMojoParameterHasADocRowAndViceVersa` for the bidirectional
producer-and-view assertion, which is the closer precedent: a generated descriptor as producer, a
hand-written `.adoc` as view, both directions checked, stale view entries named in the failure message,
and the view located by walking up from the test working directory.

**Derive the advertised surface, never restate it.** The pin boots a real server on an ephemeral
loopback port both ways (with and without an `ExecuteTool.Config`, exactly as
`GraphitronMcpServerTest`'s `executeToolIsAdvertisedExactlyWhenADevDatabaseIsConfigured` already does)
and reads `listTools`, `listResources`, and `listPrompts` off the SDK client. A hand-written expected
list would drift the same way the instructions drifted; deriving from the booted server means a newly
registered tool fails this test on the commit that registers it. Because the ambient string is composed
per boot, the assertion runs **per boot** rather than over a union: the no-database boot must not
mention `execute`, and the configured boot must.

*Derived* does not mean the one restated list in the tree should go.
`GraphitronMcpServerTest.statusToolIsAdvertisedAndReportsUnavailableByDefault` carries a literal
`containsExactlyInAnyOrder("status", "catalog.tables", …)` over the eleven unconditional tools, and
`mcp-aggregated-diagnostics` already leans on it ("fails until updated"). Keep it, because the two pins
catch disjoint failures and neither subsumes the other. The derived pin compares the booted server
against the prose, so a rename applied consistently to both passes it silently; the literal list is the
only thing that makes a rename or a removal stop the build and demand a human confirm it was intended.
Put the other way: the literal list anchors *what is registered*, the derived pin anchors *that the
prose agrees with whatever is registered*. Deriving is the right rule for the new pin precisely because
its subject is agreement between two surfaces, not the identity of either one.

The three namespaces stay distinct. A flat `Set<String>` would report "unrouted:
graphitron://directives" without saying which surface that is, and would make a per-surface exemption
reason impossible, so the pin carries a small `(kind, name)` record (tool / resource / prompt) through
one shared assertion helper.

**Assert the partition in both directions.**

- *Advertised is routed.* Every advertised name appears in the ambient string, or sits in an exempt
  set. This is the failure the item observed.
- *Routed is advertised.* Every tool-shaped backticked token in the ambient string is an advertised
  name. This is the failure the item has not observed yet and the one that actively misleads: a
  routing line surviving a rename or a removal points an agent at a tool that is not there. An
  ambient block accumulates exactly this as the tool surface churns.

Overlap and stale exemptions fail too, as in `EdgeCoverageTest.assertPartition`.

- Presence is checked in **backticked form** (`` `schema` ``, not `schema`). This is load-bearing: the
  bare word "schema" appears throughout the prose, so a substring check would pass vacuously on the
  tool the second observed episode is about. The instructions already quote every tool name in
  backticks, so the convention costs nothing and the test comment should say why it exists. The reverse
  direction needs a token rule rather than a name list (backticked tokens that look like tool names:
  lowercase, optionally dotted, no spaces), so the prose's other backticked terms (`@table`,
  `Type.field`, `mvn graphitron:dev`) do not read as tool claims. Getting that rule slightly wrong
  fails loudly and locally, which is the right failure mode.
- The exempt set lands **empty**, and after the composition decision above it is empty for a real
  reason rather than because nothing was examined. Keep the slot, with a comment requiring a written
  reason per entry: it is where a future deliberate omission has to become visible. The guard's value
  is the stale direction and the next tool, not the exempt half.
- Set placement is test-local, unlike `EdgeCoverageTest`'s (which reads `EdgeProducer`'s sets out of
  main code). No main code has any use for the exemption list, and putting it in main to mirror a
  sibling test would be shape-matching for its own sake.

**The same derived surface covers the manual's tool table.** `docs/manual/how-to/mcp-agent-context.adoc`
is the second hand-maintained view of the same advertised set, and it is unpinned today, so it drifts
for the same reason. The pin asserts it bidirectionally against the same derived names. This is
what structurally keeps the two views from becoming copies of each other: they are pinned to the same
base and differ in *grain*, which the Spec states so the reviewer has something to check. The manual
is **per tool** (tool-owned facts, read once by a human deciding whether to connect an agent). The
ambient block is **per question** (one line per question, naming several tools or none, injected on
every request). Different grains over one base is one model with two views; the same grain twice is a
copy.

The two directions get different spans over that file, because its shape does not support one rule
for both. Presence runs over the **whole document**: every advertised name across the three
namespaces already appears in backticks somewhere in it, `about` and `directives` included, so the
forward half needs nothing reworded. Staleness runs over the **tool table's first column only**,
where a backticked token is unambiguously a tool claim. Two things force that split rather than the
row-per-name rule `MojoDocCoverageTest` gets to use: the table is ten rows for twelve tools
(`services`, `conditions`, `records` share one cell, which the pin splits), and `about` and
`directives` are named in the prose above the table rather than as rows. A whole-document reverse
check is the wrong instrument here for a third reason: the manual's backticked vocabulary is much
wider than the ambient file's and includes tool-shaped tokens that are not tools (`dev`,
`graphitron`), so it would need an ignore list that grows with the prose. Rewording the manual to fit
a simpler rule stays out of scope.

**Pin the summary-line convention.** Convention 1 is a contract an agent will act on, so it needs
something that fails when a new tool breaks it: drive each of the six paged tools once against a built
workspace (the fixtures `GraphitronMcpServerTest` already uses) and assert the first line of the text
content names the tool and carries the unpaged total. The measured scope above is what the assertion
encodes, and it is why the pin covers the paged tools rather than every advertised tool: the
warm-degradation arms return a bare `WarmState` notice and would fail a universal form of this
assertion. That is a real inconsistency in the result surface, and prefixing those messages is a
reasonable follow-up, filed separately rather than smuggled in here.

Convention 2 is already pinned (the two `GraphitronMcpServerTest` cases named in the Design section).
Convention 3 is pinned by the existing snapshot-axis assertions on `status` / `schema` / `diagnostics` /
`edges`. So each of the three ambient claims is backed, which is the bar for stating them at all.

**A size ceiling on the ambient string.** The same test asserts the composed string (base plus the
execute tail, the worst case) stays under a declared budget. Arbitrary in its exact value, principled
in its existence, and the case is stronger than the one behind
`DocSizeBudgetTest.developmentPrinciplesStaysUnderBudget`: that budget guards a per-consult cost, this
one guards a per-request cost for every request of every session, and the next item to touch the file
is already planning an append. The ceiling forces an append to be terse or to displace something, which
is exactly the decision that should be conscious. Details, each with its reason, so a future raise is
an argued change rather than a bumped constant:

- **Unit: characters**, not the words the doc-budget precedent uses. The real cost is tokens; a routing
  table's words are much shorter than prose words, so a word count understates it relative to the
  file it would be compared against.
- **Value: 3600**, a round number roughly a quarter above the 2834-character composed draft, so the pin
  does not fire on a wording pass but a new paragraph has to be paid for.
- **The reason lives in the test, not in the file.** The doc-budget precedent puts the budget statement
  inside the document it governs, which works because that document's reader is the author. This
  file's only reader is the agent, so an author-facing note inside it would itself be ambient cost
  charged on every request. The test comment is the right home, and the Spec's own wording is what a
  future author encounters through the failure message.

## Sequencing with the aggregated-diagnostics item

R569 (`mcp-aggregated-diagnostics`, in Spec) plans to append a paragraph to "the diagnostics guidance"
in `instructions.txt`. No such guidance exists today, which is the overlap. Settled here, since this
item is the one reaching Spec with the file as its subject:

- **This item writes the diagnostics routing line** (`What is broken right now: diagnostics`), as one
  row of the table like every other tool. It does not write anything about aggregation.
- **R569 keeps its own append**, unchanged in intent: when it registers `diagnostics.aggregate` it adds
  that tool's routing line and the "call the aggregate first when the count is large" steer, plus its
  manual row, which it already plans. Its append then lands in a file that has a diagnostics line to
  attach to.
- **The coordination is mechanical once this item lands, not a note in two bodies.** R569 cannot
  register `diagnostics.aggregate` without adding the routing line and the manual row: the coverage pin
  fails until both exist. That is the same mechanism R569 already relies on for the tool-name list,
  which it notes "fails until updated". So neither item needs a `depends-on` edge, and this item does
  not touch R569's file.
- **Order is free.** This item's rewrite does not depend on the aggregate existing; R569's append does
  not depend on the rewrite (without it, the append lands in the old file and this item's rewrite later
  absorbs the paragraph into the table rather than dropping it). What the pair must not do is leave both
  items pointing at a section neither committed to creating, which is why the first bullet is a
  commitment rather than an offer.

## Out of scope

- **Tool description edits.** The local descriptions are good and the item does not touch them.
  Anything the routing table wants to say about one tool's arguments is a signal the description
  should say it, filed separately.
- **`about.md`.** It could carry a longer orientation, and it is the cheaper slot (on demand, not
  ambient). But an agent must choose to run it, so it cannot be where routing lives, and duplicating
  the routing table there would be a second copy to drift. Naming the prompt from the instructions
  is enough.
- **Rewriting the manual's tool table** (`docs/manual/how-to/mcp-agent-context.adoc`). Its prose is
  fine and its reader is a human deciding whether to connect an agent, so the item does not reword it.
  It comes *into* scope only for the coverage pin, which asserts it against the same derived
  advertised surface (see Tests): the drift risk is shared, the grain is not, and merging the two views
  would serve neither reader.
- **Prefixing the warm-degradation messages.** `WarmState.degradationMessage` returns a bare notice with
  no tool prefix, which is one of the two reasons convention 1 is scoped to paged tools. Making it
  uniform is a small main-code change with its own reasoning, filed separately rather than folded in
  to widen one assertion.
- **The snapshot axis key names.** Convention 3 tells an agent that four tools report whether the build
  behind their answer is current, and they do, but under two different key spellings:
  `snapshotAvailability` / `snapshotFreshness` from `McpWire.writeSnapshotAxes` for `diagnostics` and
  `edges`, bare `availability` / `freshness` from the hand-rolled switches in `status` and `schema`. The
  ambient sentence names the *values* rather than a key, so it is true as written and this item needs no
  change on account of it. Harmonising the keys is main-code work with a wire-visible effect, filed as
  `mcp-snapshot-axis-key-naming`.
- **The classification gap behind the second episode.** For fields that failed classification the
  snapshot carries `Unclassified` plus a prose reason and no `dmlKind` / `tableName`, so an agent
  repairing broken delete mutations genuinely cannot get the DELETE-intent population out of
  `schema`. That is a model gap (the classified model discards author intent exactly where
  classification failed), noted in the aggregated-diagnostics item's Spec review, and no amount of
  routing fixes it.
