package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.EnumTypeDefinition;
import no.fellesstudentsystem.graphitron.definitions.fields.EnumField;
import no.fellesstudentsystem.graphql.schema.SchemaHelpers;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.definitions.fields.EnumField.from;
import static no.fellesstudentsystem.graphql.mapping.GenerationDirective.MAP_ENUM;
import static no.fellesstudentsystem.graphql.mapping.GraphQLDirectiveParam.NAME;

/**
 * Representation of a GraphQL enum type.
 */
public class EnumDefinition extends AbstractObjectDefinition<EnumTypeDefinition> {
    private final String dbName;
    private final Map<String, EnumField> valuesMap;

    public EnumDefinition(EnumTypeDefinition enumTypeDefinition) {
        super(enumTypeDefinition);
        this.dbName = enumTypeDefinition.hasDirective(MAP_ENUM.getName())
                ? SchemaHelpers.getDirectiveArgumentString(enumTypeDefinition, MAP_ENUM, MAP_ENUM.getParamName(NAME))
                : null;
        this.valuesMap = from(enumTypeDefinition)
                .stream()
                .collect(Collectors.toMap(EnumField::getName, Function.identity()));
    }

    /**
     * @return Name of the API enum that should correspond to this one.
     */
    public String getDbName() {
        return dbName;
    }

    /**
     * @return Does this enum map to another enum in the API?
     */
    public boolean hasDbEnumMapping() {
        return dbName != null;
    }

    /**
     * @return A map of the enum values for this enum object.
     */
    public Map<String, EnumField> getValuesMap() {
        return valuesMap;
    }

    /**
     * Creates instances of this class for each of the {@link EnumTypeDefinition} provided.
     * @return List of EnumDefinitions.
     */
    public static List<EnumDefinition> processEnumDefinitions(List<EnumTypeDefinition> enums) {
        return enums
                .stream()
                .map(EnumDefinition::new)
                .collect(Collectors.toList());
    }
}
