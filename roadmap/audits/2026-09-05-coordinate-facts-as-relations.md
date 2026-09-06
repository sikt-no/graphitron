# The coordinate's facts as relations, and which illegal states the schema can refuse

An analysis artifact, not a roadmap item: it lives in the subdirectory the roadmap-tool ignores, and
it is Markdown so `check-adoc-tables` leaves it alone. It reads the leaf model, R222's lineage record
(`2026-08-06-r222-lineage.md`; the item itself was discarded 2026-08-06) and R333
(`coordinate-lowers-to-datafetcher-queryparts.md`, Spec) together, and asks one question the three do
not ask in the same words: given the facts, what relational shape holds them so that an illegal
combination cannot be written down at all.

## 1. Where the three agree

**The leaf zoo is a denormalized view.** R333 says it outright, and names the two textbook faults.
`CompositeColumnField` carried an arity-N `columns` list, a repeating group and a 1NF violation, so
arity became a leaf dimension. The split leaf welds on a parent-key projection that functionally
depends on the parent's query rather than on the child coordinate, a transitive dependency. The
cross-product multiplies because independent facts share one slot: `Split x Lookup x Composite x ...`
is what collapsing independent operations into one slot produces, and as a set they merely co-occur.

**R222's thesis won and its mechanisms lost.** The lineage record is blunt about it: the
slots-over-cross-product claim is the fact base's own justification, and axes are per-directive
relations. Two of its three architectural claims were inverted by that same architecture. Producers
do not read graphql-java primitives, because capture decodes the AST once and no graphql-java node
crosses into the relations, so the `Walker<S, C>` substrate had no home. And validity does not ride a
wrapper: `WalkerResult.Ok/Err` is replaced by violation rows, planning never re-checks, and a failed
coordinate simply has no row to join. Both replacements are the relational move, and both are
stronger than what they replaced.

**The entity is the coordinate.** Not the DataFetcher, which R333 demotes explicitly: earlier drafts
bundled `source` and `target` into one row and named it the DataFetcher relation, which let a view
masquerade as the top-level relation. The coordinate is the entity and everything else is a fact
about it.

## 2. What landed, and the one inversion still standing

The zoo has shrunk. Against the 2026-07-04 conformance audit's counts on HEAD `28a5b56`,
`ChildField` is 18 direct permits where it was 19, `QueryField` 10 where it was 13, `MutationField` 9
where it was 14, and `TypeFetcherGenerator` is 6348 lines where it was 6588.

The operation axis landed, as R563's `OperationMember` multiset. But it landed in Java, and
`OperationMembers.membersOf` takes an `OutputField leaf` and switches on it. There is no
`intent_operation` relation, and no relation of any prefix states a coordinate's operation set. So
the inversion the 2026-07-04 audit named is intact exactly at the fact-base boundary: everything the
store holds is upstream of the leaf, and every axis is downstream of it. The axes are a projection of
the zoo, which is the opposite of both target specs.

That is the whole gap, and it is worth stating in one line because it decides the first slice.

## 3. The facts, their dependencies, and the relational form each takes

R333 states the multiplicities. The third column is this document's contribution: the shape each
multiplicity forces, under the store's own conventions.

| Fact | Dependency | Relational form |
|---|---|---|
| `coordinate -> source` | total, 1:1 | NOT NULL columns on the coordinate anchor. Absence is unrepresentable, which is what "total" means when it is a key rather than a comment |
| `coordinate -> target` | total, 1:1 | the same, and beside `source` rather than joined to it: two facts, two walks, and R333 is explicit that co-storing them was never a normalization fault |
| `coordinate -> operation` | 0..N | its own relation, keyed by the coordinate and the operation. The only one of the six forced by a normal form |
| `coordinate -> reference` | 0..1 | its own relation. A separate relation rather than nullable columns, so "absent" is no row rather than a null a reader must test |
| `coordinate -> referencedTable` | 0..1, present exactly when `reference` is | columns on the reference relation, not a relation of its own: the co-presence is then a key property rather than an invariant somebody maintains |
| `coordinate -> resolvedTable` | derived, 0..1 | stored, with a basis column. The store's rule for this is written on `graphitron_field_navigation`: stored rather than stated as a view because a reader joins on the answer, and a view is seekable on the coordinate it is keyed by and not on a name it projects |

