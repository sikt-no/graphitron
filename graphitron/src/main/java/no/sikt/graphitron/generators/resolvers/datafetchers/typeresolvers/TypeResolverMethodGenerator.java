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

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asTypeResolverMethodName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_ENV;
import static no.sikt.graphitron.mappings.JavaPoetClassName.TYPE_RESOLVER;

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
        typeResolverWiring.add(new WiringContainer(asTypeResolverMethodName(target.getName()), target.getName(), null, false));
        return getDefaultSpecBuilder(asTypeResolverMethodName(target.getName()), TYPE_RESOLVER.className)
                .addModifiers(Modifier.STATIC)
                .addCode(returnWrap(CodeBlock.of("$1L -> $1N.getSchema().getObjectType($2N($1N.getObject()))", VARIABLE_ENV, TypeNameMethodGenerator.METHOD_NAME)))
                .build();
    }

    public List<MethodSpec> generateAll() {
        return List.of(generate(getLocalObject()));
    }

    @Override
    public List<WiringContainer> getTypeResolverWiring() {
        return typeResolverWiring;
    }
}
