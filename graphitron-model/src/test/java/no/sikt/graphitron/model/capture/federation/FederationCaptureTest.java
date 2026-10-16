package no.sikt.graphitron.model.capture.federation;

import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.test.ThreadConfinedStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.FEDERATION_VERSION_IMPORT;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

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
            FederationCapture.gather(dsl, "g", "sha256:a", FIRST);
            int imports = dsl.fetchCount(FEDERATION_VERSION_IMPORT);
            assertThat(imports).as("the library answers something").isPositive();

            FederationCapture.gather(dsl, "g", "sha256:a", SECOND);
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
            FederationCapture.gather(dsl, "g", "sha256:a", FIRST);
            int imports = dsl.fetchCount(FEDERATION_VERSION_IMPORT);

            FederationCapture.gather(dsl, "g", "sha256:b", SECOND);
            assertThat(readAt(dsl)).isEqualTo(SECOND);
            assertThat(dsl.fetchCount(FEDERATION_VERSION_IMPORT))
                .as("the old rows went first, so the same library reads to the same rows")
                .isEqualTo(imports);

            FederationCapture.gather(dsl, "g", null, SECOND.plusMinutes(1));
            FederationCapture.gather(dsl, "g", null, SECOND.plusMinutes(2));
            assertThat(readAt(dsl)).isEqualTo(SECOND.plusMinutes(2));
        });
    }

    private static LocalDateTime readAt(DSLContext dsl) {
        return dsl.select(STORE_SOURCE.READ_AT).from(STORE_SOURCE)
            .where(STORE_SOURCE.SOURCE_NAME.eq(FederationCapture.SOURCE_NAME))
            .fetchSingle().value1();
    }
}
