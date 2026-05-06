package no.sikt.graphitron.lsp.parsing;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The LSP's directive vocabulary registry. Keyed on directive name; each
 * entry carries the directive's argument list with each argument's input
 * type. Mirrors the directive surface in {@code directives.graphqls}.
 *
 * <p>Consumers query the registry through derived views. R93's primary
 * use case is {@link #argsByInputType(String)}, which returns every
 * site where a given input type is bound; the
 * {@code ExternalCodeReference} migration walks all eight bindings off
 * one call.
 *
 * <p>The registry is hand-written for now (Phase 1, R93). Parsing
 * {@code directives.graphqls} at LSP startup is a tractable follow-on
 * but adds startup cost and a runtime SDL dependency without changing
 * the consumer surface; the spec's "open architectural decisions" leaves
 * the population strategy as an implementation detail.
 *
 * <p>Adding a new directive (or a new arg on an existing directive) is
 * a single registry entry; every derived view picks it up automatically,
 * so completion and diagnostic surfaces light up without per-site
 * dispatch code.
 */
public final class DirectiveDefinitions {

    private DirectiveDefinitions() {}

    /**
     * Reference to an input-typed binding inside a directive's argument
     * list. Used by {@link #argsByInputType(String)} consumers as the
     * triple identifying a site of interest.
     *
     * @param directive  directive name (without the {@code @} prefix).
     * @param argName    argument name (or, when {@code nestedPath} is
     *                   {@code true}, the leaf {@code object_field} name
     *                   inside a structured outer arg). For
     *                   {@code @reference(path: [{condition: ...}])}
     *                   this is {@code "condition"} rather than
     *                   {@code "path"}.
     * @param nestedPath when {@code true}, the binding lives inside a
     *                   list / object value rather than as a top-level
     *                   directive argument; consumers walk the directive
     *                   arg tree to find it.
     */
    public record InputTypeBinding(String directive, String argName, boolean nestedPath) {}

    /** Definition of a single directive argument's input type. */
    public record ArgDef(String name, String inputType, boolean nestedPath) {}

    /** Definition of a single directive: name plus argument list. */
    public record DirectiveDef(String name, List<ArgDef> args) {}

    private static final List<DirectiveDef> ENTRIES = List.of(
        new DirectiveDef("externalField", List.of(
            new ArgDef("reference", "ExternalCodeReference", false)
        )),
        new DirectiveDef("enum", List.of(
            new ArgDef("enumReference", "ExternalCodeReference", false)
        )),
        new DirectiveDef("service", List.of(
            new ArgDef("service", "ExternalCodeReference", false),
            new ArgDef("contextArguments", "[String!]", false)
        )),
        new DirectiveDef("tableMethod", List.of(
            new ArgDef("tableMethodReference", "ExternalCodeReference", false),
            new ArgDef("contextArguments", "[String!]", false)
        )),
        new DirectiveDef("record", List.of(
            new ArgDef("record", "ExternalCodeReference", false)
        )),
        new DirectiveDef("batchKeyLifter", List.of(
            new ArgDef("lifter", "ExternalCodeReference", false),
            new ArgDef("targetColumns", "[String!]!", false)
        )),
        new DirectiveDef("condition", List.of(
            new ArgDef("condition", "ExternalCodeReference", false),
            new ArgDef("override", "Boolean", false),
            new ArgDef("contextArguments", "[String!]", false)
        )),
        new DirectiveDef("reference", List.of(
            // path: [ReferenceElement!]! — the ExternalCodeReference
            // sits at path[].condition, walked via nested object_field.
            new ArgDef("condition", "ExternalCodeReference", true)
        ))
    );

    private static final Map<String, DirectiveDef> BY_NAME = ENTRIES.stream()
        .collect(Collectors.toUnmodifiableMap(DirectiveDef::name, d -> d));

    /** All directive definitions in registry order. */
    public static List<DirectiveDef> all() {
        return ENTRIES;
    }

    /** Looks up a directive definition by name (no leading {@code @}). */
    public static Optional<DirectiveDef> byName(String directive) {
        return Optional.ofNullable(BY_NAME.get(directive));
    }

    /**
     * Every site (across every directive) where {@code inputType} is
     * bound. The R93 migration walks
     * {@code argsByInputType("ExternalCodeReference")} to drive both
     * its detection and its diagnostic dispatch.
     */
    public static List<InputTypeBinding> argsByInputType(String inputType) {
        var out = new java.util.ArrayList<InputTypeBinding>();
        for (var def : ENTRIES) {
            for (var arg : def.args()) {
                if (arg.inputType().equals(inputType)) {
                    out.add(new InputTypeBinding(def.name(), arg.name(), arg.nestedPath()));
                }
            }
        }
        return List.copyOf(out);
    }
}
