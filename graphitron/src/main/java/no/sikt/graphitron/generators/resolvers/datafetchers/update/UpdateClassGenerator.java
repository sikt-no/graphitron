package no.sikt.graphitron.generators.resolvers.datafetchers.update;

import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphitron.generators.abstractions.KickstartResolverClassGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * Class generator for basic update resolver classes.
 */
public class UpdateClassGenerator extends DataFetcherClassGenerator<ObjectDefinition> {
    public static final String SAVE_DIRECTORY_NAME = "mutation";

    public UpdateClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateAll() {
        var mutation = processedSchema.getMutationType();
        if (mutation == null || mutation.isExplicitlyNotGenerated()) {
            return List.of();
        }

        var code = generate(mutation);
        if (code.methodSpecs().isEmpty()) {
            return List.of();
        }
        return List.of(code);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        var generators = List.of(new UpdateMethodGenerator(target, processedSchema));
        var className = getGeneratedClassName(target.getName() + getFileNameSuffix());
        var spec = getSpec(capitalize(target.getName()), generators);
        generators.forEach(it -> addFetchers(it.getDataFetcherWiring(), className));
        return spec.build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return KickstartResolverClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
