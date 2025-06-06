package no.sikt.graphitron.definitions.objects;

import graphql.language.ObjectTypeDefinition;
import no.sikt.graphitron.definitions.fields.ObjectField;

import java.util.List;

/**
 * Object that corresponds to a GraphQL type whose name ends with the "Connection" suffix.
 * The behaviour of this class should always reflect the <a href="https://relay.dev/graphql/connections.htm">connections specification</a>.
 */
public class ConnectionObjectDefinition extends AbstractObjectDefinition<ObjectTypeDefinition, ObjectField> {
    private final EdgeObjectDefinition edgeObject;

    public ConnectionObjectDefinition(ObjectTypeDefinition objectDefinition, EdgeObjectDefinition edgeObject) {
        super(objectDefinition);
        this.edgeObject = edgeObject;
    }

    @Override
    protected List<ObjectField> createFields(ObjectTypeDefinition objectDefinition) {
        return ObjectField.from(objectDefinition.getFieldDefinitions(), getName());
    }

    /**
     * @return The underlying GraphQL object that is referred to within the edge type's node field.
     */
    public String getNodeType() {
        return edgeObject.getNodeType();
    }

    /**
     * @return Edge object for this connection. Defined by the "edges" field on the connection object.
     */
    public EdgeObjectDefinition getEdgeObject() {
        return edgeObject;
    }
}
