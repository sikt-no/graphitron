package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

/**
 * Generator that creates the data fetching methods for interface implementations, e.g. queries used by the node resolver.
 */
public class NodeHelperDBMethodGenerator extends NestedFetchDBMethodGenerator {
    private final Set<ObjectField> objectFieldsReturningNode;

    public NodeHelperDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema, Set<ObjectField> objectFieldsReturningNode) {
        super(localObject, processedSchema);
        this.objectFieldsReturningNode = objectFieldsReturningNode;
    }

    @Override
    public List<MethodSpec> generateAll() {
        var fields = objectFieldsReturningNode
                .stream()
                .filter(entry -> getLocalObject().implementsInterface(NODE_TYPE.getName()))
                .sorted(Comparator.comparing(AbstractField::getName))
                .map(it -> new VirtualSourceField(getLocalObject(), it.getTypeName()))
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
