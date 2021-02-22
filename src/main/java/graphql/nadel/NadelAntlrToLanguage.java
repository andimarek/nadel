package graphql.nadel;

import graphql.Internal;
import graphql.language.EnumTypeDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.NodeBuilder;
import graphql.language.ObjectTypeDefinition;
import graphql.language.SDLDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.nadel.dsl.CommonDefinition;
import graphql.nadel.dsl.EnumTypeDefinitionWithTransformation;
import graphql.nadel.dsl.ExtendedFieldDefinition;
import graphql.nadel.dsl.FieldMappingDefinition;
import graphql.nadel.dsl.FieldTransformation;
import graphql.nadel.dsl.InputObjectTypeDefinitionWithTransformation;
import graphql.nadel.dsl.InterfaceTypeDefinitionWithTransformation;
import graphql.nadel.dsl.NodeId;
import graphql.nadel.dsl.ObjectTypeDefinitionWithTransformation;
import graphql.nadel.dsl.RemoteArgumentDefinition;
import graphql.nadel.dsl.RemoteArgumentSource;
import graphql.nadel.dsl.ScalarTypeDefinitionWithTransformation;
import graphql.nadel.dsl.ServiceDefinition;
import graphql.nadel.dsl.StitchingDsl;
import graphql.nadel.dsl.TypeMappingDefinition;
import graphql.nadel.dsl.UnderlyingServiceHydration;
import graphql.nadel.dsl.UnionTypeDefinitionWithTransformation;
import graphql.nadel.parser.GraphqlAntlrToLanguage;
import graphql.nadel.parser.antlr.StitchingDSLParser;
import graphql.parser.MultiSourceReader;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static graphql.Assert.assertShouldNeverHappen;
import static graphql.nadel.dsl.ExtendedFieldDefinition.newExtendedFieldDefinition;
import static graphql.nadel.dsl.RemoteArgumentSource.SourceType.CONTEXT;
import static graphql.nadel.dsl.RemoteArgumentSource.SourceType.FIELD_ARGUMENT;
import static graphql.nadel.dsl.RemoteArgumentSource.SourceType.OBJECT_FIELD;
import static graphql.nadel.util.FpKit.map;

@Internal
public class NadelAntlrToLanguage extends GraphqlAntlrToLanguage {

    private AtomicInteger idCounter = new AtomicInteger(1);

    public NadelAntlrToLanguage(CommonTokenStream tokens, MultiSourceReader multiSourceReader) {
        super(tokens, multiSourceReader);
    }

    @Override
    protected void addCommonData(NodeBuilder nodeBuilder, ParserRuleContext parserRuleContext) {
        super.addCommonData(nodeBuilder, parserRuleContext);
        nodeBuilder.additionalData(additionalIdData());
    }

    private Map<String, String> additionalIdData() {
        Map<String, String> additionalData = new LinkedHashMap<>();
        String nodeIdVal = String.valueOf(idCounter.getAndIncrement());
        additionalData.put(NodeId.ID, nodeIdVal);
        return additionalData;
    }

    public StitchingDsl createStitchingDsl(StitchingDSLParser.StitchingDSLContext ctx) {
        StitchingDsl.Builder builder = StitchingDsl.newStitchingDSL();
        addCommonData(builder, ctx);
        if (ctx.serviceDefinition() != null) {
            ServiceDefinition serviceDefinition = createServiceDefinition(ctx.serviceDefinition());
            builder.serviceDefinition(serviceDefinition);
        }
        if (ctx.commonDefinition() != null) {
            builder.commonDefinition(createCommonDefinition(ctx.commonDefinition()));
        }

        List<SDLDefinition> definitions = createTypeSystemDefinitions(ctx.typeSystemDefinition());
        builder.sdlDefinitions(definitions);
        List<SDLDefinition> extensions = createTypeSystemExtensions(ctx.typeSystemExtension());
        builder.addSdlDefinitions(extensions);

        return builder.build();
    }

