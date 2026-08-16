package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.jooq.Field;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.UniqueKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static no.sikt.graphitron.model.Tables.SQL_INDEX;
import static no.sikt.graphitron.model.Tables.SQL_INDEX_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.JVM_CLASS_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER_TYPE_REF;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_RETURN_TYPE_REF;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT_TYPE_REF;
import static no.sikt.graphitron.model.Tables.JVM_SCALAR_TYPE_FIELD;

/**
 * The catalog capture load: fills the {@code sql_} family from the jOOQ catalog walk and the
 * {@code jvm_} family from the bytecode-only classpath scan. Both families are named for the
 * vocabulary a row is written in rather than for the reader that produced it, which is why neither
 * prefix names jOOQ: jOOQ defines no table, column or foreign key, and a class on the compile
 * classpath is a JVM fact whether or not it extends anything of graphitron's.
 *
 * <p>Both inputs arrive already reduced to values, which is the property the store then enforces
 * structurally: no live {@code Table<?>}, {@code ForeignKey}, or {@code Class<?>} can cross into a
 * relation, so nothing lazy survives the codegen classloader closing at the end of a pass.
 *
 * <p>Two deliberate departures from the shapes it reads. Foreign keys are stored once, on the
 * declaring side; the incoming direction {@code CatalogFacts} denormalises is a query here, which
 * is most of the point of having a store. And constraints take the shape a real catalog gives
 * them, one supertype relation discriminated by type with per-form detail in siblings, rather
 * than one relation per form: "what constrains this table?" is then one predicate instead of a
 * union, and the forms this iteration does not capture arrive as further type values.
 */
final class CatalogFactCapture {

    /** The standard's {@code TABLE_CONSTRAINTS} vocabulary, as far as the catalog walk reads it. */
    private static final String PRIMARY_KEY = "PRIMARY KEY";
    private static final String UNIQUE = "UNIQUE";
    private static final String FOREIGN_KEY = "FOREIGN KEY";

    /** {@code store_source.source_kind}'s catalog arm; the classpath arms are the scan's. */
    private static final String JOOQ_SCHEMA = "JOOQ_SCHEMA";

    private CatalogFactCapture() {}

    static void capture(FactSink sink, JooqCatalog jooq,
                        List<CompletionData.ExternalReference> extensions,
                        ClasspathSources sources) {
        captureCatalog(sink, jooq);
        captureExtensions(sink, sources, extensions);
    }

    /**
     * Fills the {@code sql_} family from the jOOQ catalog. Reads {@link JooqCatalog} rather than
     * the {@link no.sikt.graphitron.rewrite.catalog.CatalogFacts} projection beside it, because
     * that projection is built for the MCP catalog tools and every narrowing it makes for them
     * would land in the store as a fact about the consumer's database: it splits the primary key
     * out of the unique keys, drops a unique constraint whose column set the primary key already
     * covers, and carries no referenced-constraint name. All three are projection choices, and a
     * foreign key referencing a deduped-away constraint would point at nothing here.
     *
     * @param jooq the catalog to walk, or {@code null} for a caller with none in hand
     */
    private static void captureCatalog(FactSink sink, JooqCatalog jooq) {
        if (jooq == null) {
            return;
        }
        // Which package each table's schema lives in, resolved up front: a foreign key may cross
        // schemas that codegen wrote into different packages, and the referenced side of its row
        // has to name the referenced constraint's own source.
        var sourceByTable = new HashMap<String, String>();
        for (JooqCatalog.TableEntry entry : jooq.allTableEntries()) {
            Table<?> table = entry.table();
            String schema = table.getSchema() == null ? "" : table.getSchema().getName();
            sourceByTable.put(schema + "." + table.getName(), packageOf(table));
        }
        for (String source : new LinkedHashSet<>(sourceByTable.values())) {
            GraphSourceMembership.note(sink, source);
        }
        clearSchemaSources(sink, new LinkedHashSet<>(sourceByTable.values()));
        captureSchemas(sink, jooq);
        for (JooqCatalog.TableEntry entry : jooq.allTableEntries()) {
            Table<?> table = entry.table();
            String schema = table.getSchema() == null ? "" : table.getSchema().getName();
            String name = table.getName();
            String source = packageOf(table);
            if (!sink.claim(SQL_TABLE, source, schema, name)) {
                continue;
            }
            var record = sink.dsl().newRecord(SQL_TABLE);
            record.setSourceName(source);
            record.setTableSchema(schema);
            record.setTableName(name);
            record.setJooqName(entry.javaFieldName());
            record.setClassFqn(table.getClass().getName());
            record.setRecordClassFqn(table.getRecordType().getName());
            record.setDescription(nullIfBlank(table.getComment()));
            sink.add(record);

            captureColumns(sink, jooq, table, source, schema, name);
            captureConstraints(sink, jooq, table, source, schema, name);
            captureForeignKeys(sink, jooq, table, source, schema, name, sourceByTable);
            captureIndexes(sink, jooq, table, source, schema, name);
        }
    }

