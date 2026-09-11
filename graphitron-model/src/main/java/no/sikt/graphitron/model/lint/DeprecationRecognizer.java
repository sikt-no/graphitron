package no.sikt.graphitron.model.lint;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_INPUT_FIELD;

/**
 * Answers whether something the corpus declares is deprecated, by reading what capture wrote.
 *
 * <p>The two markers graphitron unifies are the reason this type exists at all. GraphQL forces them
 * apart: native {@code @deprecated} is valid on a field, argument, input field and enum value, and
 * <em>not</em> on a directive definition, so a deprecated directive says so with a token in its
 * description instead. A reader asking whether something is deprecated should not have to know
 * which of the two marked it, and does not.
 *
 * <p>It used to recognise them, over a parsed registry, at the moment a reader asked. Now capture
 * records them and this reads the rows: {@code graphitron_deprecated_directive} for the docstring
 * form, {@code graphitron_deprecated_directive_argument} for the native form on a directive's own
 * argument, and {@code graphitron_deprecated_input_field} for the native form on an input
 * object's field. Which marker a row came from is the relation it is in, so nothing here
 * switches on a shape, and no method here parses anything: three relations, three reads.
 */
public final class DeprecationRecognizer {

    private final StoreHandle store;

    public DeprecationRecognizer(StoreHandle store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Carrier for deprecation info, agnostic to whether the marker came from native
     * {@code @deprecated(reason:)} or graphitron's docstring convention.
     *
     * @param reason the replacement-hint text. For native deprecation, the {@code reason:} arg's
     *               value (empty string when the marker carries no reason). For docstring
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

    /** Whole-directive deprecation via the docstring token. */
    public Optional<DeprecationInfo> directiveDeprecation(String name) {
        var t = GRAPHITRON_DEPRECATED_DIRECTIVE;
        return store.dsl().select(t.REASON).from(t)
            .where(t.GRAPH_NAME.eq(store.graphName()))
            .and(t.DIRECTIVE_NAME.eq(name))
            .fetchOptional(t.REASON)
            .map(DeprecationInfo::docstring);
    }

    /** Directive-argument deprecation via the native marker. */
    public Optional<DeprecationInfo> directiveArgDeprecation(String directive, String arg) {
        var t = GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT;
        return store.dsl().select(t.REASON).from(t)
            .where(t.GRAPH_NAME.eq(store.graphName()))
            .and(t.DIRECTIVE_NAME.eq(directive))
            .and(t.ARGUMENT_NAME.eq(arg))
            .fetchOptional(t.REASON)
            .map(DeprecationInfo::native_);
    }

    /**
     * Input-field deprecation via the native marker, resolved to the field's coordinate by capture
     * rather than read off the applied-directive anchor. That anchor holds the reason as the
     * rendered literal, quotes and all; this holds what the author wrote.
     */
    public Optional<DeprecationInfo> inputFieldDeprecation(String type, String field) {
        var t = GRAPHITRON_DEPRECATED_INPUT_FIELD;
        return store.dsl().select(t.REASON).from(t)
            .where(t.GRAPH_NAME.eq(store.graphName()))
            .and(t.TYPE_NAME.eq(type))
            .and(t.FIELD_NAME.eq(field))
            .fetchOptional(t.REASON)
            .map(DeprecationInfo::native_);
    }
}