The axes named in conversation map onto the same six. Position (root, singlechild, child) is part of
`source`. Target kind (tablefield, resultfield, nestingfield) is part of `target`. The producer set
(query, lookup, service, routine, externalField, column, nodeid) is the `operation` set, whose
members already have their own decode relations and lack only a statement of which one answers.
Source-or-reference is the `reference` fact's presence.

## 4. Four tiers of refusal

This is the part the two specs do not cover. "Unrepresentable" is not one thing; there are four
tiers, and a design should push each rule as high as the fact admits and record where it landed.

**Tier 1, the key refuses it.** A functional dependency written as a key makes a second value
unwritable. `coordinate -> source` on the anchor means "two sources" cannot be spelled. This tier is
free and it is the strongest.

**Tier 2, a foreign key refuses it.** Composite references that include a discriminator make
cross-relation combinations unwritable. The store already owns this trick and states why:
`graphitron_tabletype` carries a redundant `UNIQUE (graph_name, type_name, table_source_name,
table_schema, table_name)` whose comment says it exists so that "a relation carrying both a type and
the table it resolved to can reference the pair rather than each half separately". Generalised: put
the discriminator in the parent's superkey and have the child reference the pair. A target relation
that references `(graph_name, coordinate, 'TABLE')` cannot acquire a row under a coordinate whose
target kind is nesting, and no test is needed to say so.

**Tier 3, a CHECK refuses it.** Within one row only. The existing example is the departure triple's
all-or-nothing pair of CHECKs on `graphitron_field_table`.

**Tier 4, nothing refuses it and a row records it.** Two kinds of rule land here and they should not
be confused. A rule spanning several relations' rows cannot be a constraint at all: "`@routine` must
be the first application on a root chain" reads the position fact and the operation set together. And
a rule over author input must not be a constraint even when it could be, because capture runs before
assembly and for readers that never run it, so refusing leaves an author mid-edit with no store. The
store's shape for both is a detection relation naming the coordinate and what disagreed, which is
`intent_authored_claim_conflict` and `graphitron_minted_conflict`.

The discipline is to know which tier each legality rule reached, because a tier-4 rule reads exactly
like a tier-1 rule in prose and is enforced by nothing.

## 5. Five mechanisms, each with a precedent in the store

**Totality on the anchor.** A total 1:1 fact becomes NOT NULL columns on the entity, so its absence
is unrepresentable rather than a null every reader tests.

**Partiality as a relation.** A 0..1 fact becomes its own relation keyed by the entity, so absence is
no row. This is what lets a reader anti-join instead of testing nulls, and it is why the reference
fact should not be nullable columns on the anchor.

**Multiplicity as a relation with the discriminator in the key.** A 0..N fact becomes rows, which is
the repeating group made atomic. The operation set is the case, and it is the one R333 says a normal
form forces.

**Co-presence by co-location.** Two facts that are present exactly together go in one row, so the
invariant is a key property. `referencedTable` beside `reference` is the case.

**Settledness in the key.** An unresolved resolution has no row, so "resolved to two things" is
unrepresentable, and which coordinates those are is the anti-join against the entry. This is
`graphitron_tabletype`'s stated rule, and `FieldEndpoints.routineResult` already practices it by
demanding `candidates = 1`. It is the mechanism that turns "ambiguous" from a value into an absence.

## 6. What this says to do first

The coordinate anchor now exists. `graphitron_field` is the emitted field population as a table, both
authored and minted, keyed by a coordinate that `graphitron_element` anchors, so the facts have
something to hang off that they did not have three days ago.

The operation set is the first slice, on three independent grounds. It is the only fact a normal form
forces. It is the only axis with no relation at all, so it is where the leaf is still the substrate.
And its members already have their decode relations, so the work is the resolution and not the
capture.

The order after that follows the dependencies rather than the ambition: `source` and `target` on the
anchor, which is also where the position trichotomy stops being a nullable triple that conflates
root, unbound and routine-bound origins; then `reference` with `referencedTable` beside it; then
`resolvedTable` as the stored derivation its readers join on.

R589's strangler frame is the cutting rule and it is not the one R222 assumed: migrate by consumer,
not by derivation layer. The leaf zoo survives until its last consumer reads facts instead, and the
2026-07-04 audit names the consumer that decides it, the `catalog` package's projection switches that
feed the LSP and MCP. A leaf dissolution that does not re-source those onto the facts leaves the zoo
alive as an LSP shim.
