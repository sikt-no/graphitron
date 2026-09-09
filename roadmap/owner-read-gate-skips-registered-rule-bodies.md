---
id: R941
title: "The owner-read gate resolves a declared relation through the register, so a registration cannot hide a family crossing"
status: Spec
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-09-09
last-updated: 2026-09-09
---

# The owner-read gate resolves a declared relation through the register, so a registration cannot hide a family crossing

## Goal

Registering a relation stops changing which gates can read its rule. Three terms, glossed once. The
*fact store* is the H2 database each generator pass captures the consumer's schema, the jOOQ catalog
and the classpath into, and then answers its verdicts out of by SQL. A *gatherer* is one producer
that fills part of that store; every relation in it is owned by exactly one gatherer, and a
*declared* relation is one carrying a `meta_relation` row naming that owner, the grain its rows are
keyed at, and what one row is. A *registration* is a row of `meta_materialize` that keeps a rule's
text in a view under a `_live` name and moves the canonical name every reader spells onto a table
the capture refills once per pass, so readers meet stored rows instead of re-evaluating the rule.

`MetaDeclarationGateTest.aDeclaredViewReadsOnlyWhatItsOwnerMay` is the one gate that reads bodies. It
walks each declared view's stored definition and fails the build where the view reads a declared
relation owned by a gatherer its own owner has no `meta_gatherer_dependency` edge to. What it walks
is selected by joining the census `meta_relation_family` and filtering `relation_type` to `VIEW`, and
a registered target is a table, so a declared relation leaves that population on the day somebody
registers it. The text of its rule is still in the store, in the `_live` view beside it that no
`meta_relation` row names. Registering a relation therefore removes it from the only check that reads
what it reads, which inverts what the register is for: a registration is meant to be invisible to
consumers, not to the gates.

When this item lands, the census answers which relation states a given relation's rule, resolving a
registered target to its source view, and the gate walks that. It reads the rule wherever the rule is
stated, and `relation_type = 'VIEW'` gives way to a column that says outright whether there is a body
to walk, which is all that filter was ever standing in for. A contributor who
registers a declared relation keeps every gate they had, and the gate's reach grows with the
declaration roster the way its javadoc already claims rather than shrinking each time the register
grows.

**What this is worth today, stated plainly.** Nothing is misfiled. The 62 declared relations and the
20 registered targets are disjoint sets, so the walked population does not move on the shipped store
and the seeded detection case is the whole demonstration. The bite is also narrower than the
register's size suggests: all seven declared `intent_` relations are owned by `derivation`, the
gatherer that runs last, and `meta_gatherer_dependency` gives `derivation` an edge to each of the six
corpus gatherers, so a derivation-owned rule may read anything and this gate passes it whatever its
body says. Of the seven declared views the gate walks today, exactly one has an owner whose set is
narrow enough to fail: `graphql_element_field`, owned by `sdl`, which declares no dependency edge at
all. Where the hole will bite is the same place, a relation owned by a corpus gatherer and
materialized. That is why this is an unenforced invariant at priority 3 rather than a bug, and also
why it is worth closing before it is load-bearing: a gate that has stopped looking reports nothing,
so the day the hole costs something is not a day anybody finds out.

**Which clause of the ownership rule this takes, and which it declines.** The rule decides *which*
gatherer owns a rule by expanding a candidate "through every `intent_` relation it names until only
captured relations are left, reading a materialized target as its rule so that a registration cannot
hide a crossing underneath it", and then counting the families those captured relations sit in, one
family being a misfiling. This item takes the middle clause and leaves the other two standing. It
does not count families: the fact model's own *Enforced by* paragraph records that census as
unenforced and owed to the item that drains the declaration roster. It does not expand through
undeclared intermediates either: a declared view reading an undeclared view that reaches another
family stays invisible, because the gate passes over a read no `meta_relation` row owns. Both of
those are one-hop blind spots of the same rule, so it is fair to ask why this one and not them. The
answer is that a registered pair is one relation's body under two names, which the register already
states and which therefore costs a resolution rather than a redesign, while an undeclared
intermediate is a reach question the declaration roster answers as it drains.

## Implementation

**The census states which relation holds a relation's rule.** `meta_relation_family`, the one
relational answer to which family a relation belongs to, gains a column: `rule_relation_name`, the
relation whose stored definition states this relation's rule. That is the relation itself for a
view, its `meta_materialize.source_view_name` for a registered target, and null for a captured base
table, which states no rule of its own. One `LEFT JOIN` onto the register and one `CASE` in the
census body, plus the `COMMENT ON COLUMN` that `FactSchemaGateTest`'s column-comment gate requires.
Nothing else about the census moves, and no reader of it is disturbed: `StoreCatalog`, `StoreProse`
and the five gates that read the view all name the columns they project, none of them selecting
`*`.

