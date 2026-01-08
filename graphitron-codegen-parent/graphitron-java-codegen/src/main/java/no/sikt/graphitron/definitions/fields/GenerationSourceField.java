package no.sikt.graphitron.definitions.fields;

import graphql.language.*;
import graphql.language.ObjectField;
import no.sikt.graphitron.configuration.externalreferences.CodeReference;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldReference;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldType;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.helpers.ServiceWrapper;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphitron.definitions.sql.SQLCondition;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.directives.DirectiveHelpers;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.directives.GenerationDirectiveParam;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.toCamelCase;
import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;
import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalObjectFieldByName;
import static no.sikt.graphql.directives.GenerationDirective.*;
import static no.sikt.graphql.directives.GenerationDirective.SERVICE;
import static no.sikt.graphql.directives.GenerationDirectiveParam.*;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_QUERY;

/**
 * This class represents the general functionality associated with GraphQLs fields that can initialise code generation.
 */
public abstract class GenerationSourceField<T extends NamedNode<T> & DirectivesContainer<T>> extends AbstractField<T> implements GenerationField {
    private final boolean isGenerated, isResolver, isGeneratedAsResolver, isExternalField,
            hasFieldDirective, hasNodeID, hasTableMethod, hasService;
    private final ArrayList<FieldReference> fieldReferences;
    private final Map<String, List<FieldReference>> multitableReferences;

    private final SQLCondition condition;
    private final MethodMapping mappingForRecordFieldOverride;
    private final ServiceWrapper serviceWrapper;
    private final String nodeIdTypeName;
    private final Map<String, TypeName> contextFields;
    public GenerationSourceField(T field, FieldType fieldType, String container) {
        super(field, fieldType, container);
        fieldReferences = new ArrayList<>();
        if (field.hasDirective(REFERENCE.getName())) {
            var referenceDirective = field.getDirectives(REFERENCE.getName()).get(0);

            Optional.ofNullable(referenceDirective.getArgument(PATH.getName()))
                    .map(Argument::getValue)
                    .ifPresent(it -> addFieldReferences(it, fieldReferences));
        }

        multitableReferences = new HashMap<>();
        if (field.hasDirective(MULTITABLE_REFERENCE.getName())) {
            var referenceDirective = field.getDirectives(MULTITABLE_REFERENCE.getName()).get(0);

            Optional.ofNullable(referenceDirective.getArgument(ROUTES.getName()))
                    .map(Argument::getValue)
                    .ifPresent(this::addMultitableReferences);
        }

        condition = field.hasDirective(GenerationDirective.CONDITION.getName()) && fieldType != null ? new SQLCondition(field) : null;
        serviceWrapper = field.hasDirective(SERVICE.getName()) || field.hasDirective(TABLE_METHOD.getName()) ? new ServiceWrapper(field) : null;
        if (field.hasDirective(FIELD.getName())) {
            mappingForRecordFieldOverride = getJavaName().isEmpty() ? new MethodMapping(toCamelCase(getUpperCaseName())) : new MethodMapping(getJavaName());
        } else {
            mappingForRecordFieldOverride = getMappingFromFieldOverride();
        }

        hasFieldDirective = field.hasDirective(FIELD.getName());
        isExternalField = field.hasDirective(EXTERNAL_FIELD.getName());
        isGenerated = !field.hasDirective(NOT_GENERATED.getName());
        isResolver = field.hasDirective(SPLIT_QUERY.getName())
                || (field instanceof FieldDefinition && !container.equals(SCHEMA_QUERY.getName()) && !container.equals(SCHEMA_MUTATION.getName()) && !((FieldDefinition) field).getInputValueDefinitions().isEmpty());
        hasTableMethod = field.hasDirective(TABLE_METHOD.getName());
        hasService = field.hasDirective(SERVICE.getName());

        isGeneratedAsResolver = (isResolver || container.equals(SCHEMA_QUERY.getName()) || container.equals(SCHEMA_MUTATION.getName())) && isGenerated;

        hasNodeID = field.hasDirective(GenerationDirective.NODE_ID.getName());
        nodeIdTypeName = getOptionalDirectiveArgumentString(field, GenerationDirective.NODE_ID, GenerationDirectiveParam.TYPE_NAME)
                .orElse(null);
        contextFields = findContextFields();
    }

    /**
     * Construct references from the {@link GenerationDirectiveParam#PATH references} parameter.
     */
    private void addFieldReferences(Value<?> referencesValue, List<FieldReference> listToAddTo) {
        var values = referencesValue instanceof ArrayValue ? ((ArrayValue) referencesValue).getValues() : List.of(((ObjectValue) referencesValue));

        values.stream()
                .filter(value -> value instanceof ObjectValue)
                .forEach(value -> {
                    var objectFields = ((ObjectValue) value).getObjectFields();
                    var table = getOptionalObjectFieldByName(objectFields, GenerationDirectiveParam.TABLE);
                    var key = getOptionalObjectFieldByName(objectFields, KEY);
                    var referencedCondition = getOptionalObjectFieldByName(objectFields, GenerationDirectiveParam.CONDITION)
                            .map(ObjectField::getValue)
                            .map(it -> (ObjectValue) it)
                            .map(ObjectValue::getObjectFields)
                            .map(fields -> new SQLCondition(new CodeReference(fields, getName()))).orElse(null);

                    listToAddTo.add(
                            new FieldReference(
                                    table.map(DirectiveHelpers::stringValueOf).map(JOOQMapping::fromTable).orElse(null),
                                    key.map(DirectiveHelpers::stringValueOf).map(JOOQMapping::fromKey).orElse(null),
                                    referencedCondition
                            )
                    );
                });
    }

