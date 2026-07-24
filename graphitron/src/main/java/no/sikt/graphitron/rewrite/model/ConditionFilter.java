package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;

/**
 * A developer-supplied {@code @condition} method on a {@code FIELD_DEFINITION}: a
 * {@link MethodRef} the fetcher generator calls as a WHERE-clause contribution.
 *
 * <p>The condition method signature is:
 * <pre>
 *     Condition method(Table&lt;?&gt; targetTable, arg1, arg2, ...)
 * </pre>
 * with parameters in declaration order via {@link #params()}.
 *
 * <p>The first parameter always has {@link ParamSource.Table} as its source (the target table
 * alias) and is implicit: it is not represented in {@link #callParams()}. Subsequent parameters
 * have {@link ParamSource.Arg} or {@link ParamSource.Context}.
 *
 * <p>The {@code override} flag from the {@code @condition} directive is consumed by the builder
 * ({@code FieldBuilder}), which on {@code override: true} omits the {@link GeneratedConditionFilter}
 * otherwise generated for the field's arguments. This record never carries an override flag; the
 * suppression is expressed entirely by the absence of the {@link GeneratedConditionFilter} entry
 * in the field's {@code filters} list.
 */
public record ConditionFilter(
    String className,
    String methodName,
    List<MethodRef.Param> params
) implements WhereFilter, MethodRef {

    private static final TypeName CONDITION = ClassName.get("org.jooq", "Condition");

    @Override
    public TypeName returnType() {
        return CONDITION;
    }

    @Override
    public List<CallParam> callParams() {
        return MethodRef.super.callParams();
    }
}
