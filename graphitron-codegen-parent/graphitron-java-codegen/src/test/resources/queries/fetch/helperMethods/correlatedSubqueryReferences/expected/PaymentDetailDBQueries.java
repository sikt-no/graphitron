import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Rental;
import fake.graphql.example.model.Staff;
import fake.graphql.example.model.StaffName;
import fake.graphql.example.model.Store;
import java.lang.Long;
import java.util.Map;
import java.util.Set;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.Row1;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class PaymentDetailDBQueries {
    public static Map<Row1<Long>, Rental> rentalForPaymentDetail(DSLContext _iv_ctx,
            Set<Row1<Long>> _rk_paymentDetail, SelectionSet _iv_select) {
        var _a_payment = PAYMENT.as("payment_1831371789");
        var _a_payment_1831371789_rental = _a_payment.rental().as("rental_2757859610");
        var _iv_orderFields = _a_payment_1831371789_rental.fields(_a_payment_1831371789_rental.getPrimaryKey().getFieldsArray());
        return _iv_ctx
                .select(
                        DSL.row(_a_payment.PAYMENT_ID),
                        DSL.field(
                                DSL.select(rentalForPaymentDetail_rental())
                                .from(_a_payment_1831371789_rental)

                        )
                )
                .from(_a_payment)
                .where(DSL.row(_a_payment.PAYMENT_ID).in(_rk_paymentDetail))
                .fetchMap(_iv_r -> _iv_r.value1().valuesRow(), Record2::value2);
    }

    private static SelectField<Rental> rentalForPaymentDetail_rental() {
        var _a_payment = PAYMENT.as("payment_1831371789");
        var _a_payment_1831371789_rental = _a_payment.rental().as("rental_2757859610");
        var _a_rental_staff = RENTAL.as("rental_2416434249");
        var _a_rental_staff_rentalstaff_staff = STAFF.as("staff_4280024544");
        return DSL.row(
                        DSL.field(
                                DSL.select(_1_rentalForPaymentDetail_rental_staff())
                                .from(_a_rental_staff)
                                .join(_a_rental_staff_rentalstaff_staff)
                                .on(no.sikt.graphitron.codereferences.conditions.PaymentCondition.rentalStaff(_a_rental_staff, _a_rental_staff_rentalstaff_staff))
                                .where(_a_payment_1831371789_rental.RENTAL_ID.eq(_a_rental_staff.RENTAL_ID))

                        )
                ).mapping(Rental::new);
    }

    private static SelectField<Staff> _1_rentalForPaymentDetail_rental_staff() {
        var _a_payment = PAYMENT.as("payment_1831371789");
        var _a_payment_1831371789_rental = _a_payment.rental().as("rental_2757859610");
        var _a_rental_staff = RENTAL.as("rental_2416434249");
        var _a_store = STORE.as("store_4283914359");
        return DSL.row(
                        DSL.field(
                                DSL.select(_2_rentalForPaymentDetail_rental_staff_store())
                                .from(_a_store)
                                .where(_a_payment_1831371789_rental.RENTAL_ID.eq(_a_rental_staff.RENTAL_ID))

                        )
                ).mapping(Functions.nullOnAllNull(Staff::new));
    }

    private static SelectField<Store> _2_rentalForPaymentDetail_rental_staff_store() {
        var _a_payment = PAYMENT.as("payment_1831371789");
        var _a_payment_1831371789_rental = _a_payment.rental().as("rental_2757859610");
        var _a_rental_staff = RENTAL.as("rental_2416434249");
        var _a_store = STORE.as("store_4283914359");
        var _a_store_4283914359_staff = _a_store.staff().as("staff_3749272683");
        return DSL.row(
                        _a_store.STORE_ID,
                        DSL.row(
                                DSL.field(
                                        DSL.select(_a_store_4283914359_staff.LAST_NAME)
                                        .from(_a_store_4283914359_staff)
                                        .where(_a_payment_1831371789_rental.RENTAL_ID.eq(_a_rental_staff.RENTAL_ID))

                                )
                        ).mapping(Functions.nullOnAllNull(StaffName::new))
                ).mapping(Functions.nullOnAllNull(Store::new));
    }
}
