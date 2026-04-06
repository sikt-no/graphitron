package no.sikt.graphitron.rewrite.generators.lookup;

import java.util.List;

/**
 * All data needed to generate the lookup methods for one {@code LookupQueryField} whose argument
 * is a {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableInputType}.
 *
 * <p>{@code typeName} is the return type name (e.g. {@code "Customer"}), used to name the
 * generated class ({@code CustomerLookup}).
 *
 * <p>{@code tableJavaFieldName} is the Java field name in the generated jOOQ {@code Tables}
 * class (e.g. {@code "CUSTOMER"}), used for references like {@code CUSTOMER.CUSTOMER_ID}.
 *
 * <p>{@code fields} is the ordered list of input fields to include in the generated record,
 * one per non-{@code @notGenerated} field in the input type.
 */
public record LookupSpec(
    String typeName,
    String tableJavaFieldName,
    List<LookupInputFieldSpec> fields
) {}
