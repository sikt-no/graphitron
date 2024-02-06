package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;

public class JavaRecordMapperClassGenerator extends RecordMapperClassGenerator {
    public static final String FILE_NAME_SUFFIX = "JavaMapper";

    public JavaRecordMapperClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(InputField target) {
        return getSpec(target.getTypeName(), List.of(new JavaRecordMapperMethodGenerator(target, processedSchema))).build();
    }

    @Override
    protected boolean filterHasTableAndRecordProperties(InputField field) {
        return processedSchema.getInputType(field).hasJavaRecordReference();
    }

    @Override
    public String getFileNameSuffix() {
        return FILE_NAME_SUFFIX;
    }
}
