package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.render.CatalogRefs;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The shape of the generated {@code ConnectionKey}, the value a build keys its per-request connections
 * by and hands down a subtree in graphql-java's {@code localContext} slot: which parts it has, decided
 * once for the build and handed to every generator that writes, reads or keys by it, so no site decides
 * for itself what a key carries.
 *
 * <p>The record has exactly the parts the schema has: the tenant, typed by the tenant column, in a build
 * with a {@code <tenantColumn>}; one part per vouched-for field, typed by the column the field reads, in a
 * build that accepts vouchers; and no part at all in a build with neither. Each question a site asks is
 * its own method: {@link #hasTenant()} for the tenant machinery and the slot it writes,
 * {@link #vouchedFor()} for the vouched-for parts, {@link #hasParts()} for whether a key can be anything
 * but the empty one.
 *
 * @param tenant     the tenant part's type, boxed, where the build has a tenant column
 * @param vouchedFor the vouched-for parts in name order, empty where the build accepts no vouchers
 */
public record ConnectionKeyShape(Optional<TypeName> tenant, List<VouchedForPart> vouchedFor) {

    /** The generated record's simple name, in the runtime's {@code schema} package. */
    public static final String CLASS_NAME = "ConnectionKey";

    /**
     * One vouched-for part: the vouched-for field's name, which is the record component's, and the Java
     * type of the column it reads, boxed.
     */
    public record VouchedForPart(String name, TypeName type) {
        public VouchedForPart {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            type = type.isPrimitive() ? type.box() : type;
        }
    }

    public ConnectionKeyShape {
        Objects.requireNonNull(tenant, "tenant");
        tenant = tenant.map(t -> t.isPrimitive() ? t.box() : t);
        vouchedFor = List.copyOf(vouchedFor);
        if (tenant.isPresent() && !vouchedFor.isEmpty()) {
            throw new IllegalArgumentException("A key has a tenant part or vouched-for parts, not both: a build"
                + " that accepts vouchers has no tenant column");
        }
    }

    /** A key with no part. */
    public static ConnectionKeyShape none() {
        return new ConnectionKeyShape(Optional.empty(), List.of());
    }

    /** The shape of a build whose tenant key type is {@code tenantKeyType}, or none where it is null. */
    public static ConnectionKeyShape ofTenantKey(TypeName tenantKeyType) {
        return new ConnectionKeyShape(Optional.ofNullable(tenantKeyType), List.of());
    }

    /** The shape of a build that accepts vouchers for values of {@code vouchedFor}'s shape. */
    public static ConnectionKeyShape vouchedFor(List<VouchedForPart> vouchedFor) {
        return new ConnectionKeyShape(Optional.empty(), vouchedFor);
    }

    /**
     * The build's shape: the tenant part from {@code tenantKeyType}, null in a build without a tenant
     * column, and the vouched-for parts read off the store's accepting fields ({@link AcceptVouchedForQuery}),
     * none where the graph accepts no vouchers.
     */
    public static ConnectionKeyShape read(StoreHandle store, TypeName tenantKeyType) {
        var parts = AcceptVouchedForQuery.read(store).stream()
            .map(field -> new VouchedForPart(field.fieldName(), typeOf(field)))
            .toList();
        return new ConnectionKeyShape(Optional.ofNullable(tenantKeyType), parts);
    }

    private static TypeName typeOf(AcceptVouchedForQuery.Field field) {
        var type = CatalogRefs.decodeBindingType(field.bindingType());
        if (type == null) {
            throw new IllegalStateException("The vouched-for field '" + field.fieldName() + "' reads a column of"
                + " binding type '" + field.bindingType() + "', which names no Java type");
        }
        return type;
    }

    /** Whether the key has a tenant part: whether the build runs the tenant machinery and writes the slot. */
    public boolean hasTenant() {
        return tenant.isPresent();
    }

    /** Whether the key has any part, a tenant or a vouched-for value. */
    public boolean hasParts() {
        return hasTenant() || !vouchedFor.isEmpty();
    }

    /** The generated {@code ConnectionKey} class of a build writing into {@code outputPackage}. */
    public static ClassName className(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", CLASS_NAME);
    }
}
