package graphql.nadel

import graphql.GraphQL
import graphql.nadel.testutils.TestUtil
import graphql.schema.DataFetcher
import graphql.schema.TypeResolver
import spock.lang.Specification

import static graphql.nadel.Nadel.newNadel
import static graphql.nadel.NadelExecutionInput.newNadelExecutionInput
import static graphql.nadel.testutils.TestUtil.schema
import static graphql.nadel.testutils.TestUtil.serviceFactory
import static graphql.nadel.testutils.TestUtil.typeDefinitions
import static graphql.schema.idl.RuntimeWiring.newRuntimeWiring
import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring

class NadelInterfaceAndUnionHandlingTest extends Specification {

    def ndsl = [
            PetService  : '''
         service PetService {
         
            type Query{
                pets(isLoyal : Boolean) : [Pet]
                raining(isLoyal : Boolean) : CatsAndDogs 
            } 
            
            interface Pet {
                name : String
                owners: [Owner] => hydrated from OwnerService.ownerById(id: $source.ownerIds)
                collar : Collar
                collarToRenamed : Collar => renamed from collar
            }
            
            type Cat implements Pet {
                name : String
                wearsBell : Boolean
                owners: [Owner] 
                collar : Collar
                collarToRenamed : Collar => renamed from collar
            }

            type Dog implements Pet {
                name : String
                wearsCollar : Boolean
                owners: [Owner] 
                collar : Collar
                collarToRenamed : Collar => renamed from collar
            }

            union CatsAndDogs = Cat | Dog
            
            interface Collar {
                color : String
                size : String
            }
            
            type DogCollar implements Collar {
                color : String
                size : String
            }

            type CatCollar implements Collar {
                color : String
                size : String
            }
         }
         ''',
            OwnerService: '''   
         service OwnerService {
            type Query {
                owner(id : String) : Owner
            }
            
            interface Owner {
                name : String
            }
            
            type CaringOwner implements Owner {
                name : String
                givesPats : Boolean
            }
    
            type CruelOwner implements Owner {
                name : String
                givesSmacks : Boolean
            }
        }
        ''']

    def underlyingSchemaSpecPets = '''
            type Query{
                hello: World 
                pets(isLoyal : Boolean) : [Pet]
                raining(isLoyal : Boolean) : CatsAndDogs 
            } 
            
            type World {
                id: ID
                name: String
            }
            
            type Mutation{
                hello: String  
            }
            
            interface Pet {
                ownerIds : [String]
                name : String
                collar : Collar
            }
            
            type Cat implements Pet {
                ownerIds : [String]
                name : String
                wearsBell : Boolean
                collar : Collar
            }

            type Dog implements Pet {
                ownerIds : [String]
                name : String
                wearsCollar : Boolean
                collar : Collar
            }

            union CatsAndDogs = Cat | Dog
            
            
            interface Collar {
                color : String
                size : String
            }
            
            type DogCollar implements Collar {
                color : String
                size : String
            }

            type CatCollar implements Collar {
                color : String
                size : String
            }
        '''

    def underlyingSchemaSpecOwners = '''
        type Query {
            ownerById(id : String) : Owner
        }
        
        interface Owner {
            name : String
        }
        
        type CaringOwner implements Owner {
            name : String
            givesPats : Boolean
        }

        type CruelOwner implements Owner {
            name : String
            givesSmacks : Boolean
        }
    '''

    GraphQL buildUnderlyingImplementationPets(String spec) {
        def sparkyTheDog = [type: "Dog", name: "Sparky", wearsCollar: true, ownerIds: ["dearly"], collar: [size: "large", color: "blue"]]
        // dogs are loyal
        def whiskersTheCat = [type: "Cat", name: "Whiskers", wearsBell: false, ownerIds: ["cruella"], collar: [size: "small", color: "red"]]
        // cats eat birds

        DataFetcher petDF = { env ->
            return [sparkyTheDog, whiskersTheCat]
        }
        DataFetcher rainingDF = { env ->
            if (env.getArgument("isLoyal") == true) {
                return sparkyTheDog
            } else {
                return whiskersTheCat
            }
        }
        TypeResolver petTR = { env ->
            if (env.object["name"] == "Sparky") {
                return env.schema.getObjectType("Dog")
            } else {
                return env.schema.getObjectType("Cat")
            }
        }
        TypeResolver catsAndDogsTR = { env ->
            if (env.object["type"] == "Dog") {
                return env.schema.getObjectType("Dog")
            } else {
                return env.schema.getObjectType("Cat")
            }
        }
        TypeResolver collarTR = { env ->
            if (env.object["type"] == "Dog") {
                return env.schema.getObjectType("DogCollar")
            } else {
                return env.schema.getObjectType("CatCollar")
            }
        }
        def runtimeWiring = newRuntimeWiring()
                .type(newTypeWiring("Query")
                        .dataFetcher("pets", petDF)
                        .dataFetcher("raining", rainingDF))
                .type(newTypeWiring("Pet").typeResolver(petTR))
                .type(newTypeWiring("CatsAndDogs").typeResolver(catsAndDogsTR))
                .type(newTypeWiring("Collar").typeResolver(collarTR))
                .build()
        def graphQLSchema = schema(spec, runtimeWiring)
        return GraphQL.newGraphQL(graphQLSchema).build()
    }

