# The defect model, designed as a whole

Written 2026-10-08 for the R876 node "The defect model is designed as a whole", the first step of
"Every coordinate carries R333's facts, and a constraint a fact breaks is a located defect". This
note fixes the shape before any DDL is written.

R333 is the model it serves. A coordinate carries small independent facts, each its own functional
dependency, and the leaf records are a denormalized view over them; classification is claims keyed by
coordinate and classifier, and a single classification is at most a view a consumer reduces them to.
Its stage vocabulary settles where defects sit: classification gathers, and validation derives, a
violated constraint becoming a located violation fact while the claims stay. So the defect model is
the constraints on R333's facts and the located violations of them. Nothing here stores a
classification.

## What stands today

Six families, each in a vocabulary of its own, unioned for reading by the `diagnostic` view.

| family | keyed by | vocabulary | arrives |
|---|---|---|---|
| `graphql_schema_problem` | graph, stage, ordinal | graphql-java's error class and prose | the capture stages write it |
| `graphitron_entry_defect`, `graphitron_defect_type` | written position and code | code, severity, fault, statement | a rule view derives it |
| `rejection_validation_error` | graph, ordinal | `Rejection` kind and variant, prose | transcribed from the walk |
| `intent_authored_claim_rejection`, `intent_field_unlowerable_ordering_rejection` | graph, ordinal | `Rejection` kind and variant, prose | minted in Java after capture |
| five `intent_*_defect` views | their own columns | a verdict column each | derived |
| `lint_finding`, `build_warning_no_rule` | graph, ordinal | rule id, source, severity | transcribed |

What is wrong with it, beyond there being six:

- The vocabularies overlap. `Rejection`'s `DEFERRED` is a defect type's `GENERATOR`, and its
  `INVALID_SCHEMA` against `AUTHOR_ERROR` is a distinction the defect type does not draw.
- Most rules have no name. Of the walk's rejection calls, 355 are `Rejection.structural` with a
  sentence, against 110 naming a variant or kind; the rule exists only as prose.
- An ordinal is not an identity. A transcribed row has no key a sweep, a join or a second reading
  can use. Only entry defects are keyed by what they are about.
- Prose is stored as a fact. The residues carry rendered messages, where an entry defect carries a
  code whose statement is declared once and a detail the renderer places.

## The model

### One vocabulary of rules

`graphitron_defect_type` is the vocabulary every store-derived defect is written in: one row per
rule, carrying its code, severity, basis and statement. A walk rule moving to the store becomes a
row here, and its message becomes the statement and a detail.

`basis` replaces `fault` and says what the verdict rests on, which is also what the author does
about it:

- `SPECIFICATION`: the GraphQL specification forbids it; make it valid GraphQL.
- `GRAPHITRON`: graphitron's directive contract gives it no meaning; change the directives. The three
  codes that say `GENERATOR` today are this: each is a well-formed schema asking for a combination the
  directives do not mean, and "generator" only named where the limit lives in the code.
- `UNSUPPORTED`: meaningful under both, and graphitron does not do it yet; choose another construct or
  wait. `Rejection`'s `DEFERRED`, and what the `diagnostic` view's `actionable` already tells apart.

The twenty codes that say `AUTHOR` today are `GRAPHITRON`, and none of the twenty-three is
`SPECIFICATION`: a schema the specification forbids is refused by graphql-java before any rule reads
it, which is `graphql_schema_problem`'s verdict.

Nothing `validate` reports is the generator's fault: the generator runs only on a schema that
validates, so every verdict is about the schema.

What a rule constrains is not a column. It is the relations its arm reads, which most arms read
several of, and the statement says it in prose; a label naming one would choose arbitrarily, and an
arm leaves a coordinate whose violation is upstream alone by its own joins, not by a label. Grouping
rules by fact is an audit's view, `2026-10-08-fact-census.md` being one.

### One relation of located violations

`graphitron_entry_defect` stays the one relation a defect is a row of, keyed by the written position
and the code, the position being where an author edits. The position is an AST entry's key, so the
relation references `graphql_ast_entry`, the supertype every entry shares its key with: a
`graphitron_ast` entry's key is the key of the directive entry written at the same position, and a
defect written on no graphitron directive, a field declaration missing a total fact or a type name
colliding at its declaration, has a position there too. A minted coordinate's defect sits at the
macro application that minted it.

What the position concerns is not stored beside it. The element is a fixed number of hops away, and
`graphitron_entry_defect_site` takes them: a declaration is its own element, a directive
application's element is its parent, and an applied argument's or a value's is reached through the
node holding it and the application above that. So an arm keeps the precise position, the path
element that broke or the code reference that resolved to nothing, and an editor points there.
Referencing the entry also gives the relation its sweep: a position the document stops writing takes
its defects with it. The detail stays one value; a rule needing several states them as rows of a
child relation, never as a list folded into a column.

### Totality, and why consequential rejections go

Some facts are total over the coordinates they apply to: every field has a `source` and a `target`,
every read has an `accessor`. A coordinate missing a total fact is a violation of that fact's
totality, stated by a rule like any other, and never a coordinate the store fails to mention. A gate
over the fixtures holds it: every coordinate carries each total fact, or a violation on that fact or
on a fact it is derived from.

That is what retires the consequential rejections. A field whose parent type broke a constraint has
no `source` to judge, and the arm judging `source` reads only coordinates whose parent's facts hold:
the violation sits once, on the fact that broke. "Parent type is unclassified" is no rule.

### Vocabularies in code

A closed vocabulary is a `CHECK` on a `VARCHAR`, bound to a hand-written enum by a forced type where
writers need the constants, on `EntryKind`'s precedent, with a gate holding the two equal. jOOQ's
synthetic enums would derive the enum from rows, and they are a commercial feature: the open-source
codegen logs "Synthetic enums are a commercial only feature" and generates nothing, measured on
3.20.11.

### Sized by fact

`2026-10-08-fact-census.md` counts the walk's 465 rejection calls against the facts: 411 are
constraints on one, in about 95 rules, of which about 15 have a code. `operation` holds 170 of them,
`joinPath` 55 and `node` 53. 54 belong to no fact; twelve are consequential and go with the walk, and
three groups, `@scalarType` binding, tenancy and federation, 25 sites, are directives whose effect
R333's catalog gives no fact. They are rules on stored relations like any other; the catalog's
prose not listing them is a gap in R333, not in this model.

## What stays outside

- Lint stays its own family for now: its rules are the engine's, and it has its own suppression.
- `graphql_schema_problem` stays: it is graphql-java's verdict on reading and assembling, keyed by
  the stage that raised it, and no graphitron rule restates it.
- `javac_diagnostic` judges the emitted code, not the schema.

## How the residue drains

A walk rule moves by becoming a code and an arm of `graphitron_entry_defect_rule` reading the
relations that state the fact it constrains. The residue families shrink as their rules move:
`rejection_validation_error` loses the rows the walk stops minting, and the `intent_*_rejection`
tables and `intent_*_defect` views go when their verdicts are codes. The `diagnostic` view reads the union until each arm is empty, and then loses the arm.
