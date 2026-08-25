package no.sikt.graphitron.rewrite.classifieddsl;

/**
 * The test-only classification directives and their SDL enums (the {@code @classified}
 * spec-by-example). This prelude is declared <em>only</em> here, in the corpus harness, and is
 * deliberately <strong>never</strong> part of the production {@code directives.graphqls} the plugin
 * auto-injects: the directives are read by {@link ClassifiedHarness}, ignored by the classifier, and
 * exist in the schema document only so graphql-java's {@code SchemaGenerator.makeExecutableSchema}
 * accepts the applications (an undeclared directive application fails schema assembly).
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

    /**
     * The test-only directive and enum declarations plus the base schema, prepended to every corpus
     * fixture before the classifier runs.
     *
     * <p><b>Why a base schema lives here.</b> A GraphQL document is only a schema if it has a query
     * root, so a fixture about mutations alone used to carry a throwaway {@code Query} of its own.
     * The cheapest throwaway, a {@code String}-returning field, is itself an author error, so those
     * fixtures classified but could not be assembled for generation, and the documentation page
     * rendered from them reported that a plain INSERT generates nothing. Declaring the root once
     * here removes the need for a throwaway: a fixture contributes its own roots with
     * {@code extend type Query} and a fixture with no roots of its own declares nothing.
     *
     * <p>The same reasoning puts {@code Node} here. It was previously injected by
     * {@code TestSchemaHelper} only when a fixture did not declare it, and only on the classification
     * path, so a fixture could say {@code implements Node} without declaring the interface and pass
     * classification while failing assembly. One declaration on the one path every reader shares is
     * what keeps the two halves of an example talking about the same schema.
     *
     * <p>{@code CorpusAnchor} binds {@code category}, deliberately a table no fixture uses: the
     * anchor is present in every fixture, so a table any fixture reached would change that fixture's
     * arrival fold by existing.
     *
     * <p>The {@code TypeVerdict} value list mirrors the non-failure leaves of
     * {@code GraphitronType}: {@link ClassifiedHarness#typeVerdictEnumConstants()} (this list) is
     * checked against {@link ClassifiedHarness#graphitronTypeNonFailureLeafNames()} (the live leaf
     * set) by {@code ClassifiedDslTest#typeVerdictMirrorsGraphitronTypeLeaves()}, which fails the
     * build if the two ever drift.
     */
    public static final String PRELUDE = """
        enum SourceWrapper { Query Mutation OnlyChild Child }

        enum Member {
          Select Join OnReturnTable OnParticipant OrderBy Paginate Lookup ServiceCall
          NodeResolve EntityResolve Count Facet Pivot Reentry
          Insert Upsert Update Delete UpdateMatching DeleteMatching RoutineWrite
        }

        enum TargetWrapper { Single List }

        enum SourceShape { Table Record }

        enum TargetShape { Table Record Column Field Connection Interface Union }

        enum TypeVerdict {
          TableType NodeType TableInterfaceType
          JavaRecordType Backed JooqRecordType JooqTableRecordType
          RootType InterfaceType UnionType ErrorType
          JavaRecordInputType PojoInputType JooqRecordInputType JooqTableRecordInputType
          NestingType EnumType ScalarType
          ConnectionType EdgeType PageInfoType FacetsType FacetValueType
        }

        enum SynthesisedType { ConnectionType EdgeType PageInfoType FacetsType FacetValueType }

        enum LauncherSource {
          AnchorTable RoutineChain CorrelatedChain CorrelatedLookupChain
          DiscriminatedCorrelatedChain
          ServiceCall ServiceTableLift PivotAggregate KeyedLookup
          ProjectedReentry DiscriminatedReentry DiscriminatedTable
        }

        enum LauncherResult { SingleRecord LoaderDelegated RecordList Connection }

        input Mint { name: String!, as: SynthesisedType! }

        directive @classified(
          source: SourceWrapper!, operations: [Member!]!, target: TargetWrapper!, targetShape: TargetShape!
          sourceShape: SourceShape
        ) on FIELD_DEFINITION

        directive @classifiedType(as: TypeVerdict!) on
          OBJECT | INTERFACE | UNION | INPUT_OBJECT | ENUM | SCALAR

        directive @synthesises(mints: [Mint!]!) on FIELD_DEFINITION

        directive @commits(source: LauncherSource!, result: LauncherResult!) on FIELD_DEFINITION

        interface Node { id: ID! }

        type Query { corpusAnchor: CorpusAnchor }

        type CorpusAnchor @table(name: "category") { name: String }
        """;
}
