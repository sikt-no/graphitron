package no.fellesstudentsystem.graphitron.generators.resolvers;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.abstractions.MethodGenerator;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverClassGenerator;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

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
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) {
        var mutation = processedSchema.getMutationType();
        if (mutation != null && mutation.isGenerated()) {
            var classes = mutation
                    .getFields()
                    .stream()
                    .filter(ObjectField::isGenerated)
                    .map(this::generate)
                    .collect(Collectors.toList());

            for (var generatedClass : classes) {
                writeToFile(generatedClass, path, packagePath, getDefaultSaveDirectoryName());
            }
        }
    }

    protected void setDependencies(List<MethodGenerator<? extends AbstractField<?>>> generators, TypeSpec.Builder spec) {
        generators
                .stream()
                .flatMap(gen -> gen.getDependencySet().stream())
                .distinct()
                .sorted()
                .filter(dep -> !(dep instanceof ServiceDependency)) // Inelegant solution, but it should work for now.
                .forEach(dep -> spec.addField(dep.getSpec()));
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
