package no.sikt.graphitron.rewrite.classifieddsl;

/**
 * The test-only classification directives and their SDL enums (the {@code @classified}
 * spec-by-example). This prelude is declared <em>only</em> here, in the corpus harness, and is
 * deliberately <strong>never</strong> part of the production {@code directives.graphqls} the plugin
 * auto-injects: the directives are read by {@link ClassifiedHarness}, ignored by the classifier, and
 * exist in the schema document only so graphql-java's {@code SchemaGenerator.makeExecutableSchema}
 * accepts the applications (an undeclared directive application fails schema assembly).
 *
 * <p>The enums make the assertion validated SDL-side: a typo in a {@code source}, {@code operation}, or
 * {@code target} value is a parse/assembly error graphql-java rejects before the harness runs.
 *
 * <ul>
 *   <li>{@code @classified} on output field definitions asserts the three-axis
 *       {@link DimensionTuple} the field classifies to; each endpoint is a wrapper plus a shape,
 *       and the enums mirror the field model's sealed-arm sets ({@code GraphitronSchema.sourceOf} /
 *       the member-derived summary fold {@code DimensionTuple.summaryArmOf} /
 *       {@code OutputField.target()}).</li>
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
     * The test-only directive and enum declarations, prepended to every corpus fixture before the
     * classifier runs. The {@code TypeVerdict} value list mirrors the non-failure leaves of
     * {@code GraphitronType}: {@link ClassifiedHarness#typeVerdictEnumConstants()} (this list) is
     * checked against {@link ClassifiedHarness#graphitronTypeNonFailureLeafNames()} (the live leaf
     * set) by {@code ClassifiedDslTest#typeVerdictMirrorsGraphitronTypeLeaves()}, which fails the
     * build if the two ever drift.
     */
    public static final String PRELUDE = """
        enum SourceWrapper { Query Mutation OnlyChild Child }

        enum Operation {
          Fetch Paginate Lookup ServiceCall Count Facet Nest Pivot
          NodeResolve EntityResolve
          Insert Upsert Update UpdateMatching Delete DeleteMatching RoutineWrite
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
          ServiceCall ServiceTableLift PivotAggregate KeyedLookup
          ProjectedReentry DiscriminatedReentry DiscriminatedTable
        }

        enum LauncherResult { SingleRecord LoaderDelegated RecordList Connection }

        input Mint { name: String!, as: SynthesisedType! }

        directive @classified(
          source: SourceWrapper!, operation: Operation!, target: TargetWrapper!, targetShape: TargetShape!
          sourceShape: SourceShape
        ) on FIELD_DEFINITION

        directive @classifiedType(as: TypeVerdict!) on
          OBJECT | INTERFACE | UNION | INPUT_OBJECT | ENUM | SCALAR

        directive @synthesises(mints: [Mint!]!) on FIELD_DEFINITION

        directive @commits(source: LauncherSource!, result: LauncherResult!) on FIELD_DEFINITION
        """;
}
