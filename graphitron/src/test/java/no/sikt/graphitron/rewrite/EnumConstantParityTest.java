package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.enums.MpaaRating;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EnumMappingResolver#constantMismatches}, the constant-comparison core both enum-parity
 * callers project off: the GraphQL-enum coercion check, which compares against Java constant names
 * because the generated code calls {@code valueOf}, and the {@code @discriminator(value:)}
 * closed-domain check, which compares against database literals because the directive value names
 * what the column stores.
 *
 * <p>{@link MpaaRating} is the subject precisely because its two spellings diverge: the literal
 * {@code PG-13} is the constant {@code PG_13}, so the same target name is valid under one spelling
 * and a mismatch under the other. Every discriminated fixture in the tree happens to seed literals
 * that are valid Java identifiers, where the two spellings coincide and no round trip can tell them
 * apart; this is where the namespace decision is actually pinned.
 */
@UnitTier
class EnumConstantParityTest {

    private static List<EnumMappingResolver.EnumConstantParity.Target> targets(String... values) {
        return java.util.Arrays.stream(values)
            .map(v -> new EnumMappingResolver.EnumConstantParity.Target(v, v))
            .toList();
    }

    @Test
    void databaseLiteralSpelling_acceptsTheDashedLiteralAndRejectsTheJavaConstantName() {
        assertThat(EnumMappingResolver.constantMismatches(MpaaRating.class,
                EnumMappingResolver.ConstantSpelling.DATABASE_LITERAL, targets("G", "PG-13", "NC-17")))
            .as("the database literal is what @discriminator(value:) names, so a dashed literal is valid")
            .isEmpty();

        assertThat(EnumMappingResolver.constantMismatches(MpaaRating.class,
                EnumMappingResolver.ConstantSpelling.DATABASE_LITERAL, targets("PG_13")))
            .as("the Java constant name is not the database literal and must not pass as one: "
                + "binding it would convert to null and match no row")
            .singleElement()
            .satisfies(m -> {
                assertThat(m.runtimeValue()).isEqualTo("PG_13");
                assertThat(m.candidates()).containsExactly("G", "PG", "PG-13", "R", "NC-17");
            });
    }

    @Test
    void javaNameSpelling_isTheMirrorImage() {
        assertThat(EnumMappingResolver.constantMismatches(MpaaRating.class,
                EnumMappingResolver.ConstantSpelling.JAVA_NAME, targets("G", "PG_13", "NC_17")))
            .as("valueOf takes the constant name, which is the GraphQL-enum caller's namespace")
            .isEmpty();

        assertThat(EnumMappingResolver.constantMismatches(MpaaRating.class,
                EnumMappingResolver.ConstantSpelling.JAVA_NAME, targets("PG-13")))
            .as("and the dashed literal is not a constant name")
            .singleElement()
            .satisfies(m -> assertThat(m.candidates()).containsExactly("G", "PG", "PG_13", "R", "NC_17"));
    }

    @Test
    void mismatchCarriesTheAuthoredSpellingSeparatelyFromTheComparedForm() {
        var mapped = List.of(new EnumMappingResolver.EnumConstantParity.Target("Pg13", "NOPE"));
        assertThat(EnumMappingResolver.constantMismatches(MpaaRating.class,
                EnumMappingResolver.ConstantSpelling.JAVA_NAME, mapped))
            .singleElement()
            .satisfies(m -> {
                assertThat(m.sdlValueName())
                    .as("the diagnostic names what the author wrote, not the mapped form")
                    .isEqualTo("Pg13");
                assertThat(m.runtimeValue()).isEqualTo("NOPE");
            });
    }

    @Test
    void databaseLiteralSpelling_fallsBackToConstantNamesOnAPlainJavaEnum() {
        // A plain Java enum has no getLiteral(); the reflective read finds no method and the
        // spelling degrades to the constant name rather than failing. Nothing in production reaches
        // this arm (the caller has already established the column is a jOOQ enum), but the core is
        // total over any enum class, which is what makes it safe to share.
        assertThat(EnumMappingResolver.constantMismatches(java.time.DayOfWeek.class,
                EnumMappingResolver.ConstantSpelling.DATABASE_LITERAL, targets("MONDAY")))
            .isEmpty();
    }

    @Test
    void constantNamesKeepDeclarationOrder() {
        assertThat(EnumMappingResolver.constantNames(MpaaRating.class,
                EnumMappingResolver.ConstantSpelling.DATABASE_LITERAL))
            .as("candidate hints read best in the enum's own order, not a hash order")
            .containsExactly("G", "PG", "PG-13", "R", "NC-17");
    }
}
