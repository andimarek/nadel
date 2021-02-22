package graphql.nadel.util

import graphql.execution.ResultPath
import spock.lang.Specification
import spock.lang.Unroll

class ResultPathUtilsTest extends Specification {

    @Unroll
    def "list ending paths"() {
        expect:

        def ePath = ResultPath.parse(path)
        ResultPathUtils.isListEndingPath(ePath) == expectedState

        where:
        path         | expectedState
        "/a/b"       | false
        "/a[0]/b"    | false
        ""           | false
        "/a[0]"      | true
        "/a[0]/b[0]" | true
        "/a/b[0]"    | true
    }

    @Unroll
    def "remove ending paths"() {
        expect:

        def ePath = ResultPath.parse(path)
        ResultPathUtils.removeLastSegment(ePath).toString() == expectedState

        where:
        path         | expectedState
        "/a/b"       | "/a"
        "/a[0]/b"    | "/a[0]"
        ""           | ""
        "/a[0]"      | "/a"
        "/a[0]/b[0]" | "/a[0]/b"
        "/a/b[0]"    | "/a/b"
    }
}
