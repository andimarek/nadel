package graphql.nadel


import graphql.nadel.introspection.DefaultIntrospectionRunner
import graphql.nadel.testutils.TestUtil
import spock.lang.Specification

import static graphql.nadel.Nadel.newNadel
import static graphql.nadel.NadelExecutionInput.newNadelExecutionInput

class NadelIntrospectionTest extends Specification {

    def simpleNDSL = """
         service MyService {
            type Query{
                hello: World  
            } 
            type World {
                id: ID
                name: String
            }
            type Mutation{
                hello: String  
            } 
         }
        """

    def simpleUnderlyingSchema = TestUtil.typeDefinitions("""
            type Query{
                hello: World  
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
    def serviceFactory = TestUtil.serviceFactory(delegatedExecution, simpleUnderlyingSchema)


    def buildNadel() {
        newNadel()
                .dsl("MyService", simpleNDSL)
                .introspectionRunner(new DefaultIntrospectionRunner())
                .serviceExecutionFactory(serviceFactory)
                .build()
    }

    def "introspection can be performed even with fragments"() {
        given:
        def query = '''
        query { 
            ... IntrospectionFrag
            __type(name : "World") {
                name
            }
            __typename
        }
        
        fragment IntrospectionFrag on Query {
            __schema { 
                queryType { name }
            }
        }
        
        '''

        Nadel nadel = buildNadel()

        when:
        def result = nadel.execute(newNadelExecutionInput().query(query))

        then:
        result.join().data == [
                __schema  : [queryType: [name: "Query"]],
                __type    : [name: "World"],
                __typename: "Query"
        ]
    }

    def "if there are a mix of system fields and normal fields it errors"() {

        given:
        def query = '''
        query { 
            __schema { 
                queryType { name }
            }
            __typename
            hello {
                name
            }
        }
        '''

        Nadel nadel = buildNadel()

        when:
        def result = nadel.execute({ ei -> ei.query(query) })
        def er = result.join()
        then:
        er.data == null
        !er.errors.isEmpty()
        er.errors[0].errorType.toString() == "MixedIntrospectionAndNormalFields"

    }
}
