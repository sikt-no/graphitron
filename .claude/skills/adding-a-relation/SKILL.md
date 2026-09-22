---
name: adding-a-relation
description: Decide whether a new fact-store rule is a view, a table, or a fact somebody upstream should have captured, and what the relation owes once it is stored. Use before adding any relation, view or GENERATED ALWAYS AS column to graphitron-model.sql, and whenever you are about to argue that no view can state a rule. Not for diagnosing a slow relation that already exists, which is store-performance.
---

# Adding a relation

The decision this skill exists for gets made silently, which is why it gets made wrong. Storing has
three possible reasons and no default. The prose version, with worked examples and the honest gate
coverage, is `docs/architecture/explanation/writing-a-rule.adoc`; this is the procedure.

## 0. The prior question, before anything else

**Does a stage upstream already hold this fact and throw it away?**

If yes, stop. The rule does not need a home, it needs an owner. A captured fact removes the
evaluation; every other lever relocates it. This is the first rung of the store's lever order and
it is ahead of indexing, rewriting and registering for that reason.

The tell is an unbounded descent through a structure. If a rule walks a tree, ask who had that tree
in hand while reading it. A rule stated at the reading can be total where a query form has to stop
at a fixed depth.

**Do not skip this step because the rule looks small.** A rule that reconstructs what capture could
have written is a modelling defect whether or not anything is currently slow.

## 1. Two questions, not one kind

Answer both. They are independent, and collapsing them is the most common error.

**Where does the rule's text live?** A view, unless a view cannot state it. A view states the rule
once, in the catalog, where a parse can read what it reads and a gate can hold it to its owner.
A rule spelled in Java or hand-written jOOQ is invisible to every catalog parse in the tree.

**Who evaluates it, and when?** A reader on read, unless one of the three below applies.

Storing the result is not a reason to stop stating the rule in SQL. When you store, the writer is:

```
INSERT INTO <target> SELECT <the rule view's columns> FROM <the rule view>
```

not a second statement of the rule. The payoff is that an `EXCEPT` between the target and the rule
view stays runnable for as long as both exist, so the table can be checked rather than believed.

## 2. The three reasons to store

Ask in order. Expect "no". All three no means it is a view, and writing it as anything else is a
defect.

1. **Does it read a corpus?** A view cannot open a file, a jar or a catalog. A rule reading one
   outside the store is a table filled by a gatherer.
2. **Does it establish a key its own references do not already cover?** A key they cover is
   *composed* and needs no table. A key they do not cover is *established*, and a foreign key
   cannot reference a view. Decide this from the declared keys and the foreign keys between them;
   it is decidable, not declared.
3. **Has a read cost been measured?** Measured, not feared, against the criterion that a
   consumer's query gets simpler or a timed reader gets faster. If you are here, use
   `store-performance` to do the measuring before you act on it.

### Not triggers

Aggregation, recursion and window functions are **not** on that list. The store has recursive
views, dozens of windowed views and dozens of grouped views, all correctly views. Treating the
construct as the rule would convert dozens of them wrongly.

They are a useful instinct about what may prove slow, which is question 3, and question 3 is
settled by measuring. The DDL header admits exactly two answers for a post-capture relation: a view
cannot express the rule, or a view expresses it correctly and too slowly. A stored relation states
which.

For recursion specifically, the real constraint is that a recursive view must terminate on the
population **the store can hold**, not the one the subject would have. A subject-level invariant
("a class hierarchy is acyclic") is not the same claim when the relation spans every source ever
read.

## 3. What the relation owes once stored

- **An owner.** Computed, not chosen: the latest, in gatherer dependency order, of the owners of
  the relations it reads. A rule reading one family's facts belongs to that family. A rule crossing
  families belongs to the gatherer that runs last among them. Do not invent a gatherer to run last.
- **A grain.** One sentence saying what a single row is about, with a key that is that sentence's
  natural key. Write the reader's query first: that is what exposes a wrong key, and nothing
  downstream will.
- **A mark and a sweep.** Add the relation to its gatherer's sweep list. Nothing checks this and
  nothing will fail if you forget; the relation will simply accumulate every reading's rows
  forever. A cascade is not a substitute: it corrects a row whose parent went, not a row whose
  parent stands and which this reading no longer derives.

Anchoring runs as a gatherer's **last step**, after that gatherer's own sweep, over the rows that
survived. It is not a gatherer of its own.

## 4. A calculated column

A `GENERATED ALWAYS AS` column earns its place where a comparison needs it at the relation that
owns the value: the case fold that lets an authored spelling meet a catalog name through an index
seek is the store's clear case.

It does not earn its place as somewhere to put a rule so an index can serve it. If a computation
had to become a column to be fast, go back to step 0: the side that knows the answer should
probably have written it down.

## 5. What will actually stop you

Know this so a green build is not mistaken for a correct relation.

`MetaDeclarationGateTest` **will** stop a new relation with no owner and no grain. The undeclared
roster only shrinks, so a new arrival has nowhere to hide. This gate works and you will meet it.

It will **not** catch:

- a missing sweep (no gate anywhere checks this)
- a wrong grain, as opposed to a declared key disagreeing with the actual primary key
- what a hand-written jOOQ producer reads, which no catalog parse can see
- a declared view reading a relation still on the undeclared roster

So the build passing means the declaration exists and is internally consistent. It does not mean
the relation is right.

## Boundary with `store-performance`

This skill decides **whether a relation should exist and in what form**. `store-performance`
diagnoses **an existing relation that is slow** and picks a lever. If you arrive at question 3 and
need a number, that skill owns the measuring; come back here with the number.
