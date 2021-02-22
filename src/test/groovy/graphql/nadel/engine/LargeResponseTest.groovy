package graphql.nadel.engine

import graphql.ExecutionInput
import graphql.execution.ExecutionId
import graphql.execution.nextgen.ExecutionHelper
import graphql.nadel.DefinitionRegistry
import graphql.nadel.FieldInfo
import graphql.nadel.FieldInfos
import graphql.nadel.Service
import graphql.nadel.ServiceExecution
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.dsl.ServiceDefinition
import graphql.nadel.hooks.ServiceExecutionHooks
import graphql.nadel.instrumentation.NadelInstrumentation
import graphql.nadel.result.ResultComplexityAggregator
import graphql.nadel.result.ResultNodesUtil
import graphql.nadel.result.RootExecutionResultNode
import graphql.nadel.testutils.TestUtil
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLSchema
import groovy.json.JsonSlurper
import spock.lang.Specification

import java.util.concurrent.CompletableFuture

import static graphql.nadel.testutils.TestUtil.createNormalizedQuery
import static graphql.nadel.testutils.TestUtil.parseQuery

class LargeResponseTest extends Specification {

    def executionHelper
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

    def "one call to one service"() {
        given:
        def schema = """
type Query {
    myActivities: MyActivity!
}

interface Node {
    id: ID!
}
enum ActivityEventType {
    ASSIGNED
    UNASSIGNED
    VIEWED
    COMMENTED
    UPDATED
    CREATED
    LIKED
    TRANSITIONED
    PUBLISHED
    EDITED
}

type MyActivity {
    workedOn: ActivityConnection!
    viewed: ActivityConnection!
}

type ActivityConnection {
    nodes: [ActivityItem]!
}

type ActivityItem implements Node {
    id: ID!
    timestamp: String
    eventType: ActivityEventType
    object: ActivityObject
    containers: [ActivityObject]
    contributors: [ActivityContributor]
}

type ActivityObject implements Node {
    id: ID!
    name: String
    cloudID: String
    url: String
    iconURL: String
}

type ActivityContributor {
    profile: ActivityUser
}

type ActivityUser {
    accountId: ID!
    name: String
    picture: String
}

"""
        def underlyingSchema = TestUtil.schema(schema)

        def overallSchema = TestUtil.schemaFromNdsl([activity: "service activity { $schema }"])
        def myActivitiesFieldDefinition = overallSchema.getQueryType().getFieldDefinition("myActivities")

        def service1 = new Service("activity", underlyingSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(myActivitiesFieldDefinition, service1)
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service1], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)


        def query = '''
{
  myActivities {
    workedOn {
      nodes {
        id
        timestamp
        eventType
        object {
          id
          name
          cloudID
          url
          iconURL
        }
        containers {
          name
        }
        contributors {
          profile {
            accountId
            name
            picture
          }
        }
      }
    }
    viewed {
      nodes {
        id
        timestamp
        eventType
        object {
          id
          name
          cloudID
          url
          iconURL
        }
        containers {
          name
        }
        contributors {
          profile {
            accountId
            name
            picture
          }
        }
      }
    }
  }
}
'''
        def resultJson = new File(getClass().getResource('/large_underlying_service_result.json').toURI()).text

        def jsonSlurper = new JsonSlurper()
        def jsonMap = jsonSlurper.parseText(resultJson)

        def response1 = new ServiceExecutionResult(jsonMap.data)

        def executionData = createExecutionData(query, overallSchema)

        service1Execution.execute(_) >> CompletableFuture.completedFuture(response1)

        when:
        long time = System.currentTimeMillis();
        def response
        response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        def data = resultData(response)
        then:
        data.myActivities.viewed.nodes.size == 50
        println "elapsed: ${System.currentTimeMillis() - time}"
    }

    private ExecutionHelper.ExecutionData createExecutionData(String query, GraphQLSchema overallSchema) {
        def document = parseQuery(query)
        def normalizedQuery = createNormalizedQuery(overallSchema, document)

        def nadelContext = NadelContext.newContext().artificialFieldsUUID("UUID")
                .normalizedOverallQuery(normalizedQuery)
                .build()
        def executionInput = ExecutionInput.newExecutionInput().query(query)
                .context(nadelContext)
                .build()
        ExecutionHelper.ExecutionData executionData = executionHelper.createExecutionData(document, overallSchema, ExecutionId.generate(), executionInput, null)
        return executionData;
    }


    Object resultData(CompletableFuture<RootExecutionResultNode> response) {
        ResultNodesUtil.toExecutionResult(response.get()).data
    }

    FieldInfos topLevelFieldInfo(GraphQLFieldDefinition fieldDefinition, Service service) {
        FieldInfo fieldInfo = new FieldInfo(FieldInfo.FieldKind.TOPLEVEL, service, fieldDefinition)
        return new FieldInfos([(fieldDefinition): fieldInfo])
    }


}


