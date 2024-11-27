package no.sikt.graphitron.generators.db.update;

import com.squareup.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.generators.abstractions.DBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * Class generator for basic mutation classes.
 */
public class UpdateDBClassGenerator extends DBClassGenerator<ObjectField> {
    public static final String SAVE_DIRECTORY_NAME = "mutation";

    public UpdateDBClassGenerator(ProcessedSchema processedSchema) {
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
                    .filter(ObjectField::hasMutationType)
                    .map(this::generate)
                    .filter(it -> !it.methodSpecs.isEmpty())
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public TypeSpec generate(ObjectField target) {
        return getSpec(
                capitalize(target.getName()),
                List.of(new UpdateDBMethodGenerator(target, processedSchema))
        ).build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
