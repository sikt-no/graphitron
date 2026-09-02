---
id: R912
title: "Only the model owns gatherers: consumers trigger a refresh, they never report a fact"
status: Backlog
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-09-02
last-updated: 2026-09-02
---

# Only the model owns gatherers: consumers trigger a refresh, they never report a fact

## Goal

A gatherer needs intimate knowledge of the model, because it decides what a row *means*: which
coordinate a directive application attaches to, which corpus a fact belongs to, which arm of a
closed taxonomy a verdict is. So gatherers belong to `graphitron-model` and nowhere else, and the
model publishes no interface through which a consumer can report a fact. What a consumer may say is
that something changed; the model then decides what must be refreshed and refreshes it. When this
lands, the only calls across the boundary are a refresh trigger and a diagnostics payload, and
neither names a model concept.

`roadmap/capture-without-the-materialization-refresh.md` breaks the generator's dependency on the
fact tier and stops at `graphitron`. It cannot reach further: `graphitron-maven-plugin` sits above
both tiers and legitimately depends on both, so the write-direction guard that item ships is a
ratchet on the generator and says nothing about the plugin. This item is the plugin's half.

## Words used here

**Gatherer** is the fact tier's own word, registered in `meta_gatherer` with a corpus junction that
is the store's definition of a crawler. **Report** means a consumer hands the model a value and the
model writes it as a row. **Trigger** means a consumer says a source moved and the model decides
what that implies.

## The rule, stated so it can be applied

**The model exposes no write API that carries model vocabulary.** "No reporting at all" is the right
instinct but slightly too broad, and the over-breadth is what makes a generic diagnostics relation
look like a concession when it is not. A gatherer must be model-owned because it decides meaning. A
located message decides nothing: position, severity, text, and which producer emitted it carry no
model concept, so there is nothing for a caller to get wrong. The danger is proportional to the
vocabulary the fact carries, and the rule should say so.

The corollary that settles the harder half: **a corpus fact can always be gathered; a conclusion can
only be reported.** The model can go read any corpus, the emitted tree included. It cannot go read a
decision that existed only in the memory of the tier that reached it.
`roadmap/run-record-families-for-commands-and-emitted-units.md` carries that corollary in full,
because it is where it decides something.

## The four writers the dev session drives today

`DevMojo` constructs all four on its session store handle. None is reachable from
`GraphQLRewriteGenerator`; each records what the *plugin* observed rather than the generator's
account of itself.

* **`JavaSourceFacts` is already a gatherer in the wrong hands.** Both halves are model-owned code
  today: `SourceWalker` and `JavaSourceFacts` are both in the fact tier's move set, and
  `compileSourceRoots` is a component of `RewriteContext`, which also moves. So the plugin owns
  neither the walk nor the write nor the knowledge of where to walk; it owns the sequencing. This one
  collapses into a model-side entry point and takes a parameter away rather than adding one. It is
  also the one write that is not downstream of a generator pass at all: it seeds at startup and
  refreshes off a `.java` watcher, its corpus being the consumer's own sources.
* **`CompileFacts` must stay a report, and that is not a compromise.** Its corpus is the emitted
  tree plus javac's verdict on it, and the model cannot go get either: the compile engine cannot
  come down, `AbiSignature` naming javapoet seven times and `TypeSpecReferenceWalk` four, so moving
  it would break the fact tier's no-javapoet rule outright. The one family that structurally cannot
  be gathered is also the one carrying no vocabulary, which is the rule confirming itself rather
  than bending.
* **`BuildWarningFacts` is the residue arm** and carries no rule by definition, so it is a payload
  on the same terms.
* **`RejectionFacts` is the case that fails hardest and needs no work here.**
  `rejection_validation_error` carries `kind` under a CHECK, `variant`, `lsp_code`, `attempt_kind`
  and `stub_key`: the thickest vocabulary in the store, reported downward from the transitional
  classification walk. The fact model already calls the family scaffolding with no stratum, existing
  so a derivation can be diffed against that walk and retiring with it. So this item should not fold
  it into anything; it should let it die.

## The trigger

The model already decides what to refresh from stamps: `StoreRefresh.prepare` does exactly this at
warm capture, reconciling only what it cannot prove unchanged. This generalises that from
capture-time to on-demand, and the API needs no vocabulary: a path is not a model concept, and the
model knows which corpus a path belongs to because it recorded the `source_root`s and the
`store_source` rows itself.

The emit side already computes the precise trigger for free. `JavaFile.writeToPathReporting` is
content-idempotent, hashing rendered content against the file on disk and writing only on a
difference, and it returns `changed()` per file. So "these files moved" is a list of paths the
incremental compiler had to compute anyway. A regeneration that changes nothing changes no file on
disk, leaves every stamp valid, and costs the refresh nothing.

One inconsistency to resolve while here: `SourceWalker`'s own per-instance cache keys on mtime,
while `java_file.stamp` is a content hash, and the store's column comment argues why the hash wins
("a checkout, a rebase or a container layer defeats" a timestamp). Two signals for one question. The
skip-if-identical writer means they agree in practice on generated output, which is not the same as
being right.

## A generic diagnostics relation

`javac_diagnostic` and `build_warning_no_rule` are one relation with a producer tag: same grain, same
payload, different producer. Collapsing them is what makes the payload rule expressible as a single
write surface rather than one per producer. `lint_` stays out, carrying a rule id and a fix tree
(`lint_finding_fix`, `lint_finding_fix_edit`) that is model vocabulary and whose own charter names
store-native predicates as its destination. `rejection_` stays out for the reason above.

The fact model's provenance guidance looks like it forbids this and does not, but the reading is
deliberate and a reviewer should be asked to confirm it rather than assume it. That guidance says
independent walks producing accounts of *the same subject* want separate relations coalesced by a
view, which is what the prefix-less `diagnostic` view is today. javac's stream and the build's
advisory residue are not two accounts of one subject; they are the same payload shape about
different subjects. "This run reported this message at this position with this severity from this
producer" survives the say-what-one-row-asserts test with no consumer, pass, or class named in it.

## Sequencing

After `roadmap/capture-without-the-materialization-refresh.md`, which is what puts `SourceWalker`,
`JavaSourceFacts`, `CompileFacts` and the diagnostics writers in `graphitron-model` in the first
place. Nothing here is foreclosed by that item: every input type those writers take is in its move
set, so they compile wherever they land, and their caller sits above both tiers either way.

Independent of `roadmap/run-record-families-for-commands-and-emitted-units.md`, which reintroduces
what a run *concluded* on top of these boundaries. This item decides what may cross the boundary;
that one uses the crossing.
