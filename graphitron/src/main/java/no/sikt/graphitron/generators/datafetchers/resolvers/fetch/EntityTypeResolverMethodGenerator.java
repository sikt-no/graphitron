package no.sikt.graphitron.generators.datafetchers.resolvers.fetch;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.objects.UnionDefinition;
import no.sikt.graphitron.generators.abstractions.TypeResolverMethodGenerator;
import no.sikt.graphitron.generators.datafetchers.wiring.WiringContainer;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asTypeResolverMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getObjectMapTypeName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_ENV;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VARIABLE_OBJECT;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

/**
 * This class generates the entity type resolver.
 */
public class EntityTypeResolverMethodGenerator extends TypeResolverMethodGenerator<UnionDefinition> {
    public static final String METHOD_NAME = "entity";

    public EntityTypeResolverMethodGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public MethodSpec generate(UnionDefinition dummy) {
        var resolverCode = returnWrap(
                CodeBlock.of(
                        "$N.getSchema().getObjectType(($T) (($T) $N).get($S))",
                        VARIABLE_ENV,
                        STRING.className,
                        getObjectMapTypeName(),
                        VARIABLE_OBJECT,
                        TYPE_NAME.getName()
                )
        );
        typeResolverWiring.add(new WiringContainer(asTypeResolverMethodName(METHOD_NAME), FEDERATION_ENTITY_UNION.getName(), null));
        return getDefaultSpecBuilder(FEDERATION_ENTITY_UNION.getName())
                .addCode(wrapResolver(resolverCode))
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (!processedSchema.hasEntitiesField()) {
            return List.of();
        }
        // Federation is not available, so this doesn't actually find the union. If it becomes available, use it instead.
        return List.of(generate(processedSchema.getUnion(FEDERATION_ENTITY_UNION.getName())));
    }

    @Override
    public boolean generatesAll() {
        return true;
    }
}
