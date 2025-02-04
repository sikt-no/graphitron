package no.sikt.graphitron.generators.resolvers.update;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.generators.abstractions.ResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * Class generator for basic update resolver classes.
 */
public class UpdateResolverClassGenerator extends ResolverClassGenerator<ObjectField> {
    public static final String
            INTERFACE_FILE_NAME_SUFFIX = "MutationResolver",
            SAVE_DIRECTORY_NAME = "mutation";

    public UpdateResolverClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateTypeSpecs() {
        var mutation = processedSchema.getMutationType();
        if (mutation != null && !mutation.isExplicitlyNotGenerated()) {
            return mutation
                    .getFields()
                    .stream()
                    .filter(ObjectField::isGeneratedWithResolver)
                    .map(this::generate)
                    .filter(it -> !it.methodSpecs().isEmpty())
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public TypeSpec generate(ObjectField target) {
        return getSpec(
                capitalize(target.getName()),
                List.of(
                        new ServiceUpdateResolverMethodGenerator(target, processedSchema),
                        new MutationTypeResolverMethodGenerator(target, processedSchema)
                )
        ).build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return ResolverClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }

    @Override
    public String getExpectedInterfaceSuffix() {
        return INTERFACE_FILE_NAME_SUFFIX;
    }
}
