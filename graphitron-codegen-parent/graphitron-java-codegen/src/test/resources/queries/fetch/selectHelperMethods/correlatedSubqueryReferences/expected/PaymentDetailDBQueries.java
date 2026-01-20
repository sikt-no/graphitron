import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;

import fake.graphql.example.model.Rental;
import fake.graphql.example.model.Staff;
import fake.graphql.example.model.StaffName;
import fake.graphql.example.model.Store;
import org.jooq.Functions;
import org.jooq.SelectField;
import org.jooq.impl.DSL;

public class PaymentDetailDBQueries {
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
