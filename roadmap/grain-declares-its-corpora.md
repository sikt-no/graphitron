---
id: R923
title: "A grain declares its corpora, so a cross-corpus grain can say so"
status: Spec
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-09-05
last-updated: 2026-09-05
---

# A grain declares its corpora, so a cross-corpus grain can say so

## Goal

The fact store's own model can say which outside inputs a grain is about, including "more than one"
and "none", and every declared relation is checked against that answer instead of a third of them
being skipped. A *grain* is what one row of a relation is about, rostered in `meta_grain`; a *corpus*
is one outside input the store transcribes, rostered in `meta_corpus`. Today a grain names exactly one
corpus, in a NOT NULL `meta_grain.corpus_name`, and that shape cannot state the truth for a grain that
spans two corpora or belongs to none. When this lands the association is a junction, the same shape
`meta_gatherer_corpus` already uses for the same question one relation over, and the gate that checks a
relation's owner against its grain reaches every declared relation by following the dependency edges
the roster already declares. What that buys beyond correctness is the edge propagation needs: a grain
that declares its corpora is a grain that can say which outside inputs, when they move, make its rows
stale.

## The column is already unchecked where it is most likely to be wrong

`MetaDeclarationGateTest.ownerAndGrainAgreeAboutTheCorpus` holds a declared relation's grain to a
corpus its owner reads, and exempts an owner that reads none, "crossing being its job". A gatherer
reads no corpus exactly when it is not a crawler, which is to say exactly when its rows are allowed to
depend on more than one corpus. So the relations whose grain is most likely to be cross-corpus are
precisely the ones the gate does not check.

Counted over the declarations as they stand: 35 grains, 40 declared relations, and 12 of those 40 are
owned by a corpus-less gatherer. Every one of the 12 sits on a grain that names a corpus nothing
validates.

Two of them are not merely unchecked but wrong, which is what makes this a modelling defect rather
than a tidy-up. `bound-table-type` is "one graph type that resolved to exactly one catalog table, in
one graph"; that is a resolution *between* the SDL and the catalog, its key shape is `graph_name,
type_name` which is SDL vocabulary, and it declares corpus `catalog`. `expanded-type` is "one type the
generator works with in one graph, whether an author declared it or macro expansion minted it"; it
declares `sdl` and its relations are owned by the `graphitron` gatherer, which reads the `sdl`
gatherer's transcription and the catalog beside it. In both cases the single-corpus column forced a
choice between two true answers, and the value that got written is unchecked, so nothing ever said so.

## The junction

Drop `meta_grain.corpus_name`. Add `meta_grain_corpus (grain_name, corpus_name)`, keyed on the pair,
foreign-keyed to `meta_grain` and `meta_corpus`. Row count carries the meaning, exactly as it already
does one relation over:

* **No rows**: the grain is not about any one corpus. Either it spans none because it is not about
  corpus content at all, or its relation's owner reaches no corpus.
* **One row**: today's meaning, and what most grains keep.
* **Two or more**: the grain is about a resolution across those corpora, which the model can now state.

The symmetry with `meta_gatherer_corpus` is the point rather than a coincidence. That junction's
comment says a gatherer with at least one row in it is a crawler; the reading here is the same sentence
one step over, that a grain with at least one row is corpus-rooted.

## The gate that replaces the exemption

Nullability was never the real defect. The exemption was, and a junction alone does not remove it:
both corpus-less gatherers, `graphitron` and `derivation`, would still be exempt under a gate phrased
against an owner's own corpora, so the same 12 relations would stay unchecked with a nicer shape.

What closes it is that a gatherer's corpus reach is already derivable. `meta_gatherer_dependency`
declares which gatherers may read which others' rows, and its comment states the shape: "Every edge
therefore runs from a gatherer that reads no corpus." So a gatherer's *reach* is the union of its own
`meta_gatherer_corpus` rows and the reach of everything it depends on. For `graphitron`, which depends
on `sdl` and `catalog`, that is `{sdl, catalog}`. For `derivation`, which depends on every crawler, it
is every corpus. For a crawler it is its own corpora, which is today's rule unchanged.

The gate becomes: **every corpus a grain declares must be within its relation's owner's reach**, with
no exemption for anyone. It is identical to today's test for the 28 crawler-owned relations, and it is
the first check the other 12 have ever had: a `graphitron`-owned grain may declare `sdl`, `catalog`, or
both, and nothing else.

## The migration, and the rule that decides it

The 35 grain rows move into the junction, 23 of them mechanically. The 12 reached only by
corpus-less-owner relations are the judgment, and they are decided by one rule rather than one at a
time:

> A grain declares every corpus whose *content* its rows depend on: the corpora that, when an instance
> of them moves, can change which rows exist at this grain or what they say.

That is the rule the declaration has to satisfy to be useful downstream, because it is exactly the
question propagation asks of it. It is not "which corpus's vocabulary the grain is phrased in", which
is what the single-corpus column has been recording by default and what produced `bound-table-type`
declaring `catalog` while keying on SDL names.

Applying it, with the owner's reach as the outer bound:

* `bound-table-type` ("one graph type that resolved to exactly one catalog table") declares `sdl` and
  `catalog`. Both halves of a resolution move it: renaming the type in SDL, or dropping the table.
* `node-type` and `node-key-position` declare `sdl` and `catalog`, on the same reading: node-ness is
  authored in the schema and its key tuple resolves against catalog keys.
* `expanded-type`, `expanded-field` and `minted-type-site` are the ones to check against the expansion
  code rather than assume, since a macro that consults the catalog to decide what to mint depends on
  it and one that reads only directives does not. Implementation reads `GraphitronFactCapture`'s
  expansion path and declares what it finds; the gate bounds the answer to `{sdl, catalog}` either way.

The remaining grains under corpus-less owners follow the same rule and the same check.

## Tests

`MetaDeclarationGateTest` carries the change rather than gaining a parallel suite. The corpus test is
rewritten against reach and loses its exemption arm, so it now binds all 40 declared relations; a new
case asserts the reach computation itself over the declared edges, including that a crawler's reach is
its own corpora and that `derivation` reaches every corpus. A case asserts that a grain whose owner
reaches no corpus declares none, which is the empty arm the junction exists to admit.

`MetaGrainCorpusTest` or an added case beside the roster gates: every grain row is reachable from at
least one declared relation, so a grain declaring corpora nothing owns cannot accumulate; and the
junction's rows are a subset of the reach of every owner that declares the grain, which is the
multi-owner form of the gate above.

## Retired vocabulary

`meta_grain.corpus_name`, the column and every reference to it, including the `ownerAndGrainAgreeAboutTheCorpus`
display name and the phrase "an owner with no corpus rows is exempt, crossing being its job" in the
gate's assertion description, which describes an exemption this item removes.

## Provenance

Found while specifying R922, which at the time proposed a relation whose grain was one gatherer's
currency on one instance of one corpus. That grain names its corpus per row and so has no single corpus
of its own, and the first draft proposed making `meta_grain.corpus_name` nullable to admit it. A
nullable column is the model saying the column does not belong on the relation, which is what prompted
the audit above.

R922 has since shed that relation entirely, so this item has no dependent and rests wholly on its own
evidence, which is where its case always was: the column is already unchecked for 12 of 40 declared
relations, and at least two of the values it carries are not true. Losing the dependent removes the
schedule pressure and nothing else. The exemption that lets a third of the roster go unchecked is worth
closing whether or not anything is waiting on it.
