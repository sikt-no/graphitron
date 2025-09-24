package no.sikt.graphitron.definitions.fields;

import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import no.sikt.graphitron.definitions.mapping.MethodMapping;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.toCamelCase;

public class VirtualInputField extends InputField {
    public static final TypeName PLACEHOLDER_TYPENAME = new TypeName("String");
    private final MethodMapping mappingForRecordFieldOverride;


    public VirtualInputField(String fieldName, String container) {
        super(new InputValueDefinition(fieldName, PLACEHOLDER_TYPENAME), container);
        this.mappingForRecordFieldOverride = new MethodMapping(toCamelCase(getUpperCaseName()));
    }

    @Override
    public MethodMapping getMappingForRecordFieldOverride() {
        return mappingForRecordFieldOverride;
    }
}
