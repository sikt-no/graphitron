package no.sikt.graphitron.model.capture;

import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.model.test.TestRunContext;
import no.sikt.graphitron.model.test.CapturedStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.SQL_ENUM_BINDING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a second walk over one catalog source does to the enum classes it binds: restates them. What
 * the walk writes for them, a generated enum's class, schema and database type, one row per class
 * whatever binds it and none for a plain scalar, is stated in the {@code scenarios/catalog-columns}
 * fact document.
 *
 * <p>On a store of its own, since its subject is two captures meeting one catalog source: in a
 * store another graph had read the catalog into first, the first capture would already be a second.
 */
class EnumBindingCaptureTest {

    private static final String SDL = """
        type Query {
          films: [Film!]!
        }

        type Film {
          id: ID!
        }
        """;

    /**
     * A second walk over the same source refreshes rather than collides. The clear runs beside the
     * column round that reaches these rows, so a capture that had left this relation out of it
     * would fail here on a duplicate key rather than on an assertion.
     */
    @Test
    @DisplayName("a second capture over the same source restates the rows rather than colliding")
    void aSecondCaptureRestatesTheRows(@TempDir Path tmp) {
        var jooq = CatalogInput.of(TestRunContext.JOOQ_PACKAGE);
        try (var store = CapturedStore.ownStoreOfCatalog(tmp, SDL, jooq)) {
            int before = store.dsl().fetchCount(SQL_ENUM_BINDING);
            assertThat(before).isPositive();
            store.andCatalogGraph("second", SDL, jooq);

            assertThat(store.dsl().fetchCount(SQL_ENUM_BINDING)).isEqualTo(before);
        }
    }
}
