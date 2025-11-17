package io.connect.sqs.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Message converter that converts SQS messages to Protobuf format using Confluent Schema Registry.
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
 * message.converter.class=io.connect.sqs.converter.ProtobufMessageConverter
 * schema.registry.url=http://schema-registry:8081
 * value.schema.id=1  # Optional: use specific schema ID
 * schema.auto.register=true
 * </pre>
 */
public class ProtobufMessageConverter extends SchemaRegistryConverter {

    private static final Logger log = LoggerFactory.getLogger(ProtobufMessageConverter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private ProtobufSchema cachedSchema;
    private Schema cachedConnectSchema;

    @Override
    protected String getSchemaType() {
        return "Protobuf";
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

            // Get or create Protobuf schema
            ProtobufSchema protobufSchema = getOrCreateSchema(jsonNode, config);

            // Convert JSON to Protobuf DynamicMessage
            DynamicMessage protoMessage = jsonToProtobuf(messageBody, protobufSchema);

            // Convert to Connect schema and value
            Schema connectSchema = protobufToConnectSchema(protobufSchema);
            Object connectValue = protobufToConnectValue(protoMessage, connectSchema);

            log.debug("Converted message {} to Protobuf format with schema {}",
                    message.messageId(), protobufSchema.name());

            return new SchemaAndValue(connectSchema, connectValue);

        } catch (Exception e) {
            log.warn("Failed to parse message {} as JSON or convert to Protobuf, using raw string",
                    message.messageId(), e);
            return createSimpleStringValue(messageBody);
        }
    }

    private SchemaAndValue createSimpleStringValue(String value) {
        return new SchemaAndValue(Schema.STRING_SCHEMA, value);
    }

    private ProtobufSchema getOrCreateSchema(JsonNode jsonNode, SqsSourceConnectorConfig config) {
        Integer schemaId = config.getValueSchemaId();

        if (schemaId != null) {
            // Use specified schema ID
            String schemaString = fetchSchemaById(schemaId);
            return new ProtobufSchema(schemaString);
        }

        if (config.isSchemaUseLatestVersion()) {
            // Use latest schema from registry
            String subject = getSubjectName(config.getKafkaTopic(), false);
            String schemaString = fetchLatestSchema(subject);
            return new ProtobufSchema(schemaString);
        }

        // Use cached schema if compatible
        if (cachedSchema != null) {
            return cachedSchema;
        }

        // Generate Protobuf schema from JSON structure
        ProtobufSchema inferredSchema = inferSchemaFromJson(jsonNode, config.getKafkaTopic());

        if (config.isSchemaAutoRegister()) {
            // Register the schema
            String subject = getSubjectName(config.getKafkaTopic(), false);
            int registeredId = registerSchema(subject, inferredSchema);
            log.info("Registered Protobuf schema with ID {} for subject {}", registeredId, subject);
        }

        cachedSchema = inferredSchema;
        return inferredSchema;
    }

    private ProtobufSchema inferSchemaFromJson(JsonNode jsonNode, String topicName) {
        String messageName = topicName.replaceAll("[^a-zA-Z0-9]", "_") + "_value";
        StringBuilder protoBuilder = new StringBuilder();

        protoBuilder.append("syntax = \"proto3\";\n\n");
        protoBuilder.append("package io.connect.sqs.protobuf;\n\n");
        protoBuilder.append("message ").append(messageName).append(" {\n");

        int fieldNumber = 1;
        Iterator<Map.Entry<String, JsonNode>> fieldIterator = jsonNode.fields();
        while (fieldIterator.hasNext()) {
            Map.Entry<String, JsonNode> field = fieldIterator.next();
            String protoType = jsonNodeToProtoType(field.getValue());
            protoBuilder.append("  ").append(protoType).append(" ")
                    .append(field.getKey()).append(" = ").append(fieldNumber++).append(";\n");
        }

        protoBuilder.append("}\n");

        String protoSchema = protoBuilder.toString();
        log.debug("Generated Protobuf schema:\n{}", protoSchema);

        return new ProtobufSchema(protoSchema);
    }

    private String jsonNodeToProtoType(JsonNode value) {
        if (value.isNull()) {
            return "string"; // Default to string for null
        } else if (value.isBoolean()) {
            return "bool";
        } else if (value.isInt()) {
            return "int32";
        } else if (value.isLong()) {
            return "int64";
        } else if (value.isDouble() || value.isFloat()) {
            return "double";
        } else if (value.isTextual()) {
            return "string";
        } else if (value.isArray()) {
            if (value.isEmpty()) {
                return "repeated string";
            }
            JsonNode firstElement = value.get(0);
            return "repeated " + jsonNodeToProtoType(firstElement);
        } else if (value.isObject()) {
            // For nested objects, use a generic map or string
            // Complex nested objects would need recursive message definition
            return "string";
        } else {
            return "string";
        }
    }

