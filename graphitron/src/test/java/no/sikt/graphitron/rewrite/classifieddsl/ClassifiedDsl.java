package no.sikt.graphitron.rewrite.classifieddsl;

/**
 * The test-only classification directives and their SDL enums (the {@code @classified}
 * spec-by-example), by name. The declarations themselves live in the corpus's own prelude document
 * ({@link CorpusDocuments#prelude()}), and are deliberately <strong>never</strong> part of the
 * production {@code directives.graphqls} the plugin auto-injects: the directives are read by
 * {@link ClassifiedHarness}, ignored by the classifier, and exist in the schema document only so
 * graphql-java's {@code SchemaGenerator.makeExecutableSchema} accepts the applications (an
 * undeclared directive application fails schema assembly).
 *
 * <p>The enums make the assertion validated SDL-side: a typo in a {@code source}, {@code operations}, or
 * {@code target} value is a parse/assembly error graphql-java rejects before the harness runs.
 *
 * <ul>
 *   <li>{@code @classified} on output field definitions asserts the {@link DimensionTuple} the
 *       field classifies to; each endpoint is a wrapper plus a shape, and the enums mirror the
 *       field model's sealed-arm sets ({@code GraphitronSchema.sourceOf} /
 *       {@code GraphitronSchema.operationMembersOf} / {@code OutputField.target()}). The
 *       {@code operations:} list asserts the coordinate's operation-member rows as a multiset of
 *       sealed-arm tokens ({@code Member} mirrors the {@code OperationMember} leaves): arm
 *       identity and row count, never payloads or the condition rows' table keys (the
 *       {@code @commits} grammar applied to the member relation).</li>
 *   <li>{@code @classifiedType(as:)} asserts the {@code GraphitronType} sealed leaf a type
 *       classifies to; {@code TypeVerdict} enumerates those leaves minus the failure leaf
 *       {@code UnclassifiedType}, and {@link ClassifiedHarness} mirrors the enum against the live
 *       leaf set.</li>
 *   <li>{@code @synthesises(mints:)} on a connection carrier field asserts the type names the
 *       carrier causes to exist and the synthesised arm each is minted as. The synthesised types
 *       have no SDL declaration to carry {@code @classifiedType}, so the expectation is stated
 *       at the coordinate that causes the synthesis; coverage is derived from the agreement of
 *       the declaration with the connection-synthesis relation's produced rows, never from the
 *       producer's output alone. {@code SynthesisedType} mirrors the relation's declared
 *       minted-arm vocabulary.</li>
 *   <li>{@code @commits(source:, result:)} on an output field asserts the arm tokens of the
 *       coordinate's launcher command row (the {@code LaunchSource} and {@code ResultShape}
 *       arms), checked against the relation the harness produces under its one canonical run
 *       configuration on declared-equals-produced agreement. It makes no membership claim
 *       (membership is producer-declared leaf-grain data, census-bound beside the dispatch);
 *       the invocation axis is not an argument because the source arm determines it (declared
 *       as producer data), and tenancy is a run-configuration fact outside the coordinate's
 *       reach. {@code LauncherSource} / {@code LauncherResult} mirror the sealed arm sets.</li>
 * </ul>
 */
public final class ClassifiedDsl {

    private ClassifiedDsl() {}

    /** The {@code @classified} directive name (read off the field-definition AST by the harness). */
    public static final String CLASSIFIED = "classified";
    /** The {@code @classifiedType} directive name (read off the type-definition AST by the harness). */
    public static final String CLASSIFIED_TYPE = "classifiedType";
    /** The {@code @synthesises} directive name (read off the field-definition AST by the harness). */
    public static final String SYNTHESISES = "synthesises";
    /** The {@code @commits} directive name (read off the field-definition AST by the harness). */
    public static final String COMMITS = "commits";
}
