package no.sikt.graphitron.generators.resolvers.datafetchers.update;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphitron.generators.abstractions.KickstartResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * Class generator for basic update resolver classes.
 */
public class UpdateClassGenerator extends DataFetcherClassGenerator<ObjectField> {
    public static final String SAVE_DIRECTORY_NAME = "mutation";

    public UpdateClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateTypeSpecs() {
        var mutation = processedSchema.getMutationType();
        if (mutation == null || mutation.isExplicitlyNotGenerated()) {
            return List.of();
        }

        return mutation
                .getFields()
                .stream()
                .filter(ObjectField::isGeneratedWithResolver)
                .map(this::generate)
                .filter(it -> !it.methodSpecs().isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public TypeSpec generate(ObjectField target) {
        return getSpec(
                capitalize(target.getName()),
                List.of(
                        new MutationServiceMethodGenerator(target, processedSchema),
                        new MutationTypeMethodGenerator(target, processedSchema)
                )
        ).build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return KickstartResolverClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
