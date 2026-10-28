package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.CarrierDsl;
import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.NullCheck;
import no.sikt.graphitron.command.PresenceProbe;
import no.sikt.graphitron.command.ResultShape;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.Presence;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@link NullCheck} a root table read's single shape carries, over each source of a
 * {@code QueryTableField}: a nullable single read of a type that can hold nothing collapses
 * under the type's presence leaves, everything else returns what it fetches. Asserts on the
 * launcher commands, never on generated method bodies.
 */
@PipelineTier
class RootNullContractPipelineTest {

    private static final String READS = """
        type Language @table(name: "language") { name: String }
        type Film @table(name: "film") {
            title: String
            language: Language @reference(path: [{key: "film_language_id_fkey"}])
            languageSplit: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
        }
        type FilmActor implements Node @table(name: "film_actor") @node(keyColumns: ["actor_id", "film_id"]) {
            id: ID! @nodeId
        }
        type Query {
            language(name: String @field(name: "name")): Language
            languageRequired(name: String @field(name: "name")): Language!
            languages: [Language!]!
            languagesPage: [Language!]! @asConnection
            languageByKey(language_id: Int! @lookupKey @field(name: "language_id")): Language
            film(title: String @field(name: "title")): Film
            filmByKey(film_id: Int! @lookupKey @field(name: "film_id")): Film
            recentOne(actorId: Int!, minLength: Int!): Film
                @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
                @reference(path: [{table: "film"}])
            filmActor(film_id: Int @field(name: "film_id")): FilmActor
        }
        """;

    /** Every arm that mints a single record outside the root table read. */
    private static final String EXCLUDED = """
        interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
          contentId: Int! @field(name: "CONTENT_ID")
        }
        type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
          contentId: Int! @field(name: "CONTENT_ID")
        }
        type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
          contentId: Int! @field(name: "CONTENT_ID")
        }
        input ContentInput {
          title: String! @field(name: "TITLE")
          contentType: String! @field(name: "CONTENT_TYPE")
        }
        enum Sprak { nn @field(name: "nno") nb @field(name: "nob") }
        type TranslatedTexts { nn: String nb: String }
        type Language @table(name: "language") { name: String }
        type Film @table(name: "film") {
          title: String
          titleTextsSplit: TranslatedTexts @splitQuery @reference(path: [{table: "film_translation"}])
                                @pivot(on: "lang_code", value: "title_txt", vocabulary: "Sprak")
          languageSplit: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
        }
        input FilmInput { title: String }
        type Query {
          films: [Film!]!
          content: Content
        }
        type Mutation {
          createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
          createContent(in: ContentInput!): Content @mutation(typeName: INSERT, table: "content")
        }
        """;

