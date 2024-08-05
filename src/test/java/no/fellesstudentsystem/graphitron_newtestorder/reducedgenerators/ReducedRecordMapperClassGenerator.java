package no.fellesstudentsystem.graphitron_newtestorder.reducedgenerators;

import com.squareup.javapoet.TypeSpec;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordValidatorMethodGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.List;

public class ReducedRecordMapperClassGenerator extends RecordMapperClassGenerator {
    public ReducedRecordMapperClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema, true);
    }

    @Override
    public TypeSpec generate(GenerationField target) {
        return getSpec(processedSchema.getRecordType(target).getName(), List.of(new RecordValidatorMethodGenerator(target, processedSchema))).build();
    }
}
