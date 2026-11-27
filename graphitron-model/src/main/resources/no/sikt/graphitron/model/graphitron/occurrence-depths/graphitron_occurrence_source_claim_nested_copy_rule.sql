SELECT DISTINCT o.graph_name, o.coordinate, 'nestedCopy' AS rule, 'TABLE' AS kind, pt.table_source_name, pt.table_schema, pt.table_name
  FROM graphitron_occurrence o
  JOIN graphitron_occurrence p ON p.graph_name = o.graph_name AND p.coordinate = o.parent_coordinate
  JOIN graphitron_field pf ON pf.graph_name = p.graph_name AND pf.coordinate = p.element_coordinate
  JOIN graphitron_occurrence_source_table pt ON pt.graph_name = p.graph_name AND pt.coordinate = p.coordinate
  JOIN graphitron_field cf ON cf.graph_name = o.graph_name AND cf.coordinate = o.element_coordinate
 WHERE o.kind = 'FIELD' AND p.kind = 'FIELD' AND NOT EXISTS (SELECT 1 FROM graphitron_join_path_claim wpc
                 WHERE wpc.graph_name = pf.graph_name AND wpc.element_coordinate = pf.coordinate
                   AND wpc.claim_kind = 'FIELD' AND wpc.last_place >= wpc.first_place)
   AND NOT EXISTS (SELECT 1 FROM graphitron_claim pc
                     WHERE pc.graph_name = pf.graph_name AND pc.coordinate = pf.coordinate
                       AND pc.kind = 'SERVICE')
   AND NOT EXISTS (SELECT 1 FROM graphitron_code_reference xr
                     JOIN code_method xm
                       ON xm.source_name = xr.method_source_name AND xm.class_name = xr.method_class_name
                      AND xm.method_name = xr.method_name AND xm.descriptor = xr.method_descriptor
                    WHERE xr.graph_name = pf.graph_name AND xr.coordinate = pf.coordinate
                      AND xr.directive_name = 'externalField' AND xr.kind = 'ROOT'
                      AND xm.result_first_argument_class IS NOT NULL)
   AND NOT EXISTS (SELECT 1 FROM graphitron_errors_field ef
                     WHERE ef.graph_name = cf.graph_name AND ef.type_name = cf.type_name
                       AND ef.field_name = cf.field_name)
   AND NOT EXISTS (SELECT 1 FROM graphitron_field_target pw
                     WHERE pw.graph_name = pf.graph_name AND pw.type_name = pf.type_name
                       AND pw.field_name = pf.field_name AND pw.wrapper_type_name IS NOT NULL)
