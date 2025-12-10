package no.sikt.graphitron.generators.codeinterface;

import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.generators.abstractions.SimpleMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringMethodGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
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
        var generators = new ArrayList<SimpleMethodGenerator>();
        generators.add(new CodeInterfaceTypeRegistryMethodGenerator());
        generators.add(new CodeInterfaceBuilderMethodGenerator(processedSchema));
        generators.add(new WiringMethodGenerator(processedSchema));
        generators.add(new CodeInterfaceSchemaMethodGenerator(includeNode));
        if (processedSchema.isFederationImported()) {
            generators.add(new CodeInterfaceFederatedSchemaMethodGenerator(processedSchema));
        }
        return List.of(getSpec(CLASS_NAME, generators).build());
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
