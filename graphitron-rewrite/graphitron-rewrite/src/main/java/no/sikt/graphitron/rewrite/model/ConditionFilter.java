package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * A developer-supplied {@code @condition} method on a {@code FIELD_DEFINITION}.
 *
 * <p>Implements both {@link WhereFilter} and {@link MethodRef}: a condition method IS a method
 * reference with the additional contract that the fetcher generator calls it as a WHERE-clause
 * contribution.
 *
 * <p>The condition method signature is:
 * <pre>
 *     Condition method(Table&lt;?&gt; targetTable, arg1, arg2, ...)
 * </pre>
 * where the parameters are in declaration order via {@link #params()}.
 *
 * <p>The first parameter always has {@link ParamSource.Table} as its source (the target table
 * alias) and is implicit — it is not represented in {@link #callParams()}. Subsequent parameters
 * have {@link ParamSource.Arg} (bound via {@code DataFetchingEnvironment.getArgument}) or
 * {@link ParamSource.Context} (bound via {@code GraphitronContext.getContextArgument}).
 *
 * <p>The {@code override} flag from the {@code @condition} directive is consumed by the builder:
 * when {@code override: true} is set, the builder omits any {@link GeneratedConditionFilter} that
 * would otherwise be generated for the field's arguments. {@link ConditionFilter} itself never
 * carries an override flag — the suppression is expressed entirely by the absence of the
 * {@link GeneratedConditionFilter} entry in the field's {@code filters} list.
 *
 * <p>{@link #callParams()} is inherited from {@link MethodRef#callParams()}, which skips implicit
 * parameters and maps each extracted parameter's source to the appropriate
 * {@link CallSiteExtraction}. When the builder gains support for constructing
 * {@code ConditionFilter} instances (currently a deferred deliverable), richer extraction
 * strategies ({@link CallSiteExtraction.EnumValueOf}, {@link CallSiteExtraction.TextMapLookup})
 * can be set at build time in the builder — the generator requires no changes.
 */
public record ConditionFilter(
    String className,
    String methodName,
    List<MethodRef.Param> params
) implements WhereFilter, MethodRef {

    @Override
    public String returnTypeName() {
        return "org.jooq.Condition";
    }

    @Override
    public List<CallParam> callParams() {
        return MethodRef.super.callParams();
    }
}
