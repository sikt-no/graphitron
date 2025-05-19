package no.sikt.graphql.directives;

import java.util.EnumSet;

/**
 * Contains all the available generator directives and their parameters.
 * This must match what is defined in the Graphitron directive schema.
 */
public enum GenerationDirective {
    SPLIT_QUERY("splitQuery"),
    NOT_GENERATED("notGenerated"),
    TABLE("table", EnumSet.of(GenerationDirectiveParam.NAME)),
    FIELD("field", EnumSet.of(GenerationDirectiveParam.NAME, GenerationDirectiveParam.JAVA_NAME)),
    INDEX("index", EnumSet.of(GenerationDirectiveParam.NAME)),
    SERVICE("service", EnumSet.of(GenerationDirectiveParam.SERVICE)),
    RECORD("record", EnumSet.of(GenerationDirectiveParam.RECORD)),
    FETCH_BY_ID("fetchByID"),
    ERROR("error", EnumSet.of(GenerationDirectiveParam.HANDLERS)),
    MUTATION("mutation", EnumSet.of(GenerationDirectiveParam.TYPE)),
    LOOKUP_KEY("lookupKey"),
    REFERENCE("reference", EnumSet.of(GenerationDirectiveParam.REFERENCES)),
    ENUM("enum", EnumSet.of(GenerationDirectiveParam.ENUM)),
    CONDITION("condition", EnumSet.of(GenerationDirectiveParam.CONDITION, GenerationDirectiveParam.OVERRIDE)),
    ORDER_BY("orderBy"),
    DISCRIMINATE("discriminate", EnumSet.of(GenerationDirectiveParam.ON)),
    DISCRIMINATOR("discriminator", EnumSet.of(GenerationDirectiveParam.VALUE)),
    EXTERNAL_FIELD("externalField"),
    NODE("node", EnumSet.of(GenerationDirectiveParam.TYPE_ID, GenerationDirectiveParam.KEY_COLUMNS));

    private final String name;

    private final EnumSet<GenerationDirectiveParam> paramSet;

    GenerationDirective(String name) {
        this.name = name;
        this.paramSet = EnumSet.noneOf(GenerationDirectiveParam.class);
    }

    GenerationDirective(String name, EnumSet<GenerationDirectiveParam> paramSet) {
        this.name = name;
        this.paramSet = paramSet;
    }

    public String getName() {
        return name;
    }

    public void checkParamIsValid(GenerationDirectiveParam param) {
        if (!paramSet.contains(param)) {
            throw new IllegalArgumentException("Directive " + name + " has no parameter called " + param.getName() + ".");
        }
    }
}
