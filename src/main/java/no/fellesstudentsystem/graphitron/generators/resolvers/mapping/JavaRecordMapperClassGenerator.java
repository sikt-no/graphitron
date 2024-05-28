package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.generators.abstractions.AbstractMapperClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;

public class JavaRecordMapperClassGenerator extends AbstractMapperClassGenerator<GenerationField> {
    public static final String FILE_NAME_TO_SUFFIX = "JavaMapper", FILE_NAME_FROM_SUFFIX = "TypeMapper";

    public JavaRecordMapperClassGenerator(ProcessedSchema processedSchema, boolean toRecord) {
        super(processedSchema, toRecord);
    }

    @Override
    public TypeSpec generate(GenerationField target) {
        return getSpec(processedSchema.getRecordType(target).getName(), List.of(new JavaRecordMapperMethodGenerator(target, processedSchema, isToRecord()))).build();
    }

    @Override
    protected boolean filterHasTableAndRecordProperties(GenerationField field) {
        return processedSchema.isRecordType(field)
                && (processedSchema.getRecordType(field).hasJavaRecordReference())
                && (isToRecord() ? processedSchema.isInputType(field) : processedSchema.isObject(field));
    }

    @Override
    public String getFileNameSuffix() {
        return isToRecord() ? FILE_NAME_TO_SUFFIX : FILE_NAME_FROM_SUFFIX;
    }
}
