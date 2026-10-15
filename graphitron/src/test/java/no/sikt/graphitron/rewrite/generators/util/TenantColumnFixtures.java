package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;

/**
 * Representative tenant columns for unit-tier drivers of a multi-tenant runtime: real fixture
 * catalog columns, on the classpath {@code EmittedCodeHarness} compiles against, whose Java type
 * is the key type the driver uses. Neither has a converter, so the canonical key round trip is the
 * identity for a value of that type.
 */
final class TenantColumnFixtures {

    private static final ClassName TABLES = ClassName.get(DEFAULT_JOOQ_PACKAGE + ".public_", "Tables");

    /** {@code FILM.TITLE}: a {@code String} column, no converter. */
    static final CodeBlock STRING_KEY_COLUMN = CodeBlock.of("$T.FILM.TITLE", TABLES);

    /** {@code FILM.FILM_ID}: an {@code Integer} column, no converter. */
    static final CodeBlock INTEGER_KEY_COLUMN = CodeBlock.of("$T.FILM.FILM_ID", TABLES);

    private TenantColumnFixtures() {}

    /** The fixture column of {@code keyType}, or {@code null} for a single-tenant ({@code null}) key type. */
    static CodeBlock columnFor(TypeName keyType) {
        if (keyType == null) {
            return null;
        }
        if (keyType.equals(ClassName.get(String.class))) {
            return STRING_KEY_COLUMN;
        }
        if (keyType.equals(ClassName.get(Integer.class))) {
            return INTEGER_KEY_COLUMN;
        }
        throw new IllegalArgumentException("No fixture tenant column of type " + keyType);
    }
}
