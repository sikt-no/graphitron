package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITIES_FIELD;

public class SelectHelperDBMethodGenerator extends NestedFetchDBMethodGenerator {
    public SelectHelperDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public List<MethodSpec> generateAll() {
        var fields = getLocalObject()
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it) && !processedSchema.isUnion(it))
                .filter(it -> !it.getName().equals(FEDERATION_ENTITIES_FIELD.getName()))
                .filter(it -> !processedSchema.isFederationService(it))
                .filter(GenerationSourceField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .filter(it -> !processedSchema.isDeleteMutationWithReturning(it))
                .filter(it -> !processedSchema.isInsertMutationWithReturning(it))
                .filter(processedSchema::isRecordType)
                .toList();

        var top = fields
                .stream()
                .map(this::generate);
        var nested = fields
                .stream()
                .map(this::generateNested)
                .flatMap(Collection::stream);
        return Stream
                .concat(top, nested)
                .toList();
    }
}
