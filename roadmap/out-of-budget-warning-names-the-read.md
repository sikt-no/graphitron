---
id: R792
title: "The out-of-budget warning names the read, and the statement drops to DEBUG"
status: Spec
bucket: dx
priority: 4
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# The out-of-budget warning names the read, and the statement drops to DEBUG

When a language-server read overruns its budget, `StoreAccess` warns once at the store boundary and
the warning's payload is the aborted SQL. In a dev console that is a wall of text: the statement a
whole-workspace diagnostics drain issues is one nested select per relation it reads, so the line runs
to thousands of characters and pushes everything around it out of view. Worse, it says nothing a
developer can act on or repeat. The reader cannot tell from it *which question* the server was asking
when it gave up, so there is nothing to name in a bug report, nothing to say out loud, and nothing to
grep for the next time the same surface goes quiet. The one fact a WARN line owes its reader, that
hovers stopped refreshing or that diagnostics stopped being republished, is the fact the current line
omits.

The statement is still the thing a fix needs, so it is not being thrown away: it moves to DEBUG on
the same logger, where somebody debugging this asks for it and nobody else pays for it.

## What changes

**A closed vocabulary of read names, stated at the door.** `StoreAccess`'s three doors take a name
for the read alongside their existing arguments, drawn from a new enum in the same package rather
than a free-text string. Each constant carries the phrase the warning uses. One constant per
store-reading surface, which is the set as it stands today:

[cols="1,3"]
|===
| Constant | Names in the warning

| `HOVER` | the hover read
| `DEFINITION` | the go-to-definition read
| `COMPLETION` | the completion read
| `INLAY_HINTS` | the inlay-hint read
| `CODE_ACTIONS` | the code-action quick-fix read
| `DIAGNOSTICS` | the workspace diagnostics drain
| `DIRECTIVE_VOCABULARY` | the directive-vocabulary read
|===

An enum rather than a string because the vocabulary is the deliverable. The point of the change is
that a developer and a maintainer can say the same words about the same read, so the words belong in
one declared place where a reader can see the whole set, a new surface must add to it rather than
invent a spelling, and a test can assert against a constant instead of a sentence. It also stops the
name drifting from the surface it names: the constant sits beside the door, so a surface that is
renamed or removed leaves an unused constant rather than a lying log line.

This is deliberately *not* folded into the door choice. Each constant happens to belong to exactly
one door today (five interactive, one drain, one session-state), and encoding that would collapse two
decisions into one: which reader answers is about latency contracts and is what the door names, while
the constant is about what the developer is told. A constant that also picked the reader would make
adding an interactive caller of the bulk door a change to the vocabulary.

**The warning names the read, and the statement moves to DEBUG.** `StoreAccess.warned` becomes, in
substance:

....
[WARNING] the workspace diagnostics drain ran out of its 30000 ms budget and was aborted, so this
surface keeps what it was already showing rather than answering from a partial read. The statement
that overran is logged at DEBUG on no.sikt.graphitron.lsp.state.StoreAccess.
....

followed, at DEBUG, by one line carrying the same read's name and the statement. The WARN keeps the
posture sentence, which is what tells a developer nothing they see is wrong, and gains the pointer to
where the statement went, so the escalation path from "I saw this" to "here is the statement" is in
the line itself rather than in the manual. The logger name is spelled out because that is what a
developer types into a logback config, and it is the boundary's own logger, so naming it costs
nothing that a rename would not already have to touch.

## What does not change

* No posture. Every surface still does exactly what it does today with an `OutOfBudget` arm; the
  warning is the only thing that moves. `StoreAnswer.OutOfBudget` keeps both components.
* Not the MCP server's out-of-budget error. `DiagnosticFacets.outOfBudget` already names its tool and
  returns the statement to the caller, and that is right for what it is: a tool result an agent reads
  as its whole answer, not a console line a human scans past. Nothing there is a console.
* Not the budgets, and not the cost of any read. That a session-wide read can spend 30 seconds is a
  performance question about the relations behind it and belongs to its own item.

## Verification

`StoreOutOfBudgetTest` already provokes real overruns through `RunawayRelation`, and `logback-classic`
is on the module's test classpath, so a case there can capture the boundary's output with a
`ListAppender` and assert what a developer sees: the WARN names the read and carries no statement, and
the statement appears once at DEBUG. That is the whole behavioural claim, and it is asserted against
the enum constant rather than against a sentence, so rewording the phrase does not break the test
while dropping the name does.