    /**
     * Claims every source the catalog census touches, on first sight, and clears each owned
     * source's {@code sql_} partition ahead of the walk: a jOOQ package carries no stamp (its walk
     * costs milliseconds), so an owned package is always rewritten, and scoping the delete to the
     * sources this run now knows it owns is what leaves sibling graphs' packages standing in a
     * shared store.
     *
     * <p>The clear runs in two rounds across every owned source rather than one round per source,
     * because {@code sql_referential_constraint.referenced_source_name} can name a <em>different</em>
     * source than the row's own {@code source_name}: a foreign key crossing schemas that codegen
     * wrote into different packages, which the multi-schema layout produces. Clearing a source's
     * {@code sql_constraint} rows (round two) while a sibling source not yet cleared still declares
     * a referential row into them (round one, only run for that sibling) violates the referenced-side
     * foreign key on a warm run. Deleting every owned source's own referential-constraint rows first,
     * over the whole set, before deleting any owned source's constraints removes every row that could
     * dangle, regardless of which source the catalog walk visits first or which source's foreign key
     * crosses into which.
     *
     * <p>The source is the generated package the schema lives in, not the classpath entry the
     * classes were loaded from. Both are true of the rows, and only the package is a refresh unit:
     * one jar carries every schema a codegen run produced, so invalidating the entry would discard
     * schemas nothing touched, while the package is the granularity codegen rewrites. It also needs
     * no code-source probe, being derivable from the live {@link Schema} the walk already holds.
     *
     * <p>A table whose schema is absent falls back to its own package, which is the same place for
     * every layout jOOQ generates; the fallback exists because {@code getSchema()} is nullable, not
     * because a real catalog reaches it.
     */
    private static void clearSchemaSources(FactSink sink, Set<String> sources) {
        var dsl = sink.dsl();
        var owned = new ArrayList<String>();
        for (String source : sources) {
            if (sink.claim(STORE_SOURCE, source)) {
                owned.add(source);
                dsl.deleteFrom(SQL_INDEX_COLUMN).where(SQL_INDEX_COLUMN.SOURCE_NAME.eq(source)).execute();
                dsl.deleteFrom(SQL_INDEX).where(SQL_INDEX.SOURCE_NAME.eq(source)).execute();
                dsl.deleteFrom(SQL_REFERENTIAL_CONSTRAINT)
                    .where(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME.eq(source)).execute();
            }
        }
        for (String source : owned) {
            dsl.deleteFrom(SQL_PRIMARY_KEY).where(SQL_PRIMARY_KEY.SOURCE_NAME.eq(source)).execute();
            dsl.deleteFrom(SQL_CONSTRAINT_COLUMN)
                .where(SQL_CONSTRAINT_COLUMN.SOURCE_NAME.eq(source)).execute();
            dsl.deleteFrom(SQL_CONSTRAINT).where(SQL_CONSTRAINT.SOURCE_NAME.eq(source)).execute();
            dsl.deleteFrom(SQL_COLUMN).where(SQL_COLUMN.SOURCE_NAME.eq(source)).execute();
            dsl.deleteFrom(SQL_TABLE).where(SQL_TABLE.SOURCE_NAME.eq(source)).execute();
            // After sql_table, which references it.
            dsl.deleteFrom(SQL_SCHEMA).where(SQL_SCHEMA.SOURCE_NAME.eq(source)).execute();
            ClasspathSources.upsert(dsl, source, JOOQ_SCHEMA);
        }
    }

