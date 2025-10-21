package fake.code.generated.queries.;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import java.lang.String;
import fake.graphql.example.model.CustomerNodeInputTable;
import no.sikt.graphql.NodeIdStrategy;
import org.jooq.DSLContext;
import no.sikt.graphql.helpers.selection.SelectionSet;

public class MutationDBQueries {
    public static String mutationForMutation(DSLContext _iv_ctx, NodeIdStrategy _iv_nodeIdStrategy, CustomerNodeInputTable in, SelectionSet _iv_select) {
        return _iv_ctx.deleteFrom(CUSTOMER)
                .where(_iv_nodeIdStrategy.hasId("CustomerNode", in.getId(), CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))
                .returningResult(_iv_nodeIdStrategy.createId("CustomerNode", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))
                .fetchOne(_iv_it -> _iv_it.into(String.class));
    }
}
