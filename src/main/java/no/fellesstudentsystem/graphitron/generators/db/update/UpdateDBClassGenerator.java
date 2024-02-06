package no.fellesstudentsystem.graphitron.generators.db.update;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

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
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) {
        var mutation = processedSchema.getMutationType();
        if (mutation != null && mutation.isGenerated()) {
            var classes = mutation
                    .getFields()
                    .stream()
                    .filter(ObjectField::isGenerated)
                    .filter(ObjectField::hasMutationType)
                    .map(this::generate)
                    .collect(Collectors.toList());

            for (var generatedClass : classes) {
                writeToFile(generatedClass, path, packagePath, getDefaultSaveDirectoryName());
            }
        }
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