    /**
     * One row per schema the census touches, carrying the schema-grain generated artifact: the
     * {@code Keys} class name. Runs before the table walk because {@code sql_table} references this
     * relation, and both draw their {@code (source, schema)} pairs from the same census, so the
     * referenced row is present by construction rather than by detection.
     *
     * <p>The pairs deduplicate on the way in, which is the whole reason this is its own relation. One
     * {@code Keys} class serves every table in its schema, so hanging its name off {@code sql_table}
     * would repeat one value across every row; and the multi-schema layout that gives each schema its
     * own package is exactly where concatenating a configured package with {@code ".Keys"} gets the
     * name wrong.
     */
    private static void captureSchemas(FactSink sink, JooqCatalog jooq) {
        var schemas = new LinkedHashMap<SchemaKey, Schema>();
        for (JooqCatalog.TableEntry entry : jooq.allTableEntries()) {
            Table<?> table = entry.table();
            Schema schema = table.getSchema();
            schemas.putIfAbsent(
                new SchemaKey(packageOf(table), schema == null ? "" : schema.getName()), schema);
        }
        for (var entry : schemas.entrySet()) {
            SchemaKey key = entry.getKey();
            if (!sink.claim(SQL_SCHEMA, key.source(), key.schemaName())) {
                continue;
            }
            var row = sink.dsl().newRecord(SQL_SCHEMA);
            row.setSourceName(key.source());
            row.setTableSchema(key.schemaName());
            row.setKeysClassFqn(jooq.keysClassFqn(entry.getValue()).orElse(null));
            sink.add(row);
        }
    }

    /** A schema's identity in the store: the generated package it lives in, plus its SQL name. */
    private record SchemaKey(String source, String schemaName) {}

    /** The generated package the table's schema lives in; the sql_ family's partition source. */
    private static String packageOf(Table<?> table) {
        Schema schema = table.getSchema();
        return (schema != null ? schema.getClass() : table.getClass()).getPackageName();
    }

    /**
     * The column rows. {@code ordinal} is the column's position in the table definition, which is
     * what {@link Table#fields()} states; the reflective field walk behind
     * {@link JooqCatalog#columnFactsOf} is documented to return its results in no particular order
     * and is here only to reach the generated Java field name, which {@link Field} does not expose.
     * Taking the ordinal from the walk instead is what the determinism rule forbids, and the
     * column's own comment already promised the definition's order.
     */
    private static void captureColumns(FactSink sink, JooqCatalog jooq, Table<?> table,
                                       String source, String schema, String name) {
        var positions = new HashMap<String, Integer>();
        Field<?>[] declared = table.fields();
        for (int i = 0; i < declared.length; i++) {
            positions.put(declared[i].getName(), i);
        }
        for (JooqCatalog.ColumnFacts column : jooq.columnFactsOf(table)) {
            Integer ordinal = positions.get(column.sqlName());
            if (ordinal == null) {
                // A generated Field the table itself does not declare. Not reachable through jOOQ's
                // generated shape, where both readings walk the same fields; skipped rather than
                // given a position the table definition does not give it.
                continue;
            }
            if (!sink.claim(SQL_COLUMN, source, schema, name, column.sqlName())) {
                continue;
            }
            var row = sink.dsl().newRecord(SQL_COLUMN);
            row.setSourceName(source);
            row.setTableSchema(schema);
            row.setTableName(name);
            row.setColumnName(column.sqlName());
            row.setOrdinal(ordinal);
            row.setJooqName(column.javaName());
            row.setSqlType(column.sqlType());
            row.setBindingType(column.bindingType());
            row.setNullable(column.nullable());
            row.setDescription(nullIfBlank(column.comment()));
            sink.add(row);
        }
    }

