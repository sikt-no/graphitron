package no.sikt.graphitron.generators.codeinterface.typeregistry;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.SimpleMethodGenerator;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.SCHEMA_READER;
import static no.sikt.graphitron.mappings.JavaPoetClassName.TYPE_DEFINITION_REGISTRY;

/**
 * This class generates code for the Type Registry fetching method.
 */
public class TypeRegistryMethodGenerator extends SimpleMethodGenerator {
    public static final String METHOD_NAME = "getTypeRegistry";

    @Override
    public MethodSpec generate() {
        // Replaced from GeneratorConfig.generatorSchemaFiles() because the generator schema is not compatible with the user schema.
        var files = GeneratorConfig.schemaFiles().stream().map(it -> CodeBlock.of("$S", it)).collect(CodeBlock.joining(",\n"));
        return MethodSpec
                .methodBuilder(METHOD_NAME)
                .addModifiers(Modifier.PUBLIC)
                .returns(TYPE_DEFINITION_REGISTRY.className)
                .addModifiers(Modifier.STATIC)
                .addCode(
                        returnWrap(
                                CodeBlock.of(
                                        "$T.getTypeDefinitionRegistry($L)",
                                        SCHEMA_READER.className,
                                        indentIfMultiline(setOf(files))
                                )
                        )
                )  // Note that this annoyingly produces too many indents. Blame faulty indent handling. Subsequent statements have correct indent though.
                .build();
    }
}