    GraphQL buildUnderlyingImplementationOwners(String spec) {
        def caringOwner = [id: "dearly", name: "Mr Dearly", givesPats: true]
        def cruelOwner = [id: "cruella", name: "Cruella De Vil", givesSmacks: true]

        DataFetcher ownerDF = { env ->
            if (env.getArgument("id") == "cruella") {
                return cruelOwner
            } else {
                return caringOwner
            }
        }
        TypeResolver ownerTR = { env ->
            if (env.object["id"] == "cruella") {
                return env.schema.getObjectType("CruelOwner")
            } else {
                return env.schema.getObjectType("CaringOwner")
            }
        }
        def runtimeWiring = newRuntimeWiring()
                .type(newTypeWiring("Query")
                        .dataFetcher("ownerById", ownerDF))
                .type(newTypeWiring("Owner").typeResolver(ownerTR))
                .build()
        def graphQLSchema = schema(spec, runtimeWiring)
        return GraphQL.newGraphQL(graphQLSchema).build()
    }

    def underlyingImplementationPets = buildUnderlyingImplementationPets(underlyingSchemaSpecPets)
    def underlyingImplementationOwners = buildUnderlyingImplementationOwners(underlyingSchemaSpecOwners)

    ServiceExecution serviceExecutionPets = TestUtil.serviceExecutionImpl(underlyingImplementationPets)
    ServiceExecution serviceExecutionOwners = TestUtil.serviceExecutionImpl(underlyingImplementationOwners)

    def underlyingSchemaPets = typeDefinitions(underlyingSchemaSpecPets)
    def underlyingSchemaOwners = typeDefinitions(underlyingSchemaSpecOwners)

    def serviceFactory = serviceFactory([
            PetService  : new Tuple2(serviceExecutionPets, underlyingSchemaPets),
            OwnerService: new Tuple2(serviceExecutionOwners, underlyingSchemaOwners)]
    )

    def "query with pass through interfaces and unions that have __typename in them work as expected"() {

        given:
        def query = '''
        query petQ($isLoyal : Boolean) { 
            pets(isLoyal : $isLoyal) {
                name
                __typename
                ... on Dog {
                    wearsCollar
                    __typename
                }
                ... on Cat {
                    wearsBell 
                }
            }
            raining(isLoyal : $isLoyal) {
                __typename
                ... on Dog {
                    wearsCollar
                }
                ... on Cat {
                    wearsBell 
                }
            }
        }    
        '''

        Nadel nadel = newNadel()
                .dsl(ndsl)
                .serviceExecutionFactory(serviceFactory)
                .build()

        when:
        def result = nadel.execute(newNadelExecutionInput().query(query).variables(isLoyal: true)).join()

        then:
        result.data == [
                pets   : [
                        [name: "Sparky", wearsCollar: true, __typename: "Dog"],
                        [name: "Whiskers", wearsBell: false, __typename: "Cat"]
                ],
                raining: [wearsCollar: true, __typename: "Dog"],
        ]
    }

    def "query with pass through interfaces and unions that have MIXED __typename in them work as expected"() {

        given:
        def query = '''
        query petQ($isLoyal : Boolean) { 
            pets(isLoyal : $isLoyal) {
                name
                ... on Dog {
                    wearsCollar
                    __typename
                }
                ... on Cat {
                    wearsBell 
                }
            }
            raining(isLoyal : $isLoyal) {
                __typename
                ... on Dog {
                    wearsCollar
                }
                ... on Cat {
                    wearsBell 
                }
            }
        }    
        '''

        Nadel nadel = newNadel()
                .dsl(ndsl)
                .serviceExecutionFactory(serviceFactory)
                .build()

        when:
        def result = nadel.execute(newNadelExecutionInput().query(query).variables(isLoyal: true)).join()

        then:
        result.data == [
                pets   : [
                        [name: "Sparky", wearsCollar: true, __typename: "Dog"],
                        [name: "Whiskers", wearsBell: false]
                ],
                raining: [wearsCollar: true, __typename: "Dog"],
        ]
    }

    def "query with pass through interfaces and unions that DONT have __typename in them work as expected"() {

        given:
        def query = '''
        query petQ($isLoyal : Boolean) { 
            pets(isLoyal : $isLoyal) {
                name
                ... on Dog {
                    wearsCollar
                }
                ... on Cat {
                    wearsBell 
                }
            }
            raining(isLoyal : $isLoyal) {
                ... on Dog {
                    wearsCollar
                }
                ... on Cat {
                    wearsBell 
                }
            }
        }    
        '''

        Nadel nadel = newNadel()
                .dsl(ndsl)
                .serviceExecutionFactory(serviceFactory)
                .build()

        when:
        def result = nadel.execute(newNadelExecutionInput().query(query).variables(isLoyal: true)).join()

        then:
        result.data == [
                pets   : [
                        [name: "Sparky", wearsCollar: true],
                        [name: "Whiskers", wearsBell: false]
                ],
                raining: [wearsCollar: true],
        ]
    }

