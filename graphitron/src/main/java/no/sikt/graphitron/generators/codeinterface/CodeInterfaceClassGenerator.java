package no.sikt.graphitron.generators.codeinterface;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;

import java.util.List;

/**
 * Class generator for the code interface.
 */
public class CodeInterfaceClassGenerator extends AbstractClassGenerator {
    public final static String SAVE_DIRECTORY_NAME = "graphitron", FILE_NAME = "Graphitron";

    public CodeInterfaceClassGenerator(List<ClassGenerator> generators) {}

    @Override
    public List<TypeSpec> generateAll() {
        return List.of();
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