    /**
     * The uniqueness constraints of a table, plus the row that says which of them is the primary
     * key. Reads {@code Table.getKeys()} whole rather than {@link JooqCatalog#candidateKeys}, whose
     * column-set dedup would drop a unique constraint the primary key already covers: that is a
     * projection choice serving the UPDATE key match, and a foreign key referencing the dropped
     * constraint would have nothing to point at here.
     */
    private static void captureConstraints(FactSink sink, JooqCatalog jooq, Table<?> table,
                                           String source, String schema, String name) {
        UniqueKey<?> primary = table.getPrimaryKey();
        var keys = new LinkedHashSet<UniqueKey<?>>(table.getKeys());
        if (primary != null) {
            // jOOQ's getKeys() carries the primary key too, so this is normally a no-op; the union
            // is what keeps sql_primary_key's reference resolvable if a model ever separates them.
            keys.add(primary);
        }
        for (UniqueKey<?> key : keys) {
            writeConstraint(sink, source, schema, name, key.getName(),
                key.equals(primary) ? PRIMARY_KEY : UNIQUE,
                jooq.keyJavaConstantName(key).orElse(null),
                key.getFields().stream().map(Field::getName).toList());
        }
        if (primary != null) {
            var row = sink.dsl().newRecord(SQL_PRIMARY_KEY);
            row.setSourceName(source);
            row.setTableSchema(schema);
            row.setTableName(name);
            row.setConstraintName(primary.getName());
            sink.add(row);
        }
    }

    /**
     * The foreign keys a table declares, as a constraint plus the referential extension naming the
     * constraint it references. The target columns are deliberately not copied onto the referencing
     * row: they are the referenced constraint's own {@code sql_constraint_column} rows matched on
     * position, which is what SQL guarantees and what both Oracle's dictionary and the standard's
     * INFORMATION_SCHEMA rely on.
     *
     * <p>Both endpoints come out of the same generated model and the census enumerates every schema
     * that model declares, so the referenced constraint is present by construction and the relation
     * declares the foreign key rather than leaving it a detection.
     */
    private static void captureForeignKeys(FactSink sink, JooqCatalog jooq, Table<?> table,
                                           String source, String schema, String name,
                                           Map<String, String> sourceByTable) {
        for (JooqCatalog.ForeignKeyFacts fk : jooq.foreignKeyFactsOf(table)) {
            if (!writeConstraint(sink, source, schema, name, fk.constraintName(), FOREIGN_KEY,
                fk.jooqName(), fk.columns())) {
                continue;
            }
            var referenced = split(fk.targetTable());
            var row = sink.dsl().newRecord(SQL_REFERENTIAL_CONSTRAINT);
            row.setSourceName(source);
            row.setReferencedSourceName(
                sourceByTable.getOrDefault(referenced[0] + "." + referenced[1], source));
            row.setTableSchema(schema);
            row.setTableName(name);
            row.setConstraintName(fk.constraintName());
            row.setReferencedSchema(referenced[0]);
            row.setReferencedTable(referenced[1]);
            row.setReferencedConstraintName(fk.referencedConstraintName());
            sink.add(row);
        }
    }

