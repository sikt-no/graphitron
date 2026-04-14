package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * A developer-supplied {@code @condition} method on a {@code FIELD_DEFINITION}.
 *
 * <p>Implements {@link WhereFilter} so that developer-supplied and Graphitron-generated
 * conditions can be mixed uniformly in a {@code List<WhereFilter>} and called by the fetcher
 * generator without distinguishing between them.
 *
 * <p>The condition method signature is:
 * <pre>
 *     Condition method(Table&lt;?&gt; targetTable, arg1, arg2, ...)
 * </pre>
 * where the parameters are derived from {@link MethodRef#params()} in declaration order.
 *
 * <p>The first parameter always has {@link ParamSource.Table} as its source (the target table
 * alias) and is implicit — it is not represented in {@link #callParams()}. Subsequent parameters
 * have {@link ParamSource.Arg} (bound via {@code DataFetchingEnvironment.getArgument}) or
 * {@link ParamSource.Context} (bound via {@code GraphitronContext.getContextArgument}).
 *
 * <p>Context argument names are those parameters where
 * {@code param.source() instanceof ParamSource.Context} — derivable on demand from
 * {@link MethodRef#params()} without a separate field.
 *
 * <p>The {@code override} flag from the {@code @condition} directive is consumed by the builder:
 * when {@code override: true} is set, the builder omits any {@link GeneratedConditionFilter} that
 * would otherwise be generated for the field's arguments. {@link ConditionFilter} itself never
 * carries an override flag — the suppression is expressed entirely by the absence of the
 * {@link GeneratedConditionFilter} entry in the field's {@code filters} list.
 */
public record ConditionFilter(
    MethodRef method
) implements WhereFilter {

    @Override
    public String className() {
        return method.className();
    }

    @Override
    public String methodName() {
        return method.methodName();
    }

    /**
     * Derives the fetcher call-site parameters from {@link #method()} by skipping the implicit
     * {@link ParamSource.Table} first parameter and mapping each remaining parameter's source to
     * the corresponding {@link CallSiteExtraction}.
     *
     * <p>When the builder gains support for constructing {@code ConditionFilter} instances
     * (currently a deferred deliverable), this derivation will move to the builder and
     * {@code callParams} will become a record component carrying the pre-resolved list.
     */
    @Override
    public List<CallParam> callParams() {
        return method.params().stream()
            .filter(p -> !(p.source() instanceof ParamSource.Table))
            .map(p -> new CallParam(p.name(), toExtraction(p.source())))
            .toList();
    }

    private static CallSiteExtraction toExtraction(ParamSource source) {
        return switch (source) {
            case ParamSource.Context ignored -> new CallSiteExtraction.ContextArg();
            default -> new CallSiteExtraction.Direct();
        };
    }
}
