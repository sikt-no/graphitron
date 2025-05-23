package no.sikt.graphitron.generators.codeinterface.typeregistry;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;

import java.util.List;

/**
 * Class generator for the class that returns the type registry.
 */
public class TypeRegistryClassGenerator extends AbstractClassGenerator {
    public final static String SAVE_DIRECTORY_NAME = "typeregistry", CLASS_NAME = "TypeRegistry";

    @Override
    public List<TypeSpec> generateAll() {
        return List.of(getSpec(CLASS_NAME, new TypeRegistryMethodGenerator()).build());
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
