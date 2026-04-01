package no.sikt.graphitron.record.field;

import java.util.List;

/**
 * A resolved reference to a Java method, with reflection data captured at parse time.
 *
 * <p>{@code qualifiedName} is always set when a condition is present in the schema — it is the
 * fully qualified method name derived from the {@code ExternalCodeReference} input object, e.g.
 * {@code "com.example.CustomerConditions.activeCustomers"}.
 *
 * <p>{@code returnTypeName} is the fully qualified return type of the method (e.g.
 * {@code "org.jooq.Condition"}); {@code null} when the method could not be resolved via reflection.
 *
 * <p>{@code params} is the list of parameters in declaration order; {@code null} when the method
 * could not be resolved. An empty list means the method was found and takes no parameters.
 */
public record MethodRef(
    String qualifiedName,
    String returnTypeName,
    List<ParamInfo> params
) {}
