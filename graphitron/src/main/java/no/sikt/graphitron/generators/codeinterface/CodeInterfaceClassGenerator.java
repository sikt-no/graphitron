package no.sikt.graphitron.generators.codeinterface;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringMethodGenerator;

import java.util.List;

/**
 * Class generator for the code interface.
 */
public class CodeInterfaceClassGenerator extends AbstractClassGenerator {
    public final static String SAVE_DIRECTORY_NAME = "graphitron", CLASS_NAME = "Graphitron";
    private final boolean includeNode;

    public CodeInterfaceClassGenerator(boolean includeNode) {
        this.includeNode = includeNode;
    }

    @Override
    public List<TypeSpec> generateAll() {
        return List.of(
                getSpec(
                        CLASS_NAME,
                        List.of(
                                new CodeInterfaceTypeRegistryMethodGenerator(),
                                new CodeInterfaceBuilderMethodGenerator(includeNode),
                                new WiringMethodGenerator(includeNode)
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
