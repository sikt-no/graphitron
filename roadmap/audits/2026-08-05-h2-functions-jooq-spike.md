# H2 functions and procedures under jOOQ: what the derivation layer can lean on

A working document, not a roadmap item. It lives in `audits/` so the roadmap-tool ignores it.
It records a spike commissioned during R589/R595 design dialogue, answering whether derivations
in the fact base can be expressed as H2 functions (table-valued or scalar) driven through jOOQ.
The spike code is disposable and is not committed anywhere. The sibling record
`2026-08-05-fact-base-h2-spike.md` grounds the store itself; this one grounds the function
surface on top of it.

## The question

The strangler plan derives semantic relations (claims, slot facts, reachability, demand) from
the base facts R595 captures. Two hopes motivated the spike: that table-valued functions could
express parameterized derivations (`path_steps(value_sdl)` as a relation), and that a
parse-boundary bridge function could make graphql-java's literal decoding available *inside*
SQL, so slot-fact derivations stay `INSERT..SELECT` statements instead of Java loops. H2's
function machinery is nonstandard (`CREATE ALIAS` bound to static Java methods), so nothing
here was safe to assume.

## Setup

H2 2.3.232, jOOQ 3.20.11 (open-source edition), JDK 25; a throwaway Maven project mirroring the
first spike's stack. Base tables `applied_arg (coordinate, value_sdl)` and `slot_step`; aliases
for a scalar function, a `ResultSet`-returning table function (with the documented
`jdbc:columnlist:` metadata handshake), a `String[]`-returning function, and a void method.
Codegen exercised three ways: live `H2Database` metadata over the running store, `DDLDatabase`
over a script containing `CREATE ALIAS`, and `DDLDatabase` over a script whose view calls a
function registered only at runtime.

## Findings

| # | Experiment | Result |
|---|---|---|
| R1/R9 | Scalar alias, literal and per-row column args | works, including through jOOQ templates |
| R2 | Table function in FROM, constant args | works |
| R3/R6 | Table function with column args (comma join, INSERT..SELECT) | **fails**: `Column not found`; H2 evaluates table functions before the query |
| R4 | `LATERAL` | **does not exist in H2** |
| R5 | Correlated `UNNEST` over an array-returning alias | **fails**, same pre-evaluation rule |
| R10/R13 | Explode via `SYSTEM_RANGE(1,n)` join + `CARDINALITY` + `ARRAY_GET` over an array alias | **works**, including as `INSERT..SELECT` |
| R11/R14 | Pure-SQL recursive-CTE delimiter split; the same wrapped in a view | works |
| R12 | View over a table function with constant args | works |
| R15 | View calling a scalar alias per row | works |
| R7 | `CALL` of a void alias | works; H2 has no true stored procedures, this is the whole procedure story |
| R8 | Inline-source alias (`CREATE ALIAS ... AS 'java'`) | compiles and runs under JDK 25; Java-in-a-string, noted and not recommended |
| G1 | Live `H2Database` codegen over aliases | every alias becomes a `Routine`; the `ResultSet` one is `AbstractRoutine<Result<Record>>`, untyped rows, **not** a table-valued-function `Table`; scalar ones are typed and DSL-usable; a view over a constant-arg table function becomes a full typed `Table` + record |
| G2/G3 | `DDLDatabase` over DDL containing `CREATE ALIAS` (either form) | **fails**: the open-source jOOQ parser rejects `CREATE ALIAS` as a pro-edition feature |
| G4 | `DDLDatabase` over DDL whose view calls a runtime-registered function | **works**; the function-typed column degrades to `Object` bare, and is fully typed when wrapped in `CAST(... AS <type>)` |

## What is "a bit special", pinned

1. **H2 pre-evaluates table functions.** Their arguments cannot reference columns of other
   tables in the same query, and there is no `LATERAL` to rescue it. The correlated
   table-function join, which is the shape a TVF-based derivation layer would be built from,
   is unwritable in H2.
2. **The metadata handshake.** A `ResultSet`-returning alias is invoked at parse time with a
   connection whose URL starts with `jdbc:columnlist:`, expecting a columns-only result;
   arguments may be null during that call. Every table function must implement this protocol.
3. **The open-source parser gate.** `CREATE ALIAS` cannot appear in the codegen DDL because
   jOOQ's parser reserves it for the pro edition. Function registration is therefore a
   bootstrap concern, permanently separated from the codegen schema.
4. **Views absorb the gap.** `DDLDatabase` happily interprets a view calling a function it has
   never heard of, provided the column is `CAST`; and live codegen turns views into fully
   typed tables. Views, not functions, are the typed surface.

## Verdict for the derivation layer

- **Table-valued functions are not the derivation vehicle.** Correlation is impossible, the
  generated binding is untyped, and they cannot enter the codegen DDL. Nothing about the
  derivation stack should be built on them.
- **Derivations stay SQL statements**: `INSERT..SELECT` strata and views, recursive CTEs
  included (R11/R14), exactly the first spike's shape. At measured store scale nothing needs
  parameterized laziness; questions with unbounded answer sets (all join paths between table
  pairs) are Java-side query methods, not stored functions.
- **The parse-boundary bridge is scalar, not table-valued.** A decode requiring the
  graphql-java parser ships as scalar aliases returning values or arrays, registered by the
  bootstrap; row explosion is `SYSTEM_RANGE` + `CARDINALITY` + `ARRAY_GET` (R10/R13), with
  parallel array functions sharing one range index when a decode yields several columns.
  Simple delimiter shapes need no function at all (R11).
- **The functions are part of the model, so they live in `graphitron-model`.** The DDL's
  derivation views call them, and H2 refuses to create a view over an unregistered function,
  so the bootstrap must register the module's aliases and then execute the DDL; that ordering
  is only possible when the alias methods ship with the module, and it makes the store whole
  from the module alone (any process booting it gets working views without core on the
  classpath). The module gains a graphql-java dependency for the literal decoders. The codegen
  side is unaffected: the parser gate keeps `CREATE ALIAS` out of the codegen script, so the
  DDL as `DDLDatabase` sees it stays pure tables and views, preserving no-live-database
  builds, and derivation views calling bridge functions come out typed under a `CAST`
  discipline on every function-derived column.
- **Procedures are a non-topic.** `CALL` on void aliases exists; nothing in the architecture
  wants it.
