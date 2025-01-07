package no.sikt.graphitron.generators.resolvers.datafetchers.fetch;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphitron.generators.abstractions.KickstartResolverClassGenerator;
import no.sikt.graphitron.generators.abstractions.MethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;

/**
 * Class generator for select data fetchers classes.
 */
public class FetchClassGenerator extends DataFetcherClassGenerator<ObjectDefinition> {
    public final String SAVE_DIRECTORY_NAME = "query";

    public FetchClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateTypeSpecs() {
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
        var generators = List.<MethodGenerator<? extends GenerationTarget>>of(
                new FetchMethodGenerator(target, processedSchema),
                new FetchNodeMethodGenerator(target, processedSchema)
        );
        var spec = getSpec(target.getName(), generators);
        target
                .getFields()
                .stream()
                .filter(it -> it.getTypeName().equals(NODE_TYPE.getName()))
                .findFirst()
                .flatMap(this::buildNodeMap)
                .ifPresent(spec::addField);
        var className = getGeneratedClassName(target.getName() + getFileNameSuffix());
        generators.forEach(it -> addFetchers(it.getDataFetcherWiring(), className));
        return spec.build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return KickstartResolverClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
