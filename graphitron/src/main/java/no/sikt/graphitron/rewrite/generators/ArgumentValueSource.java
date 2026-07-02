package no.sikt.graphitron.rewrite.generators;

/**
 * Names where a condition/ordering call's <em>runtime argument values</em> are read from at a
 * given emission site (R424). The generated condition and decode logic are identical either way;
 * only the {@code getArgument}-shaped read expression forks.
 *
 * <p>{@link Env} reads {@code env.getArgument(name)} off the enclosing {@code DataFetchingEnvironment}.
 * That is correct at every root / {@code @splitQuery} fetcher site, where {@code env} genuinely is
 * the field's own environment. It is the status quo, so root/split output stays byte-identical.
 *
 * <p>{@link FromSelectedField} reads {@code <sf>.getArguments().get(name)} off an in-scope
 * {@link graphql.schema.SelectedField} local. This is the correct source at the two inline
 * emission sites ({@code InlineTableFieldEmitter}, {@code InlineLookupTableFieldEmitter}), which
 * emit inside the generated {@code <Type>.$fields(sel, table, env)} method where {@code env}
 * belongs to the <em>ancestor</em> fetcher (the operation field that builds the whole correlated
 * multiset tree), not the inline field's own environment. The ancestor has no such argument, so an
 * {@code env.getArgument(...)} there silently returns {@code null} and drops the argument. The
 * field's own arguments live on the {@code SelectedField} local already threaded into both emitters
 * for {@code getSelectionSet()}.
 *
 * <p>This is an emission helper (a sibling of {@code CompositeDecodeHelperRegistry}), not a model
 * type: the {@code env}-vs-{@code SelectedField} fork varies by emission scope, not by model
 * content, so per generation-thinking it stays out of the model. It is threaded as an explicit
 * parameter (not carried on {@code TypeFetcherEmissionContext}, a class-scoped scratchpad) because
 * the source is emission-point-scoped: {@code NestingField} recursion declares a fresh
 * {@code SelectedField} local per depth, so the source changes per recursion depth exactly like the
 * threaded {@code sfName} it wraps. A sealed two-variant type is preferred over a nullable
 * {@code String sfName} parameter: the nullable-sentinel form is the tri-state smell the
 * sub-taxonomy principle warns against.
 */
public sealed interface ArgumentValueSource {

    /** Read runtime argument values off the enclosing {@code DataFetchingEnvironment} ({@code env.getArgument(name)}). */
    record Env() implements ArgumentValueSource {}

    /**
     * Read runtime argument values off an in-scope {@link graphql.schema.SelectedField} local
     * ({@code <sfLocal>.getArguments().get(name)}).
     *
     * @param sfLocal the Java local-variable name of the {@code SelectedField} in scope at the
     *                emission site (e.g. {@code "sf"}, {@code "sf1"} at deeper nesting depths)
     */
    record FromSelectedField(String sfLocal) implements ArgumentValueSource {}
}
