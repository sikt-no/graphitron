package no.fellesstudentsystem.graphitron.definitions.objects;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.mapping.RecordMethodMapping;
import no.fellesstudentsystem.graphitron.mappings.TableReflection;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asRecordClassName;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.getRequiredFields;

/**
 * Represents a default GraphQL input type.
 */
public class InputDefinition extends AbstractTableObjectDefinition<InputObjectTypeDefinition, InputValueDefinition, InputField> {
    private final List<InputField> inputsSortedByNullability;
    private final LinkedHashSet<String> requiredInputs, lookupInputs;
    private final TypeName recordClassName;
    private final boolean containsLookupKey;

    public InputDefinition(InputObjectTypeDefinition inputType) {
        super(inputType);
        var uppercaseName = getName().toUpperCase();
        recordClassName = ClassName.get(
                GeneratorConfig.getGeneratedJooqRecordsPackage(),
                asRecordClassName(new RecordMethodMapping(hasTable() ? getTable().getMappingName() : uppercaseName).getName())
        );
        requiredInputs = hasTable() ? getRequiredFields(getTable().getMappingName()).stream().map(String::toUpperCase).collect(Collectors.toCollection(LinkedHashSet::new)) : new LinkedHashSet<>();
        lookupInputs = getFields().stream().filter(InputField::isLookupKey).map(InputField::getName).collect(Collectors.toCollection(LinkedHashSet::new));
        containsLookupKey = !lookupInputs.isEmpty();
        inputsSortedByNullability = sortInputsByNullability();
    }

    @NotNull
    private List<InputField> sortInputsByNullability() {
        var splitOnIsRequired = getFields().stream().collect(Collectors.partitioningBy(this::isNonNullable));
        return Stream.concat(splitOnIsRequired.get(false).stream(), splitOnIsRequired.get(true).stream()).collect(Collectors.toList());
    }

    @Override
    protected List<InputField> createFields(InputObjectTypeDefinition inputType) {
        return InputField.from(inputType.getInputValueDefinitions());
    }

    /**
     * @return List of input fields contained within this input type, sorted by whether they are nullable fields.
     */
    public List<InputField> getInputsSortedByNullability() {
        return inputsSortedByNullability;
    }

    /**
     * @return Is this field non-nullable in the database?
     */
    protected boolean isNonNullable(InputField field) {
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

    /**
     * @return The {@link TypeName} for the generated record that corresponds to this input.
     */
    public TypeName getRecordClassName() {
        return recordClassName;
    }

    /**
     * @return Inputs set to be used as keys for lookup operations.
     */
    public LinkedHashSet<String> getLookupKeys() {
        return lookupInputs;
    }

    protected LinkedHashSet<String> getRequiredInputs() {
        return requiredInputs;
    }

    /**
     * @return Does this input type contain a field that is a key for a lookup operation?
     */
    public boolean containsLookupKey() {
        return containsLookupKey;
    }

    /**
     * Creates instances of this class for each of the {@link InputObjectTypeDefinition} provided.
     * @return List of InputDefinitions.
     */
    public static List<InputDefinition> processInputDefinitions(List<InputObjectTypeDefinition> inputs) {
        return inputs
                .stream()
                .map(it -> GeneratorConfig.getExtendedFunctionality().createExtensionIfAvailable(InputDefinition.class, new Class[]{InputObjectTypeDefinition.class}, it))
                .collect(Collectors.toList());
    }
}