    private CommonDefinition createCommonDefinition(StitchingDSLParser.CommonDefinitionContext commonDefinitionContext) {
        CommonDefinition.Builder builder = CommonDefinition.newCommonDefinition();
        addCommonData(builder, commonDefinitionContext);
        builder.sourceLocation(getSourceLocation(commonDefinitionContext));
        builder.comments(getComments(commonDefinitionContext));
        List<SDLDefinition> definitions = createTypeSystemDefinitions(commonDefinitionContext.typeSystemDefinition());
        builder.typeDefinitions(definitions);
        List<SDLDefinition> extensions = createTypeSystemExtensions(commonDefinitionContext.typeSystemExtension());
        builder.addTypeDefinitions(extensions);
        return builder.build();
    }

    private ServiceDefinition createServiceDefinition(StitchingDSLParser.ServiceDefinitionContext serviceDefinitionContext) {
        ServiceDefinition.Builder builder = ServiceDefinition.newServiceDefinition();
        addCommonData(builder, serviceDefinitionContext);

        builder.name(serviceDefinitionContext.name().getText());
        List<SDLDefinition> definitions = createTypeSystemDefinitions(serviceDefinitionContext.typeSystemDefinition());
        builder.definitions(definitions);
        List<SDLDefinition> extensions = createTypeSystemExtensions(serviceDefinitionContext.typeSystemExtension());
        builder.addDefinitions(extensions);
        return builder.build();
    }


    private List<SDLDefinition> createTypeSystemDefinitions(List<StitchingDSLParser.TypeSystemDefinitionContext> typeSystemDefinitionContexts) {
        return typeSystemDefinitionContexts.stream().map(this::createTypeSystemDefinition).collect(Collectors.toList());
    }

    private List<SDLDefinition> createTypeSystemExtensions(List<StitchingDSLParser.TypeSystemExtensionContext> typeSystemExtensionContexts) {
        return typeSystemExtensionContexts.stream().map(this::createTypeSystemExtension).collect(Collectors.toList());
    }

    @Override
    protected FieldDefinition createFieldDefinition(StitchingDSLParser.FieldDefinitionContext ctx) {
        FieldDefinition fieldDefinition = super.createFieldDefinition(ctx);
        if (ctx.fieldTransformation() == null && ctx.addFieldInfo() == null) {
            return fieldDefinition;
        }
        ExtendedFieldDefinition.Builder builder = newExtendedFieldDefinition(fieldDefinition);
        addCommonData(builder, ctx);
        if (ctx.fieldTransformation() != null) {
            builder.fieldTransformation(createFieldTransformation(ctx.fieldTransformation()));
        }
        if (ctx.addFieldInfo() != null) {
            builder.defaultBatchSize(Integer.parseInt(ctx.addFieldInfo().defaultBatchSize().intValue().getText()));
        }
        return builder.build();
    }

    private FieldTransformation createFieldTransformation(StitchingDSLParser.FieldTransformationContext ctx) {
        if (ctx.fieldMappingDefinition() != null) {
            return new FieldTransformation(createFieldMappingDefinition(ctx.fieldMappingDefinition()),
                    getSourceLocation(ctx), new ArrayList<>(), additionalIdData());
        } else if (ctx.underlyingServiceHydration() != null) {
            return new FieldTransformation(createUnderlyingServiceHydration(ctx.underlyingServiceHydration()),
                    getSourceLocation(ctx), new ArrayList<>(), additionalIdData());
        } else {
            return assertShouldNeverHappen();
        }
    }

    private FieldMappingDefinition createFieldMappingDefinition(StitchingDSLParser.FieldMappingDefinitionContext ctx) {
        List<String> path = map(ctx.name(), RuleContext::getText);
        return new FieldMappingDefinition(path, getSourceLocation(ctx), new ArrayList<>(), additionalIdData());
    }


