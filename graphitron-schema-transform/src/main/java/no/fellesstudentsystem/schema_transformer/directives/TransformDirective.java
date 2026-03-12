package no.fellesstudentsystem.schema_transformer.directives;

import java.util.EnumSet;

/**
 * Contains all the currently used generic directives and their available parameters.
 * It is expected that this enum matches what is found in the schema.
 */
public enum TransformDirective {
    CONNECTION("connection", EnumSet.of(TransformDirectiveParam.FOR)),
    AS_CONNECTION("asConnection", EnumSet.of(TransformDirectiveParam.FIRST_DEFAULT, TransformDirectiveParam.CONNECTION_NAME)),
    FEATURE("feature", EnumSet.of(TransformDirectiveParam.FLAGS));

    private final String name;

    private final EnumSet<TransformDirectiveParam> paramSet;

    TransformDirective(String name) {
        this.name = name;
        this.paramSet = EnumSet.noneOf(TransformDirectiveParam.class);
    }

    TransformDirective(String name, EnumSet<TransformDirectiveParam> paramSet) {
        this.name = name;
        this.paramSet = paramSet;
    }

    public String getName() {
        return name;
    }

    public String getParamName(TransformDirectiveParam param) {
        if (!paramSet.contains(param)) {
            throw new IllegalArgumentException("Directive " + name + " has no parameter called " + param.getName() + ".");
        }
        return param.getName();
    }
}
