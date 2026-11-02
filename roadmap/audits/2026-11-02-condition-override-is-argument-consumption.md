# `@condition(override:)` is argument consumption

2026-11-02. fs-plattform at `origin/main` `01c43cf6d8` (the remote could not be fetched from the
session, so this is the last fetched head); graphitron at trunk `2f280ec0e`.

## What `override:` does today

Two kinds of predicate meet at a field. A generated one is what graphitron writes when an argument
or input field maps to a column (`film_id = :filmId`). An authored one is a method a `@condition`
names. Authored predicates always apply. `override: true` switches off the generated predicates of
whatever the directive sits on and of everything nested under it: on a field, every argument and
the input fields beneath them; on an argument, that argument and its input fields; on an input
field, that field. `override: false`, the default, ANDs the method with the generated predicates.
Lookup keys are never switched off, and an inner `@condition` under an outer override still applies
(the legacy generator dropped it; `filmsOuterOverrideFilterInput` pins the difference).

The flag is read once, in `ConditionFactVisitor.overrideFlag`, and drives `enclosingOverride`
(`ClassifyContext`, `InputFieldResolver`, `FieldBuilder`), `suppressedByFieldOverride` on
`ArgumentRef`, and the `ConditionOwnedField` / `ConditionOwnedArg` carriers. Three refusals turn on
it: an input field with `override: false` and no matching column (`BuildContext`), mutation input
fields (`MutationInputResolver`, `true` only), and `@nodeId` arguments a condition owns
(`ArgumentRef`, `true` only).

## Census

461 `@condition` applications under `*/src/main/` across the subgraphs: 443 write
`override: true`, 18 omit it, none write `false`.

| Module | `override: true` | omitted |
|---|---|---|
| sis | 305 | 2 |
| opptak | 66 | 12 |
| tilgangsstyring | 36 | 2 |
| utdanningsregisteret | 28 | 2 |
| kompetanse | 4 | 0 |
| fskode | 2 | 0 |
| kreg | 2 | 0 |

The 18 omissions, read from the SDL and the methods' signatures and bodies:

**12 have no generated predicate in scope**, so the flag decides nothing: opptak `kvotesporsmalSvar`,
`arbeidserfaringstyper`, `Sak.gjeldendeGskVedtak`, `kvoteplassering`, `poengListe`,
`vgsDokumenter`, `sakSoknadsalternativer`, `kravelementVurderingerV3`, `Soker.gjeldendeGskVedtak`,
`alleGskVedtak`; sis `fellesundervisning`; utdanningsregisteret `utdanningsmuligheterVedCampus`.

**5 compose on purpose.** The method takes only the table and adds a visibility rule; the
arguments are left to the generated predicates. With `override: true` those arguments would be read
by nothing, which the rewrite refuses.

| Field | The method adds | Left to the generator |
|---|---|---|
| opptak `mineMeldinger(filter:)`, on the argument | not deleted, the signed-in person's | `filter`'s fields |
| opptak `utdanningstilbudForSokere(filter:)` | opptak published and open | `filter` |
| tilgangsstyring `mineSynligeBrukerroller(navneromstyper:)` | in an active assignment the caller sees | `navneromstyper` |
| tilgangsstyring `mineSynligePersonbrukerroller(navneromstyper:)` | the same, for person users | `navneromstyper` |
| utdanningsregisteret `utdanningsmulighetcampuser(filter:)` | `ER_ANNULLERT` false | `filter` |

**1 looks accidental.** sis `emnerGittEmnekoder(eierInstitusjonsnummer: String! @field(name:
"INSTITUSJONSNR_EIER"), emnekoder: … @lookupKey)` names
`EmneConditions.emneInstnrEierInstnrJoin(Emne, String eierInstitusjonsnummer)`, which takes the
argument and maps the test institutions 1234 and 1233 to 185 on `INSTITUSJONSNR`. Under the default
the generated `INSTITUSJONSNR_EIER = :eierInstitusjonsnummer` applies too, which seems to defeat the
mapping. Not confirmed against data.

## The rule

If an argument is used by a call it is consumed, and nothing is generated from it. Precisely:

1. Consuming means the value is bound to one of a called method's parameters, by any directive that
   calls (`@condition`, `@service`, `@tableMethod`, `@externalField`, a routine). Sitting on a field
   that has a method is not consuming.
2. Consuming an input-typed argument consumes everything inside it; consuming one input field leaves
   its siblings to the generator.
3. "Nothing is generated" includes `@lookupKey` and `@orderBy`: a lookup key a call takes is the
   call's, and graphitron does not also join on it. This differs from today, where lookup keys are
   never switched off.
4. Every argument has a consumer, a call or the generator. One with neither is an author error.
   An argument kept for compatibility and deliberately ignored is no exception; the author states
   it, for instance with a call that takes it.

Against the census: the 5 composing sites keep their behaviour (their methods take no argument),
the sis site loses the generated predicate it never asked for, and the 12 are unchanged. For the
443, the rewrite already refuses an argument switched off and read by no method, so wherever a
schema is accepted the rule gives what `override: true` gave; that is the claim the first node
checks, since sis builds with about 60 author errors today and some may be this refusal.

Why not keep the flag with the default flipped: it asks every author to decide in places where, as
in 12 of the 18 here, the choice changes nothing. Why not drop composition: the 5 deliberate sites
are the ordinary visibility-rule-plus-filters shape, and without composition each method would have
to take every filter argument and repeat the predicate graphitron already writes.
