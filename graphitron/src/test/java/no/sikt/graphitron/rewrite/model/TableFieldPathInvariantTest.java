package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An inline child read is correlated to its parent row over its join path, so
 * {@link ChildField.TableField} refuses an empty path whatever correlation it carries, and a path
 * with no correlation, although the shared {@link ParentCorrelation} carrier invariant admits an
 * empty path for a carrier on the parent's own row.
 */
@UnitTier
class TableFieldPathInvariantTest {

    private static final ReturnTypeRef.TableBoundReturnType FILM =
        new ReturnTypeRef.TableBoundReturnType("Film", TestFixtures.filmTable(), new FieldWrapper.Single(true));

    /** The films in a language, read from the language's side. */
    private static final List<JoinStep> FK_PATH = List.of(TestFixtures.fkJoin(
        TestFixtures.foreignKeyRef("film_language_id_fkey"), TestFixtures.languageTable(),
        List.of(TestFixtures.languageIdCol()), TestFixtures.filmTable(),
        List.of(TestFixtures.languageIdCol()), null, ""));

    private static ChildField.TableField field(List<JoinStep> path, ParentCorrelation correlation) {
        return new ChildField.TableField("Language", "film", null, FILM, path, List.of(),
            new OrderBySpec.None(), null, LookupResolution.None.INSTANCE, correlation,
            AliasOwner.shared(), AliasScope.rowOwner());
    }

    @Test
    void emptyPathWithNoCorrelationIsRefused() {
        assertThatThrownBy(() -> field(List.of(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("TableField Language.film needs a join path to its parent");
    }

    @Test
    void emptyPathWithALiftedCorrelationIsRefused() {
        var lifted = new ParentCorrelation.OnLiftedSlots(TestFixtures.filmTable(),
            List.of(TestFixtures.filmIdCol()));
        assertThatThrownBy(() -> field(List.of(), lifted))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("TableField Language.film needs a join path to its parent");
    }

    @Test
    void pathWithNoCorrelationIsRefused() {
        assertThatThrownBy(() -> field(FK_PATH, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("TableField Language.film needs a parent correlation");
    }

    @Test
    void oneHopPathWithItsCorrelationIsAdmitted() {
        var correlation = TestFixtures.pcFor(FK_PATH, TestFixtures.languageTable());
        assertThat(field(FK_PATH, correlation).parentCorrelation()).isSameAs(correlation);
    }
}
