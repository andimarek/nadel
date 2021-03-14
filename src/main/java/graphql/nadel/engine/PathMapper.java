package graphql.nadel.engine;

import graphql.Internal;
import graphql.execution.ResultPath;

import java.util.ArrayList;
import java.util.List;

@Internal
public class PathMapper {
    /**
     * This function corrects the given {@code executionPath} by taking the parent path
     * and appending the last child segment to it. It also replaces the last String path
     * segment with {@code resultKey}.
     * <p>
     * There are two reasons for using the parent path:
     * <p> <p>
     * Firstly, to fix hydration paths i.e. the {@code executionPath} will be the hydrated
     * field path and the parent path will be where the hydrated object is stitched in and
     * we need to merge those two paths.
     * <p>
     * e.g. if we hydrate {@code reporter} here
     * <pre>{@code
     * type Issue {
     *     reporter: User => hydrated from Users.user(id: $source.reporterId)
     * }
     * }</pre>
     * Then the {@code executionPath} could be {@code /user/name} and the parent path would
     * be {@code /issue/reporter} and the desired path would be {@code /issue/reporter/name}
     * <p><p>
     * Secondly, to fix renamed paths e.g. given the parent path {@code /devOpsRelationships}
     * and given the {@code executionPath} {@code /relationships/nodes}
     * <p>
     * where the field {@code relationships} was renamed to {@code devOpsRelationships}
     * <p>
     * we should take the renamed parent path {@code /devopsRelationships}
     * <p>
     * and append the child path {@code nodes} to it
     *
     * @param executionPath the path to correct
     * @param resultKey     the correct segment name to use
     * @param environment   context for the current path
     * @return the fixed path as described above
     */
    public ResultPath mapPath(ResultPath executionPath, String resultKey, UnapplyEnvironment environment) {
        List<Object> pathSegments = new ArrayList<>(environment.correctParentNode.getResultPath().toList());
        // Add the trailing segment from the child path to the parent path
        pathSegments.add(executionPath.getSegmentValue());

        patchLastFieldName(pathSegments, resultKey);
        return ResultPath.fromList(pathSegments);
    }

    private void patchLastFieldName(List<Object> pathSegments, String resultKey) {
        for (int i = pathSegments.size() - 1; i >= 0; i--) {
            Object segment = pathSegments.get(i);
            if (segment instanceof String) {
                pathSegments.set(i, resultKey);
                break;
            }
        }
    }
}
