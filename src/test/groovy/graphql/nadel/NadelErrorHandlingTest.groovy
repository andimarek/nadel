package graphql.nadel


import graphql.nadel.testutils.MockServiceExecution
import spock.lang.Specification

import java.util.concurrent.CompletableFuture

import static graphql.nadel.Nadel.newNadel
import static graphql.nadel.NadelExecutionInput.newNadelExecutionInput
import static graphql.nadel.testutils.TestUtil.serviceFactory
import static graphql.nadel.testutils.TestUtil.typeDefinitions
import static java.util.concurrent.CompletableFuture.completedFuture

class NadelErrorHandlingTest extends Specification {

    def simpleNDSL = [MyService: """
         service MyService {
            type Query{
                hello: World  
                helloWithArgs(arg1 : String! arg2 : String) : World
            } 
            type World {
                id: ID
                name: String
            }
            type Mutation{
                hello: String  
            } 
         }
        """]

    def simpleUnderlyingSchema = typeDefinitions("""
            type Query{
                hello: World  
                helloWithArgs(arg1 : String! arg2 : String) : World
            } 
            type World {
                id: ID
                name: String
            }
            type Mutation{
                hello: String  
            } 
        """)

    def delegatedExecution = Mock(ServiceExecution)
    def serviceFactory = serviceFactory(delegatedExecution, simpleUnderlyingSchema)


    def buildNadel() {
        newNadel()
                .dsl(simpleNDSL)
                .serviceExecutionFactory(serviceFactory)
                .build()
    }

    def "errors and some data from a service execution are reflected in the result"() {
        given:
        def query = '''
            { hello { name } }
        '''

        Nadel nadel = buildNadel()

        List<Map<String, Object>> rawErrors = [[message: "Problem1"], [message: "Problem2"]]

        1 * delegatedExecution.execute(_) >> { args ->
            completedFuture(new ServiceExecutionResult(null, rawErrors))
        }


        when:
        def er = nadel.execute(newNadelExecutionInput().query(query)).join()

        then:
        er.data == [hello: null]
        !er.errors.isEmpty()
        er.errors.collect({ ge -> ge.message }) == ["Problem1", "Problem2"]

    }

    def "errors and no data from a service execution are reflected in the result"() {
        given:
        def query = '''
            { hello { name } }
        '''

        Nadel nadel = buildNadel()

        List<Map<String, Object>> rawErrors = [[message: "Problem1"], [message: "Problem2"]]

        1 * delegatedExecution.execute(_) >> { args ->
            completedFuture(new ServiceExecutionResult([hello: [name: "World"]], rawErrors))
        }


        when:
        def er = nadel.execute(newNadelExecutionInput().query(query)).join()

        then:
        er.data == [hello: [name: "World"]]
        !er.errors.isEmpty()
        er.errors.collect({ ge -> ge.message }) == ["Problem1", "Problem2"]
    }

    def "missing null variables are handled"() {
        given:
        def query = '''
            query with($var1 : String!) { helloWithArgs(arg1 : $var1) { name } }
        '''

        Nadel nadel = buildNadel()

        0 * delegatedExecution.execute(_) >> { args ->
            completedFuture(new ServiceExecutionResult([:], []))
        }

        when:
        def er = nadel.execute(newNadelExecutionInput().query(query).variables([:])).join()

        then:
        er.data == null
        !er.errors.isEmpty()
        er.errors[0].message.contains("Variable 'var1' has coerced Null value")
    }

    def hydratedNDSL = [
            Foo: '''
                     service Foo {
                        type Query{
                            foo: Foo  
                        } 
                        type Foo {
                            name: String
                            bar: Bar => hydrated from Bar.barById(id: $source.barId)
                        }
                     }
            ''',
            Bar: '''
                     service Bar {
                        type Query{
                            bar: Bar 
                        } 
                        type Bar {
                            name: String 
                            nestedBar: Bar => hydrated from Bar.barById(id: $source.nestedBarId)
                        }
                    }
            ''']
    def hydratedUnderlyingSchema1 = typeDefinitions('''
            type Query{
                foo: Foo  
            } 
            type Foo {
                name: String
                barId: ID
            }
        ''')
    def hydratedUnderlyingSchema2 = typeDefinitions('''
            type Query{
                bar: Bar 
                barById(id: ID): Bar
            } 
            type Bar {
                id: ID
                name: String
                nestedBarId: ID
            }
        ''')

