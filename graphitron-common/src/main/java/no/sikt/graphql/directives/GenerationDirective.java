package no.sikt.graphql.directives;

import java.util.EnumSet;

/**
 * Contains all the available generator directives and their parameters.
 * This must match what is defined in the Graphitron directive schema.
 *
 * @deprecated New code should not use this enum. Directive names are now read directly from
 *     the {@link graphql.schema.idl.TypeDefinitionRegistry} in
 *     {@link no.sikt.graphitron.record.GraphitronSchemaBuilder}, which is the single
 *     directive-reading boundary. This enum remains for legacy DTO-layer code that has not
 *     yet been migrated to the {@code GraphitronType}/{@code GraphitronField} model.
 */
@Deprecated(forRemoval = true)
public enum GenerationDirective {
    SPLIT_QUERY("splitQuery"),
    NOT_GENERATED("notGenerated"),
    TABLE("table", EnumSet.of(GenerationDirectiveParam.NAME)),
    FIELD("field", EnumSet.of(GenerationDirectiveParam.NAME, GenerationDirectiveParam.JAVA_NAME)),
    /**
     * @deprecated Use {@link #ORDER} with the index parameter instead.
     */
    @Deprecated
    INDEX("index", EnumSet.of(GenerationDirectiveParam.NAME)),
    ORDER("order", EnumSet.of(GenerationDirectiveParam.INDEX, GenerationDirectiveParam.FIELDS, GenerationDirectiveParam.PRIMARY_KEY)),
    SERVICE("service", EnumSet.of(GenerationDirectiveParam.SERVICE, GenerationDirectiveParam.CONTEXT_ARGUMENTS)),
    TABLE_METHOD("tableMethod", EnumSet.of(GenerationDirectiveParam.TABLE_METHOD_REFERENCE, GenerationDirectiveParam.CONTEXT_ARGUMENTS)),
    MULTITABLE_REFERENCE("multitableReference", EnumSet.of(GenerationDirectiveParam.ROUTES)),
    RECORD("record", EnumSet.of(GenerationDirectiveParam.RECORD)),
    ERROR("error", EnumSet.of(GenerationDirectiveParam.HANDLERS)),
    MUTATION("mutation", EnumSet.of(GenerationDirectiveParam.TYPE)),
    LOOKUP_KEY("lookupKey"),
    REFERENCE("reference", EnumSet.of(GenerationDirectiveParam.PATH)),
    ENUM("enum", EnumSet.of(GenerationDirectiveParam.ENUM)),
    CONDITION("condition", EnumSet.of(GenerationDirectiveParam.CONDITION, GenerationDirectiveParam.OVERRIDE, GenerationDirectiveParam.CONTEXT_ARGUMENTS)),
    ORDER_BY("orderBy"),
    DEFAULT_ORDER("defaultOrder", EnumSet.of(GenerationDirectiveParam.INDEX, GenerationDirectiveParam.FIELDS, GenerationDirectiveParam.PRIMARY_KEY, GenerationDirectiveParam.DIRECTION)),
    DISCRIMINATE("discriminate", EnumSet.of(GenerationDirectiveParam.ON)),
    DISCRIMINATOR("discriminator", EnumSet.of(GenerationDirectiveParam.VALUE)),
    EXTERNAL_FIELD("externalField"),
    NODE("node", EnumSet.of(GenerationDirectiveParam.TYPE_ID, GenerationDirectiveParam.KEY_COLUMNS)),
    NODE_ID("nodeId", EnumSet.of(GenerationDirectiveParam.TYPE_NAME));

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
