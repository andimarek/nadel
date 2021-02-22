package graphql.nadel

import graphql.nadel.hooks.CreateServiceContextParams
import graphql.nadel.hooks.ResultRewriteParams
import graphql.nadel.hooks.ServiceExecutionHooks
import graphql.nadel.result.RootExecutionResultNode
import graphql.nadel.testutils.TestUtil
import spock.lang.Specification

import java.util.concurrent.CompletableFuture

import static graphql.language.AstPrinter.printAstCompact
import static graphql.nadel.Nadel.newNadel
import static graphql.nadel.NadelExecutionInput.newNadelExecutionInput
import static graphql.nadel.testutils.TestUtil.typeDefinitions
import static java.util.concurrent.CompletableFuture.completedFuture

class NadelRenameTest extends Specification {

    def simpleNDSL = '''
         service MyService {
            type Query{
                hello : World
                renameObject : ObjectOverall => renamed from renameObjectUnderlying   # the field is renamed
                renameInterface : InterfaceOverall => renamed from renameInterfaceUnderlying
                renameUnion : UnionOverall => renamed from renameUnionUnderlying
                renameInput(arg1 : InputOverall!, arg2 : URL, arg3 : EnumOverall) : String
                renameString : String => renamed from renameStringUnderlying
                typenameTest : TypenameTest
            }
            
            type World {
                id: ID
                name: String
            }
            
            type ObjectOverall implements InterfaceOverall => renamed from ObjectUnderlying {  # and the type is renamed
                name : String
            }
            
            interface InterfaceOverall => renamed from InterfaceUnderlying {
                name : String
            }
            
            union UnionOverall => renamed from UnionUnderlying = X | Y
            
            type X => renamed from XUnderlying {
                x : Int
            }

            type Y => renamed from YUnderlying {
                y : Int
            }
            
            
            input InputOverall => renamed from InputUnderlying  {
                inputVal : String
            }
            
            scalar URL => renamed from String
            
            enum EnumOverall => renamed from EnumUnderlying {
                X,Y
            }

            type TypenameTest {
                object : ObjectOverall
                objects : [ObjectOverall]
            }
         }
        '''

    def simpleUnderlyingSchema = typeDefinitions('''
            type Query {
                hello: World  
                renameObjectUnderlying : ObjectUnderlying
                renameInterfaceUnderlying : InterfaceUnderlying
                renameUnionUnderlying : UnionUnderlying
                renameInput(arg1 : InputUnderlying!, arg2 : String, arg3 : EnumUnderlying) : String
                renameStringUnderlying: String
                typenameTest : TypenameTest
            } 
            type World {
                id: ID
                name: String
            }

            type ObjectUnderlying implements InterfaceUnderlying {
                name : String
            }

            interface InterfaceUnderlying {
                name : String
            }
            
            union UnionUnderlying = XUnderlying | YUnderlying
            
            type XUnderlying {
                x : Int
            }

            type YUnderlying {
                y : Int
            }
            
            input InputUnderlying  {
                inputVal : String
            }
            
            enum EnumUnderlying {
                X,Y
            }
            
            type TypenameTest {
                object : ObjectUnderlying
                objects : [ObjectUnderlying]
            }
        ''')

    def delegatedExecution = Mock(ServiceExecution)
    def serviceFactory = TestUtil.serviceFactory(delegatedExecution, simpleUnderlyingSchema)

    ServiceExecutionHooks traversingExecutionHooks = new ServiceExecutionHooks() {
        @Override
        CompletableFuture<Object> createServiceContext(CreateServiceContextParams params) {
            return completedFuture(null)
        }

        @Override
        CompletableFuture<RootExecutionResultNode> resultRewrite(ResultRewriteParams params) {
            return completedFuture(params.getResultNode());
        }
    }

    def nadel = newNadel()
            .dsl("MyService", simpleNDSL)
            .serviceExecutionFactory(serviceFactory)
            .serviceExecutionHooks(traversingExecutionHooks)
            .build()

    def "simple type rename and field rename works as expected"() {
        def query = '''
        { renameObject { name } }
        '''

        given:
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()
        def data = [renameObjectUnderlying: [name: "val"]]
        when:
        def result = nadel.execute(nadelExecutionInput).join()

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            assert printAstCompact(params.query) == "query nadel_2_MyService {renameObjectUnderlying {name}}"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.data == [renameObject: [name: "val"]]
    }

    def "fragment type rename and field rename works as expected"() {
        def query = '''
        { 
            renameObject { 
                ... FragDef
            } 
        }
        
        fragment FragDef on ObjectOverall {
            name
        }
        '''

        given:
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()
        def data = [renameObjectUnderlying: [name: "val"]]
        when:
        def result = nadel.execute(nadelExecutionInput).join()

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            assert printAstCompact(params.query) ==
                    "query nadel_2_MyService {renameObjectUnderlying {...FragDef}} fragment FragDef on ObjectUnderlying {name}"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.errors.isEmpty()
        result.data == [renameObject: [name: "val"]]
    }

