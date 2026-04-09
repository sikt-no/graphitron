package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.generators.AbstractRewriteClassGenerator;
import no.sikt.graphitron.rewrite.type.GraphitronType;

import java.util.List;

/**
 * Generator that produces one {@code <TypeName>Fields.java} per table-mapped or root GraphQL type
 * in the schema.
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
public class FieldsClassGenerator extends AbstractRewriteClassGenerator {

    static final String SAVE_DIRECTORY = "rewrite.types";

    private final FieldsCodeGenerator codeGenerator = new FieldsCodeGenerator();

    @Override
    public List<TypeSpec> generateAll(GraphitronSchema schema) {
        return schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.RootType)
            .map(java.util.Map.Entry::getKey)
            .sorted()
            .map(typeName -> generateForType(schema, typeName))
            .toList();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return SAVE_DIRECTORY;
    }

    private TypeSpec generateForType(GraphitronSchema schema, String typeName) {
        var fieldNames = schema.fields().entrySet().stream()
            .filter(e -> e.getKey().getTypeName().equals(typeName))
            .map(java.util.Map.Entry::getValue)
            .filter(f -> !(f instanceof GraphitronField.NotGeneratedField))
            .filter(f -> !(f instanceof GraphitronField.UnclassifiedField))
            .map(GraphitronField::name)
            .sorted()
            .toList();
        return codeGenerator.generate(typeName, fieldNames);
    }
}
