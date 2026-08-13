---
id: R658
title: "Name the joined-table base->detail join on @discriminator, not on an inherited field @reference"
status: Backlog
bucket: feature
priority: 2
theme: interface-union
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Name the joined-table base->detail join on @discriminator, not on an inherited field @reference

## Problem

A joined-table inheritance participant (a `@discriminator` implementer whose own `@table` is a
detail table distinct from the discriminated base) has its base->detail identity join inferred, not
declared. `TypeBuilder.resolveJoinedTableParticipant` walks the participant type's fields looking
for one carrying `@reference` whose single-hop FK resolves to the base, takes the first such hop as
the identity join, and rejects the participant when it finds none:

```
Type 'MaskinportenApplikasjon': joined-table participant 'MaskinportenApplikasjon' (detail table
'maskinporten_applikasjon') has no base-resident field carrying @reference to name the base->detail
join; declare one inherited field with @reference back to 'subjekt'. Candidate foreign keys between
the tables: fk_maskinporten_applikasjon_subjekt, fk_maskinporten_applikasjon_endret_av,
fk_maskinporten_applikasjon_ansvarlig, fk_maskinporten_applikasjon_opprettet_av
```

Reported by a consumer. The remedy the message names is the wrong shape, for two reasons the
consumer's own catalog makes concrete.

**The directive is doing two jobs, and only one of them is a field's business.** A parent-`@reference`
on an inherited field declares *that field's residence* (the column lives on the base, resolve it
over there). Pinning *the type's* identity join is a per-participant fact, declared once, and it is
currently a side effect of a field-grained declaration. An author whose detail type has no
base-resident field worth exposing has to invent one purely to name the join, which is the
diagnostic above: nothing is wrong with the schema except that the mechanism has nowhere to put
the fact.

**The inference is ambiguous exactly where real catalogs are.** All four candidate FKs in the
message run from `maskinporten_applikasjon` to `subjekt`: the identity FK plus three audit and
ownership references (`endret_av`, `ansvarlig`, `opprettet_av`). Audit columns pointing back at the
same base are ordinary, so multiple FKs to the base is the common case, not the corner. That makes
the current resolution unsound in the direction that does not produce this error: `sawNonBaseReference`
only fires for a `@reference` resolving to some *other* table, so an author who does declare
`ansvarlig: Subjekt @reference(...)` hands `resolveJoinedTableParticipant` a hop that resolves to
the base, gets picked by first-wins, and then trips the PK=FK single-valued check with a message
about the join not being single-valued. The author's real mistake in that scenario is nothing; the
resolver simply guessed the wrong FK out of four and blamed the schema.

## Sketch

Add a `reference` argument to `@discriminator`, carrying the same path shape the rest of the
directive surface uses (`{key:}` being the disambiguator that matters here), and read the identity
hop off it. Field-grained parent-`@reference` keeps its first job unchanged: residence stays declared
per field, and nothing about the base-resident / detail-resident split moves.

Open questions for the Spec pass:

* Whether the argument is required on every joined-table participant or only when auto-discovery is
  ambiguous. A single FK between detail and base is unambiguous and the message above would not fire
  for it, so a required argument is a build-acceptance change for schemas that work today, and an
  optional one keeps two resolution paths alive.
* What the inherited-field `@reference` route becomes: retired outright, or accepted with the
  directive argument taking precedence. Retiring it is the honest end state (the fact has one home),
  but it breaks the shapes `JoinedTableInheritancePipelineTest` and the sakila `allParties` /
  `allSubjects` fixtures declare today, so the additive-then-cutover technique applies.
* Whether the same argument should serve the `sawNonBaseReference` rejection, which exists only
  because the resolver cannot otherwise tell an identity bridge from an ordinary reference. With the
  join declared, that rejection has nothing left to protect and should retire with it.

## Relationship to R650

Orthogonal, and R650 does not have to wait. R650's fan-out floor needs the base->detail join to be
single-valued, which the PK=FK check on `resolveJoinedTableParticipant`'s hop provides; that check
tests the hop, not where the hop was named, so it survives this redesign unchanged and moves with
the hop's new source. R650 touches this method's javadoc (it misattributes the check to the
validator) and nothing else here.

