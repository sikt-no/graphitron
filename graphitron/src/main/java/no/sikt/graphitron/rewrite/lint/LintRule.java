package no.sikt.graphitron.rewrite.lint;

/**
 * The closed set of built-in SDL lint rules. Rule identity is a type, not a free string: a
 * {@link no.sikt.graphitron.rewrite.BuildWarning.LintFinding} names its {@code LintRule}, and the
 * stable kebab-case {@link #id()} is the only string form that crosses a wire (the MCP
 * {@code diagnostics} tool projects it so an agent sees which rule fired). It is an enum, not a
 * sealed interface, by deliberate v1 scope: the plugin follow-on can widen it when external rules
 * arrive (see the configurability follow-on noted in R398).
 *
 * <p>Every rule is a warning in v1; the enum carries no per-rule severity field. Per-rule severity
 * and enable/disable are deferred to the configurability follow-on.
 *
 * <p>The {@link Source} axis is a typed partition, not decoration. {@link Source#ENGINE} rules are
 * the engine's own syntactic visitors, re-derivable from the AST alone; each is registered to
 * exactly one visitor in {@link LintRules} and the registry coverage test asserts that. They are
 * the rules a single shared traversal dispatches. {@link Source#CLASSIFIER} rules are advisories the
 * classifier already computes and emits from inside the type/field builders; the engine never
 * re-derives them, so they have no visitor and are tagged onto the existing classifier warning at
 * its emit site. Keying the coverage assertion off this axis is what lets the engine assert
 * completeness over a declared partition without a classifier advisory looking like an orphan rule.
 */
public enum LintRule {
    // Engine visitors (syntactic; one shared AST traversal; each registered to exactly one visitor).
    TYPE_NAMES_PASCAL_CASE("type-names-pascal-case", Source.ENGINE),
    FIELD_NAMES_CAMEL_CASE("field-names-camel-case", Source.ENGINE),
    INPUT_AND_ARGUMENT_NAMES_CAMEL_CASE("input-and-argument-names-camel-case", Source.ENGINE),
    ENUM_VALUES_SCREAMING_SNAKE_CASE("enum-values-screaming-snake-case", Source.ENGINE),
    DEPRECATIONS_HAVE_A_REASON("deprecations-have-a-reason", Source.ENGINE),
    TYPES_AND_FIELDS_HAVE_DESCRIPTIONS("types-and-fields-have-descriptions", Source.ENGINE),
    INPUT_OBJECT_NAME_SUFFIX("input-object-name-suffix", Source.ENGINE),
    NO_DEPRECATED_DIRECTIVE_USAGE("no-deprecated-directive-usage", Source.ENGINE),
    NO_TYPENAME_PREFIX("no-typename-prefix", Source.ENGINE),

    // Classifier-owned advisories (surfaced and tagged here, not re-derived; no engine visitor).
    SPLITQUERY_REDUNDANT_ON_RECORD_PARENT("splitquery-redundant-on-record-parent", Source.CLASSIFIER),
    REDUNDANT_RECORD_DIRECTIVE("redundant-record-directive", Source.CLASSIFIER),
    ASCONNECTION_SAME_TABLE_PK_IN("asconnection-same-table-pk-in", Source.CLASSIFIER),

    // Codegen-config advisories: derived from the Mojo <sessionState> config at report assembly,
    // not from the AST and not from a per-field classifier verdict, so neither an engine visitor nor a
    // classifier-tagged advisory. Emitted from GraphQLRewriteGenerator via SessionStateWarnings.
    NO_SESSION_STATE("no-session-state", Source.CODEGEN),
    SESSION_STATE_CONVENTION_FENCE("session-state-convention-fence", Source.CODEGEN);

    /** Where a rule's findings originate, and therefore whether the engine registry owns it. */
    public enum Source {
        /** An engine visitor over the AST; registered in {@link LintRules}, asserted by the coverage test. */
        ENGINE,
        /** A classifier verdict tagged at its existing emit site; never registered to a visitor. */
        CLASSIFIER,
        /** A codegen-config advisory derived at report assembly (R429 {@code <sessionState>}); no visitor, no classifier site. */
        CODEGEN
    }

    private final String id;
    private final Source source;

    LintRule(String id, Source source) {
        this.id = id;
        this.source = source;
    }

    /** Stable kebab-case identifier; the only string form of a rule that crosses a wire. */
    public String id() {
        return id;
    }

    public Source source() {
        return source;
    }

    /**
     * Every rule's stable {@link #id()} in enum-declaration order. This is the valid namespace a
 * consumer's {@code <lint>} config names a rule by; the suppression config validates each
     * configured id against this set and lists it back when an id resolves to no rule.
     */
    public static java.util.List<String> ids() {
        return java.util.Arrays.stream(values()).map(LintRule::id).toList();
    }
}
