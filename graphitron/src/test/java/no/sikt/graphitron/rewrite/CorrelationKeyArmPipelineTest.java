package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → generated {@code TypeSpec} pipeline tests for the gated correlation-key arms: a child
 * whose fetcher reads parent-row columns off {@code env.getSource()} carries an ordinary
 * {@code $project} switch arm projecting exactly those columns, gated on the child's field like
 * every other contribution. The gate suffices because the fetcher only runs when its field is
 * selected, and the selected field's arm is what projects the columns; an unselected child
 * projects nothing, which is the end of over-projection (pinned as SQL at the execution tier by
 * the projection SQL baseline).
 *
 * <p>One test per parent-row-reading leaf family without a natural home elsewhere: the
 * {@code @service} shapes live in {@link ServiceProjectionPipelineTest}, the nested split shape
 * in {@link NestingFieldPipelineTest}. The projection membership census's unexercised list
 * points here for the polymorphic shapes.
 */
@PipelineTier
class CorrelationKeyArmPipelineTest {

    @Test
    void batchedTableField_armProjectsTheSourceKeyColumn() {
        var film = findType("Film", """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                title: String
                languages: [Language!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { films: [Film!]! }
            """);
        assertThat(TypeSpecAssertions.armProjectsColumn(film, "languages", "LANGUAGE_ID"))
            .as("the split child's arm projects the parent-side FK column its key extraction reads")
            .isTrue();
        assertThat(TypeSpecAssertions.armProjectsColumn(film, "title", "LANGUAGE_ID"))
            .as("and no other arm carries the key on its behalf")
            .isFalse();
    }

    @Test
    void batchedLookup_armProjectsTheSourceKeyColumn() {
        var film = findType("Film", """
            type Actor @table(name: "actor") { actorId: Int @field(name: "actor_id") }
            type Film @table(name: "film") {
                title: String
                actors(actor_id: [Int!] @lookupKey): [Actor!]! @splitQuery @reference(path: [
                    {key: "film_actor_film_id_fkey"},
                    {key: "film_actor_actor_id_fkey"}
                ])
            }
            type Query { films: [Film!]! }
            """);
        assertThat(TypeSpecAssertions.armProjectsColumn(film, "actors", "FILM_ID"))
            .as("the split lookup child's arm projects the parent-side key column")
            .isTrue();
    }

    @Test
    void tableInterfaceField_armProjectsTheHopSourceSideColumn() {
        var inventory = findType("Inventory", """
            interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Inventory @table(name: "inventory") { media: MediaItem }
            type Query { inventory: Inventory }
            """);
        assertThat(TypeSpecAssertions.armProjectsColumn(inventory, "media", "FILM_ID"))
            .as("the single-table interface child's arm projects the FK hop's source-side column "
                + "its fetcher reads off parentRecord by base name")
            .isTrue();
    }

    @Test
    void batchedTableInterfaceField_armProjectsTheHopSourceSideColumn() {
        var language = findType("Language", """
            interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Language @table(name: "language") {
                name: String
                mediaList: [MediaItem!]! @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { language: Language }
            """);
        assertThat(TypeSpecAssertions.armProjectsColumn(language, "mediaList", "LANGUAGE_ID"))
            .as("the batched discriminated child's arm projects the FK hop's source-side column, "
                + "reached through its batch key rather than its twin's parent-row demand — the "
                + "same columns, one accessor")
            .isTrue();
        assertThat(TypeSpecAssertions.armProjectsColumn(language, "name", "LANGUAGE_ID"))
            .as("and no other arm carries the key on its behalf")
            .isFalse();
    }

    @Test
    void interfaceField_armProjectsEveryBranchCorrelationColumn() {
        var customer = findType("Customer", """
            interface CustRef { rowId: Int }
            type AddressP implements CustRef @table(name: "address") { rowId: Int @field(name: "address_id") }
            type StoreP implements CustRef @table(name: "store") { rowId: Int @field(name: "store_id") }
            type Customer @table(name: "customer") { ref: CustRef }
            type Query { customer: Customer }
            """);
        assertThat(TypeSpecAssertions.armProjectsColumn(customer, "ref", "ADDRESS_ID"))
            .as("the multi-table interface child's arm projects each branch's parent-side column")
            .isTrue();
        assertThat(TypeSpecAssertions.armProjectsColumn(customer, "ref", "STORE_ID"))
            .as("the multi-table interface child's arm projects each branch's parent-side column")
            .isTrue();
    }

    @Test
    void unionField_armProjectsEveryBranchCorrelationColumn() {
        var filmActor = findType("FilmActor", """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String @field(name: "FIRST_NAME") }
            union FilmOrActor = Film | Actor
            type FilmActor @table(name: "film_actor") { related: FilmOrActor }
            type Query { filmActor: FilmActor }
            """);
        assertThat(TypeSpecAssertions.armProjectsColumn(filmActor, "related", "FILM_ID"))
            .as("the union child's arm projects each branch's parent-side column")
            .isTrue();
        assertThat(TypeSpecAssertions.armProjectsColumn(filmActor, "related", "ACTOR_ID"))
            .as("the union child's arm projects each branch's parent-side column")
            .isTrue();
    }

    @Test
    void batchedPivotField_armProjectsTheSourceKeyColumn() {
        var film = findType("Film", """
            type TranslatedTexts { nn: String nb: String }
            type Film @table(name: "film") {
                title: String
                titleTexts: TranslatedTexts @splitQuery @reference(path: [{table: "film_translation"}])
                                            @pivot(on: "lang_code", value: "title_txt")
            }
            type Query { films: [Film!]! }
            """);
        assertThat(TypeSpecAssertions.armProjectsColumn(film, "titleTexts", "FILM_ID"))
            .as("the batched pivot's arm projects the key column its DataLoader extraction reads; "
                + "the pivot projection itself lives on the coordinate's pivot unit")
            .isTrue();
    }

    private static TypeSpec findType(String className, String sdl) {
        return ProjectionRenderTestSupport.renderProjections(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Type class not found: " + className));
    }
}
