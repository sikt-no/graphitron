---
id: R631
title: "One site for the loaders graph-ownership preamble"
status: Backlog
bucket: refactor
priority: 6
theme: diagnostics
depends-on: []
created: 2026-08-11
last-updated: 2026-08-11
---

# One site for the loaders graph-ownership preamble

Every post-capture writer into the fact store opens with the same graph-ownership preamble: read
`store_graph.base_dir` for the graph, mint the minimal anchor row where no capture ever reached
this store under the name, and refuse to touch a partition another checkout's directory owns,
warning once per writer. That rule now has two implementations. `OwnedGraphPartition.prepare`
(`graphitron/src/main/java/no/sikt/graphitron/rewrite/diagnostics/OwnedGraphPartition.java`) is
the shared one the two diagnostics-stratum loaders call; `CompileFacts.writeRound`
(`graphitron/src/main/java/no/sikt/graphitron/rewrite/compile/CompileFacts.java`) still carries
the same logic inline, because the helper was extracted for the new loaders rather than by
collapsing the site it was modelled on. The two agree today and the divergence is silent when
they stop: the ownership refusal is the guard that keeps one module of a shared workspace from
erasing another's rows, so a rule change applied to one copy and not the other means one family
respects a boundary its neighbours ignore, with no test failing.

The collapse is small: point `writeRound` at `prepare`, keep the javadoc reasoning about the
minted anchor's `last_captured` value with the helper (it is the same argument at every site),
and widen the helper's visibility past `…rewrite.diagnostics`, since `CompileFacts` lives in
`…rewrite.compile`. Two shapes to settle while doing it. First, whether the helper should own
the per-writer message tail as well: today each caller passes its own logger and warns with its
own family named in the sentence ("that partition's javac diagnostics", "that partition's
diagnostics"), which is worth keeping, so the parameter probably becomes the family's display
name rather than the whole message. Second, whether the warn-once cell belongs in the helper's
signature at all: `CompileFacts` uses a plain field, the new loaders a one-element array purely
because the helper is static, and one shared instance-per-writer holder would read better than
either. Adjacent: `RejectionFacts` and `BuildWarningFacts` each carry their own copy of the
location-normalisation block (canonical URI when the source name is non-empty, line and column
when the line is positive), which is the same three lines and belongs at the same one site.

