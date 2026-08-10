---
id: R621
title: "Legacy v9: split GeneratedExceptionToErrorMappingProvider under the 64KiB method limit"
status: Spec
bucket: bug
priority: 3
theme: legacy-migration
depends-on: []
created: 2026-08-10
last-updated: 2026-08-10
---

# Legacy v9: split GeneratedExceptionToErrorMappingProvider under the 64KiB method limit

> **Scope: this is a maintenance bugfix on the released v9 generator, not a rewrite feature.**
> The affected class, `ExceptionToErrorMappingProviderGenerator`, lives in
> `graphitron-codegen-parent/graphitron-java-codegen/` on the v9 line (verified at tag `v9.3.0`,
> commit `afb2363f9`). That module does not exist anywhere in the rewrite reactor: `origin/main`
> and the `claude/graphitron-rewrite` trunk carry no file by that name, and the rewrite's exception
> handling is a separate design. So **no reactor module is touched by this item**, nothing in the
> rewrite's behaviour changes, and the usual local gates (`mvn install -Plocal-db`, the Javadoc
> reference gate, the pipeline/execution test tiers) do not cover the fix. The item is filed on the
> rewrite roadmap only because that is where this repository tracks planned work; it must not be
> read as rewrite scope, and it must not be bundled into a rewrite slice.
>
> `theme: legacy-migration` is the closest fit in the tool's closed theme set. Read it here as
> "legacy line" rather than its usual sense of "retiring a legacy mechanism inside the rewrite".
> Thematically the work is `error-channel`; the legacy marker is the more important signal.

## The defect

A consumer's largest subgraph fails to compile its generated sources. javac reports:

```
error: code too large
    public GeneratedExceptionToErrorMappingProvider() {
           ^
```

The "64KB" in the original report is the JVM's per-method bytecode limit (65534 bytes for a single
`Code` attribute), not a source-file or class-file size. `ExceptionToErrorMappingProviderGenerator`
emits the entire provider body into one constructor via `createConstructor` /
`createConstructorContentForFields`, so the limit is reached as a function of schema size and the
build breaks with no workaround available to the schema author short of splitting the subgraph.

## What actually grows

Confirmed by generating output through the real generator for synthetic schemas and compiling it at
`--release 17`. Measured constructor bytecode, at 20 generic mappings per operation:

| operations | constructor bytecode | javac |
|---|---|---|
| 400 | 55,508 | ok |
| 450 | 62,508 | ok |
| 500 | over limit | `code too large` |

The mapping declarations are **not** the driver. `ExceptionToErrorMapping` implements
`equals`/`hashCode`, and `processErrorMapping` already memoises distinct mappings into `m1`, `m2`,
... , so twenty distinct mappings cost roughly 600 bytes in total no matter how many operations
reference them.

The driver is **per-operation** emission in `OperationProcessor.process`: for every query or
mutation field that carries errors it emits `var <op>GenericList = List.of(m1, ..., mk)` (and the
`Database` twin) plus the two `Map.put` statements. Measured cost is about `7 * k + 20` bytes per
operation, where `k` is the number of mappings on that operation. Above `k = 10` javac stops using
the fixed-arity `List.of` overloads and emits an `anewarray` plus a `dup`/`index`/`aload`/`aastore`
sequence per element, which is where the 7 bytes per element comes from. The wall therefore sits
near 470 operations at 20 mappings each, and proportionally earlier as the error unions widen.

Note that the identical list is re-emitted for every operation even when operations share an error
set, which is the common authoring shape: one error union reused across many mutations. That
redundancy is most of the constructor.

## Design

Two parts. Part 1 is the cheap constant-factor win and Part 2 is what makes the bound structural.
Both were prototyped against `v9.3.0` and verified by compiling the generated output; the numbers
below are measured, not estimated.

### Part 1: deduplicate the per-operation lists

Apply the memoisation that already exists for individual mappings to the lists themselves. Key on
the ordered list of mapping variable numbers, so operations resolving to the same members share one
declared list variable and later operations emit only their `put`.

Concretely: have the current `createMappingVariablesBlock` return the `List<Integer>` of variable
numbers rather than a rendered `CodeBlock` (renaming its helper to something like
`variableNumberFor`, since it no longer renders), and add a `Map<List<Integer>, String>` alongside
the existing two memo maps. First occurrence declares the list under the name the current code
already computes from the operation and handler (`asListedName(operationName + handler)`), which
keeps existing single-operation output byte-identical; subsequent occurrences reuse that name.

Per-operation cost drops from about 139 bytes to about 15. On its own this took the failing
500-operation case to 8,044 bytes and 3,000 operations to 45,544 bytes.

### Part 2: emit into budget-sized `initMappings<n>()` methods

Keep the constructor to the two `HashMap` allocations plus one call per init method, and move the
declarations and registrations into private void methods. Deduplication is scoped to an init
method, so a mapping shared across methods is constructed once per method. These are small
immutable holders built once at startup, so the duplication is acceptable; the alternative
(hoisting them to fields) buys nothing that matters and adds a field per mapping.

**The chunk boundary must be size-driven, not a fixed operation count.** A fixed 50 operations per
method was tried first and still produced `code too large` at 200 mappings per operation. Estimate
the bytecode each accumulated chunk will compile to and close the method when it crosses a budget
well below the limit. A workable model, deliberately overshooting what javac emits so the margin
absorbs the imprecision:

- 40 bytes per distinct mapping declaration in the chunk,
- 7 bytes per element of each declared list, plus 20 for the declaration statement,
- 20 bytes per emitted statement, so 40 for an operation's two `put` calls,
- budget 40,000, leaving about 25,000 bytes of headroom.

