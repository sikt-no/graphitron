package no.fellesstudentsystem.graphitron.generators.resolvers;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.abstractions.MethodGenerator;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverClassGenerator;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * Class generator for basic update resolver classes.
 */
public class UpdateResolverClassGenerator extends ResolverClassGenerator<ObjectField> {
    public static final String
            INTERFACE_FILE_NAME_SUFFIX = "MutationResolver",
            SAVE_DIRECTORY_NAME = "mutation";

    private final Map<String, Class<?>> exceptionOverrides, serviceOverrides;

    public UpdateResolverClassGenerator(ProcessedSchema processedSchema) {
        this(processedSchema, Map.of(), Map.of());
    }

    public UpdateResolverClassGenerator(
            ProcessedSchema processedSchema,
            Map<String, Class<?>> exceptionOverrides,
            Map<String, Class<?>> serviceOverrides
    ) {
        super(processedSchema);
        this.exceptionOverrides = exceptionOverrides;
        this.serviceOverrides = serviceOverrides;
    }

    @Override
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) throws IOException {
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

    protected void setDependencies(List<MethodGenerator<? extends AbstractField>> generators, TypeSpec.Builder spec) {
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
                List.of(new UpdateResolverMethodGenerator(target, processedSchema, exceptionOverrides, serviceOverrides))
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
