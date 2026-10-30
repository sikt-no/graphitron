package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A jOOQ record's column bindings, split into the checks a VALIDATION pre-step emits and the
 * bindings it cannot check. One function, read by both the pre-step and the lint, so this is where
 * their agreement is pinned.
 */
@UnitTier
class WrittenColumnsPartitionTest {

    private static final TableRef FILM = new TableRef("film", "FILM", "x.tables.Film",
        "x.tables.records.FilmRecord", "x.Tables", List.of(), List.of());
    private static final ColumnRef TITLE = new ColumnRef("title", "TITLE", "java.lang.String");
    private static final ColumnRef DESCRIPTION = new ColumnRef("description", "DESCRIPTION", "java.lang.String");

    @Test
    void aCheckBindsThePropertyToThePrimaryPathAndAColumnWithNoGetterIsUnchecked() {
        // title is an alias group: the live name first, then a deprecated one. The check reports at
        // the live name, whichever the client wrote through.
        var title = new CallSiteExtraction.ColumnBinding(
            List.of(List.of("details", "title"), List.of("name")), TITLE);
        var description = CallSiteExtraction.ColumnBinding.of(List.of("description"), DESCRIPTION);
        var subject = new ServiceMethodCall.ValidationSubject.WrittenColumns(new ValueShape.JooqRecordInput(
            new CallSiteExtraction.JooqRecord(FILM, List.of(title, description), List.of()), ArgPath.head("in")));
        Map<String, String> getters = Map.of("title", "title");

        var partition = subject.partition((table, column) -> Optional.ofNullable(getters.get(column.sqlName())));

        assertThat(partition.checks())
            .singleElement()
            .satisfies(check -> {
                assertThat(check.column()).isEqualTo(TITLE);
                assertThat(check.property()).isEqualTo("title");
                assertThat(check.primaryPath()).containsExactly("details", "title");
            });
        assertThat(partition.unchecked()).containsExactly(description);
    }
}
