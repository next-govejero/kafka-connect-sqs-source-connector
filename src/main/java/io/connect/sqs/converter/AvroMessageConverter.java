package io.connect.sqs.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder.Type;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Message converter that converts SQS messages to Avro format using Confluent Schema Registry.
 *
 * <p>This converter supports:
 * <ul>
 *   <li>Automatic schema inference from JSON messages</li>
 *   <li>Schema registration with Schema Registry</li>
 *   <li>Schema evolution support</li>
 *   <li>Custom schema ID configuration</li>
 * </ul>
 *
 * <p>Example configuration:
 * <pre>
 * message.converter.class=io.connect.sqs.converter.AvroMessageConverter
 * schema.registry.url=http://schema-registry:8081
 * value.schema.id=1  # Optional: use specific schema ID
 * schema.auto.register=true
 * </pre>
 */
public class AvroMessageConverter extends SchemaRegistryConverter {

    private static final Logger log = LoggerFactory.getLogger(AvroMessageConverter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private Schema cachedValueSchema;
    private org.apache.kafka.connect.data.Schema cachedConnectSchema;

    @Override
    protected String getSchemaType() {
        return "Avro";
    }

    @Override
    protected SchemaAndValue convertValue(Message message, SqsSourceConnectorConfig config) {
        String messageBody = message.body();

        try {
            // Parse the JSON message
            JsonNode jsonNode = objectMapper.readTree(messageBody);

            if (!jsonNode.isObject()) {
                // If not an object, wrap in a simple record
                return createSimpleStringValue(messageBody);
            }

            // Get or create Avro schema
            Schema avroSchema = getOrCreateSchema(jsonNode, config);

            // Convert JSON to Avro GenericRecord
            GenericRecord avroRecord = jsonToAvro(jsonNode, avroSchema);

            // Convert to Connect schema and value
            org.apache.kafka.connect.data.Schema connectSchema = avroToConnectSchema(avroSchema);
            Object connectValue = avroToConnectValue(avroRecord, connectSchema);

            log.debug("Converted message {} to Avro format with schema {}",
                    message.messageId(), avroSchema.getName());

            return new SchemaAndValue(connectSchema, connectValue);

        } catch (IOException e) {
            log.warn("Failed to parse message {} as JSON, using raw string", message.messageId(), e);
            return createSimpleStringValue(messageBody);
        } catch (Exception e) {
            throw new DataException("Failed to convert message " + message.messageId() + " to Avro", e);
        }
    }

    private SchemaAndValue createSimpleStringValue(String value) {
        return new SchemaAndValue(org.apache.kafka.connect.data.Schema.STRING_SCHEMA, value);
    }

    private Schema getOrCreateSchema(JsonNode jsonNode, SqsSourceConnectorConfig config) {
        Integer schemaId = config.getValueSchemaId();

        if (schemaId != null) {
            // Use specified schema ID
            String schemaString = fetchSchemaById(schemaId);
            return new Schema.Parser().parse(schemaString);
        }

        if (config.isSchemaUseLatestVersion()) {
            // Use latest schema from registry
            String subject = getSubjectName(config.getKafkaTopic(), false);
            String schemaString = fetchLatestSchema(subject);
            return new Schema.Parser().parse(schemaString);
        }

        // Infer schema from JSON if caching is enabled and schema exists
        if (cachedValueSchema != null && isCompatibleWithSchema(jsonNode, cachedValueSchema)) {
            return cachedValueSchema;
        }

        // Generate schema from JSON structure
        Schema inferredSchema = inferSchemaFromJson(jsonNode, config.getKafkaTopic());

        if (config.isSchemaAutoRegister()) {
            // Register the schema
            String subject = getSubjectName(config.getKafkaTopic(), false);
            AvroSchema avroSchema = new AvroSchema(inferredSchema);
            int registeredId = registerSchema(subject, avroSchema);
            log.info("Registered Avro schema with ID {} for subject {}", registeredId, subject);
        }

        cachedValueSchema = inferredSchema;
        return inferredSchema;
    }

    private Schema inferSchemaFromJson(JsonNode jsonNode, String topicName) {
        String schemaName = topicName.replaceAll("[^a-zA-Z0-9]", "_") + "_value";
        String namespace = "io.connect.sqs.avro";

        SchemaBuilder.FieldAssembler<Schema> fields = SchemaBuilder
                .record(schemaName)
                .namespace(namespace)
                .fields();

        Iterator<Map.Entry<String, JsonNode>> fieldIterator = jsonNode.fields();
        while (fieldIterator.hasNext()) {
            Map.Entry<String, JsonNode> field = fieldIterator.next();
            fields = addFieldToSchema(fields, field.getKey(), field.getValue());
        }

        return fields.endRecord();
    }

    private SchemaBuilder.FieldAssembler<Schema> addFieldToSchema(
            SchemaBuilder.FieldAssembler<Schema> fields, String fieldName, JsonNode value) {

        if (value.isNull()) {
            return fields.name(fieldName).type().nullable().stringType().noDefault();
        } else if (value.isBoolean()) {
            return fields.name(fieldName).type().nullable().booleanType().noDefault();
        } else if (value.isInt()) {
            return fields.name(fieldName).type().nullable().intType().noDefault();
        } else if (value.isLong()) {
            return fields.name(fieldName).type().nullable().longType().noDefault();
        } else if (value.isDouble() || value.isFloat()) {
            return fields.name(fieldName).type().nullable().doubleType().noDefault();
        } else if (value.isTextual()) {
            return fields.name(fieldName).type().nullable().stringType().noDefault();
        } else if (value.isArray()) {
            Schema itemSchema = inferArrayItemSchema(value);
            return fields.name(fieldName).type().nullable().array().items(itemSchema).noDefault();
        } else if (value.isObject()) {
            Schema nestedSchema = inferNestedObjectSchema(fieldName, value);
            return fields.name(fieldName).type().nullable().type(nestedSchema).noDefault();
        } else {
            // Default to string for unknown types
            return fields.name(fieldName).type().nullable().stringType().noDefault();
        }
    }

    private Schema inferArrayItemSchema(JsonNode arrayNode) {
        if (!arrayNode.isArray() || arrayNode.isEmpty()) {
            return Schema.create(Schema.Type.STRING);
        }

        JsonNode firstElement = arrayNode.get(0);
        if (firstElement.isObject()) {
            return inferNestedObjectSchema("item", firstElement);
        } else if (firstElement.isBoolean()) {
            return Schema.create(Schema.Type.BOOLEAN);
        } else if (firstElement.isInt()) {
            return Schema.create(Schema.Type.INT);
        } else if (firstElement.isLong()) {
            return Schema.create(Schema.Type.LONG);
        } else if (firstElement.isDouble() || firstElement.isFloat()) {
            return Schema.create(Schema.Type.DOUBLE);
        } else {
            return Schema.create(Schema.Type.STRING);
        }
    }

    private Schema inferNestedObjectSchema(String name, JsonNode objectNode) {
        String schemaName = name + "_record";
        SchemaBuilder.FieldAssembler<Schema> fields = SchemaBuilder
                .record(schemaName)
                .namespace("io.connect.sqs.avro")
                .fields();

        Iterator<Map.Entry<String, JsonNode>> fieldIterator = objectNode.fields();
        while (fieldIterator.hasNext()) {
            Map.Entry<String, JsonNode> field = fieldIterator.next();
            fields = addFieldToSchema(fields, field.getKey(), field.getValue());
        }

        return fields.endRecord();
    }

    private boolean isCompatibleWithSchema(JsonNode jsonNode, Schema schema) {
        if (schema.getType() != Schema.Type.RECORD) {
            return false;
        }

        for (Schema.Field field : schema.getFields()) {
            if (!jsonNode.has(field.name())) {
                // Check if field has default value
                if (field.defaultVal() == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private GenericRecord jsonToAvro(JsonNode jsonNode, Schema schema) {
        GenericRecord record = new GenericData.Record(schema);

        for (Schema.Field field : schema.getFields()) {
            JsonNode fieldValue = jsonNode.get(field.name());
            Object avroValue = jsonNodeToAvroValue(fieldValue, field.schema());
            record.put(field.name(), avroValue);
        }

        return record;
    }

    private Object jsonNodeToAvroValue(JsonNode node, Schema schema) {
        if (node == null || node.isNull()) {
            return null;
        }

        Schema actualSchema = schema;
        // Handle union types (nullable fields)
        if (schema.getType() == Schema.Type.UNION) {
            for (Schema unionSchema : schema.getTypes()) {
                if (unionSchema.getType() != Schema.Type.NULL) {
                    actualSchema = unionSchema;
                    break;
                }
            }
        }

        switch (actualSchema.getType()) {
            case BOOLEAN:
                return node.asBoolean();
            case INT:
                return node.asInt();
            case LONG:
                return node.asLong();
            case FLOAT:
                return (float) node.asDouble();
            case DOUBLE:
                return node.asDouble();
            case STRING:
                return node.asText();
            case ARRAY:
                List<Object> list = new ArrayList<>();
                for (JsonNode item : node) {
                    list.add(jsonNodeToAvroValue(item, actualSchema.getElementType()));
                }
                return list;
            case RECORD:
                return jsonToAvro(node, actualSchema);
            case BYTES:
                return node.binaryValue();
            default:
                return node.asText();
        }
    }

    private org.apache.kafka.connect.data.Schema avroToConnectSchema(Schema avroSchema) {
        org.apache.kafka.connect.data.SchemaBuilder builder;

        switch (avroSchema.getType()) {
            case RECORD:
                builder = org.apache.kafka.connect.data.SchemaBuilder.struct()
                        .name(avroSchema.getFullName());
                for (Schema.Field field : avroSchema.getFields()) {
                    org.apache.kafka.connect.data.Schema fieldSchema = avroFieldToConnectSchema(field.schema());
                    builder.field(field.name(), fieldSchema);
                }
                return builder.build();
            default:
                return avroFieldToConnectSchema(avroSchema);
        }
    }

    private org.apache.kafka.connect.data.Schema avroFieldToConnectSchema(Schema avroSchema) {
        // Handle union types
        if (avroSchema.getType() == Schema.Type.UNION) {
            Schema nonNullSchema = null;
            boolean hasNull = false;
            for (Schema unionSchema : avroSchema.getTypes()) {
                if (unionSchema.getType() == Schema.Type.NULL) {
                    hasNull = true;
                } else {
                    nonNullSchema = unionSchema;
                }
            }
            if (nonNullSchema != null) {
                org.apache.kafka.connect.data.Schema connectSchema = avroFieldToConnectSchema(nonNullSchema);
                if (hasNull) {
                    return org.apache.kafka.connect.data.SchemaBuilder.type(connectSchema.type()).optional().build();
                }
                return connectSchema;
            }
            return org.apache.kafka.connect.data.Schema.OPTIONAL_STRING_SCHEMA;
        }

        switch (avroSchema.getType()) {
            case BOOLEAN:
                return org.apache.kafka.connect.data.Schema.BOOLEAN_SCHEMA;
            case INT:
                return org.apache.kafka.connect.data.Schema.INT32_SCHEMA;
            case LONG:
                return org.apache.kafka.connect.data.Schema.INT64_SCHEMA;
            case FLOAT:
                return org.apache.kafka.connect.data.Schema.FLOAT32_SCHEMA;
            case DOUBLE:
                return org.apache.kafka.connect.data.Schema.FLOAT64_SCHEMA;
            case STRING:
                return org.apache.kafka.connect.data.Schema.STRING_SCHEMA;
            case BYTES:
                return org.apache.kafka.connect.data.Schema.BYTES_SCHEMA;
            case ARRAY:
                return org.apache.kafka.connect.data.SchemaBuilder
                        .array(avroFieldToConnectSchema(avroSchema.getElementType()))
                        .build();
            case RECORD:
                return avroToConnectSchema(avroSchema);
            default:
                return org.apache.kafka.connect.data.Schema.STRING_SCHEMA;
        }
    }

    private Object avroToConnectValue(GenericRecord avroRecord, org.apache.kafka.connect.data.Schema connectSchema) {
        Struct struct = new Struct(connectSchema);

        for (org.apache.kafka.connect.data.Field field : connectSchema.fields()) {
            Object avroValue = avroRecord.get(field.name());
            Object connectValue = convertAvroToConnectValue(avroValue, field.schema());
            struct.put(field, connectValue);
        }

        return struct;
    }

    private Object convertAvroToConnectValue(Object avroValue, org.apache.kafka.connect.data.Schema connectSchema) {
        if (avroValue == null) {
            return null;
        }

        if (avroValue instanceof GenericRecord) {
            return avroToConnectValue((GenericRecord) avroValue, connectSchema);
        } else if (avroValue instanceof List) {
            List<?> avroList = (List<?>) avroValue;
            List<Object> connectList = new ArrayList<>();
            org.apache.kafka.connect.data.Schema elementSchema = connectSchema.valueSchema();
            for (Object item : avroList) {
                connectList.add(convertAvroToConnectValue(item, elementSchema));
            }
            return connectList;
        } else if (avroValue instanceof CharSequence) {
            return avroValue.toString();
        }

        return avroValue;
    }
}
