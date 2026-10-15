package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A batched child keys every batch on its parent correlation, so
 * {@link ChildField.BatchedTableField} refuses a null correlation on both source arms, even on an
 * empty path where the shared {@link ParentCorrelation} carrier invariant admits null for the
 * inline standalone shape.
 */
@UnitTier
class BatchedTableFieldCorrelationInvariantTest {

    private static final List<ColumnRef> KEY_COLS = List.of(TestFixtures.filmIdCol());
    private static final TableRef FILM = TestFixtures.filmTable();
    private static final ReturnTypeRef.TableBoundReturnType RT =
        new ReturnTypeRef.TableBoundReturnType("Film", FILM, new FieldWrapper.Single(true));

    @ParameterizedTest
    @EnumSource(SourceShape.class)
    void nullCorrelationOnEmptyPathIsRefused(SourceShape shape) {
        var sourceKey = shape == SourceShape.Table
            ? TestFixtures.splitSourceKey(KEY_COLS)
            : TestFixtures.recordParentRowSourceKey(KEY_COLS);
        assertThatThrownBy(() -> new ChildField.BatchedTableField("Film", "details", null, RT,
                List.of(), List.of(), new OrderBySpec.None(), null, shape, sourceKey,
                TestFixtures.fkColumnsLift(), TestFixtures.loaderRegistration(RT, false, false),
                LookupResolution.None.INSTANCE, /* parentCorrelation */ null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("BatchedTableField Film.details needs a parent correlation");
    }
}