    private UnderlyingServiceHydration createUnderlyingServiceHydration(StitchingDSLParser.UnderlyingServiceHydrationContext ctx) {
        String serviceName = ctx.serviceName().getText();
        String topLevelField = ctx.topLevelField().getText();
        String syntheticField = null;
        if (ctx.syntheticField() != null) {
            syntheticField = ctx.syntheticField().getText();
        }

        List<RemoteArgumentDefinition> remoteArguments = new ArrayList<>();
        List<StitchingDSLParser.RemoteArgumentPairContext> remoteArgumentPairContexts = ctx.remoteCallDefinition()
                .remoteArgumentPair();
        for (StitchingDSLParser.RemoteArgumentPairContext remoteArgumentPairContext : remoteArgumentPairContexts) {
            remoteArguments.add(createRemoteArgumentDefinition(remoteArgumentPairContext));
        }

        String objectIdentifier = "id";
        boolean objectIndexed = false;
        StitchingDSLParser.ObjectResolutionContext objectResolution = ctx.objectResolution();
        if (objectResolution != null) {
            if (objectResolution.objectByIndex() != null) {
                objectIdentifier = null;
                objectIndexed = true;
            } else {
                objectIdentifier = objectResolution.objectByIdentifier().name().getText();
            }
        }

        Integer batchSize = null;
        if (ctx.batchSize() != null) {
            batchSize = Integer.parseInt(ctx.batchSize().intValue().getText());
        }
        return new UnderlyingServiceHydration(getSourceLocation(ctx), new ArrayList<>(), serviceName, topLevelField, syntheticField,
                remoteArguments, objectIdentifier, objectIndexed, batchSize, additionalIdData());
    }

    @Override
    protected ObjectTypeDefinition createObjectTypeDefinition(StitchingDSLParser.ObjectTypeDefinitionContext ctx) {
        ObjectTypeDefinition objectTypeDefinition = super.createObjectTypeDefinition(ctx);
        if (ctx.typeTransformation() == null) {
            return objectTypeDefinition;
        }
        TypeMappingDefinition typeMappingDefinition = createTypeMappingDef(ctx.typeTransformation(), ctx.name());
        ObjectTypeDefinitionWithTransformation.Builder builder = ObjectTypeDefinitionWithTransformation.newObjectTypeDefinitionWithTransformation(objectTypeDefinition);
        addCommonData(builder, ctx);
        return builder
                .typeMappingDefinition(typeMappingDefinition)
                .build();
    }

    @Override
    protected UnionTypeDefinition createUnionTypeDefinition(StitchingDSLParser.UnionTypeDefinitionContext ctx) {
        UnionTypeDefinition unionTypeDefinition = super.createUnionTypeDefinition(ctx);
        if (ctx.typeTransformation() == null) {
            return unionTypeDefinition;
        }
        TypeMappingDefinition typeMappingDefinition = createTypeMappingDef(ctx.typeTransformation(), ctx.name());
        UnionTypeDefinitionWithTransformation.Builder builder = UnionTypeDefinitionWithTransformation.newUnionTypeDefinitionWithTransformation(unionTypeDefinition);
        addCommonData(builder, ctx);
        return builder
                .typeMappingDefinition(typeMappingDefinition)
                .build();
    }

    @Override
    protected InterfaceTypeDefinition createInterfaceTypeDefinition(StitchingDSLParser.InterfaceTypeDefinitionContext ctx) {
        InterfaceTypeDefinition interfaceTypeDefinition = super.createInterfaceTypeDefinition(ctx);
        if (ctx.typeTransformation() == null) {
            return interfaceTypeDefinition;
        }
        TypeMappingDefinition typeMappingDefinition = createTypeMappingDef(ctx.typeTransformation(), ctx.name());
        InterfaceTypeDefinitionWithTransformation.Builder builder = InterfaceTypeDefinitionWithTransformation.newInterfaceTypeDefinitionWithTransformation(interfaceTypeDefinition);
        addCommonData(builder, ctx);
        return builder
                .typeMappingDefinition(typeMappingDefinition)
                .build();
    }

