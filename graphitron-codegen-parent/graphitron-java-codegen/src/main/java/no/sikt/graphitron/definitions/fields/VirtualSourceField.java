package no.sikt.graphitron.definitions.fields;

import graphql.language.FieldDefinition;
import graphql.language.TypeName;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldReference;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.sql.SQLCondition;

import java.util.List;

import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_QUERY;

/**
 * Virtual field for when we want to have a virtual source for code generation.
 */
public class VirtualSourceField extends ObjectField {
    private final List<ArgumentField> nonReservedArguments;
    private final SQLCondition condition;
    private final boolean isResolver;
    private final List<FieldReference> fieldReferences;
    private String originalFieldName;

    public VirtualSourceField(String targetTypeName, String container, List<ArgumentField> nonReservedArguments, SQLCondition condition, boolean isResolver, List<FieldReference> fieldReferences) {
        super(new FieldDefinition("for_" + targetTypeName,new TypeName(targetTypeName)), container);
        this.nonReservedArguments = nonReservedArguments;
        this.condition = condition;
        this.isResolver = isResolver;
        this.fieldReferences = fieldReferences;
    }

    public VirtualSourceField(String targetTypeName, ObjectField target) {
        this(targetTypeName,
                target.getContainerTypeName(),
                target.getNonReservedArguments(),
                target.getCondition(),
                target.isResolver(),
                target.getMultitableReferences().getOrDefault(targetTypeName, List.of()));
        this.originalFieldName = target.getName();
    }

    public VirtualSourceField(RecordObjectSpecification<?> targetType, ObjectField target) {
        this(targetType.getName(), target);
    }

    public VirtualSourceField(RecordObjectSpecification<?> targetType, String container) {
        this(targetType.getName(), container, List.of(), null, false, List.of());
    }

    public VirtualSourceField(RecordObjectSpecification<?> targetType) {
        this(targetType.getName(), SCHEMA_QUERY.getName(), List.of(), null, false, List.of());
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

    @Override
    public boolean isResolver() {
        return isResolver;
    }

    @Override
    public List<FieldReference> getFieldReferences() {
        return fieldReferences;
    }

    @Override
    public boolean hasFieldReferences() {
        return !fieldReferences.isEmpty();
    }

    public String getOriginalFieldName() {
        return originalFieldName;
    }
}
