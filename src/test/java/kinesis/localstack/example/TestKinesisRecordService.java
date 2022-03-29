package kinesis.localstack.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;

public class TestKinesisRecordService {
    private List<ProcessRecordsInput> records = Collections.synchronizedList(new ArrayList<>());

    public void addRecord(ProcessRecordsInput processRecordsInput) {
        records.add(processRecordsInput);
    }

    public List<ProcessRecordsInput> getRecords() {
        return Collections.unmodifiableList(records);
    }
}
