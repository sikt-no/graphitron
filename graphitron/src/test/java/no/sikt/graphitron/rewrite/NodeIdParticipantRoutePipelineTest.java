package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.ParticipantFilters;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code @nodeId} filter leaf on a query returning a multi-table interface or union resolves its
 * route to the decoded target once per participant, against that participant's own table, and
 * {@code @referenceFor} is what lets each participant state its own.
 *
 * <p>The sakila fixture carries the reported shape exactly. {@code film} has two foreign keys to
 * {@code language} ({@code language_id} and {@code original_language_id}), so auto-discovery cannot
 * pick one; {@code inventory} has none at all and reaches {@code language} only through
 * {@code film}. Neither participant resolves without an explicit route, and no single stated path
 * describes both, which is what a leaf-level {@code @reference} would have had to do.
 *
 * <p>Sibling of {@link MultiTableFilterLoweringTest}, which pins that a filter lowers once per
 * participant at all; what stands here is which route each participant takes and who owns the
 * predicate when none does.
 */
@PipelineTier
class NodeIdParticipantRoutePipelineTest {

    /** The decode target, a node type over {@code language}, plus the two participants. */
    private static final String LANGUAGE_FILM_INVENTORY =
        """
        type Language implements Node @table(name: "language") @node {
            id: ID! @nodeId
            name: String
        }
        type Film @table(name: "film") { title: String }
        type Inventory @table(name: "inventory") {
            inventoryId: ID! @field(name: "inventory_id")
        }
        union Stock = Film | Inventory
        """;

    // ===== Per-participant routes =====

    @Test
    void twoDifferentlyKeyedParticipants_eachTakesItsOwnStatedRoute() {
        var schema = TestSchemaHelper.buildSchema(LANGUAGE_FILM_INVENTORY + """
            input StockFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @referenceFor(type: "Film", path: [{key: "film_language_id_fkey"}])
                    @referenceFor(type: "Inventory", path: [{key: "inventory_film_id_fkey"}, {key: "film_language_id_fkey"}])
            }
            type Query { stock(filter: StockFilter): [Stock!]! }
            """);

        var filters = participantFilters(schema, "stock");
        // Film's stated route is the one foreign key it means, so the decoded language key lands on
        // film.language_id and the predicate binds locally with no join.
        assertThat(localPredicateColumn(filters, "Film")).isEqualTo("language_id");
        // Inventory's route leaves its own table at the first hop, so no column of inventory holds a
        // decoded language key and the predicate reaches the target through a correlated EXISTS.
        assertThat(remoteJoinPathLength(filters, "Inventory")).isEqualTo(2);
    }

    @Test
    void argumentCoordinate_takesTheSameRoutes() {
        var schema = TestSchemaHelper.buildSchema(LANGUAGE_FILM_INVENTORY + """
            type Query {
                stock(
                    languageId: ID @nodeId(typeName: "Language")
                        @referenceFor(type: "Film", path: [{key: "film_language_id_fkey"}])
                        @referenceFor(type: "Inventory", path: [{key: "inventory_film_id_fkey"}, {key: "film_language_id_fkey"}])
                ): [Stock!]!
            }
            """);

        var filters = participantFilters(schema, "stock");
        assertThat(localPredicateColumn(filters, "Film")).isEqualTo("language_id");
        assertThat(remoteJoinPathLength(filters, "Inventory")).isEqualTo(2);
    }

