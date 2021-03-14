package graphql.nadel.engine

import graphql.execution.ResultPath
import graphql.nadel.result.ExecutionResultNode
import spock.lang.Specification

class PathMapperTest extends Specification {
    def "it uses the parent path in the output"() {
        given:
        def mapper = new PathMapper()
        def parentPath = ResultPath.parse("/top")
        def childPath = ResultPath.parse("/unexpected/sub")
        def parentNode = Mock(ExecutionResultNode)
        def environment = new UnapplyEnvironment(parentNode, parentNode,false, false, null, null)
        1 * parentNode.getResultPath() >> parentPath

        when:
        def result = mapper.mapPath(childPath, "sub", environment)

        then:
        result.toString() == "/top/sub"
    }

    def "it keeps the index segment from the child path"() {
        given:
        def mapper = new PathMapper()
        def parentPath = ResultPath.parse("/top/sub")
        def childPath = ResultPath.parse("/unexpected/sub[0]")
        def parentNode = Mock(ExecutionResultNode)
        def environment = new UnapplyEnvironment(parentNode, parentNode,false, false, null, null)
        1 * parentNode.getResultPath() >> parentPath

        when:
        def result = mapper.mapPath(childPath, "sub", environment)

        then:
        result.toString() == "/top/sub[0]"
    }

    def "it replaces the last segment with the result key"() {
        given:
        def mapper = new PathMapper()
        def parentPath = ResultPath.parse("/top")
        def childPath = ResultPath.parse("/top/sub")
        def parentNode = Mock(ExecutionResultNode)
        def environment = new UnapplyEnvironment(parentNode, parentNode,false, false, null, null)
        1 * parentNode.getResultPath() >> parentPath

        when:
        def result = mapper.mapPath(childPath, "key", environment)

        then:
        result.toString() == "/top/key"
    }

    def "it replaces the last string segment with the result key"() {
        given:
        def mapper = new PathMapper()
        def parentPath = ResultPath.parse("/top/sub")
        def childPath = ResultPath.parse("/top/sub[0]")
        def parentNode = Mock(ExecutionResultNode)
        def environment = new UnapplyEnvironment(parentNode, parentNode,false, false, null, null)
        1 * parentNode.getResultPath() >> parentPath

        when:
        def result = mapper.mapPath(childPath, "key", environment)

        then:
        result.toString() == "/top/key[0]"
    }
}