    private DynamicMessage jsonToProtobuf(String jsonString, ProtobufSchema schema) {
        try {
            Descriptors.Descriptor descriptor = schema.toDescriptor();
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
            JsonFormat.parser().ignoringUnknownFields().merge(jsonString, builder);
            return builder.build();
        } catch (Exception e) {
            throw new SerializationException("Failed to convert JSON to Protobuf", e);
        }
    }

    private Schema protobufToConnectSchema(ProtobufSchema protobufSchema) {
        Descriptors.Descriptor descriptor = protobufSchema.toDescriptor();
        SchemaBuilder builder = SchemaBuilder.struct().name(descriptor.getFullName());

        for (Descriptors.FieldDescriptor field : descriptor.getFields()) {
            Schema fieldSchema = protoFieldToConnectSchema(field);
            builder.field(field.getName(), fieldSchema);
        }

        cachedConnectSchema = builder.build();
        return cachedConnectSchema;
    }

    private Schema protoFieldToConnectSchema(Descriptors.FieldDescriptor field) {
        if (field.isRepeated()) {
            Schema elementSchema = protoTypeToConnectSchema(field);
            return SchemaBuilder.array(elementSchema).optional().build();
        }

        Schema baseSchema = protoTypeToConnectSchema(field);
        // In proto3, all fields are optional by default
        return SchemaBuilder.type(baseSchema.type()).optional().build();
    }

    private Schema protoTypeToConnectSchema(Descriptors.FieldDescriptor field) {
        switch (field.getJavaType()) {
            case BOOLEAN:
                return Schema.BOOLEAN_SCHEMA;
            case INT:
                return Schema.INT32_SCHEMA;
            case LONG:
                return Schema.INT64_SCHEMA;
            case FLOAT:
                return Schema.FLOAT32_SCHEMA;
            case DOUBLE:
                return Schema.FLOAT64_SCHEMA;
            case STRING:
                return Schema.STRING_SCHEMA;
            case BYTE_STRING:
                return Schema.BYTES_SCHEMA;
            case MESSAGE:
                // Nested message - convert recursively
                Descriptors.Descriptor nestedDescriptor = field.getMessageType();
                SchemaBuilder nestedBuilder = SchemaBuilder.struct().name(nestedDescriptor.getFullName());
                for (Descriptors.FieldDescriptor nestedField : nestedDescriptor.getFields()) {
                    nestedBuilder.field(nestedField.getName(), protoFieldToConnectSchema(nestedField));
                }
                return nestedBuilder.build();
            case ENUM:
                return Schema.STRING_SCHEMA; // Represent enum as string
            default:
                return Schema.STRING_SCHEMA;
        }
    }

    private Object protobufToConnectValue(DynamicMessage protoMessage, Schema connectSchema) {
        Struct struct = new Struct(connectSchema);

        for (org.apache.kafka.connect.data.Field field : connectSchema.fields()) {
            Descriptors.FieldDescriptor protoField = protoMessage.getDescriptorForType()
                    .findFieldByName(field.name());

            if (protoField != null) {
                Object protoValue = protoMessage.getField(protoField);
                Object connectValue = convertProtoToConnectValue(protoValue, field.schema(), protoField);
                struct.put(field, connectValue);
            }
        }

        return struct;
    }

    private Object convertProtoToConnectValue(Object protoValue, Schema connectSchema,
            Descriptors.FieldDescriptor protoField) {
        if (protoValue == null) {
            return null;
        }

        if (protoField.isRepeated() && protoValue instanceof List) {
            List<?> protoList = (List<?>) protoValue;
            List<Object> connectList = new ArrayList<>();
            Schema elementSchema = connectSchema.valueSchema();

            for (Object item : protoList) {
                if (item instanceof DynamicMessage) {
                    connectList.add(convertNestedProtoToConnect((DynamicMessage) item, elementSchema));
                } else {
                    connectList.add(convertPrimitiveValue(item));
                }
            }
            return connectList;
        }

        if (protoValue instanceof DynamicMessage) {
            return convertNestedProtoToConnect((DynamicMessage) protoValue, connectSchema);
        }

        return convertPrimitiveValue(protoValue);
    }

    private Object convertNestedProtoToConnect(DynamicMessage nestedMessage, Schema connectSchema) {
        if (connectSchema.type() != Schema.Type.STRUCT) {
            return nestedMessage.toString();
        }

        Struct struct = new Struct(connectSchema);
        for (org.apache.kafka.connect.data.Field field : connectSchema.fields()) {
            Descriptors.FieldDescriptor protoField = nestedMessage.getDescriptorForType()
                    .findFieldByName(field.name());
            if (protoField != null) {
                Object value = nestedMessage.getField(protoField);
                struct.put(field, convertProtoToConnectValue(value, field.schema(), protoField));
            }
        }
        return struct;
    }

    private Object convertPrimitiveValue(Object value) {
        if (value instanceof com.google.protobuf.ByteString) {
            return ((com.google.protobuf.ByteString) value).toByteArray();
        } else if (value instanceof Descriptors.EnumValueDescriptor) {
            return ((Descriptors.EnumValueDescriptor) value).getName();
        }
        return value;
    }
}
