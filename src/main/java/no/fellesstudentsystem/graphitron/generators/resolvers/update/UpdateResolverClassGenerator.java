package no.fellesstudentsystem.graphitron.generators.resolvers.update;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;

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
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) {
        var mutation = processedSchema.getMutationType();
        if (mutation != null && !mutation.isExplicitlyNotGenerated()) {
            mutation
                    .getFields()
                    .stream()
                    .filter(ObjectField::isGeneratedWithResolver)
                    .map(this::generate)
                    .filter(it -> !it.methodSpecs.isEmpty())
                    .forEach(generatedClass -> writeToFile(generatedClass, path, packagePath, getDefaultSaveDirectoryName()));
        }
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
