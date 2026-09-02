package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.schema.input.SchemaRecipe;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;

import java.util.Objects;
import java.util.Optional;

/**
 * The configuration capture transcribes <em>about</em> its subject graph, one typed value rather
 * than a loose parameter per family member. Absence is explicit per component and structural:
 * a component's emptiness means the run was not asked, and capture writes no row rather than a
 * row carrying a synthesised value.
 *
 * <p>One value because the alternative accumulates: the recipe, the supergraph declaration and
 * every family parameter after them would each arrive as a nullable positional argument on all
 * five public entry points, which is the untyped default door {@link
 * no.sikt.graphitron.rewrite.schema.input.SchemaSource} refuses, rebuilt at the seam narrowing
 * {@link GraphIdentity} cleaned. The attribution map stays outside it, being derived from the
 * run's inputs rather than declared by its author.
 *
 * @param recipe     how the run's schema files were found, transcribed so a currency check can
 *                   re-expand it without building the module
 * @param supergraph which supergraph this graph declared itself a subgraph of, from the
 *                   {@code <supergraph>} parameter. Empty is standalone, which is the default
 *                   rather than a state an author spells
 * @param output     where a generating run wrote, from {@code <outputPackage>},
 *                   {@code <jooqPackage>} and {@code <outputDirectory>}. Empty for a run with no
 *                   output coordinates at all, which is a validate-only run: the package sentinel
 *                   such a run carries is its own admission of that, and transcribing the
 *                   sentinel would mint the derived fact that can disagree
 * @param tenantColumn the database-per-tenant column declaration, from {@code <tenantColumn>};
 *                   empty on a single-tenant build
 * @param lint       the {@code <lint>} suppression, decomposed rather than rendered.
 *                   {@link LintConfig#empty()} carries the no-suppression case, which writes no
 *                   rows, so absence needs no second spelling
 * @param sessionState the {@code <sessionState>} form. Sealed, and
 *                   {@link SessionStateConfig#none()} is the no-configuration arm, so this
 *                   component is never absent and the arm carries what absence would have
 */
public record SubjectConfig(Optional<SchemaRecipe> recipe, Optional<String> supergraph,
                            Optional<OutputCoordinates> output, Optional<String> tenantColumn,
                            LintConfig lint, SessionStateConfig sessionState) {
    public SubjectConfig {
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(supergraph, "supergraph");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(tenantColumn, "tenantColumn");
        Objects.requireNonNull(lint, "lint");
        Objects.requireNonNull(sessionState, "sessionState");
    }

    /** A subject that declared nothing at all. */
    public static SubjectConfig none() {
        return new SubjectConfig(Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), LintConfig.empty(), SessionStateConfig.none());
    }

    /** A subject whose only declaration is its recipe. */
    public static SubjectConfig of(SchemaRecipe recipe) {
        return new SubjectConfig(Optional.ofNullable(recipe), Optional.empty(), Optional.empty(),
            Optional.empty(), LintConfig.empty(), SessionStateConfig.none());
    }
}
