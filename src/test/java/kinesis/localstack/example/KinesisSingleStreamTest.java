package kinesis.localstack.example;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amazonaws.services.kinesis.producer.KinesisProducer;
import com.amazonaws.services.kinesis.producer.KinesisProducerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.kinesis.common.ConfigsBuilder;
import software.amazon.kinesis.common.InitialPositionInStream;
import software.amazon.kinesis.common.InitialPositionInStreamExtended;
import software.amazon.kinesis.common.KinesisClientUtil;
import software.amazon.kinesis.coordinator.Scheduler;
import software.amazon.kinesis.exceptions.InvalidStateException;
import software.amazon.kinesis.exceptions.ShutdownException;
import software.amazon.kinesis.lifecycle.events.InitializationInput;
import software.amazon.kinesis.lifecycle.events.LeaseLostInput;
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
import software.amazon.kinesis.lifecycle.events.ShardEndedInput;
import software.amazon.kinesis.lifecycle.events.ShutdownRequestedInput;
import software.amazon.kinesis.processor.ShardRecordProcessor;
import software.amazon.kinesis.processor.ShardRecordProcessorFactory;
import software.amazon.kinesis.retrieval.KinesisClientRecord;
import software.amazon.kinesis.retrieval.polling.PollingConfig;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.CLOUDWATCH;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.KINESIS;

@Testcontainers
public class KinesisSingleStreamTest {
    static class TestProcessorFactory implements ShardRecordProcessorFactory {

        private final TestKinesisRecordService service;

        public TestProcessorFactory(TestKinesisRecordService service) {
            this.service = service;
        }

        @Override
        public ShardRecordProcessor shardRecordProcessor() {
            return new TestRecordProcessor(service);
        }
    }

    static class TestRecordProcessor implements ShardRecordProcessor {

        public final TestKinesisRecordService service;

        public TestRecordProcessor(TestKinesisRecordService service) {
            this.service = service;
        }

        @Override
        public void initialize(InitializationInput initializationInput) {

        }

        @Override
        public void processRecords(ProcessRecordsInput processRecordsInput) {
            service.addRecord(processRecordsInput);
        }

        @Override
        public void leaseLost(LeaseLostInput leaseLostInput) {

        }

        @Override
        public void shardEnded(ShardEndedInput shardEndedInput) {
            try {
                shardEndedInput.checkpointer().checkpoint();
            } catch (InvalidStateException e) {
                e.printStackTrace();
            } catch (ShutdownException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void shutdownRequested(ShutdownRequestedInput shutdownRequestedInput) {

        }
    }

    static class TestKinesisRecordService {
        private List<ProcessRecordsInput> records = Collections.synchronizedList(new ArrayList<>());

        public void addRecord(ProcessRecordsInput processRecordsInput) {
            records.add(processRecordsInput);
        }

        public List<ProcessRecordsInput> getRecords() {
            return Collections.unmodifiableList(records);
        }
    }

    public static final String streamName = "stream-name";
    public static final String partitionKey = "partition-key";

    DockerImageName localstackImage = DockerImageName.parse("localstack/localstack:latest");

    @Container
    public LocalStackContainer localstack = new LocalStackContainer(localstackImage)
            .withServices(KINESIS, CLOUDWATCH)
            .withEnv("KINESIS_INITIALIZE_STREAMS", streamName + ":1");

    public Scheduler scheduler;
    public TestKinesisRecordService service = new TestKinesisRecordService();
    public KinesisProducer producer;

    @BeforeEach
    void setup() {
        KinesisAsyncClient kinesisClient = KinesisClientUtil.createKinesisAsyncClient(
                KinesisAsyncClient.builder().endpointOverride(localstack.getEndpointOverride(KINESIS)).region(Region.of(localstack.getRegion()))
        );
        DynamoDbAsyncClient dynamoClient = DynamoDbAsyncClient.builder().region(Region.of(localstack.getRegion())).endpointOverride(localstack.getEndpointOverride(DYNAMODB)).build();
        CloudWatchAsyncClient cloudWatchClient = CloudWatchAsyncClient.builder().region(Region.of(localstack.getRegion())).endpointOverride(localstack.getEndpointOverride(CLOUDWATCH)).build();

        ConfigsBuilder configsBuilder = new ConfigsBuilder(streamName, "KinesisPratTest", kinesisClient, dynamoClient, cloudWatchClient, UUID.randomUUID().toString(), new TestProcessorFactory(service));

        scheduler = new Scheduler(
                configsBuilder.checkpointConfig(),
                configsBuilder.coordinatorConfig(),
                configsBuilder.leaseManagementConfig(),
                configsBuilder.lifecycleConfig(),
                configsBuilder.metricsConfig(),
                configsBuilder.processorConfig().callProcessRecordsEvenForEmptyRecordList(false),
                configsBuilder.retrievalConfig().initialPositionInStreamExtended(InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.TRIM_HORIZON)).retrievalSpecificConfig(
                new PollingConfig(streamName, configsBuilder.kinesisClient()))
        );

        new Thread(scheduler).start();

        producer = producer();
    }

    @AfterEach
    public void teardown() throws ExecutionException, InterruptedException, TimeoutException {
        producer.destroy();
        Future<Boolean> gracefulShutdownFuture = scheduler.startGracefulShutdown();
        gracefulShutdownFuture.get(60, TimeUnit.SECONDS);
    }

    public KinesisProducer producer() {
        var configuration = new KinesisProducerConfiguration()
                .setVerifyCertificate(false)
                .setCredentialsProvider(localstack.getDefaultCredentialsProvider())
                .setMetricsCredentialsProvider(localstack.getDefaultCredentialsProvider())
                .setRegion(localstack.getRegion())
                .setCloudwatchEndpoint(localstack.getEndpointOverride(CLOUDWATCH).getHost())
                .setCloudwatchPort(localstack.getEndpointOverride(CLOUDWATCH).getPort())
                .setKinesisEndpoint(localstack.getEndpointOverride(KINESIS).getHost())
                .setKinesisPort(localstack.getEndpointOverride(KINESIS).getPort());

        return new KinesisProducer(configuration);
    }

    @Test
    void test() {
        String expected = "Hello";
        producer.addUserRecord(streamName, partitionKey, ByteBuffer.wrap(expected.getBytes(StandardCharsets.UTF_8)));

        var result = await().timeout(600, TimeUnit.SECONDS)
                .until(() -> service.getRecords().stream()
                .flatMap(r -> r.records().stream())
                        .map(KinesisClientRecord::data)
                        .map(r -> StandardCharsets.UTF_8.decode(r).toString())
                .collect(toList()), records -> records.size() > 0);
        assertThat(result).anyMatch(r -> r.equals(expected));
    }
}
