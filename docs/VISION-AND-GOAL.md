# Graphitron: Vision and Goals

## The Insight

Two declarative schemas often describe the same thing.

**Your database schema** declares what data exists: tables, columns, relationships, constraints. It’s a formal description of your data model.

**Your API schema** declares what data clients can request: types, fields, queries. It’s a formal description of your data capabilities.

When both schemas represent the same conceptual domain—which they usually do—there’s significant overlap. A `users` table with `id`, `name`, and `email` columns maps naturally to a `User` type with `id`, `name`, and `email` fields.

Yet most API implementations require writing code by hand to bridge these two schemas. Developers manually translate between the API’s view of data and the database’s view of data, even when the translation is straightforward.

**Graphitron is based on the insight that this translation can be derived from a declarative mapping between schemas.** Given clear rules for how API types correspond to database tables, how fields correspond to columns, and how relationships correspond to foreign keys, the implementation code can be generated.

## The Problem Graphitron Solves

Building a data API typically involves:

1. Define your database schema (tables, columns, relationships)
1. Define your API schema (types, fields, queries)
1. Write code that fetches data from the database when the API is called
1. Handle nested data efficiently (avoid redundant queries)
1. Only fetch what’s actually requested (avoid over-fetching)
1. Keep everything in sync as schemas evolve

Steps 3-6 are where most of the work happens—and most of the bugs. The code is often repetitive, error-prone, and tedious to maintain. When the database schema changes, the API code must change. When the API schema changes, the fetching logic must change.

Graphitron eliminates this manual work by generating the implementation from a declarative mapping.

## How Graphitron Works (Conceptually)

You provide:

- Your database schema (which you already have)
- Your API schema (which you need to define anyway)
- Annotations on the API schema that map types to tables, fields to columns

Graphitron generates:

- All the code that fetches data from the database
- Efficient handling of nested data
- Selection-aware queries that only fetch requested fields
- Proper batching to avoid redundant database calls

The generated code is:

- Type-safe and compile-time validated
- As efficient as hand-written code (often more so)
- Automatically updated when schemas change

## Design Principles

### Declarative Over Imperative

Mappings are declared, not coded. You say “this type maps to this table” rather than writing fetch logic. This makes the system easier to understand, maintain, and verify.

### Database as Source of Truth

The database schema is authoritative. Graphitron validates that your mappings correspond to real tables and columns. If the database changes, validation fails fast with clear errors.

### Generate, Don’t Abstract

Graphitron generates concrete implementation code rather than providing a runtime abstraction layer. This means:

- No runtime overhead
- Full visibility into what code runs
- Standard debugging and profiling tools work
- No framework lock-in at runtime

### Explicit Escape Hatches

Not everything fits a declarative mapping. When you need custom logic—complex calculations, external service calls, business rules—Graphitron provides explicit ways to drop into hand-written code while maintaining the benefits of generation for everything else.

### Fail Fast with Good Errors

Misconfigurations are caught at build time, not runtime. Error messages include context: what was expected, what was found, where to look.

## What Graphitron Is Not

**Not a database abstraction.** Graphitron doesn’t hide your database or provide a generic data access layer. It generates code that works with your specific database schema.

**Not a schema generator.** Graphitron doesn’t generate your API schema from your database schema (or vice versa). You define both schemas; Graphitron handles the implementation between them.

**Not a runtime framework.** The generated code has no runtime dependencies on Graphitron. Once generated, it’s just code.

**Not magic.** Complex domains require thought about how to map them. Graphitron handles the mechanical translation; you still need to design your schemas well.

## Goals

1. **Eliminate boilerplate.** Developers should spend time on schema design and business logic, not on repetitive data-fetching code.
1. **Guarantee correctness.** Generated code should be correct by construction. Type mismatches, missing columns, and invalid relationships should be caught at build time.
1. **Match hand-written performance.** Generated code should be at least as efficient as what a skilled developer would write manually.
1. **Stay out of the way.** When you need custom behavior, Graphitron should make it easy to add without fighting the framework.
1. **Evolve with schemas.** When database or API schemas change, regenerating code should be the only step needed (assuming the mapping is still valid).

## The Bigger Picture

Graphitron treats API implementation as a **mapping problem** rather than a **coding problem**.

This isn’t a new idea—compilers have long generated code from declarative specifications. ORMs map objects to tables. Query builders map method calls to SQL. Graphitron applies the same principle to the specific problem of implementing data APIs over relational databases.

The bet is that most data API implementation is mechanical translation that doesn’t benefit from hand-written code. By making the mapping explicit and the generation automatic, developers can focus on what actually requires human judgment: schema design, business rules, and the parts of the system that aren’t just moving data around.