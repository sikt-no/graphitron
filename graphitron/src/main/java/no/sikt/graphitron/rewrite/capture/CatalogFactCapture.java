package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;

import java.util.List;

import static no.sikt.graphitron.model.Tables.CATALOG_COLUMN;
import static no.sikt.graphitron.model.Tables.CATALOG_FOREIGN_KEY;
import static no.sikt.graphitron.model.Tables.CATALOG_FOREIGN_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.CATALOG_INDEX;
import static no.sikt.graphitron.model.Tables.CATALOG_INDEX_COLUMN;
import static no.sikt.graphitron.model.Tables.CATALOG_KEY;
import static no.sikt.graphitron.model.Tables.CATALOG_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.CATALOG_TABLE;
import static no.sikt.graphitron.model.Tables.EXTENSION_CLASS;
import static no.sikt.graphitron.model.Tables.EXTENSION_METHOD;
import static no.sikt.graphitron.model.Tables.EXTENSION_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.EXTENSION_RECORD_COMPONENT;
import static no.sikt.graphitron.model.Tables.EXTENSION_SCALAR_CONSTANT;

/**
 * The catalog capture load: fills the {@code catalog_} family from the jOOQ catalog walk and the
 * {@code extension_} family from the bytecode-only classpath scan.
 *
 * <p>Both inputs arrive already reduced to values, which is the property the store then enforces
 * structurally: no live {@code Table<?>}, {@code ForeignKey}, or {@code Class<?>} can cross into a
 * relation, so nothing lazy survives the codegen classloader closing at the end of a pass.
 *
 * <p>Two deliberate departures from the shapes it reads. Foreign keys are stored once, on the
 * declaring side; the incoming direction {@code CatalogFacts} denormalises is a query here, which
 * is most of the point of having a store. And every uniqueness constraint is one row with the
 * primary key flagged rather than segregated, because excluding the PK is a projection choice
 * rather than a fact about the catalog.
 */
final class CatalogFactCapture {

    private CatalogFactCapture() {}

    static void capture(FactSink sink, CatalogFacts facts,
                        List<CompletionData.ExternalReference> extensions) {
        captureCatalog(sink, facts);
        captureExtensions(sink, extensions);
    }

    private static void captureCatalog(FactSink sink, CatalogFacts facts) {
        for (CatalogFacts.Table table : facts.tablesByQualifiedName().values()) {
            if (!sink.claim(CATALOG_TABLE, table.schema(), table.name())) {
                continue;
            }
            var record = sink.dsl().newRecord(CATALOG_TABLE);
            record.setTableSchema(table.schema());
            record.setTableName(table.name());
            record.setJavaName(table.name());
            record.setDescription(table.comment().orElse(null));
            sink.add(record);

            int ordinal = 0;
            for (CatalogFacts.Column column : table.columns()) {
                if (!sink.claim(CATALOG_COLUMN, table.schema(), table.name(), column.sqlName())) {
                    continue;
                }
                var row = sink.dsl().newRecord(CATALOG_COLUMN);
                row.setTableSchema(table.schema());
                row.setTableName(table.name());
                row.setColumnName(column.sqlName());
                row.setOrdinal(ordinal++);
                row.setJavaName(column.javaName());
                row.setSqlType(column.sqlType());
                row.setNullable(column.nullable());
                row.setDescription(column.comment().orElse(null));
                sink.add(row);
            }

            table.primaryKey().ifPresent(key -> captureKey(sink, table, key, true));
            for (CatalogFacts.Key key : table.uniqueKeys()) {
                captureKey(sink, table, key, false);
            }

            for (CatalogFacts.Index index : table.indexes()) {
                if (!sink.claim(CATALOG_INDEX, table.schema(), table.name(), index.name())) {
                    continue;
                }
                var row = sink.dsl().newRecord(CATALOG_INDEX);
                row.setTableSchema(table.schema());
                row.setTableName(table.name());
                row.setIndexName(index.name());
                sink.add(row);
                int position = 0;
                for (String column : index.columns()) {
                    var columnRow = sink.dsl().newRecord(CATALOG_INDEX_COLUMN);
                    columnRow.setTableSchema(table.schema());
                    columnRow.setTableName(table.name());
                    columnRow.setIndexName(index.name());
                    columnRow.setPosition(position++);
                    columnRow.setColumnName(column);
                    sink.add(columnRow);
                }
            }
        }

        // Foreign keys after every table exists: the relation references both endpoints, and the
        // target may be a table this loop reached later than the declaring one.
        for (CatalogFacts.Table table : facts.tablesByQualifiedName().values()) {
            for (CatalogFacts.OutgoingForeignKey fk : table.foreignKeys().outgoing()) {
                if (!sink.claim(CATALOG_FOREIGN_KEY, table.schema(), table.name(), fk.constraintName())) {
                    continue;
                }
                var target = split(fk.targetTable());
                var record = sink.dsl().newRecord(CATALOG_FOREIGN_KEY);
                record.setTableSchema(table.schema());
                record.setTableName(table.name());
                record.setConstraintName(fk.constraintName());
                record.setTargetSchema(target[0]);
                record.setTargetTable(target[1]);
                sink.add(record);
                int position = 0;
                int columns = Math.min(fk.columns().size(), fk.targetColumns().size());
                while (position < columns) {
                    var row = sink.dsl().newRecord(CATALOG_FOREIGN_KEY_COLUMN);
                    row.setTableSchema(table.schema());
                    row.setTableName(table.name());
                    row.setConstraintName(fk.constraintName());
                    row.setPosition(position);
                    row.setSourceColumn(fk.columns().get(position));
                    row.setTargetColumn(fk.targetColumns().get(position));
                    sink.add(row);
                    position++;
                }
            }
        }
    }

