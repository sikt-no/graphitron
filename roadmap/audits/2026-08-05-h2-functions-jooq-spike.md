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
| G5a | `DDLDatabase` with `parseIgnoreComments` over one script holding tables, `CREATE ALIAS` inside `/* [jooq ignore start] */ ... /* [jooq ignore stop] */`, and a view calling the alias | **works**: the parser skips the block, the view generates as a typed table (`CAST` column comes out `String`) |
| G5b | H2 `RunScript` executing that same single script | **works**: the alias registers, the view is created after it, and querying the view runs the real function |
| R16/G1b | H2's own typing of an *uncast* function column in a view, and live-H2 codegen over it | H2 types it `CHARACTER VARYING` from the alias's Java signature, and live codegen emits `TableField<..., String>`; no cast needed on this path |
| G6 | Single-module Maven wiring: extra compiler execution at generate-sources compiling only the function and build-driver packages, then an `exec:java` codegen driver (project classpath) booting in-memory H2, running the full DDL (plain `CREATE ALIAS`, uncast views), then live-H2 `GenerationTool` with `includeRoutines=false` | **works end to end**: `mvn package` green, uncast view column generated as `String`, no routine classes generated, ordinary module code referencing the generated classes compiles in the default pass |

## What is "a bit special", pinned

1. **H2 pre-evaluates table functions.** Their arguments cannot reference columns of other
   tables in the same query, and there is no `LATERAL` to rescue it. The correlated
   table-function join, which is the shape a TVF-based derivation layer would be built from,
   is unwritable in H2.
2. **The metadata handshake.** A `ResultSet`-returning alias is invoked at parse time with a
   connection whose URL starts with `jdbc:columnlist:`, expecting a columns-only result;
   arguments may be null during that call. Every table function must implement this protocol.
3. **The open-source parser gate, and its sanctioned bypass.** jOOQ's parser reserves
   `CREATE ALIAS` for the pro edition, and the failure is at parse time, before any
   include/exclude filtering could apply, so "just don't generate routines" is not available.
   What is available is `parseIgnoreComments`: statements wrapped in
   `/* [jooq ignore start] */ ... /* [jooq ignore stop] */` are invisible to jOOQ's parser and
   ordinary statements to H2 executing the same file (G5). That was the `DDLDatabase`-era
   mitigation; dropping `DDLDatabase` (see the verdict) makes the ignore blocks unnecessary,
   and the aliases sit plainly in the one script, before the views that call them.
4. **Views absorb the gap, and the `CAST` blame lands on the simulation, not on H2.** Real H2
   types an uncast function column from the alias's Java signature, and live-H2 codegen emits
   the correct field type from it (R16/G1b); the `Object` degradation happens only in
   `DDLDatabase`'s parse-and-simulate path, where the alias is invisible and jOOQ substitutes
   `NULL` for the call. So the `CAST` discipline is the price of the hermetic
   `DDLDatabase` build, not an H2 deficiency. The cast-free alternative is live-H2 codegen
   over a store booted from the full script, where routine generation can genuinely be
   filtered (exclusion works at the metadata layer). Its apparent cost, alias classes compiled
   before codegen inside one module, dissolved under test: plugin classloaders cannot see
   `target/classes` (which is why the jOOQ-mcve reference shape pairs `sql-maven-plugin` with
   a *file-based* H2 and would force a functions sub-module), but an `exec:java` codegen
   driver runs on the project classpath, where H2 loads the just-compiled classes (G6). The
   decision landed there: `DDLDatabase` is dropped.

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
  so the `CREATE ALIAS` statements sit plainly in the same DDL script, before the views that
  call them; the bootstrap is just "execute the script", and the DDL stays the single source,
  functions included. The alias methods ship with the module (they must be on the classpath
  when the script runs), which makes the store whole from the module alone: any process
  booting it gets working views without core on the classpath. The module gains a
  graphql-java dependency for the literal decoders.
- **Codegen is live-H2 through a build driver; `DDLDatabase` is dropped.** The wiring G6
  proved, all in one module: a maven-compiler execution at generate-sources compiles only the
  function and build-driver packages; an `exec:java` execution runs the codegen driver on the
  project classpath, which boots an in-memory H2, executes the full DDL (aliases resolve
  against the classes compiled a moment earlier), and points jOOQ's live H2 metadata
  generation at the same database with `includeRoutines=false`; build-helper adds the
  generated sources, and the default compile builds everything else against them. No ignore
  blocks, no `CAST` discipline, no routine classes, no external database process; the build
  executes the same script through the same engine the runtime bootstrap uses, so a view
  calling a missing function fails the build with a real H2 error instead of passing a
  simulation. The jOOQ-mcve template's `sql-maven-plugin`-plus-file-H2 shape is the
  plugin-only equivalent and would need a functions sub-module; the driver supersedes it
  here.
- **Procedures are a non-topic.** `CALL` on void aliases exists; nothing in the architecture
  wants it.
