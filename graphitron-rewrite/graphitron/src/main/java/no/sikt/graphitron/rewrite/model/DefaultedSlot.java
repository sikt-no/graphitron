package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.TypeName;

/**
 * One non-bound parameter on a payload class's canonical constructor: a slot the emitter
 * fills with a literal default at code-emission time. Used by {@link ErrorChannel} (every
 * slot except the errors slot) and by {@link PayloadAssembly} (every slot except the row
 * slot); the remaining bound slots are identified by index on the carrier and printed
 * separately.
 *
 * <ul>
 *   <li>{@code index} : the slot's position in the constructor's parameter list.</li>
 *   <li>{@code name} : the declared parameter name, recorded for diagnostics.</li>
 *   <li>{@code type} : the parameter's resolved {@link TypeName}, recorded for diagnostics.</li>
 *   <li>{@code defaultLiteral} : the literal expression the emitter prints at this slot
 *       ({@code "null"} for reference types; {@code "0"}, {@code "0L"}, {@code "0.0"},
 *       {@code "false"}, or {@code "'\\0'"} for primitives). Resolved once at classify time;
 *       the emitter prints it directly without re-deriving from {@code type}.</li>
 * </ul>
 */
public record DefaultedSlot(
    int index,
    String name,
    TypeName type,
    String defaultLiteral
) {
    public DefaultedSlot {
        if (index < 0) {
            throw new IllegalArgumentException(
                "DefaultedSlot: index must be non-negative; got " + index);
        }
        if (defaultLiteral == null) {
            throw new IllegalArgumentException(
                "DefaultedSlot: defaultLiteral must be present for slot '" + name + "'");
        }
    }
}
