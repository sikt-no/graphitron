package no.sikt.graphitron.rewrite.model;

import com.palantir.javapoet.ClassName;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A batched child keys every batch on its parent correlation, so
 * {@link ChildField.BatchedTableField} refuses a null correlation on both source arms, even on an
 * empty path where the shared {@link ParentCorrelation} carrier invariant admits null for a
 * carrier on the parent's own row. A routine-bearing path carries exactly one routine node; its
 * filters, ordering, pagination and connection are not pinned.
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

    @Test
    void aPathWithTwoRoutineNodesIsRefused() {
        var first = routineHop("languagesOf", TestFixtures.filmTable(), "r_0");
        var second = routineHop("languagesAgain", TestFixtures.languageTable(), "r_1");
        var path = List.<JoinStep>of(first, second);
        var rt = new ReturnTypeRef.TableBoundReturnType("Language", TestFixtures.languageTable(),
            new FieldWrapper.List(true, true));
        assertThatThrownBy(() -> new ChildField.BatchedTableField("Film", "languages", null, rt,
                path, List.of(), new OrderBySpec.None(), null, SourceShape.Table,
                TestFixtures.splitSourceKey(KEY_COLS), TestFixtures.fkColumnsLift(),
                TestFixtures.loaderRegistration(rt, false, false), LookupResolution.None.INSTANCE,
                new ParentCorrelation.OnLateralArgs(first)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one lateral routine node");
    }

    private static JoinStep.Hop routineHop(String name, TableRef from, String alias) {
        return new JoinStep.Hop(
            new TableExpr.RoutineCall(new RoutineRef(ClassName.get("test", "Routines"), name, List.of()),
                TestFixtures.languageTable()),
            new On.Lateral(), from, null, alias);
    }
}
