package no.sikt.graphitron.generators.mapping;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.AbstractMapperClassGenerator;
import no.sikt.graphitron.generators.abstractions.MethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

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
    protected boolean filterProperties(GenerationField field) {
        var type = processedSchema.getRecordType(field);
        if (!type.hasTable() && !field.hasServiceReference() || type.hasJavaRecordReference()) {
            return false;
        }

        return isToRecord() ? processedSchema.isInputType(field) : processedSchema.isObject(field);
    }

    @Override
    public String getFileNameSuffix() {
        return isToRecord() ? FILE_NAME_TO_SUFFIX : FILE_NAME_FROM_SUFFIX;
    }
}