    def "query with pass through interfaces and unions that have __typename in fragments work as expected"() {

        given:
        def query = '''
        query petQ($isLoyal : Boolean) { 
            pets(isLoyal : $isLoyal) {
                name
                ... DogFrag
                ... on Dog {
                    wearsCollar
                    __typename
                }
                ... on Cat {
                    wearsBell 
                    __typename
                }
                
            }
            raining(isLoyal : $isLoyal) {
                ... on Dog {
                    wearsCollar
                    __typename
                }
                ... on Cat {
                    wearsBell 
                    __typename
                }
            }
        }    
        fragment DogFrag on Dog {
            wearsCollar
            __typename
        }
        '''

        Nadel nadel = newNadel()
                .dsl(ndsl)
                .serviceExecutionFactory(serviceFactory)
                .build()

        when:
        def result = nadel.execute(newNadelExecutionInput().query(query).variables(isLoyal: true)).join()

        then:
        result.data == [
                pets   : [
                        [name: "Sparky", wearsCollar: true, __typename: "Dog"],
                        [name: "Whiskers", wearsBell: false, __typename: "Cat"]
                ],
                raining: [wearsCollar: true, __typename: "Dog"],
        ]
    }


    def "query with pass through interfaces and unions that have aliased __typename in fragments work as expected"() {

        given:
        def query = '''
        query petQ($isLoyal : Boolean) { 
            pets(isLoyal : $isLoyal) {
                name
                ... DogFrag
                ... on Cat {
                    wearsBell 
                    aliasedCatTypeName : __typename
                }

            }
        }    
        
        fragment DogFrag on Dog {
            wearsCollar
            aliasedDogTypeName : __typename
        }

        '''

        Nadel nadel = newNadel()
                .dsl(ndsl)
                .serviceExecutionFactory(serviceFactory)
                .build()

        when:
        def result = nadel.execute(newNadelExecutionInput().query(query).variables(isLoyal: true)).join()

        then:
        result.data == [
                pets: [
                        [name: "Sparky", wearsCollar: true, aliasedDogTypeName: "Dog"],
                        [name: "Whiskers", wearsBell: false, aliasedCatTypeName: "Cat"]
                ],
        ]
    }

    def "query with hydrated interfaces work as expected"() {

        given:
        def query = '''
        query petQ($isLoyal : Boolean) { 
            pets(isLoyal : $isLoyal) {
                name
                owners {
                    name
                    ... on CaringOwner {
                        givesPats    
                    }
                    ... on CruelOwner {
                        givesSmacks    
                    }
                }
            }
        }    
        '''

        Nadel nadel = newNadel()
                .dsl(ndsl)
                .serviceExecutionFactory(serviceFactory)
                .build()
        when:
        def result = nadel.execute(newNadelExecutionInput().query(query).variables(isLoyal: true)).join()

        then:
        result.data == [
                pets: [
                        [name: "Sparky", owners: [[name: "Mr Dearly", givesPats: true]]],
                        [name: "Whiskers", owners: [[name: "Cruella De Vil", givesSmacks: true]]]
                ],
        ]
    }


    def "lower level interface fields get typename added"() {

        given:
        def query = '''
        query petQ($isLoyal : Boolean) { 
            pets(isLoyal : $isLoyal) {
                name
                collar {
                    color
                }
            }
        }    
        '''

        Nadel nadel = newNadel()
                .dsl(ndsl)
                .serviceExecutionFactory(serviceFactory)
                .build()
        when:
        def result = nadel.execute(newNadelExecutionInput().query(query).variables(isLoyal: true)).join()

        then:
        result.errors.isEmpty()
        result.data == [
                pets: [
                        [name: "Sparky", collar: [color: "blue"]],
                        [name: "Whiskers", collar: [color: "red"]]
                ],
        ]

    }

    def "lower level interface fields which are renamed get typename added"() {

        given:
        def query = '''
        query petQ($isLoyal : Boolean) { 
            pets(isLoyal : $isLoyal) {
                name
                collarToRenamed {
                    color
                }
            }
        }    
        '''
        Nadel nadel = newNadel()
                .dsl(ndsl)
                .serviceExecutionFactory(serviceFactory)
                .build()
        when:
        def result = nadel.execute(newNadelExecutionInput().query(query).variables(isLoyal: true)).join()

        then:
        result.errors.isEmpty()
        result.data == [
                pets: [
                        [name: "Sparky", collarToRenamed: [color: "blue"]],
                        [name: "Whiskers", collarToRenamed: [color: "red"]]
                ],
        ]

    }
}
