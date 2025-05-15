package no.fellesstudentsystem.schema_transformer.mapping;

import java.util.EnumSet;

/**
 * Contains all the currently used generic directives and their available parameters.
 * It is expected that this enum matches what is found in the schema.
 */
public enum GraphQLDirective {
    CONNECTION("connection", EnumSet.of(GraphQLDirectiveParam.FOR)),
    AS_CONNECTION("asConnection", EnumSet.of(GraphQLDirectiveParam.FIRST_DEFAULT, GraphQLDirectiveParam.CONNECTION_NAME)),
    FEATURE("feature", EnumSet.of(GraphQLDirectiveParam.FLAGS));

    private final String name;

    private final EnumSet<GraphQLDirectiveParam> paramSet;

    GraphQLDirective(String name) {
        this.name = name;
        this.paramSet = EnumSet.noneOf(GraphQLDirectiveParam.class);
    }

    GraphQLDirective(String name, EnumSet<GraphQLDirectiveParam> paramSet) {
        this.name = name;
        this.paramSet = paramSet;
    }

    public String getName() {
        return name;
    }

    public String getParamName(GraphQLDirectiveParam param) {
        if (!paramSet.contains(param)) {
            throw new IllegalArgumentException("Directive " + name + " has no parameter called " + param.getName() + ".");
        }
        return param.getName();
    }
}
