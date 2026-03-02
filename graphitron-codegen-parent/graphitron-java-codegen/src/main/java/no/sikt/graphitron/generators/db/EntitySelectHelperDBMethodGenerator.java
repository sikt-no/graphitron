package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Stream;

import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITY_UNION;

/**
 * Generator that creates the data fetching methods for entity implementations, e.g. queries used by the federation entity resolver.
 */
public class EntitySelectHelperDBMethodGenerator extends NestedFetchDBMethodGenerator {

    public EntitySelectHelperDBMethodGenerator(
            ObjectDefinition localObject,
            ProcessedSchema processedSchema
    ) {
        super(localObject, processedSchema);
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (!processedSchema.federationEntitiesExist() || !getLocalObject().isEntity()) {
            return List.of();
        }

        var field = new VirtualSourceField(getLocalObject(), FEDERATION_ENTITY_UNION.getName());
        var top = Stream.of(generate(field));
        var nested = generateNested(field).stream();
        return Stream.concat(top, nested).toList();
    }
}