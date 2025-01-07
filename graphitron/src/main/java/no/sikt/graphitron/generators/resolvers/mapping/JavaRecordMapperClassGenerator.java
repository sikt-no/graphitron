package no.sikt.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.generators.abstractions.AbstractMapperClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

public class JavaRecordMapperClassGenerator extends AbstractMapperClassGenerator<GenerationField> {
    public static final String FILE_NAME_TO_SUFFIX = "JavaMapper", FILE_NAME_FROM_SUFFIX = "TypeMapper";

    public JavaRecordMapperClassGenerator(ProcessedSchema processedSchema, boolean toRecord) {
        super(processedSchema, toRecord);
    }

    @Override
    public TypeSpec generate(GenerationField target) {
        return getSpec(processedSchema.getRecordType(target).getName(), new JavaRecordMapperMethodGenerator(target, processedSchema, isToRecord())).build();
    }

    @Override
    protected boolean filterHasTableAndRecordProperties(GenerationField field) {
        return processedSchema.getRecordType(field).hasJavaRecordReference()
                && (isToRecord() ? processedSchema.isInputType(field) : processedSchema.isObject(field));
    }

    @Override
    public String getFileNameSuffix() {
        return isToRecord() ? FILE_NAME_TO_SUFFIX : FILE_NAME_FROM_SUFFIX;
    }
}
