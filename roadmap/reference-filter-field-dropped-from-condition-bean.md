---
id: R1071
title: "A filter input field with @field(name:) is dropped from the bean a whole-input @condition receives"
status: Spec
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A filter input field with @field(name:) is dropped from the bean a whole-input @condition receives

## Goal

A filter input field that names its column with `@field(name:)` reaches the Java bean a
whole-input `@condition` method receives, matched to the bean's member by the field's GraphQL
name; and any filter input field or argument whose value a `@condition(override: true)` leaves with
no reader at all fails the build naming it, instead of being silently ignored. Today, on this sis schema:

```graphql
extend input QueryStudenterFilterInput {
  privatEpost: String @field(name: "EMAILADRESSE_PRIVAT") @reference(path: [{table: "PERSON"}])
}
```

read by a condition method taking a bean with a `setPrivatEpost` setter, the generated builder never
sets `privatEpost`, the method sees `null`, and `studenter(filter: {privatEpost: "<an address>"})`
matches every student. After this item the same SDL, unchanged, filters to the one matching student;
and a bean that genuinely has no member for a field the override hands it is a build error rather
than a filter that matches everything.

A *whole-input condition* here is a `@condition` whose method takes the filter input as one
parameter, typed as a consumer-authored bean (a Java record, or a class with a no-arg constructor
and setters) that the generated code builds from the GraphQL input map before calling the method.

## Evidence

Surfaced by the sis v9 against v10 differential test on Oracle (2026-10-19). The field above is read
by `StudentFilterConditions.privatEpostCondition(student, filter.getPrivatEpost())` through the
generated builder `QueryConditions.createQueryStudenterFilterRecord`, which never reads `privatEpost`
into `QueryStudenterFilterRecord`. `studenter(filter: {eierOrganisasjonskode: "1234", privatEpost:
"<an address>"}) { totalCount }` returns 2044 (every student) where v9 returned 1, and a
non-existent address also returns 2044 (v9: 0). No warning at build time. Graphitron 9's
declaration was the `@reference` without `@field`. A scan of every generated bean builder in sis
against introspection found this to be the only dropped input field.

## Diagnosis

`@reference` is incidental; `@field(name:)` is the cause. One directive is read on two axes at
once:

- **The column axis.** The filter input is classified against the consuming field's table, and
  `BuildContext.classifyInputFieldInternal` takes `@field(name:)` as the column name
  (`EMAILADRESSE_PRIVAT`, on `PERSON` via the `@reference` arm). This is what the user manual says
  `@field` means on an input field (`reference/directives/field.adoc`: "Resolves as a column name on
  ... `INPUT_FIELD_DEFINITION`").
- **The bean axis.** The bean is built by the same `InputBeanResolver.buildBean` the `@service`
  path uses (`ServiceCatalog.conditionCarrier`, `ConditionSite.Consuming` arm), and
  `InputBeanResolver.bindingKey` takes `@field(name:)` as the *Java member* name. It looks for a
  setter `setEMAILADRESSE_PRIVAT`, finds none, and `InputBeanResolver.bindJavaBean` skips an
  unmatched scalar field in silence by design. (On a record bean the same miss is loud: `bindRecord`
  direction A/B rejects. The sis bean is a setter class, so it fell through the quiet arm.)

sis cannot route around it. Without `@field` the column axis rejects the field: the `@reference`
arm of `classifyInputFieldInternal` resolves the column whatever the override says, and fails with
"no column 'privatEpost' reachable via @reference path". With `@field` the bean drops it. No
declaration satisfies both readings.

The condition manual already states the principle the fix follows: "The bean belongs to the
parameter. It does not make the input type class-backed, so the input's classification ... [is]
what [it] would be without the condition" (`reference/directives/condition.adoc`, Input-object
parameters). The input's classification is table-bound, and on a table-bound input `@field` names a
column. The bean, which is parameter-local, has no claim on the directive.

The silent drop has a second, independent enabler. Under an override, the generator admits a filter
field whose implicit predicate it suppresses on the assumption that the overriding condition reads
it (`FieldBuilder.walkInputFieldConditions`, the `UnboundField` arm's "admitted when an enclosing
`@condition(override: true)` resolves it", and the `!enclosingOverride` guards on the
column-backed arms). Nothing checks that assumption. A bean is the one carrier whose read set is
both known and partial, so it is where the assumption fails without a trace.

