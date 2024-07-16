package s3.localstack.example;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

@Testcontainers
public class S3Test {

    DockerImageName localstackImage = DockerImageName.parse("localstack/localstack:latest");

    @Container
    public LocalStackContainer localstack = new LocalStackContainer(localstackImage)
        .withServices(S3);

    private AmazonS3 s3Client;

    @BeforeEach
    void setup() {
        s3Client = AmazonS3ClientBuilder.standard()
            .withForceGlobalBucketAccessEnabled(true)
            .withEndpointConfiguration(localstack.getEndpointConfiguration(S3))
            .withPathStyleAccessEnabled(true)
            .build();
    }

    @Test
    void test() {
        s3Client.createBucket("bucket");
        s3Client.putObject("bucket", "key", "value");
        s3Client.listObjects("bucket");
        s3Client.deleteObject("bucket", "key");
        s3Client.deleteBucket("bucket");
    }
}
