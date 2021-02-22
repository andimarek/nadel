package graphql.nadel.result;

import graphql.Internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


@Internal
public class ResultComplexityAggregator {
    private AtomicInteger totalNodeCount = new AtomicInteger(0);
    private Map<String, Integer> serviceNodeCounts = Collections.synchronizedMap(new LinkedHashMap<>());
    private AtomicInteger totalFieldRenameCount = new AtomicInteger(0);
    private AtomicInteger totalTypeRenameCount = new AtomicInteger(0);

    public int getTotalNodeCount() {
        return totalNodeCount.get();
    }
    public int getFieldRenamesCount() { return totalFieldRenameCount.get(); }
    public int getTypeRenamesCount() { return totalTypeRenameCount.get(); }

    public Map<String, Integer> getServiceNodeCounts() {
        return serviceNodeCounts;
    }

    public int getNodeCountsForService(String serviceName) {
        return serviceNodeCounts.get(serviceName);
    }

    public void incrementServiceNodeCount(String serviceFieldName, int nodeCount) {
        serviceNodeCounts.compute(serviceFieldName, (k, v) -> (v == null) ? nodeCount : v + nodeCount);
        totalNodeCount.getAndAdd(nodeCount);
    }

    public void incrementFieldRenameCount(int fieldRenameCount) {
        totalFieldRenameCount.getAndAdd(fieldRenameCount);
    }

    public void incrementTypeRenameCount(int typeRenameCount) {
        totalTypeRenameCount.getAndAdd(typeRenameCount);
    }

    public Map<String, Object> snapshotResultComplexityData() {

        Map<String, Object> resultComplexityMap = new LinkedHashMap<>();
        resultComplexityMap.put("totalNodeCount", getTotalNodeCount());
        resultComplexityMap.put("serviceNodeCounts", getServiceNodeCounts());
        resultComplexityMap.put("fieldRenamesCount", getFieldRenamesCount());
        resultComplexityMap.put("typeRenamesCount", getTypeRenamesCount());

        return resultComplexityMap;
    }


    @Override
    public String toString() {
        return "ResultComplexityAggregator{" +
                "totalNodeCount=" + totalNodeCount +
                ", serviceNodeCountsMap=" + serviceNodeCounts +
                ", totalFieldRenameCount=" + totalFieldRenameCount +
                ", totalTypeRenameCount=" + totalTypeRenameCount +
                '}';
    }
}
