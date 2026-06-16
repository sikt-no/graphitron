# The (source, intent, target) reframe: consequences of dissolving `mapping`

An analysis artifact, not a roadmap item. It lives in `audits/` so the
roadmap-tool (which scans `roadmap/*.md` non-recursively and wants `id:`
front-matter) ignores it, and it is Markdown so the `check-adoc-tables` build
step (which scans `.adoc` only) leaves it alone.

It tests a claim raised on 2026-06-16 while designing the R316 (`decompose-sourcekey`)
spec: the field-dimensional model's third axis, `mapping`, is mislabeled. The real
third axis is `target`, and the catalog-vs-Java distinction `mapping` encodes is a
per-endpoint *polarity*, not a dimension. This document follows that thread to its
consequences, then weighs pivoting against not pivoting.

## The claim

A field is an edge from a **source** (the parent, what arrives at `env.getSource()`)
to a **target** (the output it projects), spanned by an **intent** (the operation).
The three dimensions are the two endpoints and the operation between them:
`(source, intent, target)`. `mapping` is not one of them.

## Why `mapping` is not a dimension

`Mapping` (R281, materialised by R290 as `OutputField.mapping()`) packs two
unrelated facts into one enum (`Table`, `TableConnection`, `Column`, `Record`,
`Field`):

1. **The shape of the output**: whole table vs single column vs connection
   (catalog side), whole record vs single scalar field (Java side). This is
   genuinely "where we are going and in what shape": the **target** dimension.
2. **A mirror/reflect polarity**: `Table : Column :: Record : Field`, the
   catalog-vs-Java nature of the value.

Fact 2 is not single-valued per field. The **source** has a polarity (`SourceShape`
is `Table` or `Record`, already shipped on `Carrier.Source` by R305) and the
**target** has a polarity (catalog vs Java). They are independent: a `Record` source
projecting into a `Table` target is exactly the re-fetch crossing. `mapping`
filed the target's polarity on one axis and left the source's polarity on another
(`carrier`), so any consumer that cares about the *relationship* between the two
endpoints has to read two axes jointly.

`requiresReFetch()` is that consumer, and it is the proof. Today it is derived from
`intent × mapping` (R290 slice 4; R305 sharpened it to "`Table` mapping AND a
record-producing endpoint", i.e. a `Source{Record}` received or a Service/DML intent
produced). Read in the reframe it is just: *a record-producing endpoint (source is
`Record`, or an intent that produces a record) crossing into a catalog `Table`
target.* That is a relationship between source-polarity / intent and target-polarity.
It only reads as "`intent × mapping`" because the target's polarity was filed under
`mapping` and the source's under `carrier`. Give each endpoint its own polarity and
re-fetch becomes a clean function of `(source, intent, target)`, not a joint read of
a conflated axis.

## What each dimension becomes

