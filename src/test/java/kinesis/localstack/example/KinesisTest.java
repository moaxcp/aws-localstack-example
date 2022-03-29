package kinesis.localstack.example;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.kinesis.producer.KinesisProducer;
import com.amazonaws.services.kinesis.producer.KinesisProducerConfiguration;
import org.junit.Rule;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.regions.Region;

import static org.testcontainers.containers.localstack.LocalStackContainer.Service.CLOUDWATCH;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.KINESIS;

@Testcontainers
public class KinesisTest {
    DockerImageName localstackImage = DockerImageName.parse("localstack/localstack:0.12.12");

    @Container
    public LocalStackContainer localstack = new LocalStackContainer(localstackImage)
            .withServices(KINESIS);

    public KinesisProducer producer() {
        var configuration = new KinesisProducerConfiguration()
                .setRecordMaxBufferedTime(100)
                .setRecordTtl(30000)
                .setRequestTimeout(5000)
                .setRegion(localstack.getRegion())
                .setCloudwatchEndpoint(localstack.getEndpointOverride(CLOUDWATCH).getHost())
                .setCloudwatchPort(localstack.getEndpointOverride(CLOUDWATCH).getPort())
                .setKinesisEndpoint(localstack.getEndpointOverride(KINESIS).getHost())
                .setKinesisPort(localstack.getEndpointOverride(KINESIS).getPort());

        return new KinesisProducer(configuration);
    }

    @Test
    void test() {
        var producer = producer();
        producer.addUserRecord("stream-name", "partition-key", ByteBuffer.wrap("Hello".getBytes(StandardCharsets.UTF_8)));
    }
}
