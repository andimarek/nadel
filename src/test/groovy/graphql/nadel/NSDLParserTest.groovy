package graphql.nadel

import graphql.language.Node
import graphql.language.ObjectTypeDefinition
import graphql.nadel.dsl.NodeId
import graphql.parser.InvalidSyntaxException
import org.antlr.v4.runtime.misc.ParseCancellationException
import spock.lang.Specification

import static graphql.nadel.testutils.TestUtil.astAsMap
import static graphql.nadel.testutils.TestUtil.expectedJson

class NSDLParserTest extends Specification {


    def "rename with more than one level is not allowed"() {
        given:
        def simpleDSL = """
        service Foo {
            type Query {
                hello: String => renamed from detail.subDetail.hello
            }
        }
       """
        NSDLParser parser = new NSDLParser()
        when:
        parser.parseDSL(simpleDSL)

        then:
        thrown(InvalidSyntaxException)

    }

    def "simple service definition"() {
        given:
        def simpleDSL = """
        service Foo {
            type Query {
                hello(arg1: String, arg2: Integer): String
            }
        }
       """
        NSDLParser parser = new NSDLParser()
        when:
        def stitchingDSL = parser.parseDSL(simpleDSL)

        then:
        astAsMap(stitchingDSL) == expectedJson("service-definition.json")
        assertNodesHaveIds(stitchingDSL.children)
    }

    def "parse error"() {
        given:
        def simpleDSL = """
        service Foo {
            urlX: "someUrl"
        }
       """
        when:
        NSDLParser parser = new NSDLParser()
        parser.parseDSL(simpleDSL)

        then:
        thrown(Exception)

    }

    def "not all tokens are parsed"() {
        given:
        def simpleDSL = """
        service Foo {
        }
        someFoo
       """
        when:
        NSDLParser parser = new NSDLParser()
        parser.parseDSL(simpleDSL)

        then:
        thrown(ParseCancellationException)
    }

    def "parse field mapping"() {
        given:
        def dsl = """
        service FooService {
            type Query {
                foo: Foo
            }

            type Foo {
                id: ID => renamed from fooId
            }
        }
        """
        when:
        NSDLParser parser = new NSDLParser()
        then:
        def stitchingDSL = parser.parseDSL(dsl)

        then:
        astAsMap(stitchingDSL) == expectedJson("field-mapping.json")
        assertNodesHaveIds(stitchingDSL.children)

    }


    def "parse hydration"() {
        given:

        def dsl = '''
        service FooService {
            type Query {
                foo: Foo
            }

            type Foo {
                id(inputArg: ID!): ID => hydrated from OtherService.resolveId(otherId: $source.id, 
                arg1: $context.ctxParam, arg2: $argument.inputArg)
            }
        }
        '''
        when:
        NSDLParser parser = new NSDLParser()
        def stitchingDSL = parser.parseDSL(dsl)
        then:
        astAsMap(stitchingDSL) == expectedJson("hydration.json")
        assertNodesHaveIds(stitchingDSL.children)
    }

    def "parse hydration with a synthetic field"() {
        given:

        def dsl = '''
        service FooService {
            type Query {
                foo: Foo
            }

            type Foo {
                id(inputArg: ID!): ID => hydrated from OtherService.resolver.resolveId(otherId: $source.id, 
                arg1: $context.ctxParam, arg2: $argument.inputArg)
            }
        }
        '''
        when:
        NSDLParser parser = new NSDLParser()
        def stitchingDSL = parser.parseDSL(dsl)
        then:
        astAsMap(stitchingDSL) == expectedJson("hydration-synthetic.json")
        assertNodesHaveIds(stitchingDSL.children)
    }


    def "parse object type transformation"() {
        given:

        def dsl = """
            service FooService {
                type Query {
                    foo: Foo
                }

                type Foo  @directiveFirst  => renamed from UnderlyingFooName {
                    id: ID
                }
            }
        """
        when:
        NSDLParser parser = new NSDLParser()
        def stitchingDSL = parser.parseDSL(dsl)
        then:
        astAsMap(stitchingDSL) == expectedJson("object-type-transformation.json")
        assertNodesHaveIds(stitchingDSL.children)
    }

