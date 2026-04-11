package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.Comparator;
import java.util.List;

/**
 * Produces one {@code <TypeName>Fields.java} per table-mapped or root GraphQL type in the schema.
 *
 * <p>Class names are {@code <GraphQLTypeName>Fields} (e.g. {@code FilmFields} for GraphQL type
 * {@code Film}, {@code QueryFields} for the root {@code Query} type). This is distinct from the
 * SQL-scope classes in {@code rewrite.tables}, which are named after the jOOQ table class.
 *
 * <p>Only {@link GraphitronType.TableType} and {@link GraphitronType.RootType} types produce a
 * {@code *Fields} class. Fields annotated with {@code @notGenerated} and fields that could not be
 * classified are excluded from the generated wiring.
 *
 * <p>Generated files are placed in the {@code rewrite.types} sub-package of the configured
 * output package.
 */
public class FieldsClassGenerator {

    public static List<TypeSpec> generate(GraphitronSchema schema) {
        var codeGenerator = new FieldsCodeGenerator();
        return schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.RootType)
            .map(java.util.Map.Entry::getKey)
            .sorted()
            .map(typeName -> generateForType(schema, typeName, codeGenerator))
            .toList();
    }

    private static TypeSpec generateForType(GraphitronSchema schema, String typeName, FieldsCodeGenerator codeGenerator) {
        var type = (GraphitronType.OutputType) schema.type(typeName);
        var fields = type.fields().stream()
            .filter(f -> !(f instanceof GraphitronField.NotGeneratedField))
            .filter(f -> !(f instanceof GraphitronField.UnclassifiedField))
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        TableRef parentTable = tableRefForType(type);
        return codeGenerator.generate(typeName, parentTable, fields);
    }

    /**
     * Returns the {@link TableRef} for the given type if it is a
     * {@link GraphitronType.TableType}, or {@code null} for root types and other non-table types.
     */
    private static TableRef tableRefForType(GraphitronType.OutputType type) {
        if (type instanceof GraphitronType.TableType tt) {
            return tt.table();
        }
        return null;
    }
}
