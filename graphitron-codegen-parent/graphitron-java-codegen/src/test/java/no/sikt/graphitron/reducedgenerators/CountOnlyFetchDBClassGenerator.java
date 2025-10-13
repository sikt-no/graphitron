package no.sikt.graphitron.reducedgenerators;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphitron.generators.db.FetchCountDBMethodGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.getFormatGeneratedName;

public class CountOnlyFetchDBClassGenerator extends DBClassGenerator {
    public CountOnlyFetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectField target) {
        return getSpec(getFormatGeneratedName(target), new FetchCountDBMethodGenerator(target, processedSchema)).build();
    }
}
