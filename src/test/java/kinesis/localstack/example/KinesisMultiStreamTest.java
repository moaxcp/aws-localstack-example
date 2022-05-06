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
import software.amazon.kinesis.common.StreamConfig;
import software.amazon.kinesis.common.StreamIdentifier;
import software.amazon.kinesis.coordinator.Scheduler;
import software.amazon.kinesis.exceptions.InvalidStateException;
import software.amazon.kinesis.exceptions.ShutdownException;
import software.amazon.kinesis.lifecycle.events.InitializationInput;
import software.amazon.kinesis.lifecycle.events.LeaseLostInput;
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
import software.amazon.kinesis.lifecycle.events.ShardEndedInput;
import software.amazon.kinesis.lifecycle.events.ShutdownRequestedInput;
import software.amazon.kinesis.processor.FormerStreamsLeasesDeletionStrategy;
import software.amazon.kinesis.processor.FormerStreamsLeasesDeletionStrategy.NoLeaseDeletionStrategy;
import software.amazon.kinesis.processor.MultiStreamTracker;
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
import static software.amazon.kinesis.common.InitialPositionInStream.TRIM_HORIZON;
import static software.amazon.kinesis.common.StreamIdentifier.singleStreamInstance;

@Testcontainers
public class KinesisMultiStreamTest {
    static class TestProcessorFactory implements ShardRecordProcessorFactory {

        private final TestKinesisRecordService service;

        public TestProcessorFactory(TestKinesisRecordService service) {
            this.service = service;
        }

        @Override
        public ShardRecordProcessor shardRecordProcessor() {
            throw new UnsupportedOperationException("must have streamIdentifier");
        }

        public ShardRecordProcessor shardRecordProcessor(StreamIdentifier streamIdentifier) {
            return new TestRecordProcessor(service, streamIdentifier);
        }
    }

    static class TestRecordProcessor implements ShardRecordProcessor {

        public final TestKinesisRecordService service;
        public final StreamIdentifier streamIdentifier;

        public TestRecordProcessor(TestKinesisRecordService service, StreamIdentifier streamIdentifier) {
            this.service = service;
            this.streamIdentifier = streamIdentifier;
        }

        @Override
        public void initialize(InitializationInput initializationInput) {

        }

        @Override
        public void processRecords(ProcessRecordsInput processRecordsInput) {
            service.addRecord(streamIdentifier, processRecordsInput);
        }

        @Override
        public void leaseLost(LeaseLostInput leaseLostInput) {

        }

        @Override
        public void shardEnded(ShardEndedInput shardEndedInput) {
            try {
                shardEndedInput.checkpointer().checkpoint();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void shutdownRequested(ShutdownRequestedInput shutdownRequestedInput) {

        }
    }

    static class TestKinesisRecordService {
        private List<ProcessRecordsInput> firstStreamRecords = Collections.synchronizedList(new ArrayList<>());
        private List<ProcessRecordsInput> secondStreamRecords = Collections.synchronizedList(new ArrayList<>());

        public void addRecord(StreamIdentifier streamIdentifier, ProcessRecordsInput processRecordsInput) {
            if(streamIdentifier.streamName().contains(firstStreamName)) {
                firstStreamRecords.add(processRecordsInput);
            } else if(streamIdentifier.streamName().contains(secondStreamName)) {
                secondStreamRecords.add(processRecordsInput);
            } else {
                throw new IllegalStateException("no list for stream " + streamIdentifier);
            }
        }

        public List<ProcessRecordsInput> getFirstStreamRecords() {
            return Collections.unmodifiableList(firstStreamRecords);
        }

        public List<ProcessRecordsInput> getSecondStreamRecords() {
            return Collections.unmodifiableList(secondStreamRecords);
        }
    }

    public static final String firstStreamName = "first-stream-name";
    public static final String secondStreamName = "second-stream-name";
    public static final String partitionKey = "partition-key";

    DockerImageName localstackImage = DockerImageName.parse("localstack/localstack:latest");

    @Container
    public LocalStackContainer localstack = new LocalStackContainer(localstackImage)
            .withServices(KINESIS, CLOUDWATCH)
            .withEnv("KINESIS_INITIALIZE_STREAMS", firstStreamName + ":1," + secondStreamName + ":1");

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

        MultiStreamTracker tracker = new MultiStreamTracker() {

            private List<StreamConfig> configs = List.of(
                    new StreamConfig(singleStreamInstance(firstStreamName), InitialPositionInStreamExtended.newInitialPosition(TRIM_HORIZON)),
                    new StreamConfig(singleStreamInstance(secondStreamName), InitialPositionInStreamExtended.newInitialPosition(TRIM_HORIZON)));
            @Override
            public List<StreamConfig> streamConfigList() {
                return configs;
            }

            @Override
            public FormerStreamsLeasesDeletionStrategy formerStreamsLeasesDeletionStrategy() {
                return new NoLeaseDeletionStrategy();
            }
        };

        ConfigsBuilder configsBuilder = new ConfigsBuilder(tracker, "KinesisPratTest", kinesisClient, dynamoClient, cloudWatchClient, UUID.randomUUID().toString(), new TestProcessorFactory(service));

        scheduler = new Scheduler(
                configsBuilder.checkpointConfig(),
                configsBuilder.coordinatorConfig(),
                configsBuilder.leaseManagementConfig(),
                configsBuilder.lifecycleConfig(),
                configsBuilder.metricsConfig(),
                configsBuilder.processorConfig().callProcessRecordsEvenForEmptyRecordList(false),
                configsBuilder.retrievalConfig()
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
    void testFirstStream() {
        String expected = "Hello";
        producer.addUserRecord(firstStreamName, partitionKey, ByteBuffer.wrap(expected.getBytes(StandardCharsets.UTF_8)));

        var result = await().timeout(600, TimeUnit.SECONDS)
                .until(() -> service.getFirstStreamRecords().stream()
                .flatMap(r -> r.records().stream())
                        .map(KinesisClientRecord::data)
                        .map(r -> StandardCharsets.UTF_8.decode(r).toString())
                .collect(toList()), records -> records.size() > 0);
        assertThat(result).anyMatch(r -> r.equals(expected));
    }

    @Test
    void testSecondStream() {
        String expected = "Hello";
        producer.addUserRecord(secondStreamName, partitionKey, ByteBuffer.wrap(expected.getBytes(StandardCharsets.UTF_8)));

        var result = await().timeout(600, TimeUnit.SECONDS)
                .until(() -> service.getSecondStreamRecords().stream()
                        .flatMap(r -> r.records().stream())
                        .map(KinesisClientRecord::data)
                        .map(r -> StandardCharsets.UTF_8.decode(r).toString())
                        .collect(toList()), records -> records.size() > 0);
        assertThat(result).anyMatch(r -> r.equals(expected));
    }
}
