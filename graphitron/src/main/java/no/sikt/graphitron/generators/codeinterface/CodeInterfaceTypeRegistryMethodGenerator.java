package no.sikt.graphitron.generators.codeinterface;

import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.generators.abstractions.SimpleMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.typeregistry.TypeRegistryClassGenerator;
import no.sikt.graphitron.generators.codeinterface.typeregistry.TypeRegistryMethodGenerator;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.asMethodCall;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;
import static no.sikt.graphitron.mappings.JavaPoetClassName.TYPE_DEFINITION_REGISTRY;

/**
 * This class generates code for a simple Type Registry fetching method.
 */
public class CodeInterfaceTypeRegistryMethodGenerator extends SimpleMethodGenerator {
    @Override
    public MethodSpec generate() {
        var className = getGeneratedClassName(TypeRegistryClassGenerator.SAVE_DIRECTORY_NAME, TypeRegistryClassGenerator.CLASS_NAME);
        return MethodSpec
                .methodBuilder(TypeRegistryMethodGenerator.METHOD_NAME)
                .addModifiers(Modifier.PUBLIC)
                .addModifiers(Modifier.STATIC)
                .returns(TYPE_DEFINITION_REGISTRY.className)
                .addCode(returnWrap(asMethodCall(className, TypeRegistryMethodGenerator.METHOD_NAME)))
                .build();
    }
}
