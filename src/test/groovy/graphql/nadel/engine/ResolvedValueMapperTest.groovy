package graphql.nadel.engine

import graphql.Scalars
import graphql.execution.ResultPath
import graphql.introspection.Introspection
import graphql.nadel.result.ExecutionResultNode
import graphql.nadel.result.LeafExecutionResultNode
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLSchema
import spock.lang.Specification

class ResolvedValueMapperTest extends Specification {
    def mapper = new ResolvedValueMapper()

    def "changes __typename field value accordingly"(String given, String expected) {
        given:
        def mapping = ["Underlying": "Exposed", "A": "B", "C": "D"]
        def unapplyEnvironment = new UnapplyEnvironment(Mock(ExecutionResultNode), false, false, mapping, Mock(GraphQLSchema))
        def node = LeafExecutionResultNode.newLeafExecutionResultNode()
                .executionPath(ResultPath.rootPath())
                .completedValue(given)
                .fieldDefinition(Introspection.TypeNameMetaFieldDef)
                .build()

        when:
        def mapped = mapper.mapCompletedValue(node, unapplyEnvironment)

        then:
        mapped.completedValue == expected

        where:
        given        | expected
        "Underlying" | "Exposed"
        "A"          | "B"
        "B"          | "B"
        "C"          | "D"
        "D"          | "D"
        "Unmapped"   | "Unmapped"
    }

    def "leaves non __typename fields alone"() {
        given:
        def mapping = ["Underlying": "Exposed", "A": "B", "C": "D"]
        def unapplyEnvironment = new UnapplyEnvironment(Mock(ExecutionResultNode), false, false, mapping, Mock(GraphQLSchema))
        def node = LeafExecutionResultNode.newLeafExecutionResultNode()
                .executionPath(ResultPath.rootPath())
                .completedValue("Underlying")
                .fieldDefinition(GraphQLFieldDefinition.newFieldDefinition().name("__someField").type(Scalars.GraphQLString).build())
                .build()

        when:
        def mapped = mapper.mapCompletedValue(node, unapplyEnvironment)

        then:
        mapped == node
        mapped.completedValue == "Underlying"
    }

}
