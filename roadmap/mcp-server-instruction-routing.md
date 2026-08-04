---
id: R584
title: "MCP server instructions route agents to every tool family"
status: Backlog
bucket: feature
priority: 5
theme: tooling
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
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

The fix is not "name the other eleven tools". It is to decide what the handshake `instructions`
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
tool's local prose while eleven tools and a resource get nothing. Cutting that duplication is what
pays for the routing table: the rewrite adds coverage of every tool without becoming a wall of prose,
because the per-tool detail it would otherwise carry already has a home.

The routing job is worth doing even where the local descriptions are excellent, and they are. Two
independent reasons:

- A description answers "what does this tool do", never "which of your twelve tools answers the
  question I have". The second episode above is a routing failure, not a description failure:
  `schema`'s description does name field classifications, and the agent still grepped, because
  nothing connected the question it had ("which mutations delete, and from what table") to the tool
  that answers it.
- Local descriptions are not reliably in context. Claude Code defers MCP tool schemas: a session
  sees the tool *names* and must make an explicit search call to load a description. So the design
  cannot assume the agent has read all twelve; it can assume the agent has read the instructions.
  This is client-specific and should not be load-bearing on its own, which is why the first reason
  is stated first. It does mean the ambient slot should name each tool in a form that survives
  having only names available, which the question-keyed table does.

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

1. *Every result's first line is a summary carrying the pre-paging totals.* Read it before paging or
   post-processing. This is the first episode's fix, and it is true of every structured tool
   (`catalog.tables`, `diagnostics`, `schema`, the code tools, `edges`, `catalog.search`), which is
   exactly why the claim belongs in the ambient slot rather than repeated twelve times.
2. *IDs are stable and shared across tools.* A schema-qualified SQL table name, a `Type.field`
   coordinate, and a `fqcn#method/arity` method ref select the same thing in every tool that takes
   one, so a result's id feeds the next call. `edges` already says this about its own endpoints
   ("the same stable IDs the catalog / schema / code tools accept"); the ambient slot is where the
   claim covers the whole surface, and it is what turns a `catalog.search` hit or an `edges`
   neighbour into a follow-up call rather than a fresh search by name.
3. *Every tool reads the live workspace, and the schema-facing ones report the snapshot's
   availability and freshness* (`schema`, `diagnostics`, `edges`, `status`; the catalog and code tools
   read their own live projections and carry no snapshot axis). After a save, re-call. The point is to
   stop an agent reasoning about whether an earlier answer went stale when re-asking is one cheap
   call, and to make `Built/Previous` legible when it appears.

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
>   mutations write and to what: `schema`.
> - What is broken right now: `diagnostics`.
> - Which consumer Java the schema binds to: `services`, `conditions`, `records`.
> - What else touches this, what breaks if I change it: `edges`.
> - Does the operation actually work: `execute` (present only when the project configured a dev
>   database).
> - Is the dev session live, and is its answer current: `status`.
>
> Three things hold across every tool:
>
> - The first line of a result is a summary with the totals before paging. Read it before you page or
>   post-process: the count you want is usually already there.
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

### What writing it surfaced

- The draft runs 393 words / 2450 characters against today's 195 / 1291, so covering twelve tools, a
  resource, and three conventions costs a little under double. That is the honest price, and it is
  still small against a session's context. But it is the ambient slot, charged per request, so the
  item ships a size ceiling with the coverage pin (below) rather than leaving the next append
  unbounded.
- `status` is the one tool whose routing line reads thin, because convention 3 already tells the
  agent what `status` is for. Kept anyway: the exempt half of the coverage pin should be empty on
  landing, so the first entry ever added to it has to argue for itself.
- The routing table's own answer to "which tool answers this" is sometimes "read the local
  description next" (`edges` directions, `execute`'s rollback guarantee, `catalog.search`'s warming
  degrade). That is the split working, not a gap.

## Implementation

- `graphitron-mcp/src/main/resources/mcp/instructions.txt`: the rewrite above.
- `graphitron-mcp/src/test/java/no/sikt/graphitron/mcp/` gains the coverage pin (new test class;
  `GraphitronMcpServerTest` is already 1200+ lines and its subject is the transport contract, not the
  bundled prose). Naming follows `EdgeCoverageTest`.
- No main-code change. The tool descriptions stay as they are: the item cuts duplication out of the
  ambient file, not out of the local descriptions, because the local one is the copy with a
  single-tool reader.

## Tests

