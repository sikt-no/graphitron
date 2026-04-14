package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import java.util.Comparator;
import java.util.List;

/**
 * Produces one table class per table-mapped GraphQL type in the schema.
 *
 * <p>Class names follow the GraphQL type name (e.g. {@code Film} for GraphQL type {@code Film}).
 * If two GraphQL types map to the same SQL table, each gets its own table class with its own
 * {@code fields()}, {@code selectMany}, etc.
 *
 * <p>Generated files are placed in the {@code rewrite.tables} sub-package of the configured
 * output package.
 */
public class TableClassGenerator {

    public static List<TypeSpec> generate(GraphitronSchema schema) {
        var codeGenerator = new TableCodeGenerator();
        return schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.NodeType)
            .map(java.util.Map.Entry::getKey)
            .sorted()
            .map(typeName -> generateForType(schema, typeName, codeGenerator))
            .toList();
    }

    private static TypeSpec generateForType(GraphitronSchema schema, String typeName,
            TableCodeGenerator codeGenerator) {
        var type = (GraphitronType.TableBackedType) schema.type(typeName);
        var columnFields = schema.fieldsOf(typeName).stream()
            .filter(f -> f instanceof ChildField.ColumnField)
            .map(f -> (ChildField.ColumnField) f)
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        return codeGenerator.generate(typeName, type.table(), columnFields);
    }
}
