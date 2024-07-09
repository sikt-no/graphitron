package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Film;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class PaymentDBQueries {
    public static Map<String, Film> filmForPayment(DSLContext ctx, Set<String> paymentIds,
                                            SelectionSet select) {
        return ctx
                .select(
                        PAYMENT.getId(),
                        DSL.row(
                                PAYMENT.rental().inventory().film().getId(),
                                select.optional("title", PAYMENT.rental().inventory().film().TITLE)
                        ).mapping(Functions.nullOnAllNull(Film::new))
                )
                .from(PAYMENT)
                .where(PAYMENT.hasIds(paymentIds))
                .orderBy(PAYMENT.rental().inventory().film().getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}