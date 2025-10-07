package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

public abstract class AbstractMapperClassGenerator<T extends GenerationField> extends AbstractSchemaClassGenerator<T> {
    public static final String DEFAULT_SAVE_DIRECTORY_NAME = "mappers";
    private final boolean toRecord;

    public AbstractMapperClassGenerator(ProcessedSchema processedSchema, boolean toRecord) {
        super(processedSchema);
        this.toRecord = toRecord;
    }

    @Override
    public List<TypeSpec> generateAll() {
        return processedSchema
                .getTransformableFields()
                .stream()
                .filter(this::filterProperties)
                .map(it -> (T) it)
                .map(this::generate)
                .filter(this::typeSpecFilter)
                .collect(Collectors.toList());
    }

    protected boolean typeSpecFilter(TypeSpec spec) {
        return !spec.methodSpecs().isEmpty();
    }

    protected abstract boolean filterProperties(GenerationField field);

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    public boolean isToRecord() {
        return toRecord;
    }
}
