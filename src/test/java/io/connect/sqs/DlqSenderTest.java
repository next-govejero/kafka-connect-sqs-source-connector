package io.connect.sqs;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DlqSenderTest {

    private SqsSourceConnectorConfig config;
    private DlqSender dlqSender;
    private Map<String, String> props;

    @BeforeEach
    void setUp() {
        props = new HashMap<>();
        props.put(SqsSourceConnectorConfig.SQS_QUEUE_URL_CONFIG,
                "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue");
        props.put(SqsSourceConnectorConfig.KAFKA_TOPIC_CONFIG, "test-topic");
        props.put(SqsSourceConnectorConfig.AWS_REGION_CONFIG, "us-east-1");
        props.put(SqsSourceConnectorConfig.SASL_MECHANISM_CONFIG, "SCRAM-SHA-512");
        props.put(SqsSourceConnectorConfig.SASL_JAAS_CONFIG,
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"test\" password=\"test\";");
    }

    @Test
    void shouldReturnNullWhenDlqNotConfigured() {
        config = new SqsSourceConnectorConfig(props);
        dlqSender = new DlqSender(config);

        Message message = Message.builder().messageId("msg-1").body("body").build();
        Exception error = new RuntimeException("error");

        SourceRecord record = dlqSender.createDlqRecord(message, error, 0);

        assertThat(record).isNull();
    }

    @Test
    void shouldCreateDlqRecordWhenConfigured() {
        props.put(SqsSourceConnectorConfig.DLQ_TOPIC_CONFIG, "dlq-topic");
        config = new SqsSourceConnectorConfig(props);
        dlqSender = new DlqSender(config);

        Message message = Message.builder()
                .messageId("msg-1")
                .body("body")
                .md5OfBody("md5")
                .build();
        Exception error = new RuntimeException("error message");

        SourceRecord record = dlqSender.createDlqRecord(message, error, 2);

        assertThat(record).isNotNull();
        assertThat(record.topic()).isEqualTo("dlq-topic");
        assertThat(record.key()).isEqualTo("msg-1");
        assertThat(record.value()).isEqualTo("body");

        // Check headers
        assertThat(record.headers().lastWithName("sqs.message.id").value()).isEqualTo("msg-1");
        assertThat(record.headers().lastWithName("error.message").value()).isEqualTo("error message");
        assertThat(record.headers().lastWithName("retry.count").value()).isEqualTo(2);
        assertThat(record.headers().lastWithName("sqs.md5.of.body").value()).isEqualTo("md5");
    }
}