    /**
     * The supertype row and its ordered columns, shared by all three constraint forms.
     *
     * <p>{@code jooqName} is the {@code Keys}-class constant, null when the key resolved to none.
     * Threaded in rather than derived here: it comes from reference identity against the live key,
     * which only the catalog walk holds.
     */
    private static boolean writeConstraint(FactSink sink, String source, String schema, String name,
                                           String constraintName, String type, String jooqName,
                                           List<String> columns) {
        if (!sink.claim(SQL_CONSTRAINT, source, schema, name, constraintName)) {
            return false;
        }
        var record = sink.dsl().newRecord(SQL_CONSTRAINT);
        record.setSourceName(source);
        record.setTableSchema(schema);
        record.setTableName(name);
        record.setConstraintName(constraintName);
        record.setConstraintType(type);
        record.setJooqName(jooqName);
        sink.add(record);
        int position = 0;
        for (String column : columns) {
            var row = sink.dsl().newRecord(SQL_CONSTRAINT_COLUMN);
            row.setSourceName(source);
            row.setTableSchema(schema);
            row.setTableName(name);
            row.setConstraintName(constraintName);
            row.setPosition(position++);
            row.setColumnName(column);
            sink.add(row);
        }
        return true;
    }

    private static void captureIndexes(FactSink sink, JooqCatalog jooq, Table<?> table,
                                       String source, String schema, String name) {
        for (JooqCatalog.IndexFacts index : jooq.indexFactsOf(table)) {
            if (!sink.claim(SQL_INDEX, source, schema, name, index.name())) {
                continue;
            }
            var row = sink.dsl().newRecord(SQL_INDEX);
            row.setSourceName(source);
            row.setTableSchema(schema);
            row.setTableName(name);
            row.setIndexName(index.name());
            sink.add(row);
            int position = 0;
            for (String column : index.columns()) {
                var columnRow = sink.dsl().newRecord(SQL_INDEX_COLUMN);
                columnRow.setSourceName(source);
                columnRow.setTableSchema(schema);
                columnRow.setTableName(name);
                columnRow.setIndexName(index.name());
                columnRow.setPosition(position++);
                columnRow.setColumnName(column);
                sink.add(columnRow);
            }
        }
    }

