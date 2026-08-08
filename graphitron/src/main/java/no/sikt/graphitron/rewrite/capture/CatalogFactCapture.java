package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.UniqueKey;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_INDEX;
import static no.sikt.graphitron.model.Tables.SQL_INDEX_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT;
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

    private CatalogFactCapture() {}

    static void capture(FactSink sink, JooqCatalog jooq,
                        List<CompletionData.ExternalReference> extensions) {
        captureCatalog(sink, jooq);
        captureExtensions(sink, extensions);
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
        for (JooqCatalog.TableEntry entry : jooq.allTableEntries()) {
            Table<?> table = entry.table();
            String schema = table.getSchema() == null ? "" : table.getSchema().getName();
            String name = table.getName();
            if (!sink.claim(SQL_TABLE, schema, name)) {
                continue;
            }
            var record = sink.dsl().newRecord(SQL_TABLE);
            record.setTableSchema(schema);
            record.setTableName(name);
            record.setJooqName(entry.javaFieldName());
            record.setDescription(nullIfBlank(table.getComment()));
            sink.add(record);

            captureColumns(sink, jooq, table, schema, name);
            captureConstraints(sink, table, schema, name);
            captureForeignKeys(sink, jooq, table, schema, name);
            captureIndexes(sink, jooq, table, schema, name);
        }
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
                                       String schema, String name) {
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
            if (!sink.claim(SQL_COLUMN, schema, name, column.sqlName())) {
                continue;
            }
            var row = sink.dsl().newRecord(SQL_COLUMN);
            row.setTableSchema(schema);
            row.setTableName(name);
            row.setColumnName(column.sqlName());
            row.setOrdinal(ordinal);
            row.setJooqName(column.javaName());
            row.setSqlType(column.sqlType());
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
    private static void captureConstraints(FactSink sink, Table<?> table, String schema, String name) {
        UniqueKey<?> primary = table.getPrimaryKey();
        var keys = new LinkedHashSet<UniqueKey<?>>(table.getKeys());
        if (primary != null) {
            // jOOQ's getKeys() carries the primary key too, so this is normally a no-op; the union
            // is what keeps sql_primary_key's reference resolvable if a model ever separates them.
            keys.add(primary);
        }
        for (UniqueKey<?> key : keys) {
            writeConstraint(sink, schema, name, key.getName(),
                key.equals(primary) ? PRIMARY_KEY : UNIQUE,
                key.getFields().stream().map(Field::getName).toList());
        }
        if (primary != null) {
            var row = sink.dsl().newRecord(SQL_PRIMARY_KEY);
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
                                           String schema, String name) {
        for (JooqCatalog.ForeignKeyFacts fk : jooq.foreignKeyFactsOf(table)) {
            if (!writeConstraint(sink, schema, name, fk.constraintName(), FOREIGN_KEY, fk.columns())) {
                continue;
            }
            var referenced = split(fk.targetTable());
            var row = sink.dsl().newRecord(SQL_REFERENTIAL_CONSTRAINT);
            row.setTableSchema(schema);
            row.setTableName(name);
            row.setConstraintName(fk.constraintName());
            row.setReferencedSchema(referenced[0]);
            row.setReferencedTable(referenced[1]);
            row.setReferencedConstraintName(fk.referencedConstraintName());
            sink.add(row);
        }
    }

    /** The supertype row and its ordered columns, shared by all three constraint forms. */
    private static boolean writeConstraint(FactSink sink, String schema, String name,
                                           String constraintName, String type, List<String> columns) {
        if (!sink.claim(SQL_CONSTRAINT, schema, name, constraintName)) {
            return false;
        }
        var record = sink.dsl().newRecord(SQL_CONSTRAINT);
        record.setTableSchema(schema);
        record.setTableName(name);
        record.setConstraintName(constraintName);
        record.setConstraintType(type);
        sink.add(record);
        int position = 0;
        for (String column : columns) {
            var row = sink.dsl().newRecord(SQL_CONSTRAINT_COLUMN);
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
                                       String schema, String name) {
        for (JooqCatalog.IndexFacts index : jooq.indexFactsOf(table)) {
            if (!sink.claim(SQL_INDEX, schema, name, index.name())) {
                continue;
            }
            var row = sink.dsl().newRecord(SQL_INDEX);
            row.setTableSchema(schema);
            row.setTableName(name);
            row.setIndexName(index.name());
            sink.add(row);
            int position = 0;
            for (String column : index.columns()) {
                var columnRow = sink.dsl().newRecord(SQL_INDEX_COLUMN);
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
     * Records the classes the classpath scan read. Javadoc and Java source positions stay
     * out by design: they live on the LSP source walker's cadence and are joined at request time,
     * so a {@code .java} edit is seen without a generator rebuild.
     */
    private static void captureExtensions(FactSink sink, List<CompletionData.ExternalReference> extensions) {
        for (CompletionData.ExternalReference reference : extensions) {
            String className = reference.className();
            if (!sink.claim(JVM_CLASS, className)) {
                continue;
            }
            var record = sink.dsl().newRecord(JVM_CLASS);
            record.setClassName(className);
            record.setClassKind(reference.classKind());
            sink.add(record);

            for (CompletionData.Method method : reference.methods()) {
                String descriptor = descriptorOf(method);
                if (!sink.claim(JVM_METHOD, className, method.name(), descriptor)) {
                    continue;
                }
                var row = sink.dsl().newRecord(JVM_METHOD);
                row.setClassName(className);
                row.setMethodName(method.name());
                row.setDescriptor(descriptor);
                row.setReturnType(method.returnType());
                row.setReturnsCondition(method.returnsCondition());
                sink.add(row);
                int position = 0;
                for (CompletionData.Parameter parameter : method.parameters()) {
                    var parameterRow = sink.dsl().newRecord(JVM_METHOD_PARAMETER);
                    parameterRow.setClassName(className);
                    parameterRow.setMethodName(method.name());
                    parameterRow.setDescriptor(descriptor);
                    parameterRow.setPosition(position++);
                    parameterRow.setParameterName(parameter.name());
                    parameterRow.setParameterType(parameter.type());
                    sink.add(parameterRow);
                }
            }

            int position = 0;
            for (CompletionData.RecordComponent component : reference.recordComponents()) {
                if (!sink.claim(JVM_RECORD_COMPONENT, className, component.name())) {
                    position++;
                    continue;
                }
                var row = sink.dsl().newRecord(JVM_RECORD_COMPONENT);
                row.setClassName(className);
                row.setComponentName(component.name());
                row.setPosition(position++);
                row.setDisplayType(component.displayType());
                sink.add(row);
            }

            for (CompletionData.ScalarConstant constant : reference.scalarConstants()) {
                if (!sink.claim(JVM_SCALAR_TYPE_FIELD, className, constant.fieldName())) {
                    continue;
                }
                var row = sink.dsl().newRecord(JVM_SCALAR_TYPE_FIELD);
                row.setClassName(className);
                row.setFieldName(constant.fieldName());
                sink.add(row);
            }
        }
    }

    /**
     * The overload discriminator that keeps {@code jvm_method}'s key natural. The scan's
     * projection carries erased display types rather than the raw JVM descriptor, so the
     * discriminator is rebuilt from them: same information, same discriminating power over the
     * overload set of one class, and no live handle involved.
     */
    private static String descriptorOf(CompletionData.Method method) {
        var builder = new StringBuilder("(");
        for (CompletionData.Parameter parameter : method.parameters()) {
            builder.append(parameter.type()).append(';');
        }
        return builder.append(')').append(method.returnType()).toString();
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
