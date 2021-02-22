package graphql.nadel.normalized;

import graphql.Internal;
import graphql.execution.MergedField;
import graphql.nadel.dsl.NodeId;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static graphql.Assert.assertNotNull;
import static graphql.Assert.assertNull;

@Internal
public class NormalizedQueryFromAst {

    private final List<NormalizedQueryField> topLevelFields;
    private final Map<String, List<NormalizedQueryField>> normalizedFieldsByFieldId;
    private final Map<NormalizedQueryField, MergedField> mergedFieldByNormalizedFields;

    public NormalizedQueryFromAst(List<NormalizedQueryField> topLevelFields,
                                  Map<String, List<NormalizedQueryField>> normalizedFieldsByFieldId,
                                  Map<NormalizedQueryField, MergedField> mergedFieldByNormalizedFields) {
        this.topLevelFields = topLevelFields;
        this.normalizedFieldsByFieldId = normalizedFieldsByFieldId;
        this.mergedFieldByNormalizedFields = mergedFieldByNormalizedFields;
    }

    public List<NormalizedQueryField> getTopLevelFields() {
        return topLevelFields;
    }

    public Map<String, List<NormalizedQueryField>> getNormalizedFieldsByFieldId() {
        return normalizedFieldsByFieldId;
    }

    public List<NormalizedQueryField> getNormalizedFieldsByFieldId(String astFieldId) {
        return normalizedFieldsByFieldId.getOrDefault(astFieldId, Collections.emptyList());
    }

    public Map<NormalizedQueryField, MergedField> getMergedFieldByNormalizedFields() {
        return mergedFieldByNormalizedFields;
    }

    public List<String> getFieldIds(NormalizedQueryField normalizedQueryField) {
        MergedField mergedField = mergedFieldByNormalizedFields.get(normalizedQueryField);
        return NodeId.getIds(mergedField);
    }

    public NormalizedQueryField getTopLevelField(String topLevelFieldResultKey) {
        NormalizedQueryField topLevelField = null;

        for (NormalizedQueryField candidate : getTopLevelFields()) {
            if (candidate.getResultKey().equals(topLevelFieldResultKey)) {
                assertNull(topLevelField, () -> "Found more than one normalized top level field with the same name");
                topLevelField = candidate;
            }
        }
        assertNotNull(topLevelField, () -> "Could not find top level field");

        return topLevelField;
    }
}
