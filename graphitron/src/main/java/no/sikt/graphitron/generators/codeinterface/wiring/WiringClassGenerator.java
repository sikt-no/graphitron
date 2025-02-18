package no.sikt.graphitron.generators.codeinterface.wiring;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;

import java.util.List;

/**
 * Class generator for the RuntimeWiring class.
 */
public class WiringClassGenerator extends AbstractClassGenerator {
    public final static String SAVE_DIRECTORY_NAME = "wiring", FILE_NAME = "Wiring";
    private final WiringMethodGenerator generator;

    public WiringClassGenerator(List<ClassGenerator> generators, boolean includeNode) {
        generator = new WiringMethodGenerator(generators, includeNode);
    }

    @Override
    public List<TypeSpec> generateAll() {
        return List.of(getSpec(FILE_NAME, generator).build());
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
