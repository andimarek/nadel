package graphql.nadel.engine


import graphql.GraphQLError
import graphql.execution.nextgen.ExecutionHelper
import graphql.nadel.DefinitionRegistry
import graphql.nadel.ServiceExecution
import graphql.nadel.StrategyTestHelper
import graphql.nadel.dsl.ServiceDefinition
import graphql.nadel.hooks.ServiceExecutionHooks
import graphql.nadel.instrumentation.NadelInstrumentation
import graphql.nadel.result.ResultComplexityAggregator
import graphql.nadel.testutils.TestUtil

class NadelExecutionStrategyTest3 extends StrategyTestHelper {

    ExecutionHelper executionHelper
    def service1Execution
    def service2Execution
    def serviceDefinition
    def definitionRegistry
    def instrumentation
    def serviceExecutionHooks
    def resultComplexityAggregator

    void setup() {
        executionHelper = new ExecutionHelper()
        service1Execution = Mock(ServiceExecution)
        service2Execution = Mock(ServiceExecution)
        serviceDefinition = ServiceDefinition.newServiceDefinition().build()
        definitionRegistry = Mock(DefinitionRegistry)
        instrumentation = new NadelInstrumentation() {}
        serviceExecutionHooks = new ServiceExecutionHooks() {}
        resultComplexityAggregator = new ResultComplexityAggregator()
    }


    def "renamed and hydrated query using same underlying source"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([
                Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                 renamedField: String => renamed from issue.field
                 details: [Detail] => hydrated from Foo.detail(detailIds: $source.issue.fooId)
              }
              type Detail {
                 detailId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                foo: Foo 
                detail(detailIds: [ID]): Detail
              } 
              type Foo {
                field: String
                fooId: ID
                issue: Issue
              }
              
              type Issue {
                fooId: ID
                field: String
              }
              type Detail {
                 detailId: ID!
                 name: String
              }
        """)
        def query = """{ foo {renamedField details { name}}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {issue {field} issue {fooId}}}"
        def response1 = [foo: [issue: [field: "field", fooId: "ID"]]]
        def expectedQuery2 = "query nadel_2_Foo {detail(detailIds:\"ID\") {name}}"
        def response2 = [detail: [name: "apple"]]
        def overallResponse = [foo: [renamedField: "field", details: [name: "apple"]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1, expectedQuery2],
                [response1, response2],
                2,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "renamed field with normal field using same source"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                 renamedField: String => renamed from issue.field
                 details: [Detail] => hydrated from Foo.detail(detailIds: $source.issue.fooId)
                 issue: Issue
              }
              type Issue {
                fooDetail: Detail
              }

              type Detail {
                 detailId: ID!
                 name: String
              }
        }
        '''])
        def underlyingSchema = TestUtil.schema("""
              type Query {
                foo: Foo 
                detail(detailIds: [ID]): Detail
              } 
              type Foo {
                field: String
                issue: Issue
              }
              
              type Issue {
                fooDetail: Detail
                field: String
              }
              type Detail {
                 detailId: ID!
                 name: String
              }
        """)
        def query = """{ foo {  issue {fooDetail {name}} renamedField}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {issue {fooDetail {name}} issue {field}}}"
        def response1 = [foo: [issue: [field: "field", fooDetail: [name: "fooName"]]]]
        def overallResponse = [foo: [issue: [fooDetail: [name: "fooName"]], renamedField: "field"]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1],
                [response1],
                1,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "same source for 2 hydrations"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                 issue: Issue => hydrated from Foo.issue(issueId: $source.fooId)
                 detail: Detail => hydrated from Foo.detail(detailId: $source.fooId)
              }
              type Detail {
                 detailId: ID!
                 name: String
              }
              type Issue {
                fooId: ID
                field: String
              }

        }
        '''])
        def underlyingSchema = TestUtil.schema("""
          type Query {
            foo: Foo 
            detail(detailId: ID): Detail
            issue(issueId: ID): Issue
          } 
          type Foo {
            field: String
            fooId: ID
            issue: Issue
          }
          
          type Issue {
            fooId: ID
            field: String
          }
          type Detail {
             detailId: ID!
             name: String
          }
    """)
        def query = """{ foo {issue {field} detail { name}}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {fooId fooId}}"
        def response1 = [foo: [fooId: "ID"]]
        def expectedQuery2 = "query nadel_2_Foo {issue(issueId:\"ID\") {field}}"
        def response2 = [issue: [field: "field_name"]]
        def expectedQuery3 = "query nadel_2_Foo {detail(detailId:\"ID\") {name}}"
        def response3 = [detail: [name: "apple"]]

        def overallResponse = [foo: [issue: [field: "field_name"], detail: [name: "apple"]]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1, expectedQuery2, expectedQuery3],
                [response1, response2, response3],
                3,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "same source for 2 nested hydrations and a rename"() {
        given:
        def overallSchema = TestUtil.schemaFromNdsl([Foo:'''
        service Foo {
              type Query {
                foo: Foo
              } 
              type Foo {
                 renamedField: String => renamed from issue.field
                 issue: Issue => hydrated from Foo.issue(issueId: $source.issue.fooId)
                 detail: Detail => hydrated from Foo.detail(detailId: $source.issue.fooId)
              }
              type Detail {
                 detailId: ID!
                 name: String
              }
              type Issue {
                fooId: ID
                field: String
              }

        }
        '''])
        def underlyingSchema = TestUtil.schema("""
          type Query {
            foo: Foo 
            detail(detailId: ID): Detail
            issue(issueId: ID): Issue
          } 
          type Foo {
            field: String
            fooId: ID
            issue: Issue
          }
          
          type Issue {
            fooId: ID
            field: String
          }
          type Detail {
             detailId: ID!
             name: String
          }
    """)
        def query = """{ foo {issue {field} detail { name} renamedField}}"""

        def expectedQuery1 = "query nadel_2_Foo {foo {issue {fooId} issue {fooId} issue {field}}}"
        def response1 = [foo: [issue: [fooId: "ID", field: "field1"]]]
        def expectedQuery2 = "query nadel_2_Foo {issue(issueId:\"ID\") {field}}"
        def response2 = [issue: [field: "field_name"]]
        def expectedQuery3 = "query nadel_2_Foo {detail(detailId:\"ID\") {name}}"
        def response3 = [detail: [name: "apple"]]

        def overallResponse = [foo: [issue: [field: "field_name"], detail: [name: "apple"], renamedField: "field1"]]


        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1ServiceWithNHydration(
                overallSchema,
                "Foo",
                underlyingSchema,
                query,
                ["foo"],
                [expectedQuery1, expectedQuery2, expectedQuery3],
                [response1, response2, response3],
                3,
                resultComplexityAggregator
        )
        then:
        errors.size() == 0
        response == overallResponse
    }


}
