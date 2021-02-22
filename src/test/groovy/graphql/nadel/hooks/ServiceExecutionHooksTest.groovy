package graphql.nadel.hooks

import graphql.ExecutionInput
import graphql.execution.ExecutionId
import graphql.execution.nextgen.ExecutionHelper
import graphql.language.Argument
import graphql.language.AstTransformer
import graphql.language.Field
import graphql.language.Node
import graphql.language.NodeVisitorStub
import graphql.language.StringValue
import graphql.nadel.DefinitionRegistry
import graphql.nadel.FieldInfo
import graphql.nadel.FieldInfos
import graphql.nadel.Service
import graphql.nadel.ServiceExecution
import graphql.nadel.ServiceExecutionParameters
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.dsl.ServiceDefinition
import graphql.nadel.engine.HooksVisitArgumentValueEnvironment
import graphql.nadel.engine.NadelContext
import graphql.nadel.engine.NadelExecutionStrategy
import graphql.nadel.engine.ResultNodesTransformer
import graphql.nadel.instrumentation.NadelInstrumentation
import graphql.nadel.normalized.NormalizedQueryFactory
import graphql.nadel.result.ExecutionResultNode
import graphql.nadel.result.LeafExecutionResultNode
import graphql.nadel.result.ResultComplexityAggregator
import graphql.nadel.result.RootExecutionResultNode
import graphql.nadel.testutils.ExecutionResultNodeUtil
import graphql.nadel.testutils.TestUtil
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLSchema
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TraverserVisitor
import graphql.util.TreeTransformerUtil
import spock.lang.Specification

import java.util.concurrent.CompletableFuture

import static graphql.language.AstPrinter.printAstCompact
import static graphql.nadel.testutils.TestUtil.parseQuery
import static java.util.concurrent.CompletableFuture.completedFuture

