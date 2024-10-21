package no.fellesstudentsystem.graphitron.maven;

public class TestConfiguration {
    public static final String
            SCHEMA_EXTENSION = ".graphqls",
            COMMON_TEST_SCHEMA_NAME = "schema" + SCHEMA_EXTENSION,
            DEFAULT_OUTPUT_PACKAGE = "fake.code.generated",
            DEFAULT_JOOQ_PACKAGE = "no.sikt.graphitron.jooq.generated.testdata",
            SRC_ROOT = "src/test/resources",
            EXPECTED_OUTPUT_NAME = "expected";
}
