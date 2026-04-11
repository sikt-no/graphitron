# Plan: Rewrite IntrospectMojo to use JooqCatalog

## Context

`IntrospectMojo` (goal `graphitron:introspect`) generates `graphitron-lsp-config.json` for the LSP
server. It currently uses `TableReflection` — a legacy class that eagerly pre-builds four static
maps from the jOOQ catalog at class-load time.

The rest of the codebase now uses `JooqCatalog` — a thin, instance-based wrapper that loads
`DefaultCatalog.DEFAULT_CATALOG` once on construction and queries lazily via the jOOQ API. The
prerequisites for this rewrite are already in place:

- `JooqCatalog.ColumnEntry` now carries a `nullable` flag (needed by `buildFields`)
- `JooqCatalog.allColumnsOf(String tableSqlName)` returns all `ColumnEntry` objects for a table

The mojo itself was not rewritten because the session pivoted to the validate goal.
`graphitron-maven-plugin` already depends on `graphitron-java-codegen`, so no module changes are
needed.

---

## What needs to change in IntrospectMojo

### Initialisation

Add a `JooqCatalog catalog` field. Initialise at the top of `execute()`:

```java
catalog = new JooqCatalog(GeneratorConfig.getGeneratedJooqPackage());
```

### `buildLspConfig()`

```java
// Before
var tableNames = TableReflection.getTableNames();   // Set<String> of Java field names
for (var tableName : tableNames) { ... }

// After
for (var sqlName : catalog.allTableSqlNames()) {
    var entry = catalog.findTable(sqlName).orElseThrow();
    // entry.javaFieldName() replaces tableName as the key for TableConfig.table_name
    // entry.table() is passed directly to buildReferences / used in buildFields
}
```

### `buildReferences(Table<?> table)`

Signature changes to accept `Table<?>` directly (already available from `TableEntry.table()`).

Outgoing FK target table name:
```java
var targetSqlName = fk.getKey().getTable().getName();
var targetJavaName = catalog.findTable(targetSqlName)
    .map(JooqCatalog.TableEntry::javaFieldName)
    .orElse(targetSqlName.toUpperCase());
```

Incoming FK loop:
```java
for (var otherSqlName : catalog.allTableSqlNames()) {
    var otherEntry = catalog.findTable(otherSqlName).orElse(null);
    if (otherEntry == null || otherEntry.table() == table) continue;
    for (var fk : otherEntry.table().getReferencesTo(table)) {
        references.add(new TableReference(otherEntry.javaFieldName(), getKeyFieldName(fk), true));
    }
}
```

### `buildFields(String tableSqlName)`

```java
for (var col : catalog.allColumnsOf(tableSqlName)) {
    fields.add(new FieldConfig(col.javaName(), getGraphQLTypeName(col.columnClass()), col.nullable()));
}
```

### `getGraphQLTypeName`

Change parameter from `Class<?>` to `String` (canonical class name):

```java
private String getGraphQLTypeName(String canonicalName) {
    var simpleName = canonicalName.contains(".")
        ? canonicalName.substring(canonicalName.lastIndexOf('.') + 1)
        : canonicalName;
    return javaToGraphQLTypeMapping.getOrDefault(canonicalName, simpleName);
}
```

### Imports

Remove: `import no.sikt.graphitron.mappings.TableReflection;`, `import org.jooq.TableField;`  
Add: `import no.sikt.graphitron.rewrite.JooqCatalog;`

---

## Files to modify

| File | Change |
|---|---|
| `graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/IntrospectMojo.java` | Replace `TableReflection` with `JooqCatalog` as above |

`IntrospectMojoTest.java` tests only JSON serialisation and does not exercise jOOQ reflection —
no test changes required.

---

## Verification

```bash
mvn test -pl :graphitron-maven-plugin
```
