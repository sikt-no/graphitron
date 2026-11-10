---
name: fact-documents
description: Move a module's store-backed tests onto fact documents, a batch at a time, as a pipeline of agents - cluster the capture sites by behaviour, triage each into a verdict, author the documents, convert the tests, have a different agent review what was deleted, and verify one build at a time while the module's inline-capture ceiling falls. Use when converting tests that capture a schema inline (TestSchemaHelper, CapturedStore arms, SeededStore) onto the shared FactGraphs population, when lowering an InlineCaptureGateTest ceiling, or when asked to deduplicate a module's tests against the fact documents. Not for writing one new fact document by hand, which needs only the testing page.
---

# Fact documents: the migration pipeline

Where an example goes, the claim vocabulary, the folder claims, what an authoritative test is and
which harness a store-backed test takes are all in `docs/architecture/how-to/testing.adoc`: the
"Pipeline tier", "What a test is built out of", "Authoritative tests" and "Where a store-backed test
gets its store" sections. That page is the source and this skill is the procedure; it deliberately
does not restate the page, because two copies of a rule drift. Read those sections before the first
batch, and point every agent brief at them rather than paraphrasing them.

What this skill owns is the order of work: how a batch of test classes moves onto documents without
losing an assertion, without two agents editing one file, and without a duplicate surviving because
nobody saw its twin.

## The batch

A batch is a handful of test classes in one module, chosen so their capture sites can be read in one
sitting. Each stage below is a different agent, briefed per the `delegate-to-subagents` skill: a
self-contained brief, `file:line` evidence back, never file dumps. The session holds every ruling.

### 1. Cluster (one agent, read-only)

List the batch's capture sites, `InlineCaptures` names what counts as one, and beside them the
documents and the tests in other modules that pin the same behaviours, grouped by behaviour. The point
is that a duplicate is seen as one cluster before any site in it is ruled on: rule on sites one at a
time and the second copy of a behaviour looks like a fresh case.

The keystone report helps here: `FactExpectationTest` writes
`graphitron-model/target/facts-report/keystones.txt`, naming the behaviours each page shows, the ones
no page shows by how many documents pin them, and the documents that pin no family.

### 2. Triage (agents in parallel, read-only)

One row per capture site, in a table the session reads before anything changes:

| Column | What it holds |
|---|---|
| Site | `file:line` of the capture |
| Behaviour | what it pins, in the cluster's words |
| Verdict | one of the closed set below |
| Target | the document or assertion file it goes to, or the covering test |
| Note | anything the session needs to rule |

The verdicts, and nothing else:

- **claims in a document**: the assertion becomes rows a document claims;
- **an assertion file**: an expectation that is not a store row, or one the module adds without
  editing the document;
- **a Java test reading the population**: a reader's behaviour, read through `FactGraphs`;
- **a store of its own**: the reasons the testing page gives, and only those;
- **inline, its defect not yet stored**: on the testing page's terms ("What is not an example"),
  named beside the module's `InlineCaptureGateTest` ceiling;
- **deleted as a duplicate**: the authoritative test that already covers it, by `file:line`. A
  duplicate verdict without that `file:line` is refused.

The session rules on the table: it confirms or changes each verdict before stage 3 starts.

### 3. Author (agents grouped by target document)

Write the documents and assertion files. Group the work by target document, so no two agents edit one
file; an agent owns every site whose target is its document. Each agent checks its documents with
`mvn test -pl :graphitron-model -Dtest=FactExpectationTest -Dfacts=<path prefix>` (the `build` skill
has the flags), which captures only those documents, and finishes clean before handing back.

### 4. Convert (agents per test class)

Move the test classes onto the population: `FactGraphs.reader()` as an extension, `read(path, ...)`
where they captured. Delete each Java assertion that became a claim. Negative-test every new claim:
break it, see `FactExpectationTest` fail on it, restore it. A claim that cannot be made to fail pins
nothing.

### 5. Coverage review (a different agent)

An agent that did not author or convert compares each deleted assertion with what replaced it, or with
the cover triage named for it, and reports any assertion that went without one. This is review, not a
gate: each claim's two-way check and its negative test are enforced, the completeness of the
replacement is judged.

### 6. Verify (one build-running agent at a time)

One agent runs builds at a time, per `delegate-to-subagents`. When the batch lands, lower the
module's `InlineCaptureGateTest` ceiling to the new count, and, where documents merged, the duplicate
pins `FactExpectationTest` holds. A ceiling is lowered in the commit that earns it and never raised.

## What the commit reports

The affected test phases before and after, in interleaved pairs measured as the `build-profile` skill
describes; `FactStores.captures()` at the end of the module's run; the tests deleted as duplicates and
the documents merged; and the ceilings it lowered.

## Smells

Each is a migration habit; the rule it breaks is on the testing page, "What a test is built out of".

- **A document named for the test class it came from** pins whatever that class happened to assert,
  not one subject.
- **A seeded site moved rather than dissolved.** It becomes a document stating the example whose
  capture writes those rows.
- **An inline capture kept because converting it was awkward.** Awkward is a missing building block,
  not a reason to stay inline.
