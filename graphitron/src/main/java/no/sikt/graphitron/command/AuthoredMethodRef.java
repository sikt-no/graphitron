package no.sikt.graphitron.command;

/**
 * A reference to one static method in developer-authored code: the class name exactly as the
 * author wrote it in the directive, plus the method's simple name. The authored counterpart of
 * {@link UnitMethodRef}, and a separate type rather than a reuse of it because the two name
 * different things: a {@link UnitRef} addresses a compilation unit <em>we</em> emit, minted from
 * the plan's naming vocabulary and split into package and simple name because the write step
 * needs a landing address. An authored class is nobody's to mint or to place, so the name rides
 * as the one string the author supplied and the call site resolves it.
 *
 * <p>Two components because two are what a call site reads: the emitted call is
 * {@code Class.method(table, locals...)}. The model's method reference carries the rest of a
 * reflected signature (return type, parameters, declared exceptions); none of it reaches an
 * emitter through this carrier, and carrying it would make a command row depend on a classpath
 * scan for facts nothing renders.
 */
public record AuthoredMethodRef(String className, String methodName) {

    public AuthoredMethodRef {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException(
                "an authored method reference requires the class name as the author wrote it");
        }
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException(
                "an authored method reference requires a non-blank method name");
        }
    }
}
