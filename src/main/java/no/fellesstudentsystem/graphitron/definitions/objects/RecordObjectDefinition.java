package no.fellesstudentsystem.graphitron.definitions.objects;

import com.squareup.javapoet.ClassName;
import graphql.language.TypeDefinition;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import no.fellesstudentsystem.graphitron.definitions.helpers.ClassReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.definitions.interfaces.ObjectSpecification;
import no.fellesstudentsystem.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQMapping;
import no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat;
import no.fellesstudentsystem.graphitron.mappings.TableReflection;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asRecordClassName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.toCamelCase;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.getRequiredFields;
import static no.fellesstudentsystem.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.NOT_GENERATED;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.RECORD;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.NAME;

/**
 * A generalized implementation of {@link ObjectSpecification} for types that can be linked to tables or records.
 */
public abstract class RecordObjectDefinition<T extends TypeDefinition<T>, U extends GenerationField> extends AbstractObjectDefinition<T, U> implements RecordObjectSpecification<U> {
    private final JOOQMapping table;
    private final boolean hasTable, usesJavaRecord, isGenerated, hasResolvers, explicitlyNotGenerated;
    private final ClassReference classReference;
    private final List<U> inputsSortedByNullability;
    private final LinkedHashSet<String> requiredInputs;

    public RecordObjectDefinition(T objectDefinition) {
        super(objectDefinition);
        hasTable = objectDefinition.hasDirective(GenerationDirective.TABLE.getName());
        table = hasTable
                ? JOOQMapping.fromTable(getOptionalDirectiveArgumentString(objectDefinition, GenerationDirective.TABLE, NAME).orElse(getName().toUpperCase()))
                : null;

        isGenerated = getFields().stream().anyMatch(GenerationTarget::isGenerated);
        hasResolvers = getFields().stream().anyMatch(GenerationTarget::isGeneratedWithResolver);
        explicitlyNotGenerated = objectDefinition.hasDirective(NOT_GENERATED.getName());
        usesJavaRecord = objectDefinition.hasDirective(RECORD.getName());
        if (usesJavaRecord) {
            classReference = new ClassReference(new CodeReference(objectDefinition, RECORD, GenerationDirectiveParam.RECORD, objectDefinition.getName()));
        } else if (hasTable) {
            classReference = new ClassReference(asRecordClassName(toCamelCase(table.getMappingName())), GeneratorConfig.getGeneratedJooqRecordsPackage());
        } else {
            classReference = null;
        }
        requiredInputs = hasTable() ? getRequiredFields(getTable().getMappingName()).stream().map(String::toUpperCase).collect(Collectors.toCollection(LinkedHashSet::new)) : new LinkedHashSet<>();
        inputsSortedByNullability = sortInputsByNullability();
    }

    @NotNull
    private List<U> sortInputsByNullability() {
        var splitOnIsRequired = getFields().stream().collect(Collectors.partitioningBy(this::isNonNullable));
        return Stream.concat(splitOnIsRequired.get(false).stream(), splitOnIsRequired.get(true).stream()).collect(Collectors.toList());
    }

    /**
     * @return Is this field non-nullable in the database?
     */
    protected boolean isNonNullable(GenerationField field) {
        if (field.isID() && hasTable()) {
            var idFields = TableReflection.getRequiredFields(getTable().getMappingName()).stream()
                    .map(String::toUpperCase)
                    .collect(Collectors.toList());
            if (!idFields.isEmpty()) {
                if (requiredInputs.containsAll(idFields)) {
                    return true;
                }
            }
        }
        return requiredInputs.contains(field.getUpperCaseName());
    }

    @Override
    public boolean isGenerated() {
        return isGenerated;
    }

    @Override
    public boolean isGeneratedWithResolver() {
        return hasResolvers;
    }

    @Override
    public boolean isExplicitlyNotGenerated() {
        return explicitlyNotGenerated;
    }

    @Override
    public JOOQMapping getTable() {
        return table;
    }

    @Override
    public boolean hasTable() {
        return hasTable;
    }

    @Override
    public Class<?> getRecordReference() {
        return hasRecordReference() ? classReference.getReferenceClass() : null;
    }

    @Override
    public String getRecordReferenceName() {
        return hasRecordReference() ? classReference.getClassNameString() : null;
    }

    @Override
    public ClassName getRecordClassName() {
        return hasRecordReference() ? classReference.getClassName() : null;
    }

    @Override
    public boolean hasJavaRecordReference() {
        return usesJavaRecord;
    }

    @Override
    public boolean hasRecordReference() {
        return classReference != null;
    }

    @Override
    public ClassName asSourceClassName(boolean toRecord) {
        return toRecord ? getGraphClassName() : getRecordClassName();
    }

    @Override
    public ClassName asTargetClassName(boolean toRecord) {
        return toRecord ? getRecordClassName() : getGraphClassName();
    }

    @Override
    public String asRecordName() {
        return hasJavaRecordReference() || hasTable ? getRecordReferenceName() : NameFormat.asRecordName(getName());
    }

    /**
     * @return List of input fields contained within this input type, sorted by whether they are nullable fields.
     */
    public List<U> getInputsSortedByNullability() {
        return inputsSortedByNullability;
    }

    protected LinkedHashSet<String> getRequiredInputs() {
        return requiredInputs;
    }
}
