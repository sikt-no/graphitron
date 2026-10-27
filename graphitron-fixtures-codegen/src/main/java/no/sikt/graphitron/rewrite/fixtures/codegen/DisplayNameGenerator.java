package no.sikt.graphitron.rewrite.fixtures.codegen;

import org.jooq.Field;
import org.jooq.Generator;
import org.jooq.GeneratorContext;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * Fixture client-side generator: computes a display name as {@code family_name || ', ' ||
 * given_name} off the row's own table, the shape of a consumer's computed column (a jOOQ
 * {@code <generator>} on a {@code forcedType}, a commercial-distribution feature).
 * {@link NodeIdFixtureGenerator} writes it onto the generated {@code DataType} of the
 * {@code display_name} columns of graphitron-sakila-db's {@code display_name_person} and
 * {@code display_name_account}.
 *
 * <p>The open-source runtime keeps the generator on the {@code DataType} and reports
 * {@code computedOnClient()}, but never runs it, so the column reads as the stored value. Were it
 * run, it reads its columns off {@link GeneratorContext#table()}, so it would fail on a field
 * rendered with no owning table exactly as a consumer's generator does.
 */
public class DisplayNameGenerator implements Generator<Record, Table<Record>, String> {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    @Override
    public Field<String> apply(GeneratorContext<Record, Table<Record>, String> ctx) {
        return DSL.concat(
            ctx.table().field("family_name", String.class),
            DSL.inline(", "),
            ctx.table().field("given_name", String.class));
    }
}
