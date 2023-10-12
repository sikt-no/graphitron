package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Language;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class PaymentDBQueries {
    public Map<String, Language> originalLanguageForPayment(DSLContext ctx, Set<String> paymentIds,
                                                            SelectionSet select) {
        return ctx
                .select(
                        PAYMENT.getId(),
                        DSL.row(
                                PAYMENT.rental().inventory().film().filmOriginalLanguageIdFkey().getId().as("id"),
                                select.optional("name", PAYMENT.rental().inventory().film().filmOriginalLanguageIdFkey().NAME).as("name")
                        ).mapping(Functions.nullOnAllNull(Language::new)).as("originalLanguage")
                )
                .from(PAYMENT)
                .where(PAYMENT.hasIds(paymentIds))
                .fetchMap(Record2::value1, Record2::value2);
    }
}