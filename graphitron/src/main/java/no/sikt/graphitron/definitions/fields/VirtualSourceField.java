package no.sikt.graphitron.definitions.fields;

import graphql.language.FieldDefinition;
import graphql.language.TypeName;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.sql.SQLCondition;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Virtual field for when we want to have a virtual source for code generation.
 */
public class VirtualSourceField extends ObjectField {
    public static final String VIRTUAL_FIELD_NAME = "_";
    private final List<ArgumentField> nonReservedArguments;
    private final SQLCondition condition;

    public VirtualSourceField(RecordObjectSpecification<?> targetType, String container, List<ArgumentField> nonReservedArguments, SQLCondition condition) {
        super(new FieldDefinition(VIRTUAL_FIELD_NAME, new TypeName(targetType.getName())), container);
        this.nonReservedArguments = nonReservedArguments;
        this.condition = condition;
    }

    public VirtualSourceField(RecordObjectSpecification<?> targetType, String container) {
        this(targetType, container, List.of(), null);
    }

    /**
     * @return List of all input non-reserved arguments for this field.
     */
    @Override
    public List<ArgumentField> getNonReservedArguments() {
        return nonReservedArguments;
    }

    /**
     * @return Does this field have any input fields defined that are not reserved?
     */
    @Override
    public boolean hasNonReservedInputFields() {
        return !nonReservedArguments.isEmpty();
    }

    @Override
    public SQLCondition getCondition() {
        return condition;
    }

    @Override
    public boolean hasCondition() {
        return condition != null;
    }

    @Override
    public boolean hasOverridingCondition() {
        return hasCondition() && condition.isOverride();
    }
}
