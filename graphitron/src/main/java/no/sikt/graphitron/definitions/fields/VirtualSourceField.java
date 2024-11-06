package no.sikt.graphitron.definitions.fields;

import graphql.language.FieldDefinition;
import graphql.language.TypeName;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;

/**
 * Virtual field for when we want to have a virtual source for code generation.
 */
public class VirtualSourceField extends ObjectField {
    public static final String VIRTUAL_FIELD_NAME = "_";

    public VirtualSourceField(RecordObjectSpecification<?> targetType, String container) {
        super(new FieldDefinition(VIRTUAL_FIELD_NAME, new TypeName(targetType.getName())), container);
    }
}
