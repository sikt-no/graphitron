package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The shape of the generated {@code ConnectionKey}, the value a build keys its per-request connections
 * by: which parts it has, decided once for the build and handed to every generator that writes, reads or
 * keys by it, so no site decides for itself.
 *
 * <p>The tenant part is present in a multi-tenant build, typed as the tenant column's Java type. The
 * vouched-for part is present in a build that accepts vouchers, one component per vouched-for field,
 * typed as the column it reads. The shape answers three questions apart:
 * <ul>
 *   <li>{@link #emitsKey()}: whether a {@code ConnectionKey} is emitted and the carrier keys its
 *       connections by it, either part being present;</li>
 *   <li>{@link #tenantMachinery()}: whether the tenant machinery runs, the tenant part being present;</li>
 *   <li>{@link #writesSlot()}: whether the key is handed down a subtree in graphql-java's
 *       {@code localContext} slot. Only the tenant part writes it so far: nothing below a vouched entity
 *       reads it yet, the store refusing an accepting type with a field fetched apart from it.</li>
 * </ul>
 * A build with neither part emits no {@code ConnectionKey}; its connections are keyed as the single-tenant
 * one-key case.
 *
 * @param tenantKey the tenant part's type, boxed, in a multi-tenant build
 * @param vouchedFor the vouched-for part's components in name order, empty in a build that accepts nothing
 */
public record ConnectionKeyShape(Optional<TypeName> tenantKey, List<VouchedFor> vouchedFor) {

    /** The generated record's simple name, in the runtime's {@code schema} package. */
    public static final String CLASS_NAME = "ConnectionKey";

    /** The vouched-for part's nested record, {@code ConnectionKey.VouchedFor}. */
    public static final String VOUCHED_FOR_CLASS_NAME = "VouchedFor";

    /** One component of the vouched-for part: the vouched-for field's name and its value's Java type. */
    public record VouchedFor(String fieldName, TypeName type) {
        public VouchedFor {
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(type, "type");
            type = type.isPrimitive() ? type.box() : type;
        }
    }

    public ConnectionKeyShape {
        Objects.requireNonNull(tenantKey, "tenantKey");
        tenantKey = tenantKey.map(t -> t.isPrimitive() ? t.box() : t);
        vouchedFor = List.copyOf(vouchedFor);
    }

    /** A key with no part: nothing is emitted and the carrier is keyed as the one-key case. */
    public static ConnectionKeyShape none() {
        return new ConnectionKeyShape(Optional.empty(), List.of());
    }

    /** The shape of a build whose tenant key type is {@code tenantKeyType}, or none where it is null. */
    public static ConnectionKeyShape ofTenantKey(TypeName tenantKeyType) {
        return new ConnectionKeyShape(Optional.ofNullable(tenantKeyType), List.of());
    }

    /**
     * The shape of a build whose tenant key type is {@code tenantKeyType} (null in a single-tenant build)
     * and whose accepting fields vouch for {@code vouchedFor}, as the store states them.
     */
    public static ConnectionKeyShape of(TypeName tenantKeyType, List<AcceptVouchedForQuery.Field> vouchedFor) {
        return new ConnectionKeyShape(Optional.ofNullable(tenantKeyType), vouchedFor.stream()
            .map(f -> new VouchedFor(f.fieldName(), ClassName.bestGuess(f.bindingType())))
            .toList());
    }

    /** Whether a {@code ConnectionKey} is emitted and the carrier keys its connections by it. */
    public boolean emitsKey() {
        return tenantKey.isPresent() || !vouchedFor.isEmpty();
    }

    /** Whether the tenant machinery runs: the request tenant set, routing and fan-out. */
    public boolean tenantMachinery() {
        return tenantKey.isPresent();
    }

    /** Whether the key has a vouched-for part, so a connection may be acquired for a vouched-for value. */
    public boolean accepts() {
        return !vouchedFor.isEmpty();
    }

    /** Whether the build hands the key down a subtree in graphql-java's {@code localContext} slot. */
    public boolean writesSlot() {
        return tenantKey.isPresent();
    }

    /** The generated {@code ConnectionKey} class of a build writing into {@code outputPackage}. */
    public static ClassName className(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", CLASS_NAME);
    }

    /** The generated {@code ConnectionKey.VouchedFor} class of a build writing into {@code outputPackage}. */
    public static ClassName vouchedForClassName(String outputPackage) {
        return className(outputPackage).nestedClass(VOUCHED_FOR_CLASS_NAME);
    }
}
