package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.generators.abstractions.AbstractClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RecordMapperClassGenerator extends AbstractClassGenerator<InputField> {
    public static final String
        DEFAULT_SAVE_DIRECTORY_NAME = "mappers",
        FILE_NAME_SUFFIX = "Mapper";

    public RecordMapperClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(InputField target) {
        return getSpec(
                target.getTypeName(),
                List.of(
                        new RecordMapperMethodGenerator(target, processedSchema),
                        new RecordValidatorMethodGenerator(target, processedSchema)
                )
        ).build();
    }

    @Override
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) {
        var mutation = processedSchema.getMutationType();
        if (mutation != null && mutation.isGenerated()) {
            mutation
                    .getFields()
                    .stream()
                    .flatMap(field -> processedSchema.findTableOrRecordFields(field).stream())
                    .filter(this::filterHasTableAndRecordProperties)
                    .collect(Collectors.toMap(processedSchema::getInputType, Function.identity(), (it1, it2) -> it1)) // Filter duplicates if multiple fields use the same input type.
                    .values()
                    .stream()
                    .map(this::generate)
                    .forEach(generatedClass -> writeToFile(generatedClass, path, packagePath));
        }
    }

    protected boolean filterHasTableAndRecordProperties(InputField field) {
        var in = processedSchema.getInputType(field);
        return in.hasTable() && !in.hasJavaRecordReference();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    @Override
    public String getFileNameSuffix() {
        return FILE_NAME_SUFFIX;
    }
}
