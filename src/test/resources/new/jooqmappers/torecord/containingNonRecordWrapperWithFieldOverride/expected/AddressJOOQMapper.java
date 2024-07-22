package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.AddressRecord;

public class AddressJOOQMapper {
    public static List<AddressRecord> toJOOQRecord(List<Address> address, String path,
                                                   RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var addressRecordList = new ArrayList<AddressRecord>();

        if (address != null) {
            for (var itAddress : address) {
                if (itAddress == null) continue;
                var addressRecord = new AddressRecord();
                addressRecord.attach(ctx.configuration());
                var address_inner1 = itAddress.getInner1();
                if (address_inner1 != null) {
                    if (arguments.contains(pathHere + "inner1/code")) {
                        addressRecord.setPostalCode(address_inner1.getCode());
                    }

                }

                var address_inner2 = itAddress.getInner2();
                if (address_inner2 != null) {
                    if (arguments.contains(pathHere + "inner2/code")) {
                        addressRecord.setPostalCode(address_inner2.getCode());
                    }

                }

                addressRecordList.add(addressRecord);
            }
        }

        return addressRecordList;
    }
}
