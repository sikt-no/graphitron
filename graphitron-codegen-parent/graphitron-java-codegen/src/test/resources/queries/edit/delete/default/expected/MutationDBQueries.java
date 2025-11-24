package fake.code.generated.queries.;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import java.lang.String;
import fake.graphql.example.model.CustomerInput;
import no.sikt.graphql.NodeIdStrategy;
import org.jooq.DSLContext;
import no.sikt.graphql.helpers.selection.SelectionSet;

public class MutationDBQueries {
    public static String mutationForMutation(DSLContext _iv_ctx, NodeIdStrategy _iv_nodeIdStrategy, CustomerInput _mi_in, SelectionSet _iv_select) {
        return _iv_ctx.deleteFrom(CUSTOMER)
                .where(CUSTOMER.CUSTOMER_ID.eq(_mi_in.getCustomerId()))
                .returningResult(_iv_nodeIdStrategy.createId("CustomerNode", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))
                .fetchOne(_iv_it -> _iv_it.into(String.class));
    }
}
