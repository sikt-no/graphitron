package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Produces one table class per {@link GraphitronType.TableType} in the schema.
 *
 * <p>Collects per table:
 * <ul>
 *   <li>{@link ChildField.ColumnField}s for the {@code fields()} SELECT-list method</li>
 *   <li>{@link QueryField.QueryTableField}s for per-field condition methods</li>
 * </ul>
 */
public class TableClassGenerator {

    public static List<TypeSpec> generate(GraphitronSchema schema) {
        var codeGenerator = new TableCodeGenerator();

        var tablesByClassName = new LinkedHashMap<String, TableRef>();
        var columnsByClassName = new LinkedHashMap<String, List<ChildField.ColumnField>>();
        var queryFieldsByClassName = new LinkedHashMap<String, List<QueryField.QueryTableField>>();

        // Collect column fields per table
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

        // Collect query table fields per target table
        for (var type : schema.types().values()) {
            if (!(type instanceof GraphitronType.RootType)) continue;
            for (var field : schema.fieldsOf(type.name())) {
                if (field instanceof QueryField.QueryTableField qtf) {
                    var className = qtf.returnType().table().javaClassName();
                    queryFieldsByClassName.computeIfAbsent(className, k -> new ArrayList<>()).add(qtf);
                }
            }
        }

        return tablesByClassName.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getKey()))
            .map(e -> codeGenerator.generate(
                e.getValue(),
                columnsByClassName.getOrDefault(e.getKey(), List.of()),
                queryFieldsByClassName.getOrDefault(e.getKey(), List.of())))
            .toList();
    }
}