    @Test
    void oneStatedRouteAndOneAutoDiscovered_mergeRatherThanReplace() {
        // Target film instead of language: film_actor has a unique single-hop FK to it and needs no
        // route, while rental reaches it only through inventory. A participant nobody named keeps
        // automatic discovery, which is the override-merge the output coordinate already has.
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node {
                id: ID! @nodeId
                title: String
            }
            type FilmActor @table(name: "film_actor") { actorId: ID! @field(name: "actor_id") }
            type Rental @table(name: "rental") { rentalId: ID! @field(name: "rental_id") }
            union FilmUse = FilmActor | Rental
            input FilmUseFilter {
                filmId: ID @nodeId(typeName: "Film")
                    @referenceFor(type: "Rental", path: [{key: "rental_inventory_id_fkey"}, {key: "inventory_film_id_fkey"}])
            }
            type Query { filmUses(filter: FilmUseFilter): [FilmUse!]! }
            """);

        var filters = participantFilters(schema, "filmUses");
        assertThat(localPredicateColumn(filters, "FilmActor")).isEqualTo("film_id");
        assertThat(remoteJoinPathLength(filters, "Rental")).isEqualTo(2);
    }

    @Test
    void anApplicationNamingNoParticipantOfThisConsumerIsInertThere() {
        // The same input type consumed by a second query whose participant set has no Inventory. The
        // application is keyed by the definition coordinate while the participant set is each
        // consumer's fact, so at this consumer the unmatched application simply does not apply.
        var schema = TestSchemaHelper.buildSchema(LANGUAGE_FILM_INVENTORY + """
            type FilmTranslation @table(name: "film_translation") { filmId: ID! @field(name: "film_id") }
            union DubbedStock = Film | FilmTranslation
            input StockFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @referenceFor(type: "Film", path: [{key: "film_language_id_fkey"}])
                    @referenceFor(type: "Inventory", path: [{key: "inventory_film_id_fkey"}, {key: "film_language_id_fkey"}])
                    @referenceFor(type: "FilmTranslation", path: [{key: "film_translation_film_id_fkey"}, {key: "film_language_id_fkey"}])
            }
            type Query {
                stock(filter: StockFilter): [Stock!]!
                dubbedStock(filter: StockFilter): [DubbedStock!]!
            }
            """);

        assertThat(schema.field("Query", "dubbedStock")).isInstanceOf(QueryField.QueryUnionField.class);
        var filters = participantFilters(schema, "dubbedStock");
        assertThat(localPredicateColumn(filters, "Film")).isEqualTo("language_id");
        assertThat(remoteJoinPathLength(filters, "FilmTranslation")).isEqualTo(2);
    }

    // ===== One-leaf rejections =====

    @Test
    void duplicateParticipantOnOneLeafIsRejected() {
        assertThat(causeFor("""
            input StockFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @referenceFor(type: "Film", path: [{key: "film_language_id_fkey"}])
                    @referenceFor(type: "Film", path: [{key: "film_original_language_id_fkey"}])
                    @referenceFor(type: "Inventory", path: [{key: "inventory_film_id_fkey"}, {key: "film_language_id_fkey"}])
            }
            type Query { stock(filter: StockFilter): [Stock!]! }
            """)).contains("names participant 'Film' more than once");
    }

    @Test
    void referenceAlongsideReferenceForIsRejectedAsAmbiguous() {
        assertThat(causeFor("""
            input StockFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @reference(path: [{key: "film_language_id_fkey"}])
                    @referenceFor(type: "Inventory", path: [{key: "inventory_film_id_fkey"}, {key: "film_language_id_fkey"}])
            }
            type Query { stock(filter: StockFilter): [Stock!]! }
            """)).contains("carries both @reference and @referenceFor");
    }

    @Test
    void referenceForOnANonNodeIdInputFieldIsRejectedOnTheAxis() {
        String message = causeFor("""
            input StockFilter {
                title: String @referenceFor(type: "Film", path: [{key: "film_language_id_fkey"}])
            }
            type Query { stock(filter: StockFilter): [Stock!]! }
            """);
        // Worded on the axis, not the directive: the plain-@reference input rail has the same gap and
        // is the natural place for the next arm, so this must not read as "only @nodeId may carry it".
        assertThat(message).contains("the only per-participant path an input field resolves today");
        assertThat(message).doesNotContain("only @nodeId");
    }

    // ===== The override escape and its uniformity invariant =====

    @Test
    void everyParticipantUnresolvedUnderOverride_handsThePredicateToTheMethod() {
        // No route is stated and neither participant can discover one, so nothing is left for the
        // generator to bind; the authored method takes the whole WHERE contribution.
        var schema = TestSchemaHelper.buildSchema(LANGUAGE_FILM_INVENTORY + """
            input StockFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "languageIdDecodedKeyCondition"}, override: true)
            }
            type Query { stock(filter: StockFilter): [Stock!]! }
            """);

        assertThat(schema.field("Query", "stock")).isInstanceOf(QueryField.QueryUnionField.class);
        var filters = participantFilters(schema, "stock");
        assertThat(filters).hasSize(2);
        for (var pf : filters) {
            assertThat(pf.filters())
                .as("participant '" + pf.participant().typeName() + "' fires the authored method")
                .anySatisfy(f -> assertThat(f).isInstanceOf(
                    no.sikt.graphitron.rewrite.model.ConditionFilter.class));
            assertThat(pf.filters())
                .as("and emits no implicit column predicate of its own")
                .noneSatisfy(f -> assertThat(f).isInstanceOf(GeneratedConditionFilter.class));
        }
    }

    @Test
    void argumentCoordinate_everyParticipantUnresolvedUnderOverride_handsThePredicateToTheMethod() {
        // The same escape one coordinate over. The lift is minted by a different arm against a
        // different carrier (ArgumentRef.ScalarArg.ConditionOwnedArg rather than
        // InputField.ConditionOwnedField), and the argument's @condition is reflected on a path of
        // its own, so the input-field case above does not stand in for this one.
        var schema = TestSchemaHelper.buildSchema(LANGUAGE_FILM_INVENTORY + """
            type Query {
                stock(
                    languageId: ID @nodeId(typeName: "Language")
                        @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "languageIdDecodedKeyCondition"}, override: true)
                ): [Stock!]!
            }
            """);

        assertThat(schema.field("Query", "stock")).isInstanceOf(QueryField.QueryUnionField.class);
        var filters = participantFilters(schema, "stock");
        assertThat(filters).hasSize(2);
        for (var pf : filters) {
            assertThat(pf.filters())
                .as("participant '" + pf.participant().typeName() + "' fires the authored method")
                .anySatisfy(f -> assertThat(f).isInstanceOf(
                    no.sikt.graphitron.rewrite.model.ConditionFilter.class));
            assertThat(pf.filters())
                .as("and emits no implicit column predicate of its own")
                .noneSatisfy(f -> assertThat(f).isInstanceOf(GeneratedConditionFilter.class));
        }
    }

    @Test
    void everyParticipantUnresolvedWithoutOverrideStillFails() {
        // The boundary pair: the same leaf without override: true is the shape that fails today, and
        // still does. override: false means the implicit column predicate has to compose, and there
        // is no route for it to compose over.
        assertThat(rejectionFor("""
            input StockFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argConditionTypeUnique"}, override: false)
            }
            type Query { stock(filter: StockFilter): [Stock!]! }
            """)).isNotBlank();
    }

    @Test
    void aSplitBetweenTheTwoContractsIsRejectedNamingTheParticipants() {
        // Film's route resolves, so its method would receive the language table; Inventory's does
        // not, so its method would receive inventory. One method cannot mean both, and nothing
        // downstream notices: the parameter is Table<?>-shaped either way.
        // The split is a cross-participant fact, so unlike the leaf-local causes above it is the
        // consuming field's own rejection: no one participant is wrong.
        String message = rejectionFor("""
            input StockFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @referenceFor(type: "Film", path: [{key: "film_language_id_fkey"}])
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argConditionTypeUnique"}, override: true)
            }
            type Query { stock(filter: StockFilter): [Stock!]! }
            """);
        assertThat(message).contains("the participants split");
        assertThat(message).contains("Film").contains("Inventory");
        assertThat(message).contains("@referenceFor");
    }

    @Test
    void aStatedRouteThatDoesNotResolveStillFailsUnderOverride() {
        // Only an *undiscovered* route escapes into the method's hands. An author who names a
        // foreign key has asked the build to check it, so a route that does not resolve is a
        // rejection whatever the override says; the shape fails today and keeps failing.
        assertThat(causeFor("""
            input StockFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @referenceFor(type: "Film", path: [{key: "no_such_fkey"}])
                    @referenceFor(type: "Inventory", path: [{key: "inventory_film_id_fkey"}, {key: "film_language_id_fkey"}])
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argConditionTypeUnique"}, override: true)
            }
            type Query { stock(filter: StockFilter): [Stock!]! }
            """)).contains("no_such_fkey");
    }

    // ===== The decoded handoff =====

    /**
     * The override escape decodes in the glue. Each branch's conditions class hosts the throw-mode
     * decode helper for the leaf's node type, which is the structural trace of the decode the author
     * used to be told to perform: on the shipped behaviour the arm emitted no decode at all, so the
     * helper's presence is falsifiable rather than incidental.
     *
     * <p>That the authored call then receives the decoded local is a body fact, and it is proven
     * where body facts are: the compile tier, where the sakila fixture declares an {@code Integer}
     * parameter that only compiles if the glue passes one, and the execution tier, where a real
     * encoded id round-trips and a plain key is refused.
     */
    @Test
    void theOverrideEscapeHostsTheDecodeHelperOnEachBranch() {
        var schema = TestSchemaHelper.buildSchema(LANGUAGE_FILM_INVENTORY + """
            input StockFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "languageIdDecodedKeyCondition"}, override: true)
            }
            type Query { stock(filter: StockFilter): [Stock!]! }
            """);

        var helperNames = ConditionRenderTestSupport
            .renderCommittedConditions(schema, "no.example.generated").stream()
            .flatMap(spec -> spec.methodSpecs().stream())
            .map(MethodSpec::name)
            .filter(name -> name.startsWith("decodeLanguage"))
            .toList();
        assertThat(helperNames).containsOnly("decodeLanguageKeyOrThrow");
    }

    /**
     * The refusal that keeps the contract enforceable from the SDL. Declaring the parameter as the
     * wire string used to be the only thing an author could write; it now names the coordinate and
     * the type the decoded key has, rather than surfacing as a javac error inside emitted glue with
     * no line back to the schema.
     */
    @Test
    void aWireStringParameterOnADecodedSlotIsRejectedNamingTheRequiredType() {
        String message = causeFor("""
            input StockFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "languageIdWireString"}, override: true)
            }
            type Query { stock(filter: StockFilter): [Stock!]! }
            """);
        assertThat(message).contains("languageIdWireString");
        assertThat(message).contains("java.lang.Integer");
        assertThat(message).contains("java.lang.String");
    }

    /**
     * An authored parameter rejects where a routed leaf would dispatch. A bare {@code @nodeId}
     * argument on a multitable root infers its node type from each participant's own table, so the
     * branches decode different types; with no {@code @condition} that is the supported per-branch
     * dispatch, and with one it cannot be, because the decoded keys are two Java types and a
     * condition declaration set may differ in its table parameter only.
     */
    @Test
    void aDivergentArgumentBoundByAConditionIsRejectedRatherThanDispatched() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmNode implements Node @table(name: "film") @node { id: ID! @nodeId }
            type InventoryNode implements Node @table(name: "inventory") @node { id: ID! @nodeId }
            union Stock = FilmNode | InventoryNode
            type Query {
                stock(
                    id: ID @nodeId
                        @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argConditionTypeUnique"}, override: true)
                ): [Stock!]!
            }
            """);

        var field = schema.field("Query", "stock");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        String message = ((GraphitronField.UnclassifiedField) field).reason();
        assertThat(message).contains("FilmNode").contains("InventoryNode");
        assertThat(message).contains("@nodeId(typeName:");
    }

    // ===== The third directive site =====

    /**
     * A field-level {@code @condition} binds its field's own arguments, so a parameter bound to a
     * {@code @nodeId} argument is at the argument coordinate however the directive was written, and
     * takes the decode there. The case runs without {@code override}, which is the shape that makes
     * the rule load-bearing rather than tidy: the implicit column predicate stands beside the
     * authored call in one emitted glue method, and leaving the authored parameter on the
     * declared-type rule would read one wire value two contradictory ways in that one method.
     */
    @Test
    void aFieldLevelConditionBindingANodeIdArgumentTakesTheDecode() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language implements Node @table(name: "language") @node {
                id: ID! @nodeId
                name: String
            }
            type Query {
                languages(languageId: ID @nodeId(typeName: "Language")): [Language!]!
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "languageIdDecodedKeyCondition"})
            }
            """);

        var field = schema.field("Query", "languages");
        assertThat(field)
            .as("the field was expected to classify; it rejected as %s", field)
            .isInstanceOf(QueryField.QueryTableField.class);
        var filters = ((QueryField.QueryTableField) field).filters();

        var authored = filters.stream()
            .filter(f -> f instanceof no.sikt.graphitron.rewrite.model.ConditionFilter)
            .map(f -> (no.sikt.graphitron.rewrite.model.ConditionFilter) f)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no authored @condition among " + filters));
        var bound = authored.params().stream()
            .filter(p -> p.name().equals("languageId"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no parameter bound to the slot in "
                + authored.params()));
        assertThat(((no.sikt.graphitron.rewrite.model.ParamSource.Arg) bound.source()).extraction())
            .as("the authored parameter receives the decoded key, not the wire string")
            .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.class);
        assertThat(filters)
            .as("and the implicit predicate the decode has to agree with is still there")
            .anySatisfy(f -> assertThat(f).isInstanceOf(GeneratedConditionFilter.class));
    }

    /**
     * The declared-type refusal reached through the field-level directive. Same ground as the
     * argument-level twin above: the contract's only other enforcer is the consumer's javac inside
     * emitted glue, and the directive's placement does not change which coordinate the slot is at.
     */
    @Test
    void aFieldLevelConditionDeclaringTheWireStringIsRejectedNamingTheRequiredType() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language implements Node @table(name: "language") @node {
                id: ID! @nodeId
                name: String
            }
            type Query {
                languages(languageId: ID @nodeId(typeName: "Language")): [Language!]!
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "languageIdWireString"})
            }
            """);

        var field = schema.field("Query", "languages");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        String message = ((GraphitronField.UnclassifiedField) field).reason();
        assertThat(message).contains("Query.languages");
        assertThat(message).contains("languageIdWireString");
        assertThat(message).contains("java.lang.Integer");
        assertThat(message).contains("java.lang.String");
    }

    /**
     * The divergence refusal beside that install, which the field-level site needs for itself: a
     * bare {@code @nodeId} argument on a multitable root decodes a different node type per branch,
     * so the one declared parameter type cannot serve every branch. Reached from the enclosing
     * field's directive rather than the argument's, and caught once the field's method is reflected,
     * because whether a field-level condition binds a given argument is not known before that.
     */
    @Test
    void aFieldLevelConditionBindingADivergentArgumentIsRejectedRatherThanDispatched() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmNode implements Node @table(name: "film") @node { id: ID! @nodeId }
            type InventoryNode implements Node @table(name: "inventory") @node { id: ID! @nodeId }
            union Stock = FilmNode | InventoryNode
            type Query {
                stock(id: ID @nodeId): [Stock!]!
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argConditionTypeUnique"}, override: true)
            }
            """);

        var field = schema.field("Query", "stock");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        // The refusal is minted at the coordinate it is about and the field keeps the consequence,
        // so the reason it gives reads off the diagnostics, as it does for the leaf-local causes.
        String message = schema.diagnostics().stream().map(ValidationError::message)
            .collect(java.util.stream.Collectors.joining(" | "));
        assertThat(message).contains("Query.stock");
        assertThat(message).contains("argument 'id'");
        assertThat(message).contains("different");
        assertThat(message).contains("@nodeId(typeName:");
    }

    // ===== Aggregation =====

    @Test
    void everyFailingParticipantSurfacesRatherThanTheFirst() {
        // Three participants, none of which can reach language, and no override. Each cause is minted
        // at its own participant coordinate and the field keeps one consequence rejection, rather
        // than the author learning about one participant per build.
        var schema = TestSchemaHelper.buildSchema("""
            type Language implements Node @table(name: "language") @node { id: ID! @nodeId }
            type Inventory @table(name: "inventory") { inventoryId: ID! @field(name: "inventory_id") }
            type Rental @table(name: "rental") { rentalId: ID! @field(name: "rental_id") }
            type Payment @table(name: "payment") { paymentId: ID! @field(name: "payment_id") }
            union Unreachable = Inventory | Rental | Payment
            input UnreachableFilter {
                languageId: ID @nodeId(typeName: "Language")
            }
            type Query { unreachable(filter: UnreachableFilter): [Unreachable!]! }
            """);

        var field = schema.field("Query", "unreachable");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(((GraphitronField.UnclassifiedField) field).reason())
            .contains("3 participants could not be lowered")
            .contains("Inventory").contains("Rental").contains("Payment");
    }

    // ===== Helpers =====

    private static List<ParticipantFilters> participantFilters(GraphitronSchema schema, String fieldName) {
        var field = schema.field("Query", fieldName);
        if (field instanceof QueryField.QueryUnionField union) return union.participantFilters();
        if (field instanceof QueryField.QueryInterfaceField iface) return iface.participantFilters();
        throw new AssertionError("field 'Query." + fieldName + "' did not classify as a multitable "
            + "polymorphic field: " + field);
    }

    /** The SQL name of the participant's one local column predicate. */
    private static String localPredicateColumn(List<ParticipantFilters> filters, String participant) {
        return bodyParams(filters, participant).stream()
            .filter(bp -> bp instanceof BodyParam.ColumnPredicate)
            .map(bp -> switch ((BodyParam.ColumnPredicate) bp) {
                case BodyParam.Eq eq -> eq.column().sqlName();
                case BodyParam.In in -> in.column().sqlName();
                case BodyParam.RowEq rowEq -> rowEq.columns().getFirst().sqlName();
                case BodyParam.RowIn rowIn -> rowIn.columns().getFirst().sqlName();
            })
            .findFirst()
            .orElseThrow(() -> new AssertionError("participant '" + participant
                + "' carries no local column predicate: " + bodyParams(filters, participant)));
    }

    /** The hop count of the participant's one correlated-EXISTS predicate. */
    private static int remoteJoinPathLength(List<ParticipantFilters> filters, String participant) {
        return bodyParams(filters, participant).stream()
            .filter(bp -> bp instanceof BodyParam.RemoteColumnPredicate)
            .map(bp -> ((BodyParam.RemoteColumnPredicate) bp).joinPath().size())
            .findFirst()
            .orElseThrow(() -> new AssertionError("participant '" + participant
                + "' carries no remote column predicate: " + bodyParams(filters, participant)));
    }

    private static List<BodyParam> bodyParams(List<ParticipantFilters> filters, String participant) {
        var pf = filters.stream()
            .filter(f -> f.participant().typeName().equals(participant))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no participant '" + participant + "' among "
                + filters.stream().map(f -> f.participant().typeName()).toList()));
        return pf.filters().stream()
            .filter(f -> f instanceof GeneratedConditionFilter)
            .flatMap(f -> ((GeneratedConditionFilter) f).bodyParams().stream())
            .toList();
    }

    /** The consuming field's rejection message; fails the test when the field classified. */
    private static String rejectionFor(String schemaTail) {
        return rejectedSchema(schemaTail).getValue();
    }

    /**
     * The joined causes minted at the failing coordinates, which is where a leaf-local or
     * participant-local cause lands. The consuming field keeps only the consequence, so a test about
     * <em>why</em> a leaf refused reads the diagnostics rather than the field's own message.
     */
    private static String causeFor(String schemaTail) {
        var rejected = rejectedSchema(schemaTail);
        return rejected.getKey().diagnostics().stream()
            .map(ValidationError::message)
            .collect(java.util.stream.Collectors.joining(" | "));
    }

    /** The built schema and the consuming field's rejection; fails the test when the field classified. */
    private static java.util.Map.Entry<GraphitronSchema, String> rejectedSchema(String schemaTail) {
        var schema = TestSchemaHelper.buildSchema(LANGUAGE_FILM_INVENTORY + schemaTail);
        GraphitronField field = schema.field("Query", "stock");
        assertThat(field)
            .as("the leaf was expected to reject; it classified as %s", field.getClass().getSimpleName())
            .isInstanceOf(GraphitronField.UnclassifiedField.class);
        return java.util.Map.entry(schema, ((GraphitronField.UnclassifiedField) field).reason());
    }
}
