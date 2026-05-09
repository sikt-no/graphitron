package no.sikt.graphitron.lsp.parsing;

import graphql.language.Description;
import graphql.language.DirectiveDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.StringValue;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The LSP's directive vocabulary, keyed by GraphQL schema coordinates.
 * Composed of a hand-coded {@link Behavior} overlay and the parsed
 * {@link TypeDefinitionRegistry} of the bundled {@code directives.graphqls}.
 *
 * <p>The parsed registry contributes the full directive surface: every
 * directive, every arg, every input type and field, plus their description
 * strings. The overlay declares semantics ("complete this as a class name",
 * "validate this against the catalog's table set") only for the subset the
 * LSP knows how to act on today. Filing semantics for a new directive
 * becomes an additive overlay entry; the parse already exposes the
 * coordinate.
 *
 * <p><b>Structural invariant.</b> Every coordinate in the overlay must
 * resolve against the parsed registry. The constructor enforces this and
 * throws {@link LspStartupException} on any unresolved coordinate; the
 * R110-style drift that motivated this Spec becomes a loud startup
 * failure before any IDE session ever runs.
 *
 * <p>The vocabulary is read once at LSP startup and never invalidated.
 * The bundled SDL ships with the LSP jar; it is shape, not state.
 */
public record LspVocabulary(
    Map<SchemaCoordinate, Behavior> overlay,
    TypeDefinitionRegistry registry
) {

    /**
     * Javadoc-style {@code @deprecated} token in a description string,
     * mirroring {@code DeprecationMarkers.DESCRIPTION_DEPRECATED_TOKEN}
     * but applied to the parsed description rather than raw SDL bytes.
     * The negative lookbehind avoids matching mid-word occurrences such
     * as {@code my@deprecated}.
     */
    private static final Pattern DESCRIPTION_DEPRECATED_TOKEN =
        Pattern.compile("(?<![A-Za-z0-9])@deprecated\\b");

    public LspVocabulary {
        overlay = Map.copyOf(overlay);
        for (var coord : overlay.keySet()) {
            if (!resolves(coord, registry)) {
                throw new LspStartupException(
                    "Schema coordinate " + coord + " does not resolve against "
                        + "directives.graphqls. Either update the overlay or "
                        + "check directive surface.");
            }
        }
    }

    /**
     * Production factory: parses the bundled {@code directives.graphqls}
     * and applies the canonical overlay declared at
     * {@link CanonicalOverlay#overlay()}.
     */
    public static LspVocabulary load() {
        return load(CanonicalOverlay.overlay());
    }

    /**
     * Test / dev factory: parses the bundled SDL and applies a caller-supplied
     * overlay. The structural invariant fires on construction.
     */
    public static LspVocabulary load(Map<SchemaCoordinate, Behavior> overlay) {
        return new LspVocabulary(overlay, parseDirectivesSdl());
    }

    /**
     * Parses an arbitrary SDL fixture instead of the bundled resource.
     * Used by unit tests that want to assert vocabulary behavior against
     * a synthetic directive surface.
     */
    public static LspVocabulary load(Map<SchemaCoordinate, Behavior> overlay, String sdl) {
        return new LspVocabulary(overlay, new SchemaParser().parse(sdl));
    }

    /** Returns the {@link Behavior} the overlay declares for {@code coord}, if any. */
    public Optional<Behavior> behaviorAt(SchemaCoordinate coord) {
        return Optional.ofNullable(overlay.get(coord));
    }

    /**
     * Returns deprecation info for {@code coord}, in either the native
     * {@code @deprecated(reason:)} form (member-level) or the docstring
     * {@code @deprecated} convention (whole-directive). Empty if the
     * coordinate is not deprecated.
     */
    public Optional<DeprecationInfo> deprecationOf(SchemaCoordinate coord) {
        return switch (coord) {
            case SchemaCoordinate.Directive d -> directiveDocstringDeprecation(d.name());
            case SchemaCoordinate.DirectiveArg da -> directiveArgNativeDeprecation(da.directive(), da.arg());
            case SchemaCoordinate.InputField f -> inputFieldNativeDeprecation(f.type(), f.field());
            case SchemaCoordinate.InputType ignored -> Optional.empty();
        };
    }

    private Optional<DeprecationInfo> directiveDocstringDeprecation(String name) {
        return findDirective(name)
            .flatMap(d -> descriptionText(d.getDescription()))
            .filter(text -> DESCRIPTION_DEPRECATED_TOKEN.matcher(text).find())
            .map(DeprecationInfo::docstring);
    }

    private Optional<DeprecationInfo> directiveArgNativeDeprecation(String directive, String arg) {
        return findDirective(directive)
            .flatMap(d -> findInputValue(d.getInputValueDefinitions(), arg))
            .flatMap(LspVocabulary::nativeDeprecationReason)
            .map(DeprecationInfo::native_);
    }

    private Optional<DeprecationInfo> inputFieldNativeDeprecation(String type, String field) {
        return findInputType(type)
            .flatMap(t -> findInputValue(t.getInputValueDefinitions(), field))
            .flatMap(LspVocabulary::nativeDeprecationReason)
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

    private static Optional<InputValueDefinition> findInputValue(
        java.util.List<InputValueDefinition> values, String name) {
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

    private static boolean resolves(SchemaCoordinate coord, TypeDefinitionRegistry registry) {
        return switch (coord) {
            case SchemaCoordinate.Directive d ->
                registry.getDirectiveDefinitions().containsKey(d.name());
            case SchemaCoordinate.DirectiveArg da ->
                registry.getDirectiveDefinition(da.directive())
                    .map(DirectiveDefinition::getInputValueDefinitions)
                    .map(args -> args.stream().anyMatch(a -> a.getName().equals(da.arg())))
                    .orElse(false);
            case SchemaCoordinate.InputType t ->
                registry.getTypeOrNull(t.name(), InputObjectTypeDefinition.class) != null;
            case SchemaCoordinate.InputField f ->
                Optional.ofNullable(registry.getTypeOrNull(f.type(), InputObjectTypeDefinition.class))
                    .map(InputObjectTypeDefinition::getInputValueDefinitions)
                    .map(fs -> fs.stream().anyMatch(v -> v.getName().equals(f.field())))
                    .orElse(false);
        };
    }

    private static TypeDefinitionRegistry parseDirectivesSdl() {
        return new SchemaParser().parse(RewriteSchemaLoader.directivesSdl());
    }

    /**
     * Carrier for deprecation info, agnostic to whether the marker came
     * from native {@code @deprecated(reason:)} or graphitron's docstring
     * {@code @deprecated} convention. Consumers query
     * {@link LspVocabulary#deprecationOf} without caring which shape declared it.
     *
     * @param reason     the replacement-hint text. For native deprecation,
     *                   the {@code reason:} arg's value (empty string when
     *                   {@code @deprecated} carries no reason). For
     *                   docstring deprecation, the whole description text
     *                   (the recogniser-side parse of "what to use instead"
     *                   lives in the consumer that surfaces it; this
     *                   record only declares "marker present" plus context).
     * @param shape      whether the marker came from the native form or
     *                   the docstring convention; informs auto-migration
     *                   policy in {@code SdlActions}.
     */
    public record DeprecationInfo(String reason, Shape shape) {
        public enum Shape { NATIVE, DOCSTRING }

        static DeprecationInfo native_(String reason) {
            return new DeprecationInfo(reason, Shape.NATIVE);
        }

        static DeprecationInfo docstring(String description) {
            return new DeprecationInfo(description, Shape.DOCSTRING);
        }
    }

    /**
     * Thrown from the {@link LspVocabulary} constructor when an overlay
     * coordinate fails to resolve against the parsed registry. R110-style
     * drift becomes a loud startup failure rather than a silent
     * unknown-directive at request time.
     */
    public static final class LspStartupException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public LspStartupException(String message) {
            super(message);
        }
    }

    /**
     * The canonical overlay shipped with the LSP. The full set of
     * coordinates the LSP knows how to act on today; mirrors the table
     * in the R119 spec body.
     */
    public static final class CanonicalOverlay {
        private CanonicalOverlay() {}

        public static Map<SchemaCoordinate, Behavior> overlay() {
            var ecrClassName = new SchemaCoordinate.InputField("ExternalCodeReference", "className");
            var sourceRowClassName = new SchemaCoordinate.DirectiveArg("sourceRow", "className");
            var out = new LinkedHashMap<SchemaCoordinate, Behavior>();
            out.put(ecrClassName, new Behavior.ClassNameBinding());
            out.put(new SchemaCoordinate.InputField("ExternalCodeReference", "method"),
                new Behavior.MethodNameBinding(ecrClassName));
            out.put(new SchemaCoordinate.InputField("ExternalCodeReference", "argMapping"),
                new Behavior.ArgMappingBinding());
            out.put(sourceRowClassName, new Behavior.ClassNameBinding());
            out.put(new SchemaCoordinate.DirectiveArg("sourceRow", "method"),
                new Behavior.MethodNameBinding(sourceRowClassName));
            out.put(new SchemaCoordinate.DirectiveArg("table", "name"),
                new Behavior.CatalogTableBinding());
            out.put(new SchemaCoordinate.DirectiveArg("field", "name"),
                new Behavior.CatalogColumnBinding());
            out.put(new SchemaCoordinate.InputField("ReferenceElement", "key"),
                new Behavior.CatalogFkBinding());
            out.put(new SchemaCoordinate.InputField("ReferenceElement", "table"),
                new Behavior.CatalogTableBinding());
            return out;
        }
    }
}
