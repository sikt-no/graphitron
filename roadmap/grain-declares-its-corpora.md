---
id: R923
title: "A grain declares its corpora, so a cross-corpus grain can say so"
status: Backlog
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
and "none". A *grain* is what one row of a relation is about, rostered in `meta_grain`; a *corpus* is
one outside input the store transcribes, rostered in `meta_corpus`. Today a grain names exactly one
corpus, in a NOT NULL `meta_grain.corpus_name` column, and that shape cannot state the truth for a
grain that spans two corpora or belongs to none. When this lands the association is a junction, the
same shape `meta_gatherer_corpus` already uses for the same question on the gatherer side, and the
gate that checks a relation's owner against its grain becomes well defined for every declared
relation instead of quietly skipping a third of them.

## The column is already unchecked where it is most likely to be wrong

`MetaDeclarationGateTest.ownerAndGrainAgreeAboutTheCorpus` holds a declared relation's grain to a
corpus its owner reads, and exempts an owner that reads none, "crossing being its job". A gatherer
reads no corpus exactly when it is not a crawler, which is to say exactly when its rows are allowed
to depend on more than one corpus. So the relations whose grain is most likely to be cross-corpus are
precisely the ones the gate does not check.

Counted over the declarations as they stand: 35 grains, 40 declared relations, and 12 of those 40 are
owned by a corpus-less gatherer. Every one of the 12 sits on a grain that names a corpus nothing
validates.

Two of them are not merely unchecked but wrong, which is what makes this a modelling defect rather
than a tidy-up. `bound-table-type` is "one graph type that resolved to exactly one catalog table, in
one graph"; that is a resolution *between* the SDL and the catalog, its key shape is `graph_name,
type_name` which is SDL vocabulary, and it declares corpus `catalog`. `expanded-type` is "one type
the generator works with in one graph, whether an author declared it or macro expansion minted it";
it declares `sdl` and its relations are owned by the `graphitron` gatherer, which reads the `sdl`
gatherer's transcription and the catalog beside it. In both cases the single-corpus column forced a
choice between two true answers, and the value that got written is unchecked, so nothing ever said so.

## The shape

Drop `meta_grain.corpus_name`. Add `meta_grain_corpus (grain_name, corpus_name)`, keyed on the pair,
foreign-keyed to `meta_grain` and `meta_corpus`. Row count carries the meaning, exactly as it already
does one relation over:

* **No rows**: the grain is not about any one corpus. Either it spans them, or it is not about corpus
  content at all.
* **One row**: today's meaning, and what most grains will keep.
* **Two or more**: the grain is about a resolution across those corpora, which is a thing the model
  can now state.

The gate strengthens rather than loosens: for a declared relation whose owner reads any corpus, *every*
corpus its grain declares must be one the owner reads. That is a stricter test than today's for a
multi-corpus grain and identical for a single-corpus one, and it is well defined for the empty case
instead of resting on a NOT NULL column nobody can fill honestly.

The symmetry with `meta_gatherer_corpus` is the point, not a coincidence. That junction's comment says
a gatherer with at least one row in it is a crawler; the reading here is the same sentence one step
over, that a grain with at least one row in it is corpus-rooted, and a grain with none is what a
non-crawler's relations are allowed to be at.

## What Spec owes

The migration of the 35 existing grain rows is the work, and 12 of them are a judgment rather than a
transcription. For each grain reached only by relations with a corpus-less owner, Spec decides whether
it declares the corpora it actually spans (`bound-table-type` as `sdl` and `catalog`) or declares none,
and states the rule it used rather than settling them one at a time. The other 23 move across
unchanged.

Spec also owes the DDL's own conventions for the new relation: the table and column comments, and
whether `meta_grain_corpus` itself wants a `meta_relation` declaration or stands on the frozen
undeclared roster beside the rest of the `meta_` family, which every existing `meta_` relation does.

## Provenance

Found while specifying R922, which needs a claim relation whose grain is one gatherer's currency on
one instance of one corpus. That grain names its corpus per row and so has no single corpus of its
own, and the first draft proposed making `meta_grain.corpus_name` nullable to admit it. A nullable
column is the model saying the column does not belong on the relation, and the audit above says the
same thing from the other direction: the column is already unchecked for 12 of 40 declared relations
and already carries at least two values that are not true. R922 depends on this item rather than
working around it.