## Implementation

Two changes. The first fixes sis; the second turns every future instance of this class of bug into
a build error. Both land together: the first changes which member a `@field` field binds at a
condition site, which on a setter bean could otherwise turn a working schema into a new silent drop,
and under `override: true` the second is what makes that loud. Under `override: false` the second
does not reach, which is why the first carries its own before/after survey (the last paragraph of
"A suppressed filter slot must have a reader").

### Bean members at a condition site match by GraphQL name

`InputBeanResolver.buildBean` gains the binding axis from its caller, threaded through
`buildInputBean` / `buildInputBeanBody` into `indexSdlFields`, `groupingGate`, and the nested
`buildInputBean` call in `bindField`, so a nested bean inherits it:

- **`@service` site** (`InputBeanResolver.enrich`): unchanged. `@field(name:)` names the member, as
  `reference/directives/field.adoc` and `how-to/handle-services.adoc` document for a service input.
  The `SERVICE_MUTATION_FIELD_INPUT_BEAN_FIELD_RENAMED_RECORD` / `..._RENAMED_JAVABEAN` cases in
  `GraphitronSchemaBuilderTest` fence this.
- **Condition site** (`ServiceCatalog.conditionCarrier`, `ConditionSite.Consuming`): the binding key
  is the SDL field name. `@field(name:)` is the column axis's, and is not read here. Consequences
  that follow from the same rule and need no separate code: `groupingGate`'s `@field` arm (a
  directive on a nested group is a member-binding claim) does not apply at a condition site, while
  its `@nodeId` arm stays; and the two-fields-one-member collision in `indexSdlFields` can no longer
  be produced by `@field` there.

Shape the axis as a small closed type (an enum or a sealed pair), not a boolean, so each site names
which axis it is on; `bindingKey` becomes a function of the axis. The record arm's direction-B
message names "a record-backed @service input"; make it name the site it is on.

The rule rests on one claim, which the implementer checks and the reviewer can check against it:
every `ConditionSite.Consuming` slot's input is classified on the column axis by
`FieldBuilder.classifyArguments` → `InputFieldResolver.resolve`, so `@field` there always has a column
reading. (A bean at `ConditionSite.InputField` is already refused by `conditionCarrier` as
`WireCoercionError.ConditionBeanAtInputField`, so `Consuming` is the only bean-building condition
site.) If a `Consuming` slot turns out not to be column-classified, `@field` there has no column
reading to defer to; raise it on this item rather than picking an axis for it.

### A suppressed filter slot must have a reader

The override cascade's admission ("the overriding condition reads this") becomes a checked claim.
The check has two halves, recorded where each fact is decided and joined in
`FieldBuilder.projectForFilter`, the only caller of `projectFilters` and the one place where both the
classified argument tree and every resolved condition (the argument-level ones inside the filters,
and the field-level `fieldCondition`) are in hand.

