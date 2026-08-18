package no.sikt.graphitron.mcp.fixtures.schema;

import org.jooq.Condition;
import org.jooq.impl.DSL;

/**
 * The condition class an SDL {@code @condition} names on an input field.
 *
 * <p>The fixture for the method population the store's producer view does not carry: that view is
 * scoped to {@code @service} and {@code @externalField}, so a coordinate whose method comes from a
 * {@code @condition} is reachable only through this module's own read of
 * {@code graphitron_field_condition}.
 */
public class FilmConditions {

    /** Named by the fixture's {@code @condition}; the census resolves the pair to this method. */
    public static Condition titled(String title) {
        return DSL.noCondition();
    }
}
