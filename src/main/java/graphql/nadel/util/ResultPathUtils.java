package graphql.nadel.util;

import graphql.Internal;
import graphql.execution.ResultPath;

import java.util.List;

@Internal
public class ResultPathUtils {

    /**
     * Returns true if the path ends with an integer segment eg /a/b[0]
     *
     * @param path the path to check
     *
     * @return true if its a list ending path
     */
    @SuppressWarnings("RedundantIfStatement")
    public static boolean isListEndingPath(ResultPath path) {
        List<Object> segments = path.toList();
        if (segments.isEmpty()) {
            return false;
        }
        if (segments.get(segments.size() - 1) instanceof String) {
            return false;
        }
        return true;
    }

    public static ResultPath removeLastSegment(ResultPath path) {
        List<Object> segments = path.toList();
        if (segments.isEmpty()) {
            return path;
        }
        segments = segments.subList(0, segments.size() - 1);
        return ResultPath.fromList(segments);
    }
}
