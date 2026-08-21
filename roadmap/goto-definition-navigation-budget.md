---
id: R795
title: "Goto-definition answers inside a navigation budget"
status: Backlog
bucket: bug
priority: 2
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# Goto-definition answers inside a navigation budget

Jump-to-definition in the language server hangs. Putting the cursor on an SDL
declaration name and asking the editor to navigate stalls for over a second
before anything happens, and on a real workspace it stalls until the store read
is aborted and the jump silently does not happen. A developer trying the
feature in a few places in a row reads that as a broken feature, which is what
it is: a navigation request is the one request an editor blocks a cursor on, so
a slow answer and no answer are the same outcome to the person waiting.

Two separate things are wrong, and neither alone accounts for the report. One
read in the request is hundreds of times more expensive than every other read
beside it, for a reason that has nothing to do with the coordinate being asked
about. And the budget that read runs under is three seconds, which is the
figure for a hover or a completion rather than for a jump, so when the read
does overrun there is a three-second stall before the editor is told nothing
was found.

## The expensive read

Every jump whose cursor sits on a declaration name (a type name, a field name,
an input-value name) is answered by `DeclarationDefinitions`, which asks the
store one statement built by `DeclarationFacts`. That statement has eleven arms,
one per population the resolution might need. Timed against the sakila example
schema, ten of those arms answer in milliseconds and one takes over a second.

That one is the `redirects` arm, which asks which catalog table jOOQ binds a
candidate backing class to. It joins the catalog census relation `sql_table` to
the derived relation `intent_type_backing`, on the class name. H2 drives that
join from the census side, so the derived relation is evaluated once per catalog
table rather than once. The filter that makes the derived relation cheap (this graph, this type
name) is on the derived side, and driving from the other side means it is applied
after the expansion instead of before it.

Measured against a captured sakila-example schema, with an empty class census and
an empty parsed-source population, so these are floors rather than what a real
dev session pays:

[cols="3,1",options="header"]
|===
| Read | Cost

| One whole `DeclarationFacts` statement, any coordinate
| about 1.3 s

| The `redirects` arm alone
| about 1.1 s

| Every other arm, each alone
| 1 to 22 ms
|===

The arm returned no rows in every case timed. The second is spent establishing
that there is nothing to say.

## What the controls say

Recorded because three of them refuted a candidate fix, and the next reader
should not have to re-run them:

[cols="3,1,3",options="header"]
|===
| Control | Cost | What it says

| The derived relation alone, same two predicates, no join
| 22 ms
| The relation is cheap once filtered. Its own cost is not the problem.

| The census relation alone, same scope
| 8 ms
| Neither is the other side.

| The two joined the way the arm joins them
| about 1.2 s
| The join is the whole cost.

| The join replaced by the resolved class names as literals
| 0 ms
| Confirms the cost is the join shape and not the rows it returns.

| The derived side wrapped in a derived table carrying its own predicates
| about 1.1 s
| *Refuted.* H2 inlines it and the plan does not change.

| The derived side as an `IN` subquery, census still the driver
| about 1.2 s
| *Refuted.* Same per-driving-row evaluation.

| Driving from the filtered derived relation, census reached by `EXISTS`
| 20 ms
| The shape that works. Sixty times faster, and the only one that is.
|===

So the lever is the join's driving side, not a materialization and not a
rewrite of the derived relation. What one row of this answer means is one
(backing class, bound table) pair, and the relation that owns that key is the
backing relation, not the catalog census. The arm needs the census's columns and
not merely its existence, so the working shape has to project them while still
departing from the filtered backing side.

`DeclarationHovers` reads the same arms, so the same fix carries the
declaration-name hover with it.

## The budget

`DevMojo` gives every keystroke-grain read one budget of three seconds, shared by
hovers, completions, inlay hints and navigation. A jump is not the same grain as
a hover: a hover that arrives late is a popup that appears late, while a jump
that arrives late is a cursor that did not move and a developer who has already
decided the feature is broken. Three seconds is far past the point where a
navigation request should have given up.

The two halves belong in one item because neither ships usefully alone. A
stricter budget on today's read would turn a stall into an instant no-jump,
making the feature fail faster rather than work. The cost fix alone leaves the
three-second stall standing for the next read that gets slow.

## Open questions for Spec

- Where the navigation budget lands, and whether it is a third reader or a
  bound on the existing interactive one. A reader per grain costs nothing in
  statements and stops the grains queueing behind each other, which is the
  argument the existing two readers were split on.
- Whether anything asserts the cost rather than the statement count. The
  statement-count tier deliberately asserts no duration, and the reason a
  regression here was invisible is that a statement count of one says nothing
  about what that one statement expands to. The inline-multiplicity report is
  the closest existing surface and it ranks relations, not readers.
- Whether other single-statement readers join a derived relation from the census
  side the same way. The shape is not specific to this arm.
