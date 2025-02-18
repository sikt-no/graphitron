package no.sikt.graphitron.generators.resolvers.kickstart.fetch;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.KickstartResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;

/**
 * Class generator for basic select resolver classes.
 */
public class FetchResolverClassGenerator extends KickstartResolverClassGenerator<ObjectDefinition> {
    public final String SAVE_DIRECTORY_NAME = "query";

    public FetchResolverClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateAll() {
        return processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(ObjectDefinition::isGenerated)
                .filter(obj -> !obj.getName().equals(SCHEMA_MUTATION.getName()))
                .map(this::generate)
                .filter(it -> !it.methodSpecs().isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        var spec = getSpec(
                target.getName(),
                List.of(
                        new FetchResolverMethodGenerator(target, processedSchema),
                        new FetchNodeResolverMethodGenerator(target, processedSchema)
                )
        );
        target
                .getFields()
                .stream()
                .filter(it -> it.getTypeName().equals(NODE_TYPE.getName()))
                .findFirst()
                .flatMap(this::buildNodeMap)
                .ifPresent(spec::addField);
        return spec.build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return KickstartResolverClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
