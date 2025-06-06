package no.sikt.graphitron.generators.codeinterface.wiring;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

/**
 * Class generator for the RuntimeWiring class.
 */
public class WiringClassGenerator extends AbstractClassGenerator {
    public final static String SAVE_DIRECTORY_NAME = "wiring", CLASS_NAME = "Wiring";
    private final List<? extends WiringBuilderMethodGenerator> generators;

    public WiringClassGenerator(List<ClassGenerator> generators, ProcessedSchema processedSchema) {
        this.generators = List.of(
                new WiringBuilderMethodGenerator(generators, processedSchema),
                new WiringMethodGenerator(processedSchema)
        );
    }

    @Override
    public List<TypeSpec> generateAll() {
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
