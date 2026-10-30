package no.sikt.graphitron.model.capture.federation;

import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.test.ThreadConfinedStore;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.FEDERATION_VERSION_DEFINITION;
import static no.sikt.graphitron.model.Tables.FEDERATION_VERSION_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.FEDERATION_VERSION_DIRECTIVE_LOCATION;
import static no.sikt.graphitron.model.Tables.FEDERATION_VERSION_IMPORT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_DEFINITION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_LINK;
import static no.sikt.graphitron.model.Tables.FEDERATION_WRITTEN_VERSION;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The federation gatherer's mark and sweep, at the library's grain: a jar the store holds the
 * reading of is not read again, a changed one is read whole, and one with no stamp is read every
 * time. What the library answers is pinned by the federation-versions fact document; this pins what
 * the store does with the answer across readings.
 */
class FederationCaptureTest {

    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 10, 16, 9, 0);
    private static final LocalDateTime SECOND = FIRST.plusMinutes(1);

    @Test
    @DisplayName("an unchanged jar keeps its rows and is not read again")
    void anUnchangedJarIsReadOnce() {
        ThreadConfinedStore.run(dsl -> {
            ModelCapture.writeGraph(dsl, new GraphIdentity("g", Path.of(".")), FIRST);
            FederationCapture.gather(dsl, "g", "sha256:a", "v2.10", FIRST);
            int imports = dsl.fetchCount(FEDERATION_VERSION_IMPORT);
            assertThat(imports).as("the library answers something").isPositive();

            FederationCapture.gather(dsl, "g", "sha256:a", "v2.10", SECOND);
            assertThat(dsl.fetchCount(FEDERATION_VERSION_IMPORT)).isEqualTo(imports);
            assertThat(readAt(dsl))
                .as("dated by the reading that read it, not by the one that found it current")
                .isEqualTo(FIRST);
        });
    }

    @Test
    @DisplayName("a changed jar, or one with no stamp, is read whole again")
    void aChangedJarIsReadAgain() {
        ThreadConfinedStore.run(dsl -> {
            ModelCapture.writeGraph(dsl, new GraphIdentity("g", Path.of(".")), FIRST);
            FederationCapture.gather(dsl, "g", "sha256:a", "v2.10", FIRST);
            int imports = dsl.fetchCount(FEDERATION_VERSION_IMPORT);

            FederationCapture.gather(dsl, "g", "sha256:b", "v2.10", SECOND);
            assertThat(readAt(dsl)).isEqualTo(SECOND);
            assertThat(dsl.fetchCount(FEDERATION_VERSION_IMPORT))
                .as("the same library reads to the same rows")
                .isEqualTo(imports);

            FederationCapture.gather(dsl, "g", null, "v2.10", SECOND.plusMinutes(1));
            FederationCapture.gather(dsl, "g", null, "v2.10", SECOND.plusMinutes(2));
            assertThat(readAt(dsl)).isEqualTo(SECOND.plusMinutes(2));
        });
    }

    @Test
    @DisplayName("a changed jar's reading sweeps what the library no longer declares and keeps the rest")
    void aChangedJarSweepsWhatTheLibraryDropped() {
        ThreadConfinedStore.run(dsl -> {
            ModelCapture.writeGraph(dsl, new GraphIdentity("g", Path.of(".")), FIRST);
            FederationCapture.gather(dsl, "g", "sha256:a", "v2.10", FIRST);
            String source = FederationCapture.SOURCE_NAME;
            var d = FEDERATION_VERSION_DEFINITION;
            dsl.insertInto(d, d.SOURCE_NAME, d.VERSION, d.NAME, d.KIND, d.UNIMPORTED_NAME,
                    d.REPEATABLE, d.TOUCHED_AT)
                .values(source, "v2.10", "@dropped", "DIRECTIVE", "@federation__dropped", false, FIRST)
                .execute();
            var a = FEDERATION_VERSION_DIRECTIVE_ARGUMENT;
            dsl.insertInto(a, a.SOURCE_NAME, a.VERSION, a.NAME, a.ARGUMENT_NAME, a.ORDINAL,
                    a.TYPE_SDL, a.NAMED_TYPE, a.NON_NULL, a.IS_LIST, a.TOUCHED_AT)
                .values(source, "v2.10", "@dropped", "reason", 0, "String", "String", false, false, FIRST)
                .execute();
            var l = FEDERATION_VERSION_DIRECTIVE_LOCATION;
            dsl.insertInto(l, l.SOURCE_NAME, l.VERSION, l.NAME, l.LOCATION, l.TOUCHED_AT)
                .values(source, "v2.10", "@dropped", "OBJECT", FIRST)
                .execute();
            var link = GRAPHITRON_FEDERATION_LINK;
            dsl.insertInto(link, link.GRAPH_NAME, link.ORIGIN, link.VERSION, link.TOUCHED_AT)
                .values("g", "CONFIGURED", "v2.10", FIRST)
                .execute();
            var g = GRAPHITRON_FEDERATION_DEFINITION;
            dsl.insertInto(g, g.GRAPH_NAME, g.NAME, g.LOCAL_NAME, g.KIND, g.SOURCE_NAME, g.VERSION,
                    g.TOUCHED_AT)
                .values("g", "@key", "@key", "DIRECTIVE", source, "v2.10", FIRST)
                .values("g", "@dropped", "@dropped", "DIRECTIVE", source, "v2.10", FIRST)
                .execute();

            FederationCapture.gather(dsl, "g", "sha256:b", "v2.10", SECOND);

            assertThat(dsl.fetchExists(d, d.NAME.eq("@dropped")))
                .as("a definition the library no longer declares goes").isFalse();
            assertThat(dsl.fetchExists(a, a.NAME.eq("@dropped"))).isFalse();
            assertThat(dsl.fetchExists(l, l.NAME.eq("@dropped"))).isFalse();
            assertThat(dsl.select(g.NAME).from(g).fetch(g.NAME))
                .as("a graph's definition goes with the library's, and one still declared stays")
                .containsExactly("@key");
            assertThat(dsl.selectDistinct(d.TOUCHED_AT).from(d).fetch(d.TOUCHED_AT))
                .as("every definition still declared is marked by the reading that read it")
                .containsExactly(SECOND);
        });
    }

    @Test
    @DisplayName("the version graphitron writes is one the library supports, or capture refuses it")
    void theWrittenVersionIsOneTheLibrarySupports() {
        ThreadConfinedStore.run(dsl -> {
            ModelCapture.writeGraph(dsl, new GraphIdentity("g", Path.of(".")), FIRST);
            FederationCapture.gather(dsl, "g", "sha256:a", "v2.10", FIRST);
            assertThat(dsl.select(FEDERATION_WRITTEN_VERSION.VERSION).from(FEDERATION_WRITTEN_VERSION)
                    .fetch(FEDERATION_WRITTEN_VERSION.VERSION))
                .containsExactly("v2.10");

            assertThatThrownBy(() -> FederationCapture.gather(dsl, "g", "sha256:a", "v9.0", SECOND))
                .isInstanceOf(DataAccessException.class);
        });
    }

    private static LocalDateTime readAt(DSLContext dsl) {
        return dsl.select(STORE_SOURCE.READ_AT).from(STORE_SOURCE)
            .where(STORE_SOURCE.SOURCE_NAME.eq(FederationCapture.SOURCE_NAME))
            .fetchSingle().value1();
    }
}