    private static void captureKey(FactSink sink, CatalogFacts.Table table,
                                   CatalogFacts.Key key, boolean primary) {
        if (!sink.claim(CATALOG_KEY, table.schema(), table.name(), key.constraintName())) {
            return;
        }
        var record = sink.dsl().newRecord(CATALOG_KEY);
        record.setTableSchema(table.schema());
        record.setTableName(table.name());
        record.setConstraintName(key.constraintName());
        record.setIsPrimary(primary);
        sink.add(record);
        int position = 0;
        for (String column : key.columns()) {
            var row = sink.dsl().newRecord(CATALOG_KEY_COLUMN);
            row.setTableSchema(table.schema());
            row.setTableName(table.name());
            row.setConstraintName(key.constraintName());
            row.setPosition(position++);
            row.setColumnName(column);
            sink.add(row);
        }
    }

    /**
     * Records the consumer's compiled extension classes. Javadoc and Java source positions stay
     * out by design: they live on the LSP source walker's cadence and are joined at request time,
     * so a {@code .java} edit is seen without a generator rebuild.
     */
    private static void captureExtensions(FactSink sink, List<CompletionData.ExternalReference> extensions) {
        for (CompletionData.ExternalReference reference : extensions) {
            String className = reference.className();
            if (!sink.claim(EXTENSION_CLASS, className)) {
                continue;
            }
            var record = sink.dsl().newRecord(EXTENSION_CLASS);
            record.setClassName(className);
            record.setClassKind(reference.recordComponents().isEmpty() ? "CLASS" : "RECORD");
            sink.add(record);

            for (CompletionData.Method method : reference.methods()) {
                String descriptor = descriptorOf(method);
                if (!sink.claim(EXTENSION_METHOD, className, method.name(), descriptor)) {
                    continue;
                }
                var row = sink.dsl().newRecord(EXTENSION_METHOD);
                row.setClassName(className);
                row.setMethodName(method.name());
                row.setDescriptor(descriptor);
                row.setReturnType(method.returnType());
                row.setReturnsCondition(method.returnsCondition());
                sink.add(row);
                int position = 0;
                for (CompletionData.Parameter parameter : method.parameters()) {
                    var parameterRow = sink.dsl().newRecord(EXTENSION_METHOD_PARAMETER);
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
                if (!sink.claim(EXTENSION_RECORD_COMPONENT, className, component.name())) {
                    position++;
                    continue;
                }
                var row = sink.dsl().newRecord(EXTENSION_RECORD_COMPONENT);
                row.setClassName(className);
                row.setComponentName(component.name());
                row.setPosition(position++);
                row.setDisplayType(component.displayType());
                sink.add(row);
            }

            for (CompletionData.ScalarConstant constant : reference.scalarConstants()) {
                if (!sink.claim(EXTENSION_SCALAR_CONSTANT, className, constant.fieldName())) {
                    continue;
                }
                var row = sink.dsl().newRecord(EXTENSION_SCALAR_CONSTANT);
                row.setClassName(className);
                row.setFieldName(constant.fieldName());
                sink.add(row);
            }
        }
    }

    /**
     * The overload discriminator that keeps {@code extension_method}'s key natural. The scan's
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
}