    @Override
    protected InputObjectTypeDefinition createInputObjectTypeDefinition(StitchingDSLParser.InputObjectTypeDefinitionContext ctx) {
        InputObjectTypeDefinition inputObjectTypeDefinition = super.createInputObjectTypeDefinition(ctx);
        if (ctx.typeTransformation() == null) {
            return inputObjectTypeDefinition;
        }
        TypeMappingDefinition typeMappingDefinition = createTypeMappingDef(ctx.typeTransformation(), ctx.name());
        InputObjectTypeDefinitionWithTransformation.Builder builder = InputObjectTypeDefinitionWithTransformation.newInputObjectTypeDefinitionWithTransformation(inputObjectTypeDefinition);
        addCommonData(builder, ctx);
        return builder
                .typeMappingDefinition(typeMappingDefinition)
                .build();
    }

    @Override
    protected EnumTypeDefinition createEnumTypeDefinition(StitchingDSLParser.EnumTypeDefinitionContext ctx) {
        EnumTypeDefinition enumTypeDefinition = super.createEnumTypeDefinition(ctx);
        if (ctx.typeTransformation() == null) {
            return enumTypeDefinition;
        }
        TypeMappingDefinition typeMappingDefinition = createTypeMappingDef(ctx.typeTransformation(), ctx.name());
        EnumTypeDefinitionWithTransformation.Builder builder = EnumTypeDefinitionWithTransformation.newEnumTypeDefinitionWithTransformation(enumTypeDefinition);
        addCommonData(builder, ctx);
        return builder
                .typeMappingDefinition(typeMappingDefinition)
                .build();
    }

    @Override
    protected ScalarTypeDefinition createScalarTypeDefinition(StitchingDSLParser.ScalarTypeDefinitionContext ctx) {
        ScalarTypeDefinition scalarTypeDefinition = super.createScalarTypeDefinition(ctx);
        if (ctx.typeTransformation() == null) {
            return scalarTypeDefinition;
        }
        TypeMappingDefinition typeMappingDefinition = createTypeMappingDef(ctx.typeTransformation(), ctx.name());
        ScalarTypeDefinitionWithTransformation.Builder builder = ScalarTypeDefinitionWithTransformation.newScalarTypeDefinitionWithTransformation(scalarTypeDefinition);
        addCommonData(builder, ctx);
        return builder
                .typeMappingDefinition(typeMappingDefinition)
                .build();
    }


    private TypeMappingDefinition createTypeMappingDef(StitchingDSLParser.TypeTransformationContext typeTransformationContext, StitchingDSLParser.NameContext name) {
        TypeMappingDefinition typeMappingDefinition = new TypeMappingDefinition(null, new ArrayList<>(), additionalIdData());
        typeMappingDefinition.setUnderlyingName(typeTransformationContext.typeMappingDefinition().name().getText());
        typeMappingDefinition.setOverallName(name.getText());
        return typeMappingDefinition;
    }

    private RemoteArgumentDefinition createRemoteArgumentDefinition(StitchingDSLParser.RemoteArgumentPairContext
                                                                            remoteArgumentPairContext) {
        return new RemoteArgumentDefinition(remoteArgumentPairContext.name().getText(),
                createRemoteArgumentSource(remoteArgumentPairContext.remoteArgumentSource()),
                getSourceLocation(remoteArgumentPairContext),
                additionalIdData());
    }

    private RemoteArgumentSource createRemoteArgumentSource(StitchingDSLParser.RemoteArgumentSourceContext ctx) {
        RemoteArgumentSource.SourceType argumentType = null;
        String argumentName = null;
        List<String> path = null;

        if (ctx.fieldArgumentReference() != null) {
            argumentName = ctx.fieldArgumentReference().name().getText();
            argumentType = FIELD_ARGUMENT;
        } else if (ctx.contextArgumentReference() != null) {
            argumentName = ctx.contextArgumentReference().name().getText();
            argumentType = CONTEXT;
        } else if (ctx.sourceObjectReference() != null) {
            path = map(ctx.sourceObjectReference().name(), RuleContext::getText);
            argumentType = OBJECT_FIELD;
        } else {
            assertShouldNeverHappen();
        }

        return new RemoteArgumentSource(argumentName, path, argumentType, getSourceLocation(ctx), additionalIdData());
    }
}