**Recording the suppressed slots.** Each place that decides to suppress an implicit predicate
appends a suppressed-occurrence record (occurrence path, the slot's own definition location, the
slot's coordinate) to an out-list threaded beside `columnSlots`, at the same point and for the same
reason `columnSlots` is filled ahead of the emission guards: one decision, made once, read by
whoever needs it. No second walk recomputes which slots an override covers, because
`enclosingOverride` is assembled in several places (field override OR argument override in
`projectFilters`, OR'd again with each nesting field's own override in `walkInputFieldConditions`,
and a list level's own `suppressed` in `walkListLevel`), and a copy of that would drift. The
recording sites:

- `walkInputFieldConditions`: a `ColumnBackedField` or `ColumnBackedReferenceField` that has no
  `@condition` of its own, is not lookup-bound, and is under an enclosing override; an
  `UnboundField` with no `@condition` of its own under an enclosing override (the branch that today
  is admitted with no further question).
- `walkListLevel`: a suppressed list level, recorded once as the list field. Its element fields are
  compared as rows (`collectCells`), so the list is the unit a reader has to reach.
- `projectFilters`: a top-level `ColumnBackedArg` (and its reference and composite siblings, the
  arms reading `suppressedByFieldOverride()`) suppressed by the field-level override with no
  argument-level `@condition`. A scalar argument a field-level override leaves unread is the same
  bug as a filter field it leaves unread, and recording it at the same decision costs nothing extra.

A slot with an authored `@condition` of its own is never recorded. The author attached a method to
that definition, which is the `ConditionOwnedField` doctrine: that method owns the slot, whatever it
does with the value. A field-level override is different in kind: it speaks for the whole consuming
field, and that it reads each slot beneath it is an inference, which is what this check verifies.

**Computing what the conditions read.** Every `ParamSource.Arg` parameter of every condition in the
consuming field's final filter list contributes a read set: the field-level condition, the
argument-level ones, and the rewrapped input-field ones (a nesting field's own `override: true`
suppresses the fields beneath it, and its method is a candidate reader of them like any other). Each
read set is decided by an exhaustive switch over its leaf extraction
(unwrapped from `NestedInputField`), so a new `CallSiteExtraction` arm fails to compile until someone
decides what it covers:

- every arm except `InputBean` covers everything at and below its absolute path (`Direct` map
  pass-through, `EnumValueOf`, `JooqConvert`, `NodeIdDecodeKeys` installed by
  `ConditionResolver.installNodeIdDecode`, and the rest): the value is handed over whole and what the
  method does with it is opaque;
- `InputBean` covers exactly the paths its `FieldBinding` tree reads: each binding's access path
  appended to the bean's absolute path, descending nested-bean bindings (list-shaped ones included,
  the only way a bean reaches into a list of input objects, since a dotted path through a list is
  already refused) and following the access path a hoisted grouping leaf carries;
- arms that cannot occur at a condition site (`JooqRecord`, refused by `conditionCarrier`) are
  invariant failures, not silent non-reads.

The absolute path of a parameter's read is not `ParamSource.Arg.path()` after
`ConditionResolver.rewrapForNested`: that stays relative to the input-field site, and only the
`NestedInputField(outerArgName, path)` wrapper carries the path from the argument. Add one accessor
that returns the absolute read path (outer argument plus segments) for a `ParamSource.Arg`, and move
`conditionReadThroughList` onto it, so the two checks cannot read paths two ways.

A recorded slot is read when some read set covers its occurrence path.

**The rejection** for an unread slot is minted the way `FieldBuilder.mintCascadeVerdict` mints its
mirror case (an unbound field with *no* override): keyed by the occurrence path in the same
`<Type>.<field>(<argument>)/.../<leaf>` serialization, located at the slot's own definition, with
the consuming field keeping one consequence rejection beside it. Make it a typed arm of
`Rejection.AuthorError` carrying the occurrence path and, when a bean is the parameter that reached
the slot's container and missed it, the bean class and the member name it looked for; the remedies
in its message are add the member, bind the slot another way, or drop the override so the implicit
predicate reads it. A typed arm puts the payload where the LSP and the glossary can read it, and
`SealedHierarchyDocCoverageTest` then makes the glossary entry mandatory rather than optional.

The check lives in the walk, not the store, for a stated reason: bean binding sets come from
reflection and the store has no relation for them yet (`code_condition_method` holds membership
only). Keying the fact by occurrence path is what lets it move into the store later without
changing what it says.

**Newly rejected shapes.** The check fires where no bean is involved too: a field-level
`override: true` whose method binds none of a filter argument's fields leaves them all unread, and
today that is admitted. That is the same bug (a client filter that changes nothing) and the
rejection is correct there as well. Survey the corpus, `GraphitronSchemaBuilderTest`, and the sakila
schemas for shapes the check newly rejects before deciding any fixture change: each is either a real
silent drop, fixed in the fixture, or a reader this rule does not model, in which case the rule grows
the arm, never an exemption.

**`override: false` is outside the check, so the first change gets its own survey.** Under
`override: false` the implicit predicate reads every column-bound field, nothing is recorded, and
the check never runs. A condition-site setter bean whose member was named after the `@field` value
(column-shaped member names are plausible in beans carried over from Graphitron 9) binds today and,
after the first change, is skipped without a message, with the method now seeing `null`. Before the
first change lands, list every condition-site bean in the corpus, the sakila schemas and the pipeline
fixtures whose input has a `@field`-bearing field, and compare its binding set before and after; a
binding that disappears is decided on this item explicitly, not absorbed. (The record arm makes the
same change loud through `bindRecord`'s bijection, so only setter beans need the comparison.)

### User manual

- `reference/directives/condition.adoc`, Input-object parameters: the bean's members match the input
  fields by their GraphQL names; on an input a condition reads, `@field(name:)` names the column, as
  everywhere on a filter input, so it does not rename the member. The "same bean, under the same
  rules" sentence gains that one exception.
- Same page, the `override` bullets: under `override: true` every argument and input field whose
  implicit predicate the override suppresses must be read by some condition parameter, and one none
  reads (a bean with no member for it, or a method that binds nothing that reaches it) fails the
  build naming it. A slot with its own `@condition` is that method's, and is not checked.
- `how-to/handle-services.adoc`, the input-bean section's pointer to `@field` for renaming members:
  say the rename applies to a `@service` input.
- `reference/diagnostics-glossary.adoc`: an entry for the new typed rejection, which
  `SealedHierarchyDocCoverageTest` requires once the arm exists.

## Tests

- **Pipeline, bean binding** (`ConditionParameterCarrierPipelineTest`): a filter input field
  `heading: String @field(name: "title")` on `Film`, read by a field-level `override: true` condition
  whose setter bean has `setHeading`; assert a `JAVA_BEAN` binding with map key `heading` and member
  `heading`. The record twin (`record HeadingFilter(String heading)`), which today fails direction A.
  A `@field` + `@reference` field (a column on `language` reached from `film`) bound the same way,
  the sis shape.
- **Pipeline, reader check**, same class or a sibling, asserting on the typed rejection's payload
  (occurrence path, bean class, member key) rather than message text: under field-level
  `override: true`, a setter bean with no member for a column-backed field rejects naming the field
  and the bean; the same bean under `override: false` builds (the implicit predicate reads the
  field); the same input read through a `Map<String, Object>` parameter builds; a method that binds a
  dotted path to the field builds; a field-level `override: true` method that binds nothing from the
  argument rejects, as does one that leaves a top-level scalar argument unread; a field with its own
  `@condition` that ignores its value builds. Path and shape coverage: a nested-bean case and a
  hoisted grouping-input case (the bean-tree walk past depth one), a list-of-input filter read by a
  bean's list-shaped nested binding and the same list left unread, and a nested input-field
  condition whose relative `ParamSource.Arg.path()` differs from its absolute read path (the case
  that fails if the check reads the wrong path).
- **Fence:** the `@service` `RENAMED_RECORD` / `RENAMED_JAVABEAN` cases stay green unmodified.
- **Execution** (`graphitron-sakila-example`, `GraphQLQueryTest`, beside the existing
  `filmsByTitleBean` cases): a query field with a filter input carrying `@field(name:)` plus
  `@reference`, read by a setter-bean `@condition(override: true)` in
  `graphitron-sakila-service`'s `BeanConditionFixtures`. Asserting the sis contrast directly: a
  value that matches narrows the result to the matching rows, and a value that matches nothing
  returns none. (`customer` → `address.district` through `customer_address_id_fkey`, as the `@field`
  manual page already uses, avoids `language.name`'s `char(20)` padding.) This is the case that
  fails today with every row returned, so it is the acceptance evidence for the first half of the
  goal; the reader-check pipeline cases are the evidence for the second.

## Other solutions we've considered

- **Fall back to the GraphQL name when the `@field` value names no member.** Smallest diff, and sis
  would pass. It gives one directive two readings chosen per field by what happens to exist on the
  bean, so renaming a setter silently changes which input field feeds it.
- **Read `@field` as the member name only when the column axis left the field unbound.** Admits the
  narrow case where an author renamed a bean member with `@field` on a filter field that resolves no
  column under an override. The reading would then depend on the database: adding a column with that
  name rebinds the bean. The member-rename use this gives up is undocumented at a condition site,
  and the reader check turns any schema relying on it into a build error naming the field.
- **Make every condition bean total** (the record arm's bijection, for setter beans too). Catches
  the sis drop, but rejects a bean that leaves out a field the implicit predicate still reads under
  `override: false`, a legitimate shape, and still leaves sis unable to declare the field at all.
- **A lint warning instead of a rejection** for the unread field, the shape R695 proposes for
  `@service` setter beans. Under an override an unread field is not a style question: the client's
  filter is accepted and changes nothing, which is a wrong answer. R695 stays as filed; its
  `@service` case has no override and no column axis, and this item does not touch it.
