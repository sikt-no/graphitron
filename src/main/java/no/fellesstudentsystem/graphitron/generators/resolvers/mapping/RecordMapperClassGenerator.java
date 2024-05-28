package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.AbstractMapperClassGenerator;
import no.fellesstudentsystem.graphitron.generators.abstractions.MethodGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;

public class RecordMapperClassGenerator extends AbstractMapperClassGenerator<GenerationField> {
    public static final String FILE_NAME_TO_SUFFIX = "JOOQMapper", FILE_NAME_FROM_SUFFIX = "TypeMapper";

    public RecordMapperClassGenerator(ProcessedSchema processedSchema, boolean toRecord) {
        super(processedSchema, toRecord);
    }

    @Override
    public TypeSpec generate(GenerationField target) {
        var recordMapper = new RecordMapperMethodGenerator(target, processedSchema, isToRecord());
        List<MethodGenerator<? extends GenerationTarget>> generators = isToRecord()
                ? List.of(recordMapper, new RecordValidatorMethodGenerator(target, processedSchema))
                : List.of(recordMapper);
        return getSpec(processedSchema.getRecordType(target).getName(), generators).build();
    }

    @Override
    protected boolean filterHasTableAndRecordProperties(GenerationField field) {
        var type = processedSchema.getRecordType(field);
        if (type == null || !type.hasTable() && !field.hasServiceReference() || type.hasJavaRecordReference()) {
            return false;
        }

        return isToRecord() ? processedSchema.isInputType(field) : processedSchema.isObject(field);
    }

    @Override
    public String getFileNameSuffix() {
        return isToRecord() ? FILE_NAME_TO_SUFFIX : FILE_NAME_FROM_SUFFIX;
    }
}
