---
id: R753
title: "Accept @deprecated alias fields sharing a write column on jOOQ-record inputs"
status: Backlog
bucket: bug
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# Accept @deprecated alias fields sharing a write column on jOOQ-record inputs

Renaming a field in a published GraphQL API follows a standard deprecation pattern: add the
new field, keep the old one marked `@deprecated` with a removal date, and point both at the
same database column so either name works during the deprecation window. Graphitron 9
supported this on write inputs; the rewrite rejects it. `InputBeanResolver.buildJooqRecord`
fails classification when two plain `@field` leaves resolve to one column ("two fields cannot
populate one column"), on the stated ground that the overlap "would last-write-wins silently".
That justification does not hold on the jOOQ-record path: `JooqRecordInstantiationEmitter`
wraps every column assignment in a `containsKey` presence guard, so an unsent alias never
touches the record; when a client sends both names, the later declaration wins, which is the
deprecation semantics authors expect. The rejection is unavoidable for affected schemas: the
deprecated field cannot be removed early without breaking the published contract, and there is
no other column to point it at, because sharing the column is the intent. Live case:
fs-plattformen's sis schema renamed `antallPlasser` to `antallPraksisplasserOnsket`, both on
column `TALL_ONSKET_PLASSER` of `UNDERVISNINGSAKTIVITET`, with the old name published as
removable no earlier than 2028-03-31; that schema cannot build on the rewrite today.

Direction of travel (to be firmed up in Spec): accept the overlap only when exactly one of the
colliding fields carries `@deprecated`, keep rejecting undeclared collisions so the diagnostic
still catches accidental duplicates, and emit in declaration order so the surviving
(non-deprecated) field wins when both are sent, which means the superseding field must be
declared after the deprecated one, or emission order must place deprecated writers first.
Scope is deliberately narrow: the sibling member-axis rejection for plain-Java-bean inputs
("both bind to Java member") must stay, because that path emits unguarded setter calls where
two fields on one member genuinely clobber each other; and decode-vs-column overlaps already
defer to the runtime value-agreement check and are untouched. Reusing that agreement-check
path here would be wrong semantics: a client sending `old: 20, new: 30` should get 30, not an
error about the two disagreeing.

---

## Where the rejection lives, and which siblings stay

Three build-time rejects guard plain-writer column overlap on write paths; this item relaxes
exactly one of them.

| Site | Path it guards | Fate in this item |
|---|---|---|
| `InputBeanResolver.buildJooqRecord` (the per-column fold over `CallSiteExtraction.ColumnBinding`) | `@service` method param typed as a jOOQ table record; runtime is `JooqRecordInstantiationEmitter`'s presence-guarded loads | **Relaxed** per the acceptance rule below |
| `MutationInputResolver.rejectPlainColumnCollision` | INSERT mutation inputs; runtime routes through SET-map puts | Unchanged; candidate follow-up item once its emitter semantics are analysed |
| `UpdateRowsWalker` stage 6b (`UpdateRowsError.PlainColumnCollision`) | UPDATE mutation inputs; single-row SET map, and the bulk path's VALUES-join crashes outright on a duplicate derived column | Unchanged; the bulk-path crash makes a blanket relax unsound |

The member-axis reject on plain-Java-bean inputs (`indexSdlFields`, "both bind to Java
member") also stays: that path emits unguarded setter calls, so two fields on one member
genuinely clobber each other regardless of what the client sent. Decode-involving overlaps
(`@nodeId` vs anything) keep their existing deferral to the runtime value-agreement check on
every path.

## Design

### Acceptance rule

In `buildJooqRecord`'s per-column collision fold: a column written by two or more plain
`@field` leaves is **admitted when all but at most one of the colliding fields carry
`@deprecated`**, and rejected otherwise. Two live (non-deprecated) fields on one column stay
an author error; a rename chain that has produced several deprecated aliases plus one live
field is fine; so is a transitional state where every writer of a column is deprecated. The
deprecation flag is read from the SDL leaf (`GraphQLInputObjectField.isDeprecated()`, already
consumed elsewhere, e.g. `InputTypeGenerator`).

The surviving rejection's message grows a third remedy so the diagnostic teaches the
carve-out: "... remove one, point its `@field(name:)` at a different column, or mark the
superseded field `@deprecated` to declare it an alias".

### Carrier change

`CallSiteExtraction.ColumnBinding` gains a `boolean deprecated` component, populated in
`InputBeanResolver.collectJooqBindings` where the SDL field is at hand. The collision fold
and the ordering rule below both read it; the flag is a fact of the SDL leaf, so the carrier
is its dimensional home (the alternative, a side-table from column name to flags threaded
through the recursion, reconstructs the association the carrier already holds).

### Ordering: the live field wins

`JooqRecordInstantiationEmitter` emits `columnBindings()` in list order, and each load is
presence-guarded, so the last-emitted writer a client actually sent wins. To make the
non-deprecated field win regardless of SDL declaration order, `buildJooqRecord` reorders each
admitted colliding group before constructing the `JooqRecord` carrier: deprecated writers
first (declaration order among themselves), the live writer last. Bindings not in any
colliding group keep declaration order, so existing generated output is byte-identical for
schemas without alias overlap.

### Emitter fork moves from "shared" to "shared with a decode"

`buildSingularHelper` currently routes to `emitWithAgreement` when any column group is
`shared()`; that branch was previously unreachable for all-plain groups (rejected upstream).
Admitted alias groups must NOT go through value agreement (a client sending both old and new
values would get a spurious disagreement error instead of the new value). The fork condition
becomes "any group that is shared **and** involves a decode"; all-plain shared groups emit
through the ordinary sequential presence-guarded loads. Inside `emitWithAgreement` (when a
record has both an alias group and a decode overlap), all-plain shared groups are likewise
excluded from the agreement preamble and emit sequentially.

## Implementation sites

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/CallSiteExtraction.java`:
  `ColumnBinding` gains the `deprecated` component (plus compact-constructor touch and javadoc).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/InputBeanResolver.java`:
  `collectJooqBindings` populates the flag; `buildJooqRecord` replaces the first-collision
  reject with the acceptance rule + reorder; message text extended.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/JooqRecordInstantiationEmitter.java`:
  fork condition narrows to decode-involving overlap; all-plain shared groups excluded from
  the agreement preamble.
- Tests as below; docs touchpoint: whichever user-manual page documents write-input column
  binding (grep at implementation time; `docs/manual/reference/directives/pivot.adoc` quotes
  the message phrase today).

## Tests

Tier names per `docs/architecture/how-to/testing.adoc`.

- **Pipeline (classification)**, in `JooqRecordServiceParamPipelineTest`: the existing
  `plainColumnCollisionAcrossNesting_rejects` keeps rejecting (two live fields). New cases:
  deprecated + live on one column classifies (carrier holds both bindings, deprecated flag
  set, live binding ordered last); deprecated + deprecated + live classifies with the live
  binding last; two live + one deprecated still rejects; the extended message names the
  `@deprecated` remedy.
- **Pipeline (emitter)**: generated helper for an alias pair contains both presence-guarded
  loads with the deprecated write first and no agreement preamble; a record carrying both an
  alias group and a decode overlap keeps the agreement preamble for the decode group only.
- **Execution**: a `@service` mutation with an alias pair; send old only (old value lands),
  new only (new value lands), both (new value wins), neither (column untouched,
  `changed=false` contract preserved).

## Out of scope

- INSERT (`MutationInputResolver`) and UPDATE (`UpdateRowsWalker`) mutation paths, per the
  table above. If the alias pattern is wanted there, that is a follow-up item with its own
  emitter analysis (SET-map puts and the bulk VALUES-join have different overlap hazards).
- The member-axis Java-bean reject.
- Any change to decode-involving overlap handling or the runtime value-agreement check.
- Warning on alias overlaps on read/output types (already accepted silently; not this item's
  concern).

## Open questions for the reviewer

1. The acceptance rule admits an all-deprecated group (no live writer). Harmless under
   presence guards, but arguably an authoring smell; should it warn?
2. Reordering (live wins) versus pure declaration order (Graphitron 9's later-declared wins):
   this spec picks reordering for author-order independence. Push back if declaration-order
   fidelity matters more than footgun removal.
