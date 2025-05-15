package no.sikt.graphitron.generators.codeinterface;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

/**
 * Class generator for the code interface.
 */
public class CodeInterfaceClassGenerator extends AbstractClassGenerator {
    public final static String SAVE_DIRECTORY_NAME = "graphitron", CLASS_NAME = "Graphitron";
    private final boolean includeNode;
    private final ProcessedSchema processedSchema;

    public CodeInterfaceClassGenerator(ProcessedSchema processedSchema) {
        this.includeNode = processedSchema.nodeExists();
        this.processedSchema = processedSchema;
    }

    @Override
    public List<TypeSpec> generateAll() {
        return List.of(
                getSpec(
                        CLASS_NAME,
                        List.of(
                                new CodeInterfaceTypeRegistryMethodGenerator(),
                                new CodeInterfaceBuilderMethodGenerator(processedSchema),
                                new WiringMethodGenerator(processedSchema),
                                new CodeInterfaceSchemaMethodGenerator(includeNode)
                        )
                ).build()
        );
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return SAVE_DIRECTORY_NAME;
    }

    @Override
    public String getFileNameSuffix() {
        return "";
    }
}
