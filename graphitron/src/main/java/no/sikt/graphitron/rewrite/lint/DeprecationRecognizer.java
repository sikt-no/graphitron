package no.sikt.graphitron.rewrite.lint;

import graphql.language.Description;
import graphql.language.DirectiveDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.StringValue;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Recognises graphitron's two deprecation-marker conventions over a parsed graphql-java
 * {@link TypeDefinitionRegistry}, with no LSP dependency. Extracted down from {@code LspVocabulary}
 * so a build-side lint visitor (visitor 8, {@code no-deprecated-directive-usage}) can consume
 * it: graphitron-lsp depends on graphitron, not the reverse, so the recogniser cannot stay LSP-side
 * and be read build-side. {@code LspVocabulary} now delegates its {@code SchemaCoordinate}-keyed
 * deprecation queries here and keeps only the {@code SchemaCoordinate} adapter LSP-side.
 *
 * <p>The two markers graphitron unifies (GraphQL forces them apart: native {@code @deprecated} is
 * valid on field / argument / input-field / enum-value but <em>not</em> on a directive definition):
 *
 * <ul>
 *   <li><b>Native</b>: a {@code @deprecated(reason:)} directive on a directive argument or an input
 *       field, read straight off the AST.</li>
 *   <li><b>Docstring</b>: a {@code @deprecated} token in a directive definition's description, for
 *       the whole-directive cases the native form cannot express.</li>
 * </ul>
 */
public final class DeprecationRecognizer {

    /**
     * Javadoc-style {@code @deprecated} token in a description string, applied to the parsed
     * description text rather than raw SDL bytes. The negative lookbehind avoids matching mid-word
     * occurrences such as {@code my@deprecated}.
     */
    private static final Pattern DESCRIPTION_DEPRECATED_TOKEN =
        Pattern.compile("(?<![A-Za-z0-9])@deprecated\\b");

    private final TypeDefinitionRegistry registry;

    public DeprecationRecognizer(TypeDefinitionRegistry registry) {
        this.registry = registry;
    }

    /**
     * Carrier for deprecation info, agnostic to whether the marker came from native
     * {@code @deprecated(reason:)} or graphitron's docstring {@code @deprecated} convention.
     *
     * @param reason the replacement-hint text. For native deprecation, the {@code reason:} arg's
     *               value (empty string when {@code @deprecated} carries no reason). For docstring
     *               deprecation, the whole description text.
     * @param shape  whether the marker came from the native form or the docstring convention.
     */
    public record DeprecationInfo(String reason, Shape shape) {
        public enum Shape { NATIVE, DOCSTRING }

        public static DeprecationInfo native_(String reason) {
            return new DeprecationInfo(reason, Shape.NATIVE);
        }

        public static DeprecationInfo docstring(String description) {
            return new DeprecationInfo(description, Shape.DOCSTRING);
        }
    }

    /** Whole-directive deprecation via the docstring {@code @deprecated} token. */
    public Optional<DeprecationInfo> directiveDeprecation(String name) {
        return findDirective(name)
            .flatMap(d -> descriptionText(d.getDescription()))
            .filter(text -> DESCRIPTION_DEPRECATED_TOKEN.matcher(text).find())
            .map(DeprecationInfo::docstring);
    }

    /** Directive-argument deprecation via the native {@code @deprecated(reason:)} marker. */
    public Optional<DeprecationInfo> directiveArgDeprecation(String directive, String arg) {
        return findDirective(directive)
            .flatMap(d -> findInputValue(d.getInputValueDefinitions(), arg))
            .flatMap(DeprecationRecognizer::nativeDeprecationReason)
            .map(DeprecationInfo::native_);
    }

    /** Input-field deprecation via the native {@code @deprecated(reason:)} marker. */
    public Optional<DeprecationInfo> inputFieldDeprecation(String type, String field) {
        return findInputType(type)
            .flatMap(t -> findInputValue(t.getInputValueDefinitions(), field))
            .flatMap(DeprecationRecognizer::nativeDeprecationReason)
            .map(DeprecationInfo::native_);
    }

    private Optional<DirectiveDefinition> findDirective(String name) {
        for (var d : registry.getDirectiveDefinitions().values()) {
            if (d.getName().equals(name)) return Optional.of(d);
        }
        return Optional.empty();
    }

    private Optional<InputObjectTypeDefinition> findInputType(String name) {
        return Optional.ofNullable(registry.getTypeOrNull(name, InputObjectTypeDefinition.class));
    }

    /**
     * Linear lookup for an {@link InputValueDefinition} by name in a list. The graphql-java API
     * exposes the lists but no name-keyed accessor; this helper avoids duplicating the loop in every
     * consumer ({@code LspVocabulary}, {@code Diagnostics}, {@code ArgNameCompletions} delegate here).
     */
    public static Optional<InputValueDefinition> findInputValue(
        List<InputValueDefinition> values, String name) {
        for (var v : values) {
            if (v.getName().equals(name)) return Optional.of(v);
        }
        return Optional.empty();
    }

    private static Optional<String> nativeDeprecationReason(InputValueDefinition v) {
        for (var dir : v.getDirectives("deprecated")) {
            for (var arg : dir.getArguments()) {
                if (arg.getName().equals("reason") && arg.getValue() instanceof StringValue s) {
                    return Optional.of(s.getValue());
                }
            }
            return Optional.of("");
        }
        return Optional.empty();
    }

    private static Optional<String> descriptionText(Description description) {
        return Optional.ofNullable(description).map(Description::getContent);
    }
}
