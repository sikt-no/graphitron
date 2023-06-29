package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.InputObjectTypeDefinition;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.mapping.RecordMethodMapping;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQTableMapping;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLRecord;
import no.fellesstudentsystem.graphql.mapping.GenerationDirective;
import no.fellesstudentsystem.graphql.schema.SchemaHelpers;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.mapping.GenerationDirective.RECORD;
import static no.fellesstudentsystem.graphql.mapping.GraphQLDirectiveParam.NAME;
import static no.fellesstudentsystem.graphql.mapping.GraphQLDirectiveParam.TABLE;

public class InputDefinition extends AbstractObjectDefinition<InputObjectTypeDefinition> {
    private final JOOQTableMapping table;
    private final boolean hasTable;
    private final List<InputField> inputs;
    private final SQLRecord record;

    public InputDefinition(InputObjectTypeDefinition inputType) { // TODO: Add info about this to readme.
        super(inputType);
        inputs = inputType.getInputValueDefinitions().stream().map(InputField::new).collect(Collectors.toList());

        var hasTable = inputType.hasDirective(GenerationDirective.TABLE.getName());
        var hasRecord = inputType.hasDirective(RECORD.getName());
        String tableName = getName().toUpperCase();
        if (hasTable) {
            tableName = SchemaHelpers.getOptionalDirectiveArgumentString(inputType, GenerationDirective.TABLE, GenerationDirective.TABLE.getParamName(NAME)).orElse(tableName);
            table = new JOOQTableMapping(tableName);
        } else if (hasRecord) {
            tableName = SchemaHelpers.getOptionalDirectiveArgumentString(inputType, RECORD, RECORD.getParamName(TABLE)).orElse(tableName);
            table = new JOOQTableMapping(tableName);
        } else {
            table = null;
        }
        this.hasTable = hasTable || hasRecord;
        record = new SQLRecord(new RecordMethodMapping(tableName));
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
     * @return Record-side method name mappings based on the DB equivalent of this input.
     */
    public SQLRecord asSQLRecord() {
        return record;
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
