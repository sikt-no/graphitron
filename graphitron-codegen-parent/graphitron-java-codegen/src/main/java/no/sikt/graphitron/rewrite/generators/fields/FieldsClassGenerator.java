package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.generators.AbstractRewriteClassGenerator;
import no.sikt.graphitron.rewrite.type.GraphitronType;

import java.util.List;

/**
 * {@link no.sikt.graphitron.generators.abstractions.ClassGenerator} that produces one
 * {@code <TypeName>Fields.java} per table-mapped or root GraphQL type in the schema.
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

    private final GraphitronSchema schema;
    private final List<String> typeNames;
    private final FieldsCodeGenerator codeGenerator = new FieldsCodeGenerator();

    public FieldsClassGenerator(GraphitronSchema schema) {
        this.schema = schema;
        this.typeNames = schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.RootType)
            .map(java.util.Map.Entry::getKey)
            .sorted()
            .toList();
    }

    @Override
    public List<TypeSpec> generateAll() {
        return typeNames.stream()
            .map(this::generateForType)
            .toList();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return SAVE_DIRECTORY;
    }

    @Override
    public String getFileNameSuffix() {
        return "";
    }

    private TypeSpec generateForType(String typeName) {
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