One coverage pin, in the shape the module already uses for partition invariants (`EdgeCoverageTest`).

**Derive the tool list, never restate it.** The pin boots a real server on an ephemeral loopback port
both ways (with and without an `ExecuteTool.Config`, exactly as `GraphitronMcpServerTest`'s
`executeToolIsAdvertisedExactlyWhenADevDatabaseIsConfigured` already does), and takes the union of
`listTools`, `listResources`, and `listPrompts` names off the SDK client. A hand-written expected list
would drift the same way the instructions drifted; deriving from the booted server means a newly
registered tool fails this test on the commit that registers it.

**Assert a partition.** Every advertised name is either present in `instructions.txt` or declared in
an exempt set. Overlap and stale entries fail too, as in `EdgeCoverageTest.assertPartition`.

- Presence is checked in **backticked form** (`` `schema` ``, not `schema`). This is load-bearing:
  the bare word "schema" appears throughout the prose, so a substring check would pass vacuously on
  the tool that the second observed episode is about. The instructions already quote every tool name
  in backticks, so the convention costs nothing and the test comment should say why it exists.
- The exempt set lands **empty**, with a comment stating that an entry needs a written reason for why
  an agent needs no cross-tool orientation for that tool. An empty half still earns the guard: the
  guard's subject is the *next* tool, and the choice between "route it" and "exempt it" is exactly
  the choice that has been made silently until now.
- Set placement is test-local, unlike `EdgeCoverageTest`'s (which reads `EdgeProducer`'s sets out of
  main code). No main code has any use for the exemption list, and putting it in main to mirror a
  sibling test would be shape-matching for its own sake.

**A size ceiling on the ambient file.** The same test asserts `instructions.txt` stays under a
declared character budget. Arbitrary in its exact value, principled in its existence: this file is
charged on every request of every session, it has no natural back-pressure, and the next item to
touch it is already planning an append. The ceiling forces an append to be terse or to displace
something, which is the decision we want made deliberately. 3200 characters is the
suggested value: a round number a third above the 2450-character draft, so the pin does not fire on
a wording pass. Put the reasoning in the test comment so a future raise is an argued change rather
than a bumped constant.

## Sequencing with the aggregated-diagnostics item

R569 (`mcp-aggregated-diagnostics`, in Spec) plans to append a paragraph to "the diagnostics guidance"
in `instructions.txt`. No such guidance exists today, which is the overlap. Settled here, since this
item is the one reaching Spec with the file as its subject:

- **This item writes the diagnostics routing line** (`What is broken right now: diagnostics`), as one
  row of the table like every other tool. It does not write anything about aggregation.
- **R569 keeps its own append**, unchanged in intent: when it registers `diagnostics.aggregate` it
  adds that tool's row and the "call the aggregate first when the count is large" steer. Its append
  then lands in a file that has a diagnostics row to attach to, and its new tool trips this item's
  coverage pin until the row exists, which is the guard doing its job.
- **No dependency either way.** Whichever lands first, the other still applies: this item's rewrite
  does not depend on the aggregate existing, and R569's append does not depend on the rewrite (it
  would just be appending to the old file). If R569 lands first, the rewrite absorbs its paragraph
  into the table rather than dropping it.

## Out of scope

- **Tool description edits.** The local descriptions are good and the item does not touch them.
  Anything the routing table wants to say about one tool's arguments is a signal the description
  should say it, filed separately.
- **`about.md`.** It could carry a longer orientation, and it is the cheaper slot (on demand, not
  ambient). But an agent must choose to run it, so it cannot be where routing lives, and duplicating
  the routing table there would be a second copy to drift. Naming the prompt from the instructions
  is enough.
- **The manual's tool table** (`docs/manual/how-to/mcp-agent-context.adoc`). Same content, different
  reader: it is written for the human deciding whether to connect an agent, one row per tool, and it
  covers setup, ports, and the `execute` configuration. The two do not need reconciling, and merging
  them would serve neither reader. What keeps them honest is that they answer different questions
  ("what does the server give me" versus "which tool do I call now"), and that only the instructions
  file is under the coverage pin.
- **The classification gap behind the second episode.** For fields that failed classification the
  snapshot carries `Unclassified` plus a prose reason and no `dmlKind` / `tableName`, so an agent
  repairing broken delete mutations genuinely cannot get the DELETE-intent population out of
  `schema`. That is a model gap (the classified model discards author intent exactly where
  classification failed), noted in the aggregated-diagnostics item's Spec review, and no amount of
  routing fixes it.
