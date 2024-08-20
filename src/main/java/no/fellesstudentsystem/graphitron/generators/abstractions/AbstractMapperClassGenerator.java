package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

public abstract class AbstractMapperClassGenerator<T extends GenerationField> extends AbstractClassGenerator<T> {
    public static final String DEFAULT_SAVE_DIRECTORY_NAME = "mappers";
    private final boolean toRecord;

    public AbstractMapperClassGenerator(ProcessedSchema processedSchema, boolean toRecord) {
        super(processedSchema);
        this.toRecord = toRecord;
    }

    @Override
    public List<TypeSpec> generateTypeSpecs() {
        return processedSchema
                .getTransformableFields()
                .stream()
                .filter(this::filterHasTableAndRecordProperties)
                .map(it -> (T) it)
                .map(this::generate)
                .filter(this::typeSpecFilter)
                .collect(Collectors.toList());
    }

    protected boolean typeSpecFilter(TypeSpec spec) {
        return !spec.methodSpecs.isEmpty();
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