Stating it there rather than assembling it per reader is the point of the change as much as the gate
is. The DDL already predicted this reader, in `meta_family_bridge.relation_name`'s own comment: "A
gate closing this roster against what a view's stored definition reads therefore has to resolve such
a row through `meta_materialize` to the source view first." Two more spellings of the same map
already stand in `MaterializeDependencies`, which builds a target-to-source-view map inline in both
`populate` and `registrationsReachedByView` because it is mid-walk when it needs one. A third
assembled inside a gate would be the fourth hand-derived reading of a fact the store can simply
carry. It also converts the predicate this item is about: "has a body", which `relation_type = 'VIEW'`
was standing in for, becomes the nullability of a column rather than a proxy each reader re-derives.

**The gate reads the column.** `MetaDeclarationGateTest.viewOffenders` becomes `ruleOffenders`, and
gets shorter than it is now. Its declared-relation query keeps the join onto the census, projects
`RULE_RELATION_NAME` where it projected nothing, and filters that `IS NOT NULL` where it filtered
`RELATION_TYPE = 'VIEW'`; the relation it hands `ViewReferences.relationsReadBy` is the projected one
rather than the declared one. No register read, no map, no new import. The null filter is
load-bearing rather than tidy: `ViewReferences.readBy` throws `IllegalStateException` on a relation
the catalog holds no stored definition for, and 55 of the 62 declared relations are captured base
tables with no definition to hold. The offender line names both relations where they differ, the
canonical name a reader spells and the relation whose text was walked, since after this change those
are no longer one string.

**The rename sweeps three prose sites in the same commit.** The case, its `@DisplayName` and the
helper move from "view" to "rule" wording, because the population is now declared relations whose
rule is stated somewhere and being a view is one way into it rather than the definition of it. The
name `aDeclaredViewReadsOnlyWhatItsOwnerMay` is cited outside the test in three places, none of them
caught by a build gate (the javadoc reference gate reads Java, and the comment-echo gate binds table
comments rather than column comments): the ownership rule paragraph in
`docs/architecture/explanation/fact-model.adoc`, that section's *Enforced by* paragraph, which also
says the gate "walks each declared view's stored definition" and now walks the definition that states
each declared relation's rule, and `meta_relation.owner_name`'s column comment, which says "a
declared view may read only relations its owner owns or declares a dependency on (gated)".

**The gate's javadoc discloses its own reach.** It states the resolution, points at the ownership
rule the fact model page already carries, and says plainly that the resolved half of the population
is empty on the shipped store today, no declared relation being a registered target, and what would
make it non-empty. A gate that has widened onto nothing should say so where the next reader meets
it, rather than leave the widening looking like coverage.

Reads are not resolved, which is a decision rather than an omission. No view body in the DDL names a
`_live` relation: the register's own column comment calls such a read a performance bug, and
`report-inline-multiplicity` in the roadmap-tool run is what finds one. Every read a walked body
makes is therefore already spelled with the canonical name, which is the name `meta_relation`
declares and the name the owner lookup wants. A read of a `_live` name, were one written, would miss
the owner map and pass in silence, the same silence every undeclared read gets today.

This item is not sequenced behind R939, but it does collide with it textually and the implementer
should expect to rebase. That item subtracts the register from the undeclared roster so a `_live`
view needs no declaration; before it, those views sit on the frozen roster instead, and either way
they are undeclared, which is all this gate's owner lookup cares about. What overlaps is the file:
R939 edits `theUndeclaredRosterOnlyShrinks` and appends its own arm to
`theGatesDetectWhatTheyClaimTo` in the same class this item edits. Whichever lands second rebases
over the other, and neither touches the other's lines beyond the class javadoc. R939 also states its
exemption as a register read of its own, `SELECT source_view_name FROM meta_materialize`, which the
census column here could source instead (a source view is a relation some other relation's
`rule_relation_name` points at). That is left alone deliberately: R939 is ahead in the pipeline and
re-sourcing a signed-off query from behind is how two items acquire a dependency neither needs.

**One consequence a future declarer meets, named so it is not mistaken for a regression this
introduces.** A declared relation that becomes a registered target also enters
`aDeclaredTableKeyMatchesItsGrain`'s `BASE TABLE` population, which requires the primary key to spell
the grain's key shape, and many registered targets carry no primary key at all. That demand arrives
from registering a declared relation rather than from this change, and it is arguably the gate
working: a target holds exactly the rows its rule computes, so its key is that grain expressed as
columns or the grain is wrong. Nothing here suppresses it.

## Tests

The census column is closed against the register it projects, in
`MaterializeRegistryGateTest` beside "every registered source is a view and every registered target
is a table", which is the case this one is the projection of. Both directions on the booted store:
every registration's target has that registration's source view as its `rule_relation_name`, and
every relation whose `rule_relation_name` is neither itself nor null is a registered target. A
relation whose rule is itself is a view and one with no rule is a base table, which is the same
claim `relation_type` makes and is what lets the gate stop reading `relation_type`.

The gate itself is held by `theGatesDetectWhatTheyClaimTo`, the seeded case that proves each gate
detects its own violation rather than passing because it never ran. Two arms are appended. Both run
on relations the booted store really has; only the `meta_relation` rows are the test's.

