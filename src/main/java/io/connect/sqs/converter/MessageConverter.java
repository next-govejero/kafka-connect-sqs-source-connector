package io.connect.sqs.converter;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.apache.kafka.connect.source.SourceRecord;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Interface for converting SQS messages to Kafka SourceRecords.
 */
public interface MessageConverter {

    /**
     * Convert an SQS message to a Kafka SourceRecord.
     *
     * @param message SQS message to convert
     * @param config Connector configuration
     * @return Kafka SourceRecord
     */
    SourceRecord convert(Message message, SqsSourceConnectorConfig config);
}
