package fake.code.generated.queries.;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import java.util.Arrays;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import no.sikt.graphql.NodeIdStrategy;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class MutationDBQueries {
    public static int mutationForMutation(DSLContext _iv_ctx, NodeIdStrategy _iv_nodeIdStrategy, CustomerRecord _mi_inRecord) {
        return _iv_ctx.transactionResult(_iv_config -> Arrays.stream(DSL.using(_iv_config).batchUpdate(_mi_inRecord).execute()).sum());
    }
}