class ServiceExecutionHooksTest extends Specification {

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
        resultComplexityAggregator = new ResultComplexityAggregator();
    }

    FieldInfos topLevelFieldInfo(GraphQLFieldDefinition fieldDefinition, Service service) {
        FieldInfo fieldInfo = new FieldInfo(FieldInfo.FieldKind.TOPLEVEL, service, fieldDefinition)
        return new FieldInfos([(fieldDefinition): fieldInfo])
    }

    ExecutionHelper.ExecutionData createExecutionData(String query, GraphQLSchema overallSchema) {
        createExecutionData(query, [:], overallSchema)
    }

    ExecutionHelper.ExecutionData createExecutionData(String query, Map<String, Object> variables, GraphQLSchema overallSchema) {
        def document = parseQuery(query)
        def normalizedQuery = new NormalizedQueryFactory().createNormalizedQuery(overallSchema, document, null, variables)

        def nadelContext = NadelContext.newContext()
                .artificialFieldsUUID("UUID")
        .normalizedOverallQuery(normalizedQuery)
                .build()
        def executionInput = ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables)
                .context(nadelContext)
                .build()
        ExecutionHelper.ExecutionData executionData = executionHelper.createExecutionData(document, overallSchema, ExecutionId.generate(), executionInput, null)
        executionData
    }

    def "service context created and arguments modified"() {
        given:
        def underlyingSchema = TestUtil.schema("""
        type Query {
            foo(id: String): String  
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([service1:"""
        service service1 {
            type Query {
                foo(id: String): String
            }
        }
        """])
        def fooFieldDefinition = overallSchema.getQueryType().getFieldDefinition("foo")

        def service = new Service("service", underlyingSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, service)

        def serviceContext = "Service-Context"

        def serviceExecutionHooks = new ServiceExecutionHooks() {

            @Override
            CompletableFuture<Object> createServiceContext(CreateServiceContextParams params) {
                return completedFuture(serviceContext)
            }

            @Override
            NewVariableValue visitArgumentValueInQuery(HooksVisitArgumentValueEnvironment env) {
                if (env.getUnderlyingInputValueDefinition().getName() == "id") {
                    StringValue newValue = StringValue.newStringValue().value("modified").build()
                    TreeTransformerUtil.changeNode(env.getTraverserContext(), newValue);
                }
                return null;
            }

            CompletableFuture<QueryRewriteResult> queryRewrite(QueryRewriteParams params) {

                def newDoc = new AstTransformer().transformParallel(params.getDocument(), new NodeVisitorStub() {
                    @Override
                    TraversalControl visitField(Field field, TraverserContext<Node> context) {
                        if (field.getName() == "foo") {
                            def arguments = [Argument.newArgument("id", StringValue.newStringValue("modified").build()).build()]
                            field = field.transform({ b -> b.arguments(arguments) })
                            return TreeTransformerUtil.changeNode(context, field)
                        }
                        return TraversalControl.CONTINUE
                    }
                })
                def rewriteResult = QueryRewriteResult.newResult().document(newDoc).build()
                return completedFuture(rewriteResult)
            }
        }
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)

        def query = "{foo(id: \"fullID\")}"
        def (executionContext, fieldSubSelection) = TestUtil.executionData(overallSchema, parseQuery(query))

        def expectedQuery = "query nadel_2_service {foo(id:\"modified\")}"

        when:
        nadelExecutionStrategy.execute(executionContext, fieldSubSelection, resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ it ->
            assert printAstCompact(it.query) == expectedQuery
            assert it.serviceContext == serviceContext
            true
        } as ServiceExecutionParameters) >> completedFuture(new ServiceExecutionResult(null))
    }

    def "service context created and arguments modified with variable reference AST"() {
        given:
        def underlyingSchema = TestUtil.schema("""
        type Query {
            foo(id: String): String  
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([service1:"""
        service service1 {
            type Query {
                foo(id: String): String
            }
        }
        """])
        def fooFieldDefinition = overallSchema.getQueryType().getFieldDefinition("foo")

        def service = new Service("service", underlyingSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, service)

        def serviceContext = "Service-Context"

        def serviceExecutionHooks = new ServiceExecutionHooks() {
            @Override
            CompletableFuture<Object> createServiceContext(CreateServiceContextParams params) {
                return completedFuture(serviceContext)
            }

            @Override
            NewVariableValue visitArgumentValueInQuery(HooksVisitArgumentValueEnvironment env) {
                NewVariableValue newVariableValue = new NewVariableValue("variable", "123")
                return newVariableValue
            }

        }
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)

        def query = 'query q($variable : String) { foo(id: $variable) }'
        def executionData = createExecutionData(query, [variable: "abc"], overallSchema)

        def expectedQuery = 'query nadel_2_service($variable:String) {foo(id:$variable)}'

        when:
        nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute({ it ->
            assert printAstCompact(it.query) == expectedQuery
            assert it.serviceContext == serviceContext
            //
            // you can add all the extra variables you like but Nadel will only pass on ones
            // that are referenced by your part of the query
            assert it.variables == ["variable": "123"]

            true
        } as ServiceExecutionParameters) >> completedFuture(new ServiceExecutionResult(null))
    }

    def "service result can be modified"() {
        given:
        def underlyingSchema = TestUtil.schema("""
        type Query {
            foo: String  
        }
        """)

        def overallSchema = TestUtil.schemaFromNdsl([service1:"""
        service service1 {
            type Query {
                foo: String
            }
        }
        """])
        def fooFieldDefinition = overallSchema.getQueryType().getFieldDefinition("foo")

        def service = new Service("service", underlyingSchema, service1Execution, serviceDefinition, definitionRegistry)
        def fieldInfos = topLevelFieldInfo(fooFieldDefinition, service)

        def serviceContext = "Service-Context"

        def serviceExecutionHooks = new ServiceExecutionHooks() {
            @Override
            CompletableFuture<Object> createServiceContext(CreateServiceContextParams params) {
                return completedFuture(serviceContext)
            }

            @Override
            CompletableFuture<RootExecutionResultNode> resultRewrite(ResultRewriteParams params) {
                def resultNode = params.resultNode

                def transformer = new ResultNodesTransformer()
                def result = transformer.transform(resultNode, new TraverserVisitor<ExecutionResultNode>() {
                    @Override
                    TraversalControl enter(TraverserContext<ExecutionResultNode> context) {
                        if (context.thisNode() instanceof LeafExecutionResultNode) {
                            LeafExecutionResultNode leafExecutionResultNode = context.thisNode()
                            def completedValue = leafExecutionResultNode.getCompletedValue()
                            def newNode = leafExecutionResultNode.withNewCompletedValue(completedValue + "-CHANGED")
                            return TreeTransformerUtil.changeNode(context, newNode)
                        }
                        return TraversalControl.CONTINUE
                    }

                    @Override
                    TraversalControl leave(TraverserContext<ExecutionResultNode> context) {
                        return TraversalControl.CONTINUE
                    }
                })
                return completedFuture(result)
            }
        }
        NadelExecutionStrategy nadelExecutionStrategy = new NadelExecutionStrategy([service], fieldInfos, overallSchema, instrumentation, serviceExecutionHooks)

        def query = "{foo}"
        def executionData = createExecutionData(query, overallSchema)

        def data = [foo: "hello world"]

        when:
        def response = nadelExecutionStrategy.execute(executionData.executionContext, executionHelper.getFieldSubSelection(executionData.executionContext), resultComplexityAggregator)


        then:
        1 * service1Execution.execute(_) >> completedFuture(new ServiceExecutionResult(data))

        ExecutionResultNodeUtil.toData(response.join()) == [foo: "hello world-CHANGED"]
    }
}
