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
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asRecordClassName;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.RECORD;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.NAME;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.TABLE;

public class InputDefinition extends AbstractObjectDefinition<InputObjectTypeDefinition> {
    private final JOOQTableMapping table;
    private final boolean hasTable;
    private final List<InputField> inputs;
    private final TypeName recordClassName;

    public InputDefinition(InputObjectTypeDefinition inputType) {
        super(inputType);
        inputs = inputType.getInputValueDefinitions().stream().map(InputField::new).collect(Collectors.toList());

        var hasTable = inputType.hasDirective(GenerationDirective.TABLE.getName());
        var hasRecord = inputType.hasDirective(RECORD.getName());
        String tableName = getName().toUpperCase();
        if (hasTable) {
            tableName = DirectiveHelpers.getOptionalDirectiveArgumentString(inputType, GenerationDirective.TABLE, GenerationDirective.TABLE.getParamName(NAME)).orElse(tableName);
            table = new JOOQTableMapping(tableName);
        } else if (hasRecord) {
            tableName = DirectiveHelpers.getOptionalDirectiveArgumentString(inputType, RECORD, RECORD.getParamName(TABLE)).orElse(tableName);
            table = new JOOQTableMapping(tableName);
        } else {
            table = null;
        }
        this.hasTable = hasTable || hasRecord;
        recordClassName = ClassName.get(GeneratorConfig.getGeneratedJooqRecordsPackage(), asRecordClassName(new RecordMethodMapping(tableName).getName()));
    }

    /**
     * @return Table objects which holds table names.
     */
    public JOOQTableMapping getTable() {
        return table;
    }

    /**
     * @return Does this object have the "{@link GenerationDirective#TABLE node}" or "{@link GenerationDirective#RECORD record}" directive
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
