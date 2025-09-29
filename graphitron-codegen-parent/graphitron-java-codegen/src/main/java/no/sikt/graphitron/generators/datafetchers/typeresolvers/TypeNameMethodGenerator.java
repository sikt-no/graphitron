package no.sikt.graphitron.generators.datafetchers.typeresolvers;

import no.sikt.graphitron.definitions.interfaces.TypeResolverTarget;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.AbstractSchemaMethodGenerator;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_OBJECT;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

/**
 * Class for generating the helper method for type resolvers.
 */
public class TypeNameMethodGenerator extends AbstractSchemaMethodGenerator<TypeResolverTarget, TypeResolverTarget> {
    public static final String METHOD_NAME = "getName";

    public TypeNameMethodGenerator(TypeResolverTarget localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(TypeResolverTarget target) {
        var components = getComponents(target).stream().map(TypeNameMethodGenerator::ifStatement).collect(CodeBlock.joining("\n"));
        return getDefaultSpecBuilder(METHOD_NAME, STRING.className)
                .addModifiers(Modifier.STATIC)
                .addParameter(OBJECT.className, VARIABLE_OBJECT)
                .addCode(components)
                .addStatement("throw new $T(\"Type of \" + $N + \" can not be resolved.\")", ILLEGAL_ARGUMENT_EXCEPTION.className, VARIABLE_OBJECT)
                .build();
    }

    private Set<ObjectDefinition> getComponents(TypeResolverTarget target) {
        if (processedSchema.isInterface(target.getName()) || processedSchema.isUnion(target.getName())) {
            return processedSchema.getTypesFromInterfaceOrUnion(target.getName());
        }
        return Set.of();
    }

    private static CodeBlock ifStatement(ObjectDefinition obj) {
        return CodeBlock
                .builder()
                .beginControlFlow("if ($N instanceof $T)", VARIABLE_OBJECT, obj.getGraphClassName())
                .add(returnWrap(CodeBlock.of("$S", obj.getName())))
                .endControlFlow()
                .build();
    }

    public List<MethodSpec> generateAll() {
        var localObject = getLocalObject();
        return localObject == null ? List.of() : List.of(generate(localObject));
    }
}
