package no.sikt.graphitron.rewrite.generators;

/**
 * Names where a condition/ordering call's <em>runtime argument values</em> are read from at a
 * given emission site. The generated condition and decode logic are identical either way;
 * only the {@code getArgument}-shaped read expression forks.
 *
 * <p>{@link Env} reads {@code env.getArgument(name)} off the enclosing
 * {@code DataFetchingEnvironment}; correct at root / {@code @splitQuery} fetcher sites, where
 * {@code env} is the field's own environment.
 *
 * <p>{@link FromSelectedField} reads {@code <sf>.getArguments().get(name)} off an in-scope
 * {@link graphql.schema.SelectedField} local; correct at the two inline emission sites
 * ({@link InlineTableFieldEmitter}, {@link InlineLookupTableFieldEmitter}), which emit inside
 * the generated {@code <Type>.$fields(sel, table, env)} method where {@code env} belongs to the
 * <em>ancestor</em> fetcher, not the inline field's own environment. The ancestor has no such
 * argument, so {@code env.getArgument(...)} there silently returns {@code null} and drops the
 * argument; the field's own arguments live on the {@code SelectedField} local already threaded
 * into both emitters.
 *
 * <p>An emission helper (a sibling of {@code CompositeDecodeHelperRegistry}), not a model type:
 * the fork varies by emission scope, not by model content. It is threaded as an explicit
 * parameter rather than carried on {@code TypeFetcherEmissionContext} because the source is
 * emission-point-scoped: {@code NestingField} recursion declares a fresh {@code SelectedField}
 * local per depth, so the source changes per depth exactly like the threaded {@code sfName} it
 * wraps. A sealed two-variant type rather than a nullable {@code String sfName} parameter, which
 * would be a tri-state sentinel.
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
