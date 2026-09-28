# The graphitron family's entry residue, 2026-09-28

Measured against the rule that entries belong to the transcription alone: a relation named
`_entry` is the graphitron-ast family's, and no other relation in the family should carry the name.

## The population

Of the 198 relations in the `graphitron_` family, 54 are `graphitron_ast_*_entry` and belong.
**Forty-seven are `graphitron_*_entry` and do not.** Every one of the 54 carries the name correctly;
every one of the 47 is the old decode wearing the transcription's name.

Where the 47 sit in add, flip, subtract:

|  | count |
|---|---|
| An ast twin exists: the add has run, the flip has not | 23 |
| No ast twin: the add has not run | 24 |
| No readers, so subtract needs no flip first | 0 |

**139 view references and 100 Java main sources** read the 47. Not one of them is subtract-ready.

The ten largest by reader count, which is where the flip is dearest:

|  relation | ast twin | views | java |
|---|---|---|---|
| `graphitron_routine_entry` | yes | 9 | 7 |
| `graphitron_field_reference_step_entry` | no | 8 | 5 |
| `graphitron_mutation_entry` | yes | 8 | 5 |
| `graphitron_service_entry` | yes | 9 | 4 |
| `graphitron_argmapping_entry` | no | 7 | 4 |
| `graphitron_field_condition_entry` | yes | 9 | 2 |
| `graphitron_field_reference_entry` | yes | 8 | 2 |
| `graphitron_table_entry` | yes | 6 | 4 |
| `graphitron_external_field_entry` | yes | 5 | 4 |
| `graphitron_argument_condition_entry` | no | 7 | 1 |

## The per-site split underneath it

Nineteen stems are stated at more than one site, covering 41 relations: an `argument_`, a `field_`
and sometimes an `input_field_` variant of one fact. `reference_step_target` exists three times, and
so do `reference_step_target_keyed` and `_keyless`.

That is the defect the directive applications collapse removed from the `graphql_` family by keying
on the coordinate, and it is untreated here. It bears on the order of the work rather than only on
its size: collapsing a triple retires three relations at once, where flipping one at a time retires
one.

## What the store already knew

`SupertypeSignatureGateTest` records 26 groups of relations carrying the same payload under
different keys, most of them in this family. The gate records rather than refuses, which was right
while the roster was short. It only ever gains rows, so what began as a record of decisions taken
has become a list of subtractions deferred.
