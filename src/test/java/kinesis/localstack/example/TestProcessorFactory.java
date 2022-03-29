package kinesis.localstack.example;

import software.amazon.kinesis.processor.ShardRecordProcessor;
import software.amazon.kinesis.processor.ShardRecordProcessorFactory;

public class TestProcessorFactory implements ShardRecordProcessorFactory {

    private final TestKinesisRecordService service;

    public TestProcessorFactory(TestKinesisRecordService service) {
        this.service = service;
    }

    @Override
    public ShardRecordProcessor shardRecordProcessor() {
        return new TestRecordProcessor(service);
    }
}
