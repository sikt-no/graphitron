package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import java.util.Objects;
import java.util.Optional;

/**
 * The shape of the generated {@code ConnectionKey}, the value a build keys its per-request connections
 * by and hands down a subtree in graphql-java's {@code localContext} slot: which parts it has, decided
 * once for the build and handed to every generator that writes, reads or keys by it, so no site decides
 * for itself whether the slot carries a key.
 *
 * <p>Every build emits a {@code ConnectionKey} and keys its connections by it. The tenant part is typed
 * as the tenant column's Java type in a multi-tenant build and as {@code Object} in one without, where no
 * routed site ever fills it; only a multi-tenant build writes the slot.
 *
 * @param tenantKey the tenant part's type, boxed, in a multi-tenant build
 */
public record ConnectionKeyShape(Optional<TypeName> tenantKey) {

    /** The generated record's simple name, in the runtime's {@code schema} package. */
    public static final String CLASS_NAME = "ConnectionKey";

    public ConnectionKeyShape {
        Objects.requireNonNull(tenantKey, "tenantKey");
        tenantKey = tenantKey.map(t -> t.isPrimitive() ? t.box() : t);
    }

    /** A key with no part: nothing is written to the slot. */
    public static ConnectionKeyShape none() {
        return new ConnectionKeyShape(Optional.empty());
    }

    /** The shape of a build whose tenant key type is {@code tenantKeyType}, or none where it is null. */
    public static ConnectionKeyShape ofTenantKey(TypeName tenantKeyType) {
        return new ConnectionKeyShape(Optional.ofNullable(tenantKeyType));
    }

    /** Whether the build writes the slot and runs the tenant machinery: whether the key has a tenant part. */
    public boolean writesSlot() {
        return tenantKey.isPresent();
    }

    /** The generated {@code ConnectionKey} class of a build writing into {@code outputPackage}. */
    public static ClassName className(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", CLASS_NAME);
    }
}
