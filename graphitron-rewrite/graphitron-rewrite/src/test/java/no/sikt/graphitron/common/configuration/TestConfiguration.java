package no.sikt.graphitron.common.configuration;

import no.sikt.graphitron.rewrite.RewriteConfig;

import java.util.Map;
import java.util.Set;

public class TestConfiguration {
    public static final String
            DEFAULT_OUTPUT_PACKAGE = "fake.code.generated",
            DEFAULT_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    public static void setProperties() {
        RewriteConfig.setProperties(
                Set.of(),
                "",
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                Map.of()
        );
    }
}
