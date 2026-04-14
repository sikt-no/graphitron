package no.sikt.graphitron.common.configuration;

import no.sikt.graphitron.configuration.GeneratorConfig;

import java.util.List;
import java.util.Set;

public class TestConfiguration {
    public static final String
            DEFAULT_OUTPUT_PACKAGE = "fake.code.generated",
            DEFAULT_JOOQ_PACKAGE = "no.sikt.graphitron.jooq.generated.testdata";

    public static void setProperties() {
        GeneratorConfig.setProperties(
                Set.of(),
                "",
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                List.of(),
                Set.of(),
                List.of()
        );
    }
}
