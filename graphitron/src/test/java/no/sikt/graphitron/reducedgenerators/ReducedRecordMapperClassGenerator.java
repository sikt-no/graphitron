package no.sikt.graphitron.reducedgenerators;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.generators.mapping.RecordMapperClassGenerator;
import no.sikt.graphitron.generators.mapping.RecordValidatorMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

public class ReducedRecordMapperClassGenerator extends RecordMapperClassGenerator {
    public ReducedRecordMapperClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema, true);
    }

    @Override
    public TypeSpec generate(GenerationField target) {
        return getSpec(processedSchema.getRecordType(target).getName(), new RecordValidatorMethodGenerator(target, processedSchema)).build();
    }
}
