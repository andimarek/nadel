package benchmark;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import graphql.Assert;
import graphql.ExecutionResult;
import graphql.nadel.Nadel;
import graphql.nadel.NadelExecutionInput;
import graphql.nadel.ServiceExecution;
import graphql.nadel.ServiceExecutionFactory;
import graphql.nadel.ServiceExecutionResult;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * See http://hg.openjdk.java.net/code-tools/jmh/file/tip/jmh-samples/src/main/java/org/openjdk/jmh/samples/ for more samples
 * on what you can do with JMH
 *
 * You MUST have the JMH plugin for IDEA in place for this to work :  https://github.com/artyushov/idea-jmh-plugin
 *
 * Install it and then just hit "Run" on a certain benchmark method
 */

public class LargeRenamedResponseBenchmark {


    @State(Scope.Benchmark)
    public static class NadelInstance {
        Nadel nadel;
        String query;

        @Setup
        public void setup() throws IOException {
            ObjectMapper objectMapper = new ObjectMapper();
            String schemaString = readFromClasspath("large_response_benchmark_schema.graphqls");
            TypeDefinitionRegistry typeDefinitionRegistry = new SchemaParser().parse(schemaString);

            String responseString = readFromClasspath("large_underlying_service_result.json");
            Map responseMap = objectMapper.readValue(responseString, Map.class);
            ServiceExecutionResult serviceExecutionResult = new ServiceExecutionResult((Map<String, Object>) responseMap.get("data"));
            ServiceExecution serviceExecution = serviceExecutionParameters -> CompletableFuture.completedFuture(serviceExecutionResult);
            ServiceExecutionFactory serviceExecutionFactory = new ServiceExecutionFactory() {
                @Override
                public ServiceExecution getServiceExecution(String serviceName) {
                    return serviceExecution;
                }

                @Override
                public TypeDefinitionRegistry getUnderlyingTypeDefinitions(String serviceName) {
                    return typeDefinitionRegistry;
                }
            };
            String nsdl = readFromClasspath("renamed_large_response_benchmark_schema.nadel");
            nadel = Nadel.newNadel().dsl("activity", nsdl).serviceExecutionFactory(serviceExecutionFactory).build();
            query = readFromClasspath("large_renamed_response_benchmark_query.graphql");
        }

        private String readFromClasspath(String file) throws IOException {
            URL url = Resources.getResource(file);
            return Resources.toString(url, Charsets.UTF_8);
        }
    }


    @Benchmark
    @Warmup(iterations = 2)
    @Measurement(iterations = 3, time = 10)
    @Threads(1)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public ExecutionResult benchMarkAvgTime(NadelInstance nadelInstance) throws ExecutionException, InterruptedException {
        NadelExecutionInput nadelExecutionInput = NadelExecutionInput.newNadelExecutionInput()
                .query(nadelInstance.query)
                .build();
        ExecutionResult executionResult = nadelInstance.nadel.execute(nadelExecutionInput).get();
        Assert.assertTrue(executionResult.getErrors().size() == 0);
//        System.out.println("data:" +executionResult.getData());
        return executionResult;
    }


}
