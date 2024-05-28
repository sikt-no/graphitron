package no.fellesstudentsystem.graphitron.generators.abstractions;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.stream.Collectors;

public abstract class AbstractMapperClassGenerator<T extends GenerationField> extends AbstractClassGenerator<T> {
    public static final String DEFAULT_SAVE_DIRECTORY_NAME = "mappers";
    private final boolean toRecord;

    public AbstractMapperClassGenerator(ProcessedSchema processedSchema, boolean toRecord) {
        super(processedSchema);
        this.toRecord = toRecord;
    }

    @Override
    public void generateQualifyingObjectsToDirectory(String path, String packagePath) {
        processedSchema
                .getTransformableFields()
                .stream()
                .filter(this::filterHasTableAndRecordProperties)
                .filter(processedSchema::isRecordType)
                .collect(Collectors.toMap(processedSchema::getRecordType, it -> (T) it, (it1, it2) -> it1)) // Filter duplicates if multiple fields use the same input type.
                .values()
                .stream()
                .map(this::generate)
                .filter(it -> !it.methodSpecs.isEmpty())
                .forEach(generatedClass -> writeToFile(generatedClass, path, packagePath));
    }

    protected abstract boolean filterHasTableAndRecordProperties(GenerationField field);

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    public boolean isToRecord() {
        return toRecord;
    }
}