    private static LauncherRelation produce(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        return LauncherCommands.produce(schema, ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE),
            DEFAULT_OUTPUT_PACKAGE);
    }

    private static NullCheck checkOf(LauncherRelation relation, String type, String field) {
        var row = relation.rowFor(type, field).orElseThrow();
        assertThat(row.result()).as(type + "." + field).isInstanceOf(ResultShape.SingleRecord.class);
        return ((ResultShape.SingleRecord) row.result()).check();
    }

    private static List<List<String>> leafNames(NullCheck check) {
        return ((NullCheck.Collapses) check).leaves().stream()
            .map(leaf -> leaf.stream().map(c -> c.sqlName()).toList())
            .toList();
    }

    @Test
    void anchoredSingle_columnsOnly_collapsesOverTheTypesLeavesWithNoProbe() {
        var check = checkOf(produce(READS), "Query", "language");
        assertThat(leafNames(check)).containsExactly(List.of("name"));
        assertThat(((NullCheck.Collapses) check).probe()).isEmpty();
    }

    @Test
    void anchoredSingle_typeHoldingAReference_carriesAProbeCorrelatedOnItsReach() {
        var check = (NullCheck.Collapses) checkOf(produce(READS), "Query", "film");
        assertThat(leafNames(check)).containsExactly(List.of("title"));
        assertThat(check.probe()).hasValueSatisfying(probe -> {
            assertThat(probe.alias()).isEqualTo("__p_Query$film");
            assertThat(probe.columnLeaves()).hasSize(1);
            // One term per reference the type holds; the inline and the @splitQuery one both probe.
            assertThat(probe.terms()).hasSize(2).allSatisfy(t -> {
                var term = (no.sikt.graphitron.command.PresenceProbe.ProbeTerm.Exists) t;
                assertThat(term.reach().size()).isEqualTo(1);
                assertThat(term.targetColumns()).extracting(c -> c.sqlName()).containsExactly("name");
            });
        });
    }

    @Test
    void anchoredSingle_typeHoldingAComputedField_carriesItsNullTermInTheProbe() {
        var relation = produce("""
            type RatedFilm @table(name: "film") {
                title: String
                rating: String @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "rating"})
            }
            type Query { ratedFilm(title: String @field(name: "title")): RatedFilm }
            """);
        var check = (NullCheck.Collapses) checkOf(relation, "Query", "ratedFilm");
        assertThat(leafNames(check)).containsExactly(List.of("title"));
        assertThat(check.probe()).hasValueSatisfying(probe -> {
            assertThat(probe.alias()).isEqualTo("__p_Query$ratedFilm");
            assertThat(probe.terms()).singleElement().isInstanceOfSatisfying(PresenceProbe.ProbeTerm.NotNull.class,
                t -> assertThat(t.method().methodName()).isEqualTo("rating"));
        });
    }

    @Test
    void lookupKeyAndRoutineChain_carryTheAnchoredReadsCheck() {
        var relation = produce(READS);
        var anchored = (NullCheck.Collapses) checkOf(relation, "Query", "film");
        for (var field : List.of("filmByKey", "recentOne")) {
            var check = (NullCheck.Collapses) checkOf(relation, "Query", field);
            assertThat(check.leaves()).as(field).isEqualTo(anchored.leaves());
            assertThat(check.probe()).as(field).hasValueSatisfying(probe -> {
                assertThat(probe.alias()).isEqualTo("__p_Query$" + field);
                assertThat(probe.terms()).isEqualTo(anchored.probe().orElseThrow().terms());
            });
        }
        assertThat(relation.rowFor("Query", "filmByKey").orElseThrow().source())
            .isInstanceOf(LaunchSource.KeyedLookup.class);
        assertThat(relation.rowFor("Query", "recentOne").orElseThrow().source())
            .isInstanceOf(LaunchSource.RoutineChain.class);
        assertThat(leafNames(checkOf(relation, "Query", "languageByKey"))).containsExactly(List.of("name"));
    }

    @Test
    void compositeNodeId_isOneLeafOfItsKeyColumns() {
        assertThat(leafNames(checkOf(produce(READS), "Query", "filmActor")))
            .singleElement().satisfies(leaf -> assertThat(leaf).containsExactlyInAnyOrder("actor_id", "film_id"));
    }

    @Test
    void nonNullSingle_returnsWhatItFetches_andListShapesHaveNoSingleRecord() {
        var relation = produce(READS);
        assertThat(checkOf(relation, "Query", "languageRequired")).isEqualTo(new NullCheck.None());
        assertThat(relation.rowFor("Query", "languages").orElseThrow().result())
            .isInstanceOf(ResultShape.RecordList.class);
        assertThat(relation.rowFor("Query", "languagesPage").orElseThrow().result())
            .isInstanceOf(ResultShape.Connection.class);
    }

    @Test
    void singleRecordsOutsideTheRootTableRead_returnWhatTheyFetch() {
        var relation = produce(EXCLUDED);
        for (var coordinate : List.of(List.of("Query", "content"), List.of("Mutation", "createFilm"),
                List.of("Mutation", "createContent"), List.of("Film", "titleTextsSplit"),
                List.of("Film", "languageSplit"))) {
            assertThat(checkOf(relation, coordinate.get(0), coordinate.get(1)))
                .as(String.join(".", coordinate)).isEqualTo(new NullCheck.None());
        }
    }

    @Test
    void schemaFreeWalk_mintsTheSchemaWalksCheckForAHandedType_andNoneOtherwise() {
        var schema = TestSchemaHelper.buildSchema(READS);
        var schemaWalk = checkOf(produce(READS), "Query", "language");

        var handed = new java.util.ArrayList<no.sikt.graphitron.rewrite.model.GraphitronField>(schema.fieldsOf("Query"));
        handed.addAll(schema.fieldsOf("Language"));
        var withType = LauncherCommands.produceWithoutSchema(handed, DEFAULT_OUTPUT_PACKAGE, CarrierDsl.ENV_ACQUIRED);
        assertThat(checkOf(withType, "Query", "language")).isEqualTo(schemaWalk);

        var queryAlone = LauncherCommands.produceWithoutSchema(schema.fieldsOf("Query"),
            DEFAULT_OUTPUT_PACKAGE, CarrierDsl.ENV_ACQUIRED);
        assertThat(checkOf(queryAlone, "Query", "language")).isEqualTo(new NullCheck.None());
        assertThat(checkOf(queryAlone, "Query", "languageByKey")).isEqualTo(new NullCheck.None());
    }

    @Test
    void typeHoldingAnUnclassifiedField_returnsWhatItFetches() {
        // actor has no "name" column, so Actor.name does not classify; the presence fold reads
        // child fields only and would decide over a part of the type. The validator rejects the
        // schema; a run that produces before validating mints None rather than a leafless check.
        var relation = produce("""
            type Actor @table(name: "actor") { name: String }
            type Query { actor(actor_id: Int @field(name: "actor_id")): Actor }
            """);
        assertThat(checkOf(relation, "Query", "actor")).isEqualTo(new NullCheck.None());
    }

    @Test
    void lookupKey_listKeepsItsScatterAndItsAbsentOrdering() {
        var relation = produce("""
            type Language @table(name: "language") { name: String }
            type Query {
                languagesByKey(language_id: [Int!]! @lookupKey @field(name: "language_id")): [Language]!
            }
            """);
        var list = relation.rowFor("Query", "languagesByKey").orElseThrow();
        assertThat(list.result()).isInstanceOf(ResultShape.RecordList.class);
        assertThat(((ResultShape.RecordList) list.result()).ordering()).isNull();
    }

    @Test
    void backstop_admitsACollapsingCheckOnlyOnTheThreeRootSources() {
        var reads = produce(READS);
        var collapsing = new ResultShape.SingleRecord(checkOf(reads, "Query", "language"));
        for (var field : List.of("language", "languageByKey", "recentOne")) {
            var row = reads.rowFor("Query", field).orElseThrow();
            assertThat(withResult(row, collapsing).result()).as(field).isEqualTo(collapsing);
        }

        var excluded = produce(EXCLUDED);
        var refused = List.of(
            List.of("Film", "languageSplit", "CorrelatedChain"),
            List.of("Film", "titleTextsSplit", "PivotAggregate"),
            List.of("Mutation", "createFilm", "ProjectedReentry"),
            List.of("Mutation", "createContent", "DiscriminatedReentry"),
            List.of("Query", "content", "DiscriminatedTable"));
        for (var entry : refused) {
            var row = excluded.rowFor(entry.get(0), entry.get(1)).orElseThrow();
            assertThat(row.source().getClass().getSimpleName()).isEqualTo(entry.get(2));
            assertThatThrownBy(() -> withResult(row, collapsing))
                .as(entry.get(2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collapsing null check");
        }
    }

    @Test
    void collapses_refusesACheckWithNeitherLeavesNorProbe() {
        assertThatThrownBy(() -> new NullCheck.Collapses(List.of(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullChecks_throwOnALeaflessContract() {
        var schema = TestSchemaHelper.buildSchema(READS);
        var field = (QueryField.QueryTableField) schema.field("Query", "language");
        assertThatThrownBy(() -> NullChecks.of(field, new Presence.NullContract.CollapsesWhenAbsent(List.of()),
                probe -> null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Query.language");
    }

    private static LauncherCommand withResult(LauncherCommand row, ResultShape result) {
        return new LauncherCommand(row.unit(), row.coordinate(), row.source(), row.where(),
            row.invocation(), row.tenancy(), result, row.selectionOccurrences());
    }
}
