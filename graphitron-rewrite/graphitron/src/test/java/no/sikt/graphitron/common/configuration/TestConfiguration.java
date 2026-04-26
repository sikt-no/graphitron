package no.sikt.graphitron.common.configuration;

import no.sikt.graphitron.rewrite.RewriteContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class TestConfiguration {
    public static final String
            DEFAULT_OUTPUT_PACKAGE = "fake.code.generated",
            DEFAULT_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    public static RewriteContext testContext() {
        return new RewriteContext(
            List.of(),
            Path.of(""),
            Path.of(""),
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE,
            Map.of()
        );
    }
}
