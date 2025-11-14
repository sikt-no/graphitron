package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Response0;
import java.lang.String;

public class Response0TypeMapper {
    public static Response0 recordToGraphType(String _mi_response0Record, String _iv_path,
                                              RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_select = _iv_transform.getSelect();
        var _mo_response0 = new Response0();

        if (_iv_select.contains(_iv_pathHere + "id")) {
            _mo_response0.setId(_mi_response0Record);
        }


        return _mo_response0;
    }
}
