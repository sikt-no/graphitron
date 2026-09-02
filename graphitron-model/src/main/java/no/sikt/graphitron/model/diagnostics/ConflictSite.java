package no.sikt.graphitron.model.diagnostics;

/**
 * One directive site that declared a type for a {@code contextArgument}, as the conflict
 * rejection carries it: the coordinate the reference was written at, and the type that site
 * declared, both as names.
 *
 * <p>Names and no types, deliberately, on the same rule the catalog refs follow: this rides a
 * {@link Rejection} arm, and a rejection is a fact the store holds. {@code declared} is the
 * structural spelling of the declared type, which is what a disagreement is decided on (the
 * classifier folds sites by this value) and all a message needs to render. Deciding how a type is
 * written into a source file belongs to the emitting tier and happens there.
 *
 * <p>{@code className} and {@code methodName} are projected at construction from whichever model
 * value the reference came from: a {@code MethodRef} for {@code @condition} /
 * {@code @externalField}, a {@code ServiceMethodCall} for a root sync {@code @service} carrier, or
 * the {@code <sessionState>} {@code <mount>} method, which spells its class with a
 * {@code <mount>} prefix so a mount-versus-{@code @service} conflict names the element rather
 * than only the routine class the reference resolves to. The model value itself stays with the
 * classifier's own output ({@code ResolvedContextArg.Site}), which is where a consumer that wants
 * to navigate to the declaration reads it.
 */
public record ConflictSite(String className, String methodName, String declared) {
}
