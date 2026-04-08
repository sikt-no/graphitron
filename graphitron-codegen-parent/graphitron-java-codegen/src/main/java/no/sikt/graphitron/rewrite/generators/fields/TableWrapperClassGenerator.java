package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.type.GraphitronType;

import java.util.List;

/**
 * {@link no.sikt.graphitron.generators.abstractions.ClassGenerator} that produces one
 * {@code <TypeName>TableWrapper.java} per {@link GraphitronType.TableType} in the schema.
 *
 * <p>Generated files are placed in the {@code rewrite.tablewrapper} sub-package of the configured
 * output package.
 */
public class TableWrapperClassGenerator extends AbstractClassGenerator {

    static final String SAVE_DIRECTORY = "rewrite.tablewrapper";

    private final List<String> typeNames;
    private final TableWrapperCodeGenerator codeGenerator = new TableWrapperCodeGenerator();

    public TableWrapperClassGenerator(GraphitronSchema schema) {
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
