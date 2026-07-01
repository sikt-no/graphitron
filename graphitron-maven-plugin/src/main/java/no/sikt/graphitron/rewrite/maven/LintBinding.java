package no.sikt.graphitron.rewrite.maven;

import java.util.List;

/**
 * POM XML binding for the {@code <lint>} block (R408). Collapses into a
 * {@link no.sikt.graphitron.rewrite.lint.LintConfig} on
 * {@link no.sikt.graphitron.rewrite.RewriteContext}:
 *
 * <pre>{@code
 * <lint>
 *   <disabledRules>
 *     <rule>input-object-name-suffix</rule>
 *   </disabledRules>
 *   <excludedTypes>
 *     <type>Legacy*</type>
 *   </excludedTypes>
 * </lint>
 * }</pre>
 *
 * The nested element names ({@code <rule>}, {@code <type>}) are conventional; Maven's configurator
 * populates each list from the child elements regardless of their tag.
 */
public class LintBinding {
    /** Rule ids to silence everywhere; each is validated against {@code LintRule.id()} at config build. */
    List<String> disabledRules;
    /** Type-name globs whose matching types the SDL lint engine skips ({@code *} any run, {@code ?} one char). */
    List<String> excludedTypes;
}
