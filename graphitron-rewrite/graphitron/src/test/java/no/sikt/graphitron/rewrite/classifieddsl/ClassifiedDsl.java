package no.sikt.graphitron.rewrite.classifieddsl;

/**
 * The test-only classification directives and their SDL enums (R281's {@code @classified}
 * spec-by-example). This prelude is declared <em>only</em> here, in the corpus harness, and is
 * deliberately <strong>never</strong> part of the production {@code directives.graphqls} the plugin
 * auto-injects: the directives are read by {@link ClassifiedHarness}, ignored by the classifier, and
 * exist in the schema document only so graphql-java's {@code SchemaGenerator.makeExecutableSchema}
 * accepts the applications (an undeclared directive application fails schema assembly).
 *
 * <p>The enums make the assertion validated SDL-side: a typo in a {@code carrier}, {@code intent}, or
 * {@code mapping} value is a parse/assembly error graphql-java rejects before the harness runs, and
 * the values autocomplete in a schema-aware editor.
 *
 * <ul>
 *   <li>{@code @classified(carrier: Carrier!, intent: Intent!, mapping: Mapping!)} on output
 *       {@code FIELD_DEFINITION}s asserts the three-axis {@link DimensionTuple} the field classifies
 *       to (R299). The {@code Carrier} and {@code Intent} enums mirror the field model's value
 *       sets (R290's {@code OutputField.carrier()} / {@code intent()} / {@code mapping()}).</li>
 *   <li>{@code @classifiedType(as: TypeVerdict!)} asserts the {@code GraphitronType} sealed leaf a
 *       type classifies to. {@code TypeVerdict} enumerates those leaves minus the failure leaf
 *       {@code UnclassifiedType}; {@link ClassifiedHarness} mirrors the enum against the live leaf set.</li>
 * </ul>
 *
 * <p>See {@code roadmap/classification-test-dsl.md} §"Classification directives".
 */
public final class ClassifiedDsl {

    private ClassifiedDsl() {}

    /** The {@code @classified} directive name (read off the field-definition AST by the harness). */
    public static final String CLASSIFIED = "classified";
    /** The {@code @classifiedType} directive name (read off the type-definition AST by the harness). */
    public static final String CLASSIFIED_TYPE = "classifiedType";

    /**
     * The test-only directive and enum declarations, prepended to every corpus fixture before the
     * classifier runs. The {@code TypeVerdict} value list mirrors the non-failure leaves of
     * {@code GraphitronType}: {@link ClassifiedHarness#typeVerdictEnumConstants()} (this list) is
     * checked against {@link ClassifiedHarness#graphitronTypeNonFailureLeafNames()} (the live leaf
     * set) by {@code ClassifiedDslTest#typeVerdictMirrorsGraphitronTypeLeaves()}, which fails the
     * build if the two ever drift, the validator-mirrors-classifier discipline applied to the type
     * half of the DSL.
     */
    public static final String PRELUDE = """
        enum Carrier { Query Mutation Source }

        enum Intent {
          Fetch Lookup NodeResolve EntityResolve Count Facet Nesting
          Insert Upsert Update UpdateMatching Delete DeleteMatching
          QueryService MutationService
        }

        enum Mapping { Table TableConnection Column Record Field }

        enum SourceShape { Table Record }

        enum SourceCardinality { One Many }

        enum TypeVerdict {
          TableType NodeType TableInterfaceType
          JavaRecordType Backed JooqRecordType JooqTableRecordType
          RootType InterfaceType UnionType ErrorType
          JavaRecordInputType PojoInputType JooqRecordInputType JooqTableRecordInputType TableInputType
          NestingType EnumType ScalarType
          ConnectionType EdgeType PageInfoType
        }

        directive @classified(
          carrier: Carrier!, intent: Intent!, mapping: Mapping!
          sourceShape: SourceShape, sourceCardinality: SourceCardinality
        ) on FIELD_DEFINITION

        directive @classifiedType(as: TypeVerdict!) on
          OBJECT | INTERFACE | UNION | INPUT_OBJECT | ENUM | SCALAR
        """;
}
