package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Payment;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static Payment paymentForQuery(DSLContext ctx, String id, String name, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                PAYMENT.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Payment::new)).as("payment")
                )
                .from(PAYMENT)
                .where(PAYMENT.ID.eq(id))
                .and(name != null ? PAYMENT.rental().inventory().film().filmOriginalLanguageIdFkey().NAME.eq(name) : DSL.noCondition())
                .fetchOne(it -> it.into(Payment.class));
    }
}
