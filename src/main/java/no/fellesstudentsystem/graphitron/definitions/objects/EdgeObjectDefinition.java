package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.ObjectTypeDefinition;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;

import java.util.List;

/**
 * Object that corresponds to a GraphQL type which is referred to by a connection's "edges" field.
 * The behaviour of this class should always reflect the <a href="https://relay.dev/graphql/connections.htm">connections specification</a>.
 */
public class EdgeObjectDefinition extends AbstractObjectDefinition<ObjectTypeDefinition, ObjectField> {
    private final String nodeType;
    private final ObjectField cursor;

    public EdgeObjectDefinition(ObjectTypeDefinition objectDefinition) {
        super(objectDefinition);
        var fields = ObjectField.from(objectDefinition.getFieldDefinitions());
        nodeType = getObjectForField(GraphQLReservedName.CONNECTION_NODE_FIELD.getName(), fields).getTypeName();
        cursor = getObjectForField(GraphQLReservedName.CONNECTION_CURSOR_FIELD.getName(), fields);
    }

    @Override
    protected List<ObjectField> createFields(ObjectTypeDefinition objectDefinition) {
        return ObjectField.from(objectDefinition.getFieldDefinitions());
    }

    private ObjectField getObjectForField(String name, List<ObjectField> fields) {
        return fields
                .stream()
                .filter(it -> it.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(getName() + " has no '" + name + "' field."));
    }

    /**
     * @return The underlying GraphQL object name that is referred to by the object's node field.
     */
    public String getNodeType() {
        return nodeType;
    }

    /**
     * @return The specification-required cursor field for the edge type.
     */
    public ObjectField getCursor() {
        return cursor;
    }
}