    /**
     * Records the classes the classpath scan read, and the classpath entries it read them from.
     * Javadoc and Java source positions stay out by design: they live on the LSP source walker's
     * cadence and are joined at request time, so a {@code .java} edit is seen without a generator
     * rebuild.
     *
     * <p>Each class carries the entry it came from, which is the partition a refresh deletes and
     * re-walks. The entry's own row is claimed on first sight rather than from a separate pass over
     * the classpath, so a census with no classes from an entry records no entry: the store says
     * what the scan read, not what it was pointed at.
     */
    private static void captureExtensions(FactSink sink, ClasspathSources sources,
                                          List<CompletionData.ExternalReference> extensions) {
        for (CompletionData.ExternalReference reference : extensions) {
            // Membership is noted ahead of the class claim: a warm run pre-claims a retained
            // partition's classes, and the retained partition is still this graph's read.
            GraphSourceMembership.note(sink, reference.sourceName());
            String className = reference.className();
            if (!sink.claim(JVM_CLASS, className)) {
                continue;
            }
            String source = sources.record(sink, reference.sourceName());
            var record = sink.dsl().newRecord(JVM_CLASS);
            record.setClassName(className);
            record.setClassKind(reference.classKind());
            record.setSourceName(source);
            sink.add(record);

            for (CompletionData.Supertype supertype : reference.supertypes()) {
                if (!sink.claim(JVM_CLASS_SUPERTYPE, className, supertype.className())) {
                    continue;
                }
                var row = sink.dsl().newRecord(JVM_CLASS_SUPERTYPE);
                row.setSourceName(source);
                row.setClassName(className);
                row.setSupertypeName(supertype.className());
                row.setDeclaredVia(supertype.declaredVia());
                sink.add(row);
            }

            for (CompletionData.Method method : reference.methods()) {
                String descriptor = method.descriptor();
                if (!sink.claim(JVM_METHOD, className, method.name(), descriptor)) {
                    continue;
                }
                var row = sink.dsl().newRecord(JVM_METHOD);
                row.setSourceName(source);
                row.setClassName(className);
                row.setMethodName(method.name());
                row.setDescriptor(descriptor);
                row.setReturnType(method.returnType());
                row.setDeclaredReturnType(method.declaredReturnType());
                row.setReturnsCondition(method.returnsCondition());
                sink.add(row);
                for (CompletionData.TypeRef ref : method.returnTypeRefs()) {
                    var refRow = sink.dsl().newRecord(JVM_METHOD_RETURN_TYPE_REF);
                    refRow.setSourceName(source);
                    refRow.setClassName(className);
                    refRow.setMethodName(method.name());
                    refRow.setDescriptor(descriptor);
                    refRow.setTypePath(ref.path());
                    refRow.setReferencedClass(ref.referencedClass());
                    refRow.setVariance(ref.variance());
                    sink.add(refRow);
                }
                int position = 0;
                for (CompletionData.Parameter parameter : method.parameters()) {
                    int parameterPosition = position++;
                    var parameterRow = sink.dsl().newRecord(JVM_METHOD_PARAMETER);
                    parameterRow.setSourceName(source);
                    parameterRow.setClassName(className);
                    parameterRow.setMethodName(method.name());
                    parameterRow.setDescriptor(descriptor);
                    parameterRow.setPosition(parameterPosition);
                    parameterRow.setParameterName(parameter.name());
                    parameterRow.setParameterType(parameter.type());
                    parameterRow.setDeclaredParameterType(parameter.declaredType());
                    sink.add(parameterRow);
                    for (CompletionData.TypeRef ref : parameter.typeRefs()) {
                        var refRow = sink.dsl().newRecord(JVM_METHOD_PARAMETER_TYPE_REF);
                        refRow.setSourceName(source);
                        refRow.setClassName(className);
                        refRow.setMethodName(method.name());
                        refRow.setDescriptor(descriptor);
                        refRow.setPosition(parameterPosition);
                        refRow.setTypePath(ref.path());
                        refRow.setReferencedClass(ref.referencedClass());
                        refRow.setVariance(ref.variance());
                        sink.add(refRow);
                    }
                }
            }

            int position = 0;
            for (CompletionData.RecordComponent component : reference.recordComponents()) {
                if (!sink.claim(JVM_RECORD_COMPONENT, className, component.name())) {
                    position++;
                    continue;
                }
                var row = sink.dsl().newRecord(JVM_RECORD_COMPONENT);
                row.setSourceName(source);
                row.setClassName(className);
                row.setComponentName(component.name());
                row.setPosition(position++);
                row.setDisplayType(component.displayType());
                row.setDeclaredType(component.declaredType());
                sink.add(row);
                for (CompletionData.TypeRef ref : component.typeRefs()) {
                    var refRow = sink.dsl().newRecord(JVM_RECORD_COMPONENT_TYPE_REF);
                    refRow.setSourceName(source);
                    refRow.setClassName(className);
                    refRow.setComponentName(component.name());
                    refRow.setTypePath(ref.path());
                    refRow.setReferencedClass(ref.referencedClass());
                    refRow.setVariance(ref.variance());
                    sink.add(refRow);
                }
            }

            for (CompletionData.ScalarConstant constant : reference.scalarConstants()) {
                if (!sink.claim(JVM_SCALAR_TYPE_FIELD, className, constant.fieldName())) {
                    continue;
                }
                var row = sink.dsl().newRecord(JVM_SCALAR_TYPE_FIELD);
                row.setSourceName(source);
                row.setClassName(className);
                row.setFieldName(constant.fieldName());
                sink.add(row);
            }
        }
    }

    /** Splits a schema-qualified table ID; an unqualified name keeps an empty schema. */
    private static String[] split(String qualified) {
        int dot = qualified.indexOf('.');
        return dot < 0
            ? new String[] {"", qualified}
            : new String[] {qualified.substring(0, dot), qualified.substring(dot + 1)};
    }

    /**
     * A description column holds what the database said or nothing; jOOQ reports an absent comment
     * as the empty string, and a relation that confused {@code ""} with absent would make the
     * distinction a query could not recover.
     */
    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