    def "inline fragment type rename and field rename works as expected"() {
        def query = '''
        { 
            renameObject { 
                ... on ObjectOverall {
                    name
                } 
            } 
        }
        '''

        given:
        def data = [renameObjectUnderlying: [name: "val"]]
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()
        when:
        def result = nadel.execute(nadelExecutionInput).join()

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            assert printAstCompact(params.query) ==
                    "query nadel_2_MyService {renameObjectUnderlying {... on ObjectUnderlying {name}}}"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.errors.isEmpty()
        result.data == [renameObject: [name: "val"]]
    }


    def "interface rename with fragments works as expected"() {
        def query = '''
        { 
            renameObject { 
                ... on InterfaceOverall { name } 
            }
        }
        '''

        given:
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()
        def data = [renameObjectUnderlying: [name: "val"]]
        when:
        def result = nadel.execute(nadelExecutionInput).join()

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            def q = printAstCompact(params.query)
            assert q == "query nadel_2_MyService {renameObjectUnderlying {... on InterfaceUnderlying {name}}}", "Unexpected query: $q"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.errors.isEmpty()
        result.data == [renameObject: [name: "val"]]
    }

    def "interface rename direct works as expected"() {
        def query = '''
        { 
            renameInterface { 
                name 
            }
        }
        '''

        given:
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .artificialFieldsUUID("xxx")
                .build()
        //
        // note how we have to give back an implementation object type not and interface
        //
        def data = [renameInterfaceUnderlying: [name: "val", type_hint_typename__xxx: "ObjectUnderlying"]]
        when:
        def result = nadel.execute(nadelExecutionInput).join()

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            def q = printAstCompact(params.query)
            assert q == "query nadel_2_MyService {renameInterfaceUnderlying {name type_hint_typename__xxx:__typename}}", "Unexpected query: $q"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.errors.isEmpty()
        result.data == [renameInterface: [name: "val"]]
    }


    def "union rename direct works as expected"() {
        def query = '''
        { 
            renameUnion { 
                ... on X {
                    x
                } 
            }
        }
        '''

        given:
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .artificialFieldsUUID("xxx")
                .build()
        //
        // note how we have to give back an implementation object type not and interface
        //
        def data = [renameUnionUnderlying: [x: 1, type_hint_typename__xxx: "XUnderlying"]]
        when:
        def result = nadel.execute(nadelExecutionInput).join()

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            def q = printAstCompact(params.query)
            assert q == "query nadel_2_MyService {renameUnionUnderlying {... on XUnderlying {x} type_hint_typename__xxx:__typename}}", "Unexpected query: $q"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.errors.isEmpty()
        result.data == [renameUnion: [x: 1]]
    }


    def "variable referenced input types rename works as expected"() {
        def query = '''
        query X($var1 : InputOverall!, $var2 : URL, $var3 : EnumOverall) { 
            renameInput(arg1 : $var1, arg2: $var2, arg3: $var3)
        }
        '''

        given:
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .variables([var1: [inputVal: "x"]])
                .build()
        //
        // note how we have to give back an implementation object type not and interface
        //
        def data = [renameInput: "done"]
        when:
        def result = nadel.execute(nadelExecutionInput).join()

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            def q = printAstCompact(params.query)
            assert q == 'query nadel_2_MyService_X($var1:InputUnderlying!,$var2:String,$var3:EnumUnderlying) {renameInput(arg1:$var1,arg2:$var2,arg3:$var3)}',
                    "Unexpected query: $q"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.errors.isEmpty()
        result.data == [renameInput: "done"]
    }


    def "string field rename"() {
        def query = '''
        query { renameString }
        '''

        given:
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()

        def data = [renameStringUnderlying: "hello"]
        when:
        def result = nadel.execute(nadelExecutionInput).join()

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            def q = printAstCompact(params.query)
            println q
            assert q == 'query nadel_2_MyService {renameStringUnderlying}',
                    "Unexpected query: $q"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.errors.isEmpty()
        result.data == [renameString: "hello"]
    }

    def "correct __typename is returned"() {
        def query = '''
        { 
            typenameTest {
                __typename
                object {
                    __typename
                }
                objects {
                    __typename
                }
            }
        }
        '''

        given:
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .artificialFieldsUUID("xxx")
                .build()

        def data = [
                typenameTest: [
                        __typename: "TypenameTest",
                        object    : [
                                __typename: "ObjectUnderlying",
                        ],
                        objects   : [
                                [__typename: "ObjectUnderlying"],
                                [__typename: "ObjectUnderlying"],
                        ],
                ]
        ]
        when:
        def result = nadel.execute(nadelExecutionInput).join()

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            def q = printAstCompact(params.query)
            assert q == "query nadel_2_MyService {typenameTest {__typename object {__typename} objects {__typename}}}", "Unexpected query: $q"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.errors.isEmpty()
        result.data == [
                typenameTest: [
                        __typename: "TypenameTest",
                        object    : [
                                __typename: "ObjectOverall",
                        ],
                        objects   : [
                                [__typename: "ObjectOverall",],
                                [__typename: "ObjectOverall",],
                        ],
                ],
        ]
    }

}
