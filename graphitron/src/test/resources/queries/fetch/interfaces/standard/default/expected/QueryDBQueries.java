package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Customer;
import fake.graphql.example.model.SomeInterface;
import java.lang.String;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.apache.commons.lang3.NotImplementedException;
import org.jooq.DSLContext;
import org.jooq.JSON;
import org.jooq.Record2;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSeekStepN;

public class QueryDBQueries {

    public static SomeInterface someInterfaceForQuery(DSLContext ctx, String id, SelectionSet select) {
        throw new NotImplementedException();
    }

    private static SelectSeekStepN<Record2<String, JSON>> customerSortFieldsForSomeInterface() {
        throw new NotImplementedException();
    }

    private static SelectJoinStep<Record2<JSON, Customer>> customerForSomeInterface() {
        throw new NotImplementedException();
    }
}
