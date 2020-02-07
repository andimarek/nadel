package benchmark;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;
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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * See http://hg.openjdk.java.net/code-tools/jmh/file/tip/jmh-samples/src/main/java/org/openjdk/jmh/samples/ for more samples
 * on what you can do with JMH
 *
 * You MUST have the JMH plugin for IDEA in place for this to work :  https://github.com/artyushov/idea-jmh-plugin
 *
 * Install it and then just hit "Run" on a certain benchmark method
 */

public class LargeResponseBenchmark {


    @State(Scope.Benchmark)
    public static class NadelInstance {
        Nadel nadel;
        String query;
        String json;
        ObjectMapper objectMapper;

        @Setup
        public void setup() throws IOException {

            objectMapper = new ObjectMapper();
            String schemaString = readFromClasspath("large_response_benchmark_schema.graphqls");
            TypeDefinitionRegistry typeDefinitionRegistry = new SchemaParser().parse(schemaString);

            this.json = readFromClasspath("large_underlying_service_result.json");
            ServiceExecutionFactory serviceExecutionFactory = new ServiceExecutionFactory() {
                @Override
                public ServiceExecution getServiceExecution(String serviceName) {
                    Map responseMap = null;
                    try {
                        responseMap = objectMapper.readValue(json, Map.class);
                    } catch (IOException e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                    ServiceExecutionResult serviceExecutionResult = new ServiceExecutionResult((Map<String, Object>) responseMap.get("data"));
                    ServiceExecution serviceExecution = serviceExecutionParameters -> CompletableFuture.completedFuture(serviceExecutionResult);
                    return serviceExecution;
                }

                @Override
                public TypeDefinitionRegistry getUnderlyingTypeDefinitions(String serviceName) {
                    return typeDefinitionRegistry;
                }
            };
            String nsdl = "service activity{" + schemaString + "}";
            nadel = Nadel.newNadel().dsl(nsdl).serviceExecutionFactory(serviceExecutionFactory).build();
            query = readFromClasspath("large_response_benchmark_query.graphql");
        }

        private String readFromClasspath(String file) throws IOException {
            URL url = Resources.getResource(file);
            return Resources.toString(url, Charsets.UTF_8);
        }
    }


    @Benchmark
    @Warmup(iterations = 2)
    @Measurement(iterations = 3)
    @Threads(1)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public ExecutionResult benchMarkAvgTime(NadelInstance nadelInstance) throws ExecutionException, InterruptedException {
        NadelExecutionInput nadelExecutionInput = NadelExecutionInput.newNadelExecutionInput()
                .forkJoinPool(ForkJoinPool.commonPool())
                .query(nadelInstance.query)
                .build();
        ExecutionResult executionResult = nadelInstance.nadel.execute(nadelExecutionInput).get();
        return executionResult;
    }

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        NadelInstance nadelInstance = new NadelInstance();
        nadelInstance.setup();
        NadelExecutionInput nadelExecutionInput = NadelExecutionInput.newNadelExecutionInput()
                .forkJoinPool(ForkJoinPool.commonPool())
                .query(nadelInstance.query)
                .build();
        ExecutionResult executionResult = nadelInstance.nadel.execute(nadelExecutionInput).get();
        System.out.println(executionResult);
    }


}
