# How jOOQ models routines, and what the store takes from it

A study of jOOQ's routine model, made before deciding how the store captures routines: what the
runtime API exposes, what the code generator emits and which configuration changes it, what jOOQ 3.21
changes, and what the two catalogs we measure against actually contain. Read from the jOOQ 3.20.11
runtime sources, the 3.20.11 code generator and its configuration XSD
(`org/jooq/meta/xsd/jooq-codegen-3.20.9.xsd` in `jooq-meta`), the jOOQ Pro 3.20.11 jars, sakila's
generated catalog, sis's compiled one, and for 3.21 the release notes (https://www.jooq.org/notes),
the 3.21 XSD (https://www.jooq.org/xsd/jooq-codegen-3.21.0.xsd) and Javadoc. Claims marked inferred
were not read or run.

## The runtime API

* **Nothing lists routines or packages.** `Schema`, `Catalog`, `Package` and `Meta` have no
  `getRoutines()` or `getPackages()`, in the open-source edition or in Pro, in 3.20 or 3.21.
  `Package` is an empty marker interface. A routine is reachable only as a generated class.
* **A routine instance tells you jOOQ's model of it.** Instantiating a concrete `AbstractRoutine`
  subclass (every generated one has a public no-argument constructor) gives the qualified name
  (catalog, schema, package where there is one, routine), the return parameter and its `DataType`
  (null for a procedure or a void function), the IN and OUT parameter lists (an INOUT parameter is
  in both), and each parameter's `isDefaulted()` and `isUnnamed()`.
  * `Parameter.getParamMode()` is meaningless on generated routines: `Internal.createParameter`
    always sets IN. The mode is which list the parameter is in.
  * The open-source runtime keeps no package field: the package survives only as the middle part of
    `getQualifiedName()` (`KJERNEAPI.PK_UTIL.F_EMNENAVN`). Pro's `Routine` adds `getPackage()`.
* **What jOOQ keeps private.** SQL-usable, overloaded and (Pro) pipelined are protected flags on
  `AbstractRoutine`, with no public equivalent in 3.20 or 3.21, and set inconsistently: the generator
  marks a Postgres procedure not SQL-usable, and an Oracle procedure is not marked.
* **A table-valued function is a table at runtime**, a `TableImpl` whose `getTableType()` is
  `FUNCTION`, listed in `Schema.getTables()`. Its parameters are a protected array with no names.

## What the generator emits

A routine class per database routine, and static methods on a holder class: the schema's `Routines`
for a schema-level routine, the package class (a `PackageImpl` subclass) for a package routine, and
`Tables` and `Routines` for a table-valued function. The holder method's signature is its form:

| Form | Signature | Generated when |
|---|---|---|
| execute | `Configuration` first, returns the value, `void`, or the one OUT parameter | every routine but an aggregate |
| value | returns `Field<T>`, over values | SQL-usable functions |
| value over fields | returns `Field<T>`, over `Field` arguments | SQL-usable functions |
| table | returns the function's `Table` subclass | table-valued functions |
| aggregate | returns `AggregateFunction` (inferred) | aggregate functions |

Examples:

* sakila's scalar `rental_count_for_customer`: `Integer rentalCountForCustomer(Configuration,
  Integer)`, `Field<Integer> rentalCountForCustomer(Integer)` and `Field<Integer>
  rentalCountForCustomer(Field<Integer>)` on `public_.Routines`.
* sakila's void function `session_disconnect`: only `void sessionDisconnect(Configuration, ...)`.
* sakila's table-valued `films_for_actor`: `FilmsForActor FILMS_FOR_ACTOR(Integer, Integer)` and its
  `Field` and execute forms on `Tables`, the same as `filmsForActor` on `Routines`, and no class under
  `routines/`.
* sis's package function `PK_UTIL.F_EMNENAVN`: the three function forms on `packages.PkUtil`.
* sis's procedure `PK_RAS.CREATE_AND_ATTACH` with one OUT parameter: `String
  createAndAttach(Configuration, ...)`.

Nothing generated ties a holder method to its routine class: no annotation, no constant. Overloads
get a suffix from `GeneratorStrategy.getOverloadSuffix` (an index by default, so `Foo1`, inferred).

**Packages are a Pro feature.** In the open-source generator `generatePackages` and both
`generatePackage` methods are empty (`0: return`). Only Pro emits package classes.

## Configuration that changes what exists

A capture that assumes the defaults is wrong under any of these (3.20 XSD, defaults in brackets):

* `<database>`: `includeRoutines` [true], `includePackages`, `includePackageRoutines`,
  `includePackageUDTs`, `includePackageConstants` [true], `includeExcludePackageRoutines` [false],
  `includeTriggerRoutines` [false], `includes` and `excludes`, and `tableValuedFunctions` (no XSD
  default; documented true for most databases and false for Oracle, where a table-valued function is
  then generated as a routine).
* `<generate>`: `routines` [true], `globalObjectReferences` and `globalRoutineReferences` [true],
  which decide whether `Routines` exists, and `globalTableReferences` [true] for `Tables`.
* Schema mapping (`inputSchema`, `outputSchema`, `outputSchemaToDefault`, catalog likewise) changes
  qualified names and Java packages, and `forcedTypes` changes parameter and return types.
* `<strategy>` matchers or a custom `GeneratorStrategy` can rename every class and method. Both
  catalogs below use a custom generator.

So routines and holder classes are found by type (`AbstractRoutine`, `PackageImpl`, the static
methods' signatures), never by the names `Routines`, `routines/` or `Tables`.

## What jOOQ 3.21 changes

3.21.0 was released 2026-03-24, 3.21.9 on 2026-09-24.

* **A table-valued function can be both a table and a routine.** `tableValuedFunctions` is
  deprecated in favour of `tableValuedFunctionsAsRoutines` and `tableValuedFunctionsAsTables`, both
  defaulting to true (jOOQ #17713), so that a table-valued function can also be called as a routine
  with defaulted arguments left out. Inferred: after the upgrade sakila's table-valued functions
  appear under `Routines` and `routines/` as well as under `Tables`, one database function in two
  generated shapes.
* **Defaultedness moves to the data type.** The `Internal.createParameter(name, type, isDefaulted,
  isUnnamed)` overloads are deprecated for removal in favour of `createParameter(name, type,
  isUnnamed)` (#19044). `Parameter.isDefaulted()` still answers, so reading instances survives the
  upgrade and reading generated call shapes would not.
* **Unchanged:** no routine or package listing (`Meta` gains UDTs and dependencies only), no public
  replacement for the protected flags, the routine and package include flags, and the open-source
  edition's Java 21 runtime floor. Oracle remains commercial.
* **A fix not in 3.20.11:** overloaded Postgres table-valued functions were generated in an unstable
  order (#19845, fixed in 3.21.2 and 3.20.13).

## The two catalogs

* **sakila** (open source, Postgres): three schema-level routines in `public_.routines`
  (`RentalCountForCustomer`, `SessionConnect` returning a composite record, `SessionDisconnect`
  void), table-valued functions in `public_.tables` and both `multischema` schemas, no procedures,
  overloads or packages.
* **sis** (jOOQ **Pro 3.19.18**, Oracle, `includeRoutines` false in
  `sis-jooq/jooq-configuration.xml`): no schema-level routines at all, and 22 package routines in
  four packages, `PkPlnr`, `PkRas` (two procedures, one with an OUT parameter, and a package UDT),
  `PkRasTilgang` (eight functions) and `PkUtil` (thirteen functions and a procedure). Neither
  graphitron's capture on trunk nor one reading the schema's `routines` package sees any of them.
  Whether Pro-generated classes load on the open-source runtime graphitron uses is untested; the
  classes read call nothing Pro-only.

## What the store takes from this

Settled with Alf on 2026-10-22:

* **A form is a fact about a method's signature, whoever wrote it.** The `code_` family states, for
  every public static method whose signature is jOOQ-shaped, the form it offers. A method written to
  those rules is available as if jOOQ had generated it; if it quacks like a routine, it is one. The
  identity is the qualified Java method.
* **The `sql_` family enriches a form with the database construct behind it**, read from the
  `AbstractRoutine` instance jOOQ generated: qualified name with package, parameters with mode,
  `DataType` and default, and return; tied to the holder methods that call it by name and arity, jOOQ
  offering no link. A generated class that does not load is a stored fact, not a skip.
* **`@externalField` is `@routine`**, a value form bound to the parent's table, to be deprecated as
  an alias later.