A source-character budget was tried and rejected: the bytecode-per-character ratio ranges from
about 0.2 for a mapping declaration to about 1.4 for a wide `List.of`, so characters are a poor
proxy and a single budget number cannot be both safe and non-wasteful. The character budget also
fragmented the wide-list case into one method per operation, each re-declaring its 200 mappings,
inflating generated source from 59 KB to 2.2 MB.

### Verified results

Largest generated method, with both parts in place, all compiling clean at `--release 17`:

| case | largest method (bytes) |
|---|---|
| 500 operations, 20 shared mappings each | 8,044 |
| 3,000 operations, 20 shared mappings each | 15,258 |
| 50 operations, 200 shared mappings each | 7,148 |
| 150 operations, 40 unique mappings each (zero sharing) | 29,697 |

## Tests

The v9 module's `ProviderTest` (`no.sikt.graphitron.exceptionhandling.ProviderTest`) has 27 cases.
Under the prototype, 25 pass unchanged, including `multipleInOneResponse`. Two fail and need their
expected output regenerated for the init-method extraction, both golden-file comparisons against
`src/test/resources/exceptions/provider/default/expected/GeneratedExceptionToErrorMappingProvider.java`:

- `defaultCase`
- `noValidation`

Add coverage the module does not have today:

1. A schema large enough that the old generator produced one oversized constructor, asserting the
   output contains more than one `initMappings<n>()` method and that the constructor body is only
   the two allocations plus the init calls. This is the regression test for the defect.
2. A schema where several operations share an error set, asserting one list declaration is reused
   across their `put` statements rather than re-declared per operation. This pins Part 1
   independently of Part 2, so a later change cannot silently drop the dedup while the size test
   still passes.
3. A compile check on generated output for a large schema, if the v9 module has a tier for it. The
   defect is invisible to string-matching assertions: only javac catches `code too large`. If there
   is no such tier, say so explicitly in the implementation notes rather than leaving the gap
   unstated.

## Reproducing without Docker

The v9 codegen module's jOOQ test-code generator hardcodes
`<driver>org.testcontainers.jdbc.ContainerDatabaseDriver</driver>`, so it cannot run against an
already-running PostgreSQL. Parameterising it as `${db.driver}` with the testcontainers driver as
the property default (mirroring what `graphitron-sakila-db` does for the rewrite through its
`local-db` profile) lets the module build with
`-Ddb.url=jdbc:postgresql://localhost:5432/sakila -Ddb.driver=org.postgresql.Driver` after applying
`src/test/resources/database/postgres-sakila-schema.sql` to a local database. Worth landing
alongside the fix so the next person investigating this module is not blocked on a container
runtime, but it is a build-config convenience and not part of the defect.

## The next wall

Method splitting cannot raise the **class constant pool** ceiling of 65535 entries. At roughly
12,000 distinct mappings (measured: 300 operations each carrying 40 unique mappings) javac reports
`error: too many constants`, because each mapping contributes a lambda (method handle, method type,
name-and-type entries) plus its strings. Getting past that needs the provider split across several
classes, which is a materially larger change. This item does not attempt it. Record the ceiling in
the implementation notes so the next report of a size failure is diagnosed against the right limit
rather than assumed to be a regression of this fix.

## Adjacent defect, deliberately not fixed here

`OperationProcessor.process` computes `databaseListName` and `genericListName` once per operation
but declares them inside the loop over the response's error fields. An operation whose response has
two error fields therefore declares the same variable twice, which does not compile, and registers
only the last of the two lists. The module already knows: `ProviderTest.multipleInOneResponse`
carries the comment `// Note, this produces illegal code.`

Part 1 incidentally removes the duplicate-declaration compile error, because distinct member sets
resolve to distinct shared variables. It does **not** fix the second half, where only the last
list reaches the map, so an operation with two error fields still loses the mappings of all but
one. That is a separate correctness bug on the same method and needs its own item; do not let it
ride along silently under a size fix.

## Out of scope

- Any change to the rewrite reactor. See the scope banner.
- The runtime types in `graphitron-common` (`ExceptionToErrorMappingProvider`,
  `GenericExceptionContentToErrorMapping`, `GenericExceptionMatcher`, the `DataAccess` twins). The
  generated class keeps implementing exactly the interface it implements today, so consumers see no
  API change.
- Splitting the provider across multiple classes for the constant-pool ceiling.
- The multiple-error-fields registration bug described above.
- The `ExceptionToErrorMapping.equals`/`hashCode` pair omitting `handler`. Mappings are filtered by
  handler before they reach the memo map, so the omission is unreachable today. Noted only so a
  future reader does not mistake it for part of this fix.

## Open decisions for the Spec author

1. **Where the fix lands.** `v9.3.0` is the newest tag and no maintenance branch for the v9 line
   exists on the remote: `origin/main` is the rewrite and no longer contains this generator, and
   the only branch carrying a newer commit against this file is the unrelated
   `origin/GG-307-refactor-conditions-logic`. A branch has to be cut from the tag, and someone has
   to decide whether this ships as a v9 patch release. This is a release-management call, not an
   implementation one.
2. **Part 1 alone, or both parts.** Part 1 is roughly 20 lines and buys about a 9x headroom
   increase wherever operations share error sets, which is the shape that broke. Part 2 is the rest
   and makes the bound hold regardless of sharing. Shipping Part 1 alone is defensible as an urgent
   unblock, but it leaves a schema whose operations each carry a unique error set exactly as
   fragile as before. Recommendation is both, since they were prototyped and measured together and
   the combined diff is about 110 lines in one file.
3. **Whether the budget constants are tunable.** The prototype hardcodes them as private static
   finals. Exposing them as Mojo configuration would be over-engineering for a limit that is fixed
   by the JVM, but the Spec author should confirm nobody wants a knob before closing that off.