- **A crossing behind a registration is an offender.** Declare `intent_spelled_table`, which is a
  registered target, owned by `compile`, and keep `sql_table` declared under `catalog` as the case
  already has it. `intent_spelled_table_live` reads `sql_table`, `graphitron_spelled_reference_entry`
  and `store_graph_source`, and `compile` has no `meta_gatherer_dependency` row at all, so the
  resolved walk finds the crossing and the gate names it. Inserting the `compile` to `catalog` edge
  clears it, the same shape the existing ownership arm uses for its own pair. Against the unresolved
  gate the first assertion passes vacuously, which is this item's defect written as a test.
- **A declared captured relation is skipped, not walked.** With `sql_table` declared and
  unregistered, its `rule_relation_name` is null and `ruleOffenders` returns rather than throwing.
  That is what the null filter buys, and it is asserted rather than left to the other arms, which
  cover it only incidentally by calling the gate while `sql_table` happens to be declared.

The arm names one shipped registration rather than discovering one, on the same terms the case
already names `sql_table` and `meta_relation_family`: if that registration is ever retired the arm
fails loudly and is re-pointed, which is the right cost for a case whose whole job is to be specific.

One thing the seeded arm cannot show, said here so a green build is not read as saying it. The case
calls each gate's helper in isolation, so the seeded registered pair meets the ownership gate without
ever meeting the key gate beside it. What a declared registered target costs in practice, the two
gates together, only appears when a real declaration lands, and that is the roster item's ground
rather than this one's.

Nothing else moves. The echo, corpus, key and roster cases keep their populations, and the widened
gate reports nothing new on the shipped store, no declared relation being registered.

Verification is the full install, and the DDL edit is why it is not optional here: the census is read
from the `graphitron` module too, by `FactSchemaGateTest` and `FactCaptureAgreementTest`, and
`graphitron-model`'s jOOQ codegen regenerates `Tables.META_RELATION_FAMILY` off a store booted from
the edited schema, so a scoped run proves less than usual. The inner loop while writing is
`mvn test -pl :graphitron-model -Dtest=MetaDeclarationGateTest`, which boots the in-memory store the
case runs on; `mvn install -pl :graphitron-model -amd` rebuilds the modules that read the census once
the DDL has moved.

## Other solutions we've considered

**Resolving inside the gate instead of in the census.** The smaller diff, and the first shape this
plan had: build the target-to-source-view map in `ruleOffenders` from
`Materializations.registrations(dsl)`, the register reader every other gate in this module's test
tier already uses, keep a relation-to-kind map beside it, and touch no DDL at all. Declined because
it spells a fourth time a fact the store can carry once, and because the two predicates the gate
needs, "what states this relation's rule" and "does it have a body", are properties of a relation
rather than questions this one gate is asking. The census is where the tree already puts that kind of
answer, and `meta_family_bridge`'s comment already names a second gate that will want it. The cost of
declining is real and is the plan's to carry: this item now edits shipped DDL for an invariant that
binds on nothing today.

**A relation of its own rather than a column on the census.** Cleaner on the axis argument, since
which relation states a rule is not a question about families. Declined on what a new relation costs
in this schema: it would be observed, on no frozen roster, and therefore owed a `meta_relation` row,
a minted grain and a comment inside the 601-character ceiling, all for a two-column projection of a
register that already exists. A column on the census costs a `COMMENT ON COLUMN`.

**Buying real reach now by declaring one registered pair.** It would make the widening bind on
shipped rows instead of on seeded ones. Declined because the constraint it runs into is not this
item's to move: `meta_relation`'s two `CHECK`s cap a declared relation's visible comment at 601
characters, registered targets carry multi-paragraph rule arguments (3436 characters on
`intent_spelled_table`), and R939 already records where that question belongs, with whichever item
drains the undeclared roster. Declaring a pair would also owe the primary key the key gate then
demands. This item closes the hole; the roster item fills the population.

**Dropping the `VIEW` filter outright,** which is the shortest reading of the Backlog note. It throws
`IllegalStateException` on the first declared base table, and 55 of the 62 declared relations are
base tables. The filter is not wrong, it is applied to the wrong relation.

**Making the walk transitive through undeclared intermediate views.** A declared view reading an
undeclared view that reads across families is also invisible to this gate, and the fix looks similar.
It is a different hole: the ownership rule is about the relation's own body, and a registered pair is
one body under two names rather than a chain of two relations. Transitivity changes what the gate
means and how it reports, and lands better once the roster has drained enough that an undeclared
intermediate is the exception.

## Provenance

Surfaced while specifying R939, which needed the twenty-first registration and found that a
registered source view is exempt from declaration by construction. That item takes the exemption as
data, subtracting `meta_materialize.source_view_name` from the declaration domain, and deliberately
does not widen this gate. The general fix is this item's, and R939's guard section names it as the
one thing its register subtraction does not fix.
