package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * A developer-supplied {@code @condition} method on a {@code FIELD_DEFINITION}.
 *
 * <p>Represents a {@link WhereFilter} entry that calls a developer-provided Java method and ANDs
 * the result into the SQL WHERE clause. The method signature is:
 * <pre>
 *     Condition method(Table&lt;?&gt; targetTable, arg1, arg2, ...)
 * </pre>
 * where the parameters are derived from {@link MethodRef#params()} in declaration order.
 *
 * <p>The first parameter always has {@link ParamSource.Table} as its source (the target table
 * alias). Subsequent parameters have {@link ParamSource.Arg} (a field argument bound via
 * {@code DataFetchingEnvironment.getArgument}) or {@link ParamSource.Context} (a context argument
 * bound via {@code GraphitronContext.getContextArgument}). Context argument names that are not
 * present as GraphQL arguments are listed in {@link #contextArgs()}.
 *
 * <p>{@code contextArgs} is the list of parameter names that resolve via
 * {@link ParamSource.Context}. This list is used both to identify which parameters bypass the
 * normal argument binding and to validate at build time that each name is a declared
 * {@code contextArguments} entry on the directive.
 *
 * <p>The {@code override} flag from the {@code @condition} directive is consumed by the builder:
 * when {@code override: true} is set, the builder omits any {@link WhereFilter.ColumnFilter} or
 * {@link WhereFilter.InputFilter} entries that would otherwise be generated for the field's
 * arguments. {@link ConditionFilter} itself never carries an override flag — the suppression is
 * expressed entirely by the absence of the corresponding filter entries in the field's
 * {@code filters} list.
 *
 * <p>Implements {@link WhereFilter} so that field-level and argument-level conditions can be
 * mixed uniformly in a {@code List<WhereFilter>}.
 */
public record ConditionFilter(
    MethodRef method,
    List<String> contextArgs
) implements WhereFilter {}
