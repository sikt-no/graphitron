package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Produces one table class per {@link GraphitronType.TableType} in the schema.
 *
 * <p>Class names come from {@link TableRef#javaClassName()}, the simple name of the
 * jOOQ-generated table class obtained at catalog resolution time via reflection. This respects any
 * custom jOOQ naming strategy. The GraphQL type name may differ from the table class name.
 *
 * <p>Collects all {@link ChildField.ColumnField} instances across all GraphQL types that map to
 * the same SQL table, deduplicates by jOOQ column name, and passes them to
 * {@link TableCodeGenerator} for the {@code fields()} SELECT-list method.
 *
 * <p>Generated files are placed in the {@code rewrite.tables} sub-package of the configured
 * output package.
 */
public class TableClassGenerator {

    public static List<TypeSpec> generate(GraphitronSchema schema) {
        var codeGenerator = new TableCodeGenerator();

        // Group table types by jOOQ class name, keeping the first TableRef per class
        var tablesByClassName = new LinkedHashMap<String, TableRef>();
        var columnsByClassName = new LinkedHashMap<String, List<ChildField.ColumnField>>();

        for (var type : schema.types().values()) {
            if (!(type instanceof GraphitronType.TableBackedType tbt)) continue;
            if (type instanceof GraphitronType.TableInterfaceType) continue;
            var tableRef = tbt.table();
            var className = tableRef.javaClassName();

            tablesByClassName.putIfAbsent(className, tableRef);
            var columns = columnsByClassName.computeIfAbsent(className, k -> new ArrayList<>());

            var seen = columns.stream()
                .map(cf -> cf.column().javaName())
                .collect(Collectors.toSet());
            for (var field : schema.fieldsOf(tbt.name())) {
                if (field instanceof ChildField.ColumnField cf && !seen.contains(cf.column().javaName())) {
                    columns.add(cf);
                    seen.add(cf.column().javaName());
                }
            }
        }

        return tablesByClassName.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getKey()))
            .map(e -> codeGenerator.generate(e.getValue(), columnsByClassName.getOrDefault(e.getKey(), List.of())))
            .toList();
    }
}
