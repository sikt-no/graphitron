# How consumer schemas name and ride keys in `@reference`, and what jOOQ names

Two checks made 2026-10-14 to decide how a path element's direction should be stated: what jOOQ
generates for a key's two directions, and how the seven Graphitron subgraphs in fs-plattform
(`origin/main` at `4d2f79280b`) write their paths. Read from files and generated sources; nothing
built.

## What jOOQ names

jOOQ 3.20.11's code generator was run on a small H2 schema with to-many paths enabled: a
self-reference (`category`), a one-to-many and a one-to-one self-reference (`staff` manager and
successor, the second unique), keys between `staff` and `store` in both directions, and an
ordinary key (`film_actor` to `actor`).

`Keys` holds the forward key and nothing else; an inverse key exists only as the runtime object
`ForeignKey.getInverseKey()`. The one place a direction is named is a table's path methods:

| Table | Path methods | Direction |
|---|---|---|
| `actor` | `filmActor()`, through `FILM_ACTOR_ACTOR_FKEY.getInverseKey()` | inverse |
| `film_actor` | `actor()` | forward |
| `category` | `category()` | forward; the inverse dropped |
| `staff` | `staffManagerFkey()`, `staffSuccessorFkey()`, `store()` | forward; both inverses dropped |
| `store` | `staff()` | forward; the inverse dropped |

For every self-reference and for the mutual keys jOOQ drops the inbound method and warns
"Ambiguous key name ... Use a custom generator strategy to disambiguate". The fixture catalog turns
to-many paths off for the same reason (`graphitron-sakila-db/pom.xml`). So jOOQ has inverse keys
but no name for one, and the cases a name would matter for are the ones it cannot name.

## How the subgraphs write keys

| Subgraph | Generator | `@reference` | `key` elements | How a key is spelled |
|---|---|---|---|---|
| sis | 9.4.4 | 1,032 | 301 | upper-case; its generator makes SQL names look like constants |
| opptak | 10.0.0-RC39 | 180 | 205 | 175 lower-cased constants, 23 bare SQL names, 6 upper-case constants; 86 schema-qualified |
| tilgangsstyring | 10.0.0-RC41 | 78, and 30 `@referenceFor` | 156 | the constant in lower case, one bare SQL name |
| utdanningsregisteret | 10.0.0-RC25 | 76 | 62 | the constant, e.g. `CAMPUS__CAMPUS_EIER_CAMPUS_FK` |
| kompetanse | 10.0.0-RC28 | 19 | 7 | bare Oracle SQL names, e.g. `FK_GRAD_RAD_INST` |
| kreg | 9.3.0 | 27 | 0 | condition-only paths |
| fskode | 9.3.2 | 1 | 0 | |

Every spelling the resolver accepts is in use, case-insensitively and with and without a schema
qualifier. Nothing anywhere looks like a jOOQ inverse key or path method name. No schema writes
`@reference(path: [])`.

About a fifth of key hops ride a key from its referenced table to its referencing one: 35 of 157
in sis, 34 of 142 in opptak, 9 of 30 in utdanningsregisteret, 31 of 107 in tilgangsstyring, 15 of
those last not the path's last hop. Some are single-valued, the child table being unique on the
key (`URegOrganisasjon.akkreditor`, `FeideBruker.navn`). Between two tables the walk orients every
one of these from where the path stands. Counted heuristically from each type's `@table` and the
key's tables, so approximate.

## Self-references

The catalogs hold 47 self-referencing keys: 19 in sis, 9 in opptak, 3 in utdanningsregisteret, 3
in tilgangsstyring, 6 in fskode, 3 in kompetanse, and 4 in kreg. None is one-to-one, the one
exception being a degenerate fskode key from a column to itself.

Twelve are named in a path, every one the only hop of its path. Eleven are single-valued and
mean the parent: `Organisasjonsenhet.parent`, `EvuKurs.folgerUndervisningForKurs`,
`EvuKursaktivitet.parent`, `Undervisningsaktivitet.parent` and
`Undervisningstimeplan.folgerUndervisningenTil` in sis; `AdmissioOrganisasjon.eier` and
`Kvotetype.plassflytKvotetype` in opptak; `URegOrganisasjon.eierOrganisasjon`,
`URegCampus.eierCampus` and two `eierCampusId` input fields in utdanningsregisteret.

One is a list: `URegOrganisasjonsenhet.overordnetOrganisasjonsenhet` in utdanningsregisteret
(`experimental/organisasjon.graphql:217`), described as the unit above in the hierarchy and
declared `[URegOrganisasjonsenhet]` over
`ORGANISASJONSENHET__ORGANISASJONSENHET_ORGANISASJONSENHET_FK`. The generator orients a
self-reference by the field's cardinality, a list reading the children, so this field returns the
units below it unless the list wrapper is what was meant.

## What it decided

A key rides forward by default, departing the table that declares it, which is the direction its
name and jOOQ's `Keys` constant name; an element says `inverse: true` to ride it against. Between
two tables the walk orients a key from where the path stands, and `inverse:` there is a check. For
a self-reference it replaces the cardinality convention: the eleven single-valued uses mean what
they meant, and `overordnetOrganisasjonsenhet` would return its parent.
