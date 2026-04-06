package no.sikt.graphitron.rewrite.field;

import java.util.List;

/**
 * A successfully resolved reference to a Java method, with reflection data captured at parse time.
 *
 * <p>A {@code MethodRef} only appears inside {@link ConditionOnlyRef} or
 * {@link FkWithConditionRef} — both of which represent resolved states. All fields are
 * non-null. Unresolved conditions are represented by {@link UnresolvedConditionRef} instead.
 *
 * <p>{@code qualifiedName} is the fully qualified method name derived from the
 * {@code ExternalCodeReference} input object, e.g.
 * {@code "com.example.CustomerConditions.activeCustomers"}.
 *
 * <p>{@code returnTypeName} is the fully qualified return type of the method (e.g.
 * {@code "org.jooq.Condition"}).
 *
 * <p>{@code params} is the list of parameters in declaration order; an empty list means the
 * method takes no parameters.
 */
public record MethodRef(
    String qualifiedName,
    String returnTypeName,
    List<ParamInfo> params
) {}