    def "parse interface type transformation"() {
        given:

        def dsl = """
            service FooService {
                type Query {
                    foo: Foo
                }

                interface FooInterface @directiveFirst  => renamed from UnderlyingFooName {
                    id: ID
                }
            }
        """
        when:
        NSDLParser parser = new NSDLParser()
        def stitchingDSL = parser.parseDSL(dsl)
        then:
        astAsMap(stitchingDSL) == expectedJson("interface-type-transformation.json")
        assertNodesHaveIds(stitchingDSL.children)
    }

    def "parse union type transformation"() {
        given:

        def dsl = """
            service FooService {
                type Query {
                    foo: Foo
                }

                union FooUnion  @directiveFirst  => renamed from UnderlyingFooName = Photo | Person
            }
        """
        when:
        NSDLParser parser = new NSDLParser()
        def stitchingDSL = parser.parseDSL(dsl)
        then:
        astAsMap(stitchingDSL) == expectedJson("union-type-transformation.json")
        assertNodesHaveIds(stitchingDSL.children)
    }

    def "unterminated string should not parse"() {
        given:
        def dsl = """
            service FooService {
                type Query {
                    "this is an unterminated comment
                    echo: String
                }
            }
        """

        when:
        NSDLParser parser = new NSDLParser()
        parser.parseDSL(dsl)

        then:
        def parseException = thrown ParseCancellationException
        parseException.message.contains("token recognition error at: '\"this is an unterminated comment\\n'")
    }

    def "parse directives to wrong place for field transformation"() {
        given:

        def dsl = """
            service FooService {
                directive @testdirective on FIELD_DEFINITION
                type Query {
                    foo: Foo
                }

                type Foo {
                    newId: ID > renamed from id @testdirective 
                }
            }
        """
        when:
        NSDLParser parser = new NSDLParser()
        parser.parseDSL(dsl)
        then:
        thrown(Exception)
    }

    def "parse directives for field transformation should not break"() {
        given:

        def dsl = """
            service FooService {
                directive @testdirective on FIELD_DEFINITION
                type Query {
                    foo: Foo
                }

                type Foo {
                    newId: ID @testdirective  => renamed from id 
                }
            }
        """

        when:
        NSDLParser parser = new NSDLParser()
        def stitchingDSL = parser.parseDSL(dsl)

        then:
        astAsMap(stitchingDSL).size() > 0
    }

    boolean assertNodesHaveIds(List<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return true
        }
        nodes.forEach({ node ->
            assert node.getAdditionalData().get(NodeId.ID), "Bad node has no id ${node.class.name}"
            // recursive
            assert assertNodesHaveIds(node.children)
        })
        true
    }

    def "allow 'service' as type name"() {
        given:

        def dsl = """
            service FooService {
                type Service {
                    id: String
                }
                type Query {
                    service: Service
                }
            }
        """

        when:
        NSDLParser parser = new NSDLParser()
        def stitchingDSL = parser.parseDSL(dsl)

        then:
        astAsMap(stitchingDSL).size() > 0
    }

    def "allow 'service' field to be hydrated"() {
        given:
        def dsl = '''
            service FooService {
                type Service {
                    id: ID
                    related: Service => hydrated from FooService.service(id: $source.relatedId)
                }
                type Query {
                    service(id: String): Service
                }
            }
        '''

        when:
        NSDLParser parser = new NSDLParser()
        def stitchingDSL = parser.parseDSL(dsl)

        then:
        astAsMap(stitchingDSL).size() > 0
    }

    def "parser allows normal graphql SDL syntax"() {

        given:

        def dsl = """
            type Service {
                id: String
            }
            type Query {
                service: Service
            }
        """

        when:
        NSDLParser parser = new NSDLParser()
        def stitchingDSL = parser.parseDSL(dsl)

        then:
        stitchingDSL.getServiceDefinition() == null
        def sdlDefinitions = stitchingDSL.getSDLDefinitions()
        sdlDefinitions.size() == 2
        (sdlDefinitions[0] as ObjectTypeDefinition).getName() == "Service"
        (sdlDefinitions[1] as ObjectTypeDefinition).getName() == "Query"
    }
}