    def "query with hydration that fail with errors are reflected in the result"() {

        def query = '''
            { foo { bar { name nestedBar {name nestedBar { name } } } } }
        '''

        ServiceExecution serviceExecution1 = new MockServiceExecution(
                [foo: [barId: "barId123"]])
        ServiceExecution serviceExecution2 = new MockServiceExecution([barById: null],
                [[message: "Error during hydration"]])

        ServiceExecutionFactory serviceFactory = serviceFactory([
                Foo: new Tuple2(serviceExecution1, hydratedUnderlyingSchema1),
                Bar: new Tuple2(serviceExecution2, hydratedUnderlyingSchema2)]
        )
        given:
        Nadel nadel = newNadel()
                .dsl(hydratedNDSL)
                .serviceExecutionFactory(serviceFactory)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()

        when:
        def er = nadel.execute(nadelExecutionInput).join()

        then:
        !er.errors.isEmpty()
        er.errors[0].message == "Error during hydration"
        er.data == [foo: [bar: null]]
    }

    def "exceptions in service execution call in graphql errors"() {

        given:
        def query = '''
        query { hello {name} }
        '''

        ServiceExecution serviceExecution = { params -> throw new RuntimeException("Pop goes the weasel") }

        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceExecutionFactory(serviceFactory(serviceExecution, simpleUnderlyingSchema))
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()

        when:
        def er = nadel.execute(nadelExecutionInput).join()

        then:
        er.data == [hello: null]
        !er.errors.isEmpty()
        def gqlError = er.errors[0]
        gqlError.message.contains("Pop goes the weasel")
        Throwable throwable = gqlError.getExtensions().get(Throwable.class.getName())
        throwable != null
        throwable instanceof RuntimeException
        throwable.getMessage() == "Pop goes the weasel"

    }

    def "exceptions in service execution result completable future in graphql errors"() {

        given:
        def query = '''
        query { hello {name} }
        '''

        ServiceExecution serviceExecution = { params ->
            CompletableFuture cf = new CompletableFuture()
            cf.completeExceptionally(new RuntimeException("Pop goes the weasel"))
            return cf
        }

        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceExecutionFactory(serviceFactory(serviceExecution, simpleUnderlyingSchema))
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()

        when:
        def er = nadel.execute(nadelExecutionInput).join()

        then:
        er.data == [hello: null]
        !er.errors.isEmpty()
        er.errors[0].message.contains("Pop goes the weasel")
    }

    def "exceptions in hydration call that fail with errors are reflected in the result"() {

        def query = '''
            { foo { bar { name nestedBar {name nestedBar { name } } } } }
        '''

        ServiceExecution serviceExecution1 = new MockServiceExecution(
                [foo: [barId: "barId123"]])
        ServiceExecution serviceExecution2 = {
            throw new RuntimeException("Pop goes the weasel")
        }

        ServiceExecutionFactory serviceFactory = serviceFactory([
                Foo: new Tuple2(serviceExecution1, hydratedUnderlyingSchema1),
                Bar: new Tuple2(serviceExecution2, hydratedUnderlyingSchema2)]
        )
        given:
        Nadel nadel = newNadel()
                .dsl(hydratedNDSL)
                .serviceExecutionFactory(serviceFactory)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()

        when:
        def er = nadel.execute(nadelExecutionInput).join()

        then:
        !er.errors.isEmpty()
        er.errors[0].message.contains("Pop goes the weasel")
        er.data == [foo: [bar: null]]
    }
}
