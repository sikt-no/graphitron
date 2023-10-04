package no.fellesstudentsystem.graphitron.definitions.objects;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import graphql.language.InputObjectTypeDefinition;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.mapping.RecordMethodMapping;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQTableMapping;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.directives.DirectiveHelpers;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asRecordClassName;
import static no.fellesstudentsystem.graphitron.mappings.PersonHack.getHackedIDFields;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.getRequiredFields;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.NAME;

/**
 * Represents a default GraphQL input type.
 */
public class InputDefinition extends AbstractObjectDefinition<InputObjectTypeDefinition> {
    private final JOOQTableMapping table;
    private final boolean hasTable;
    private final List<InputField> inputs;
    private final Set<String> requiredInputs;
    private final TypeName recordClassName;

    public InputDefinition(InputObjectTypeDefinition inputType) {
        super(inputType);
        inputs = inputType.getInputValueDefinitions().stream().map(InputField::new).collect(Collectors.toList());

        this.hasTable = inputType.hasDirective(GenerationDirective.TABLE.getName());
        String tableName = getName().toUpperCase();
        if (hasTable) {
            tableName = DirectiveHelpers.getOptionalDirectiveArgumentString(inputType, GenerationDirective.TABLE, NAME).orElse(tableName);
            table = new JOOQTableMapping(tableName);
        } else {
            table = null;
        }
        recordClassName = ClassName.get(GeneratorConfig.getGeneratedJooqRecordsPackage(), asRecordClassName(new RecordMethodMapping(tableName).getName()));
        requiredInputs = hasTable ? getRequiredFields(getTable().getName()).stream().map(String::toUpperCase).collect(Collectors.toSet()) : Set.of();
    }

    /**
     * @return Table objects which holds table names.
     */
    public JOOQTableMapping getTable() {
        return table;
    }

    /**
     * @return Does this object have the "{@link GenerationDirective#TABLE node}" directive
     * which implies a connection to a database table?
     */
    public boolean hasTable() {
        return hasTable;
    }

    /**
     * @return List of input fields contained within this input type.
     */
    public List<InputField> getInputs() {
        return inputs;
    }

    /**
     * @return List of input fields contained within this input type, sorted by whether they are required fields.
     */
    public List<InputField> getInputsSortedByRequired() {
        var splitOnIsRequired = inputs.stream().collect(Collectors.partitioningBy(this::isRequired));
        return Stream.concat(splitOnIsRequired.get(false).stream(), splitOnIsRequired.get(true).stream()).collect(Collectors.toList());
    }

    /**
     * @return Is this field non-nullable in the database?
     */
    private boolean isRequired(InputField field) {
        if (field.getFieldType().isID() && hasTable) {
            var hackedIDFields = getHackedIDFields(table.getName(), field.getRecordMappingName());
            if (hackedIDFields.isPresent()) {
                if (requiredInputs.containsAll(hackedIDFields.get())) {
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
     * Creates instances of this class for each of the {@link InputObjectTypeDefinition} provided.
     * @return List of InputDefinitions.
     */
    public static List<InputDefinition> processInputDefinitions(List<InputObjectTypeDefinition> inputs) {
        return inputs
                .stream()
                .map(InputDefinition::new)
                .collect(Collectors.toList());
    }
}
