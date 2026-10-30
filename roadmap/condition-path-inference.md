---
id: R1138
title: "A path no key states is inferred from the one condition method taking its two tables"
status: Backlog
bucket: feature
priority: 5
theme: classification-model
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# A path no key states is inferred from the one condition method taking its two tables

## Goal

Where no foreign key joins the two tables a path field connects, and the author writes no
`@reference` or `@referenceFor`, graphitron takes the path from the one condition method whose two
table parameters take those tables, the way it already takes the one foreign key between them. A
schema over views with no keys between them, which today needs a `{condition:}` element on every such
field, then states only what is ambiguous.

kreg is the case that raised it. `Kompetanseoppnaelse` is an interface implemented by three types
over three views of one base table, with no foreign keys between them, so every hop is a condition.
Each `barn` field writes `@referenceFor(type: ..., path: [{condition: barnCondition}])` once per
implementation, nine applications across three fields, all naming the same method, whose nine
overloads `barnCondition(VKoX parent, VKoY child)` already say which pair each joins:

```graphql
type Programomraade implements Kompetanseoppnaelse @table(name: "V_KO_PROGRAMOMRAADE") {
    barn: [Kompetanseoppnaelse!]!
        @referenceFor(type: "Utdanningsprogram", path: [{condition: {className: "...KompetanseoppnaelseConditions", method: "barnCondition"}}])
        @referenceFor(type: "Programomraade",    path: [{condition: {className: "...KompetanseoppnaelseConditions", method: "barnCondition"}}])
        @referenceFor(type: "KregFag",           path: [{condition: {className: "...KompetanseoppnaelseConditions", method: "barnCondition"}}])
}
```

With inference, `barn: [Kompetanseoppnaelse!]!` alone would do, provided exactly one condition method
takes each pair.

This is separate from choosing the overload of a method the author names, which the path model in
R876 already requires: there the author has chosen the method and graphitron picks among its
overloads by the path's two tables. Here no method is named at all.

## Open design

* **Where the search looks.** `code_condition_method` already holds every method an author may name
  in `@condition`, with its descriptor. Searching all of it is the simplest rule; searching only
  classes the schema names elsewhere is narrower and more predictable.
* **Stability.** A foreign-key inference becomes ambiguous only when the database changes. A condition
  inference becomes ambiguous when anyone adds a method with the same two table parameters, in any
  class the search covers, so an ordinary Java edit can break a schema that did not change. The
  defect then has to name both methods and the field, and the remedy is writing the path.
* **Intent.** A method taking two tables may be a filter rather than a join. A method name in the
  schema is the author saying it joins; inference loses that. Whether a method opts in (an
  annotation, a naming convention) or the search accepts any match is part of the design.
* **Visibility.** The editor shows the inferred path, as it is meant to show an omitted foreign-key
  `@reference`, so a reader of the schema is not left guessing which method runs.

It builds on R876's path model: a path per pair of source and target tables, a condition chosen by
both, and a defect where a field is left without a path.