- **source** (today's `carrier`, formalised). `Carrier`'s own javadoc already calls it
  "the parent-type category a field is defined on"; for root fields the parent is
  `Query` / `Mutation`. So `carrier` already *is* the source category. Arms:
  `QueryRoot` / `MutationRoot` (no parent object) and `Nested(shape, cardinality)`,
  whose `Record` shape carries the reflected source-object facts the field cannot
  change (backing class, `env.getSource()` envelope, produced record type). The `Table`
  shape carries nothing (a bare catalog row).
- **intent**, made a payload-carrying sealed hierarchy (R314 already anticipates this:
  "Intent becomes a sealed payload-carrying hierarchy, not a bare enum"). `QueryService`
  becomes a call carrier holding the arguments and the reflected service-method facts
  (parameter types, result type). `Fetch` / `Lookup` carry their correspondence; the
  writes carry their DML shape.
- **target**, which is `mapping`'s five shape values relabelled and reinterpreted as the
  destination endpoint: catalog `Table` / `Column` / `Connection`, Java `Record` /
  `Field`. The catalog-vs-Java split here is the target's *polarity*, now a sibling of
  the source's polarity rather than standing in for both.

## The unification that falls out

`ServiceTableField`: its source polarity is catalog (a `Table` parent), but the
*intent* (`QueryService`) produces a record whose type is the **same** record type a
`RecordTableField` gets from its *source* (`Source.Record`). One fact, different
owning endpoint. A single `mapping` dimension cannot own a fact whose producing
endpoint varies per field; `(source, intent, target)` can, because the fact lives on
whichever endpoint produced it. This is the structural reason the reframe is more
than a rename: it relocates information to its true owner.

## `SourceKey` decomposes cleanly (the R316 payoff)

Under the reframe, `SourceKey`'s six components scatter to their owners with no
residue that mixes concerns:

- `target` / `path` to the **target** (the slices 1-2 eviction; they were always
  target, redundant with `returnType.table()` + `joinPath`).
- source-object facts (backing class, envelope, produced record type) to the
  **source** (`Source.Record` arm).
- the service call and its reflected result to the **intent** (`QueryService`);
  accessor / lifter extraction to the **source**.
- the residual field key (`columns`, `wrap`, arity) stays on the field, and only
  meaningfully on `Record` sources (the earlier "`SourceKey` is a `Record`-source
  concept" observation).

R316 stops being "a mechanical simplification we have to argue for" and becomes "the
first concrete consumer of the corrected vocabulary."

## Blast radius

Shipped code that speaks `mapping`:

- `Mapping` enum + `OutputField.mapping()`: rename to `target`, reinterpret the
  catalog-vs-Java split as the target's polarity.
- `@classified(mapping:)` directive argument on **every** example in the R281/R299
  corpus: rewrite to `@classified(target:)`. The `carrier` (source) and `intent` args
  are already present (R299), so this is one argument per example, not a new axis.
- `requiresReFetch()` derivation (`intent × mapping`) and its `GraphitronSchemaValidator`
  mirror: re-express over `(source, intent, target)`.
- R290's "build-vs-consume reads `mapping`" consumer.
- Docs and changelog references to `mapping` across R281 / R290 / R299 / R305.

Not touched by the rename itself, and owned by separate items: the emit, which still
dispatches by leaf identity (R314), and `SourceKey`'s internals beyond relocating its
homes (R316).

Note the timing: R290, R299, and R305 all landed under the `carrier × intent ×
mapping` vocabulary within the last weeks. The pivot re-vocabularises freshly-settled
work.

## Open question: the Query/Mutation legality gate

`carrier`'s root arms gate intent legality (write intents only on `Mutation`,
`NodeResolve` only on `Query`). Under `source = {QueryRoot, MutationRoot, Nested}` the
gate survives as a source-arm property (a read-root vs write-root source). That works,
but it means "source" spans both "the operation root" and "the parent object", a wider
notion than `env.getSource()`. The vocabulary needs pinning so "source" does not read
as only the nested case.

## Pros of pivoting

- **Concern separation.** Mirror/reflect stops being smeared across `carrier` and
  `mapping`; each endpoint owns its polarity. This is the project's stated north star
  ("avoid mixing concerns").
- **`requiresReFetch` becomes a natural relationship** over `(source/intent, target)`,
  retiring the joint "`intent × mapping`" read and shrinking the validator-mirror drift
  surface, which is the same outcome R314 is chasing from the emit side.
- **`SourceKey` gets honest homes**, so R316 is a clean application rather than an
  argument plus an application.
- **Payload-carrying `intent`** unlocks R314's emit re-platforming and the retirement of
  leaf-dispatch.
- **Composable and additive.** Dimensions are endpoints plus operation; a new axis slots
  in without multiplying a cross-product.
- **Within R222's own license.** R222 declares itself "Direction, not contract" and tells
  slices they are "free to redraw the diagram as implementation slices land and surface
  new understanding." This is exactly that.

## Cons and risks of pivoting

- **Churn on recently-settled work.** Re-vocabularises R290 / R299 / R305 outputs and
  their docs and changelog, and rewrites the `mapping:` argument across every corpus
  example. Mechanical, but broad, and it touches work that is days old.
- **Overlap with R314.** Making `intent` payload-carrying and reading `target` at emit is
  partly R314's plan. Without careful sequencing this risks double-work or pre-empting
  R314's design.
- **Naming collision.** "target" already names `SourceKey.target` and shadows
  `returnType.table()`; the dimension name needs disambiguation to avoid confusion.
- **Deferred payoff.** Renaming `mapping` to `target` without also re-deriving
  `requiresReFetch` and without a consumer reading the new axes banks little immediate
  behaviour change. It is a clarity and architecture investment, not a feature; the
  return arrives when consumers actually fork on the endpoints.
- **Scope growth.** R316 began as a mechanical simplification ("bulk is deletion, not new
  design"). The thread has grown it into a model revision. The pivot is correct, but it
  is a strictly larger commitment than the item that surfaced it.

## Recommendation

Pivot, but stage it as its own item and keep R316 downstream of it.

1. **File an R222-revision item**: "dissolve `mapping`; the field dimensions are
   `(source, intent, target)`." Body captures the rename `carrier` to `source`
   (formalising what `carrier` already is), `mapping` to `target` (rename plus polarity
   reinterpretation), `intent` to a payload-carrying hierarchy, and the `requiresReFetch`
   re-derivation over the endpoints.
2. **Stage that item**: (s1) rename plus corpus-argument rewrite, behaviour-neutral; (s2)
   re-derive `requiresReFetch` over source-polarity / intent and target-polarity, dropping
   the joint read and tightening the validator mirror; (s3) `intent` payload-carrying.
   Coordinate s3 with R314 so the emit side consumes it rather than duplicating it.
3. **Write R316 in the corrected vocabulary** as the first concrete consumer, landing the
   `SourceKey` decomposition. Do not fold the model revision into R316.

A smaller alternative exists: keep R316 mechanical under the current vocabulary now (move
`SourceKey`'s pieces to the existing slots without the `mapping` rename) and file the
`(source, intent, target)` pivot as a separate future revision to pursue when there is
appetite. That de-risks R316 and avoids churning days-old work, at the cost of banking the
conceptual clarity later and leaving `SourceKey`'s homes half-honest in the interim.