    private void addMultitableReferences(Value<?> referencesValue) {
        var values = referencesValue instanceof ArrayValue ? ((ArrayValue) referencesValue).getValues() : List.of(((ObjectValue) referencesValue));

        values.stream()
                .filter(value -> value instanceof ObjectValue)
                .forEach(value -> {
                    var objectFields = ((ObjectValue) value).getObjectFields();
                    var typeName = objectFields.stream()
                            .filter(it -> it.getName().equals(TYPE_NAME.getName())).findFirst()
                            .map(it -> ((StringValue) it.getValue()).getValue())
                            .orElseThrow(() -> // Should never be thrown because typeName is not nullable
                                    new RuntimeException(String.format("%s directive is missing %s parameter.",MULTITABLE_REFERENCE.getName(), TYPE_NAME.getName())));

                    var fieldReferencesForType = new ArrayList<FieldReference>();

                    objectFields.stream()
                            .filter(it -> it.getName().equals(PATH.getName()))
                            .findFirst()
                            .ifPresent(it -> addFieldReferences(it.getValue(), fieldReferencesForType));

                    multitableReferences.put(typeName, fieldReferencesForType);
                });
    }

    /**
     * @return Gather all the context fields that are reachable from this object alone.
     */
    private Map<String, TypeName> findContextFields() {
        var serviceFields = hasServiceReference() || hasTableMethod ? serviceWrapper.getContextFields() : Map.<String, TypeName>of();
        var conditionFields = hasCondition() ? condition.getContextFields() : Map.<String, TypeName>of();
        var referenceConditionFields = fieldReferences
                .stream()
                .map(FieldReference::getTableCondition)
                .filter(Objects::nonNull)
                .flatMap(it -> it.getContextFields().entrySet().stream());
        return Stream
                .concat(Stream.concat(serviceFields.entrySet().stream(), conditionFields.entrySet().stream()), referenceConditionFields)
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> y, LinkedHashMap::new));
    }

    @Override
    public boolean isResolver() {
        return isResolver;
    }

    public boolean isExternalField() {
        return isExternalField;
    }

    public boolean hasFieldDirective() {
        return hasFieldDirective;
    }

    @Override
    public MethodMapping getMappingForRecordFieldOverride() {
        return mappingForRecordFieldOverride;
    }

    /**
     * Get the method mapping for Java record field access.
     * @param mapsJavaRecord true for types with @record directive (uses raw @field name), false otherwise (uses toCamelCase)
     * @return The appropriate MethodMapping for getter/setter generation
     */
    public MethodMapping getJavaRecordMethodMapping(boolean mapsJavaRecord) {
        if (mapsJavaRecord) {
            return getJavaName().isEmpty()
                    ? getMappingFromFieldOverride()
                    : new MethodMapping(getJavaName());
        }
        return getMappingForRecordFieldOverride();
    }

    @Override
    public String getFieldRecordMappingName() {
        return mappingForRecordFieldOverride.getName();
    }

    @Override
    public List<FieldReference> getFieldReferences() {
        return fieldReferences;
    }

    @Override
    public boolean hasFieldReferences() {
        return !fieldReferences.isEmpty();
    }

    public Map<String, List<FieldReference>> getMultitableReferences() {
        return multitableReferences;
    }

    public boolean hasMultitableReferences() {
        return !multitableReferences.isEmpty();
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
    public boolean isGenerated() {
        return isGenerated;
    }

    @Override
    public boolean hasServiceReference() { return hasService; }

    @Override
    public ServiceWrapper getExternalMethod() {
        return serviceWrapper;
    }

    @Override
    public Map<String, TypeName> getContextFields() {
        return contextFields;
    }

    @Override
    public boolean hasMutationType() {
        return false;
    }

    @Override
    public MutationType getMutationType() {
        return null;
    }

    @Override
    public boolean isGeneratedWithResolver() {
        return isGeneratedAsResolver;
    }

    @Override
    public boolean isExplicitlyNotGenerated() {
        return !isGenerated;
    }

    /**
     * @return Does this field have the @nodeId directive?
     */
    public boolean hasNodeID() {
        return hasNodeID;
    }

    /**
     * @return The type name configured in the @nodeId directive
     */
    public Optional<String> getNodeIdTypeName() {
        return Optional.ofNullable(nodeIdTypeName);
    }

    public boolean hasTableMethodDirective() {
        return hasTableMethod;
    }

    public String formatPath() {
        return String.format("'%s.%s'", getContainerTypeName(), getName());
    }


    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GenerationSourceField<?> that = (GenerationSourceField<?>) obj;
        return Objects.equals(getName(), that.getName())
            && Objects.equals(getContainerTypeName(), that.getContainerTypeName());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), getContainerTypeName());
    }
}
