package no.sikt.graphitron.generators.resolvers.datafetchers.typeresolvers;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.interfaces.TypeResolverTarget;
import no.sikt.graphitron.generators.abstractions.AbstractSchemaMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.declare;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asTypeResolverMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getObjectMapTypeName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_ENV;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_OBJECT;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITY_UNION;
import static no.sikt.graphql.naming.GraphQLReservedName.TYPE_NAME;

/**
 * Class for generating any type resolvers.
 */
public class TypeResolverMethodGenerator extends AbstractSchemaMethodGenerator<TypeResolverTarget, TypeResolverTarget> {
    protected final List<WiringContainer> typeResolverWiring = new ArrayList<>();

    public TypeResolverMethodGenerator(TypeResolverTarget localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(TypeResolverTarget target) {
        var typeName = isEntity(target) ? FEDERATION_ENTITY_UNION.getName() : target.getName();
        typeResolverWiring.add(new WiringContainer(asTypeResolverMethodName(typeName), typeName, null, false));
        var spec = getDefaultSpecBuilder(asTypeResolverMethodName(typeName), TYPE_RESOLVER.className)
                .addModifiers(Modifier.STATIC);
        if (!isEntity(target)) { // At some point these two path will have to unify if both are to work.
            return spec.addCode(returnWrap(getResolver())).build();
        }

        return spec
                .beginControlFlow("return $N ->", VARIABLE_ENV)
                .addCode(declare(VARIABLE_OBJECT, CodeBlock.of("$N.getObject()", VARIABLE_ENV)))
                .beginControlFlow("if (!($N instanceof $T))", VARIABLE_OBJECT, MAP.className)
                .addCode(returnWrap("null"))
                .endControlFlow()
                .addCode(returnWrap(getEntityResolverReturn()))
                .endControlFlow("") // Keep this empty string, logic to set semicolon only kicks in if a string is set.
                .build();
    }

    private boolean isEntity(TypeResolverTarget target) {
        return target == null || FEDERATION_ENTITY_UNION.getName().equals(target.getName());
    }

    protected static CodeBlock getEntityResolverReturn() {
        return CodeBlock.of(
                "$N.getSchema().getObjectType(($T) (($T) $N).get($S))",
                VARIABLE_ENV,
                STRING.className,
                getObjectMapTypeName(),
                VARIABLE_OBJECT,
                TYPE_NAME.getName());
    }

    protected static CodeBlock getResolver() {
        return CodeBlock.of("$1L -> $1N.getSchema().getObjectType($2N($1N.getObject()))", VARIABLE_ENV, TypeNameMethodGenerator.METHOD_NAME);
    }

    public List<MethodSpec> generateAll() {
        return List.of(generate(getLocalObject()));
    }

    @Override
    public List<WiringContainer> getTypeResolverWiring() {
        return typeResolverWiring;
    }
}
