package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.type.GraphitronType;

import java.util.List;

/**
 * {@link no.sikt.graphitron.generators.abstractions.ClassGenerator} that produces one
 * {@code <TypeName>Fields.java} per {@link GraphitronType.TableType} in the schema.
 *
 * <p>Generated files are placed in the {@code rewrite.fields} sub-package of the configured
 * output package.
 */
public class FieldsClassGenerator extends AbstractClassGenerator {

    static final String SAVE_DIRECTORY = "rewrite.fields";

    private final List<String> typeNames;
    private final FieldsCodeGenerator codeGenerator = new FieldsCodeGenerator();

    public FieldsClassGenerator(GraphitronSchema schema) {
        this.typeNames = schema.types().values().stream()
            .filter(t -> t instanceof GraphitronType.TableType)
            .map(GraphitronType::name)
            .sorted()
            .toList();
    }

    @Override
    public List<TypeSpec> generateAll() {
        return typeNames.stream()
            .map(codeGenerator::generate)
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
}
