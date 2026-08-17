package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.KeyLift;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified schema pipeline tests for the discriminated interface child's delivery split:
 * a child returning a {@code @table} + {@code @discriminate} interface batches through a
 * DataLoader at list cardinality ({@link ChildField.BatchedTableInterfaceField}) and keeps the
 * per-parent fetch at single cardinality ({@link ChildField.TableInterfaceField}).
 *
 * <p>The fork itself is the multi-table polymorphic child's rule, but the batch <em>key</em> is
 * the plain table child's: the single FK hop's source side rather than the parent's primary key,
 * because one FK correlates the whole participant set here instead of each participant holding
 * its own FK back to the parent. That divergence is what these cases pin; the verdict tuple
 * itself rides the {@code table-interface} corpus example.
 *
 * <p>Asserted on the classified field record, not on generated method bodies (per the development
 * principles).
 */
@PipelineTier
class BatchedDiscriminatedInterfaceChildPipelineTest {

    /**
     * The discriminated base is {@code content} and the parent is {@code film}; the child holds
     * the FK ({@code content.film_id → film.film_id}), so the same {@code @reference} serves both
     * cardinalities and only the wrapper differs between the two coordinates.
     */
    private static final String SDL = """
        interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
          title: String @field(name: "TITLE")
        }
        type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
          title: String @field(name: "TITLE")
          length: Int @field(name: "LENGTH")
        }
        type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
          title: String @field(name: "TITLE")
        }
        type Film @table(name: "film") {
          title: String
          contents: [Content!]! @reference(path: [{key: "content_film_id_fkey"}])
          content: Content @reference(path: [{key: "content_film_id_fkey"}])
        }
        type Query { films: [Film!]! }
        """;

    @Test
    void listCardinality_mintsTheBatchedLeafKeyedOnTheFkSourceSide() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        var field = (ChildField.BatchedTableInterfaceField) schema.field("Film", "contents");

        // The discriminated payload is the unbatched twin's, carried unchanged.
        assertThat(field.discriminatorColumn()).isEqualToIgnoringCase("CONTENT_TYPE");
        assertThat(field.knownDiscriminatorValues()).containsExactlyInAnyOrder("FILM", "SHORT");
        assertThat(field.participants()).hasSize(2);
        assertThat(field.returnType().table().tableName()).isEqualToIgnoringCase("content");

        // The batch key is the FK hop's source side (film.film_id), the columns the unbatched
        // twin reports as its parent-row demand — not the parent's primary key by a separate
        // derivation, and not the child-side column.
        assertThat(field.sourceKey().columns())
            .extracting(c -> c.sqlName().toLowerCase())
            .containsExactly("film_id");
        assertThat(field.sourceKey().wrap()).isInstanceOf(SourceKey.Wrap.Row.class);
        assertThat(field.lift()).isInstanceOf(KeyLift.FkColumns.class);
        assertThat(field.parentCorrelation()).isInstanceOf(ParentCorrelation.OnFkSlots.class);

        // The @splitQuery loader contract: one key per parent row, positional batch.
        assertThat(field.loaderRegistration().container())
            .isEqualTo(LoaderRegistration.Container.POSITIONAL_LIST);
        assertThat(field.loaderRegistration().dispatch())
            .isEqualTo(LoaderRegistration.Dispatch.LOAD_ONE);
    }

    /**
     * The regression half: the fork is a fork, not a wholesale move. Single cardinality keeps the
     * unbatched leaf, whose per-parent fetch reads its correlation off the parent row.
     */
    @Test
    void singleCardinality_keepsTheUnbatchedPerParentLeaf() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        var field = (ChildField.TableInterfaceField) schema.field("Film", "content");

        assertThat(field).isNotInstanceOf(ChildField.BatchedTableInterfaceField.class);
        assertThat(field).isNotInstanceOf(no.sikt.graphitron.rewrite.model.BatchKeyField.class);
        // The demand the parent SELECT must carry is the same column list the batched sibling
        // keys on, which is why the batched leaf declines the demand capability rather than
        // declaring a second answer to one question.
        assertThat(field.parentRowColumns())
            .extracting(c -> c.sqlName().toLowerCase())
            .containsExactly("film_id");
    }

    /**
     * The two coordinates agree on their delivery across both computation sites. The leaf
     * encoding is the crosswalk; the materialized relation is the production. {@code
     * DeliveryFactPinTest} pins the agreement over the corpus and its marker fixture; this pins
     * the verdicts themselves, so a regression that flipped both sites in step would still fail.
     */
    @Test
    void deliveryVerdictsFollowCardinalityAtBothSites() {
        var schema = TestSchemaHelper.buildSchema(SDL);

        assertThat(schema.deliveryOf(
                graphql.schema.FieldCoordinates.coordinates("Film", "contents")))
            .isEqualTo(new no.sikt.graphitron.rewrite.model.DeliveryFact.Batched(
                no.sikt.graphitron.rewrite.model.DeliveryFact.Trigger.PolymorphicFanIn.INSTANCE));
        assertThat(schema.deliveryOf(
                graphql.schema.FieldCoordinates.coordinates("Film", "content")))
            .isEqualTo(no.sikt.graphitron.rewrite.model.DeliveryFact.Inline.INSTANCE);
    }
}
