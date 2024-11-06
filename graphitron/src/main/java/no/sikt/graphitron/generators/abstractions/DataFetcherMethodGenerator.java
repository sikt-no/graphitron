package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

abstract public class DataFetcherMethodGenerator<T extends ObjectField> extends AbstractMethodGenerator<T> {
    public DataFetcherMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }
}
