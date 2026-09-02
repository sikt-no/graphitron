package no.sikt.graphitron.common.configuration;

import no.sikt.graphitron.model.config.RunContext;

import java.nio.file.Path;
import java.util.List;

public class TestConfiguration {
    public static final String
            DEFAULT_OUTPUT_PACKAGE = "fake.code.generated",
            DEFAULT_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    public static RunContext testContext() {
        return new RunContext(
            List.of(),
            Path.of(""), "TestConfiguration",
            Path.of(""),
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE
        );
    }
}
