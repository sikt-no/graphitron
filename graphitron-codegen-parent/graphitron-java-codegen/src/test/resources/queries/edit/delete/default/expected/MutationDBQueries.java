package fake.code.generated.queries.;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import java.lang.String;
import fake.graphql.example.model.CustomerNodeInputTable;
import no.sikt.graphql.NodeIdStrategy;
import org.jooq.DSLContext;
import no.sikt.graphql.helpers.selection.SelectionSet;

public class MutationDBQueries {
    public static String mutationForMutation(DSLContext ctx, NodeIdStrategy nodeIdStrategy, CustomerNodeInputTable in, SelectionSet select) {
        return ctx.deleteFrom(CUSTOMER)
                .where(nodeIdStrategy.hasId("CustomerNode", in.getId(), CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))
                .returningResult(nodeIdStrategy.createId("CustomerNode", CUSTOMER.fields(CUSTOMER.getPrimaryKey().getFieldsArray())))
                .fetchOne(it -> it.into(String.class));
    }
}
