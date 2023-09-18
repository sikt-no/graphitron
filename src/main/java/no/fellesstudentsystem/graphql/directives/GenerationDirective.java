package no.fellesstudentsystem.graphql.directives;

import java.util.EnumSet;

/**
 * Contains all the available generator directives and their parameters.
 * This must match what is defined in the Graphitron directive schema.
 */
public enum GenerationDirective {
    SPLIT_QUERY("splitQuery"),
    NOT_GENERATED("notGenerated"),
    TABLE("table", EnumSet.of(GenerationDirectiveParam.NAME)),
    COLUMN("column", EnumSet.of(GenerationDirectiveParam.NAME, GenerationDirectiveParam.TABLE, GenerationDirectiveParam.KEY)),
    SERVICE("service", EnumSet.of(GenerationDirectiveParam.NAME)),
    ERROR("error", EnumSet.of(GenerationDirectiveParam.NAME)),
    MUTATION_TYPE("mutationType", EnumSet.of(GenerationDirectiveParam.TYPE)),
    REFERENCE("reference", EnumSet.of(GenerationDirectiveParam.TABLE, GenerationDirectiveParam.KEY, GenerationDirectiveParam.CONDITION)),
    MAP_ENUM("mapEnum", EnumSet.of(GenerationDirectiveParam.NAME)),
    CONDITION("condition", EnumSet.of(GenerationDirectiveParam.NAME, GenerationDirectiveParam.OVERRIDE));

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

    public String getParamName(GenerationDirectiveParam param) {
        if (!paramSet.contains(param)) {
            throw new IllegalArgumentException("Directive " + name + " has no parameter called " + param.getName() + ".");
        }
        return param.getName();
    }
}
