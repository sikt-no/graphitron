# About graphitron

graphitron is a code generator developed by Sikt. It turns a **GraphQL schema** plus **jOOQ-generated database models** into Java resolvers, so the schema is the source of truth and the data-access plumbing is generated rather than hand-written.

## The dev loop

You are connected to a running `mvn graphitron:dev` session. That single command runs one JVM that:

- **watches** the project's `.graphqls` schema files and regenerates the affected Java sources under `target/generated-sources/graphitron` on every save (only files whose rendered content actually changed are rewritten);
- **serves a language server (LSP)** on a loopback port for the editor: diagnostics, hover, completion, and go-to-definition that cross between the schema and the generated Java and jOOQ classes;
- **rebuilds its in-process catalog** when the project's compiled jOOQ output changes, so a database-schema change is picked up without restarting the dev session.

## Working with a graphitron project

- The schema is authored with graphitron directives (for example `@table`, `@field`, `@service`, `@condition`) that tell the generator how each GraphQL type and field maps onto jOOQ tables, columns, and hand-written service methods.
- Editing the schema, not the generated Java, is how behaviour changes; the generated sources are overwritten on the next save.
- The graphitron documentation site is the reference for the directive vocabulary, the generation pipeline, and the schema conventions.
