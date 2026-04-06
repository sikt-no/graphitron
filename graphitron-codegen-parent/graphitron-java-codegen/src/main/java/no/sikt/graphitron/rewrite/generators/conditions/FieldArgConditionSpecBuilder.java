package no.sikt.graphitron.rewrite.generators.conditions;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.field.ArgumentSpec;
import no.sikt.graphitron.rewrite.field.QueryField;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import no.sikt.graphitron.rewrite.type.TableRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@link FieldArgConditionSpec} instances from a {@link GraphitronSchema}.
 *
 * <p>Scans all {@link QueryField.TableQueryField} fields for arguments annotated with
 * {@code @condition}. Groups them by the return type name and merges across multiple query fields
 * that share the same return type (de-duplicating by column Java name).
 */
public class FieldArgConditionSpecBuilder {

    public static List<FieldArgConditionSpec> build(GraphitronSchema schema, JooqCatalog catalog) {
        Map<String, List<ArgConditionSpec>> argsByType = new LinkedHashMap<>();
        Map<String, String> tableFieldByType = new LinkedHashMap<>();
        Map<String, Set<String>> seenColumnsByType = new LinkedHashMap<>();

        schema.fields().values().stream()
            .filter(f -> f instanceof QueryField.TableQueryField)
            .map(f -> (QueryField.TableQueryField) f)
            .forEach(field -> {
                var conditionArgs = field.arguments().stream()
                    .filter(ArgumentSpec::conditionArg)
                    .toList();
                if (conditionArgs.isEmpty()) return;

                if (!(field.returnType() instanceof ReturnTypeRef.TableBoundReturnType trt)) return;
                if (!(trt.table() instanceof TableRef.ResolvedTable resolvedTable)) return;

                var typeName = trt.returnTypeName();
                var table = resolvedTable.table();
                var tableFieldName = resolvedTable.javaFieldName();

                tableFieldByType.put(typeName, tableFieldName);
                argsByType.computeIfAbsent(typeName, k -> new ArrayList<>());
                seenColumnsByType.computeIfAbsent(typeName, k -> new LinkedHashSet<>());

                for (var arg : conditionArgs) {
                    var colEntry = catalog.findColumn(table, arg.columnName());
                    if (colEntry.isEmpty()) continue;

                    var col = colEntry.get();
                    // De-duplicate by column Java name when multiple fields share a return type
                    if (!seenColumnsByType.get(typeName).add(col.javaName())) continue;

                    argsByType.get(typeName).add(new ArgConditionSpec(
                        arg.name(),
                        col.javaName(),
                        arg.typeName(),
                        col.column().getType().getName(),
                        determineOp(arg.name())
                    ));
                }
            });

        return argsByType.entrySet().stream()
            .filter(e -> !e.getValue().isEmpty())
            .map(e -> new FieldArgConditionSpec(
                e.getKey(),
                tableFieldByType.get(e.getKey()),
                List.copyOf(e.getValue())
            ))
            .toList();
    }

    /**
     * Infers the SQL comparison operator from the argument name prefix.
     * {@code max*} → {@code le} (less-than-or-equal),
     * {@code min*} → {@code ge} (greater-than-or-equal),
     * everything else → {@code eq} (equality).
     */
    private static String determineOp(String argName) {
        if (argName.startsWith("max")) return "le";
        if (argName.startsWith("min")) return "ge";
        return "eq";
    }
}
