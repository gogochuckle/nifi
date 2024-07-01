/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.kafka.pubsub;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.DeprecationNotice;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.annotation.lifecycle.OnUnscheduled;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyDescriptor.Builder;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.kafka.shared.attribute.KafkaFlowFileAttribute;
import org.apache.nifi.kafka.shared.component.KafkaClientComponent;
import org.apache.nifi.kafka.shared.property.KeyEncoding;
import org.apache.nifi.kafka.shared.property.provider.KafkaPropertyProvider;
import org.apache.nifi.kafka.shared.property.provider.StandardKafkaPropertyProvider;
import org.apache.nifi.kafka.shared.validation.DynamicPropertyValidator;
import org.apache.nifi.kafka.shared.validation.KafkaClientCustomValidationFunction;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.RecordSetWriterFactory;

import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.apache.nifi.expression.ExpressionLanguageScope.NONE;
import static org.apache.nifi.expression.ExpressionLanguageScope.VARIABLE_REGISTRY;

@CapabilityDescription("Consumes messages from Apache Kafka specifically built against the Kafka 2.0 Consumer API. "
    + "The complementary NiFi processor for sending messages is PublishKafkaRecord_2_0. Please note that, at this time, the Processor assumes that "
    + "all records that are retrieved from a given partition have the same schema. If any of the Kafka messages are pulled but cannot be parsed or written with the "
    + "configured Record Reader or Record Writer, the contents of the message will be written to a separate FlowFile, and that FlowFile will be transferred to the "
    + "'parse.failure' relationship. Otherwise, each FlowFile is sent to the 'success' relationship and may contain many individual messages within the single FlowFile. "
    + "A 'record.count' attribute is added to indicate how many messages are contained in the FlowFile. No two Kafka messages will be placed into the same FlowFile if they "
    + "have different schemas, or if they have different values for a message header that is included by the <Headers to Add as Attributes> property.")
@DeprecationNotice(classNames = "org.apache.nifi.processors.kafka.pubsub.ConsumeKafkaRecord_2_6")
@Tags({"Kafka", "Get", "Record", "csv", "avro", "json", "Ingest", "Ingress", "Topic", "PubSub", "Consume", "2.0"})
@WritesAttributes({
    @WritesAttribute(attribute = "record.count", description = "The number of records received"),
    @WritesAttribute(attribute = "mime.type", description = "The MIME Type that is provided by the configured Record Writer"),
    @WritesAttribute(attribute = KafkaFlowFileAttribute.KAFKA_PARTITION, description = "The partition of the topic the records are from"),
    @WritesAttribute(attribute = KafkaFlowFileAttribute.KAFKA_TIMESTAMP, description = "The timestamp of the message in the partition of the topic."),
    @WritesAttribute(attribute = KafkaFlowFileAttribute.KAFKA_TOPIC, description = "The topic records are from")
})
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@DynamicProperty(name = "The name of a Kafka configuration property.", value = "The value of a given Kafka configuration property.",
        description = "These properties will be added on the Kafka configuration after loading any provided configuration properties."
        + " In the event a dynamic property represents a property that was already set, its value will be ignored and WARN message logged."
        + " For the list of available Kafka properties please refer to: http://kafka.apache.org/documentation.html#configuration.",
        expressionLanguageScope = VARIABLE_REGISTRY)
@SeeAlso({ConsumeKafka_2_0.class, PublishKafka_2_0.class, PublishKafkaRecord_2_0.class})
public class ConsumeKafkaRecord_2_0 extends AbstractProcessor implements KafkaClientComponent {

    static final AllowableValue OFFSET_EARLIEST = new AllowableValue("earliest", "earliest", "Automatically reset the offset to the earliest offset");
    static final AllowableValue OFFSET_LATEST = new AllowableValue("latest", "latest", "Automatically reset the offset to the latest offset");
    static final AllowableValue OFFSET_NONE = new AllowableValue("none", "none", "Throw exception to the consumer if no previous offset is found for the consumer's group");
    static final AllowableValue TOPIC_NAME = new AllowableValue("names", "names", "Topic is a full topic name or comma separated list of names");
    static final AllowableValue TOPIC_PATTERN = new AllowableValue("pattern", "pattern", "Topic is a regex using the Java Pattern syntax");

    static final PropertyDescriptor TOPICS = new Builder()
            .name("topic")
            .displayName("Topic Name(s)")
            .description("The name of the Kafka Topic(s) to pull from. More than one can be supplied if comma separated.")
            .required(true)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .expressionLanguageSupported(VARIABLE_REGISTRY)
            .build();

    static final PropertyDescriptor TOPIC_TYPE = new Builder()
            .name("topic_type")
            .displayName("Topic Name Format")
            .description("Specifies whether the Topic(s) provided are a comma separated list of names or a single regular expression")
            .required(true)
            .allowableValues(TOPIC_NAME, TOPIC_PATTERN)
            .defaultValue(TOPIC_NAME.getValue())
            .build();

    static final PropertyDescriptor RECORD_READER = new Builder()
        .name("record-reader")
        .displayName("Record Reader")
        .description("The Record Reader to use for incoming FlowFiles")
        .identifiesControllerService(RecordReaderFactory.class)
        .expressionLanguageSupported(NONE)
        .required(true)
        .build();

    static final PropertyDescriptor RECORD_WRITER = new Builder()
        .name("record-writer")
        .displayName("Record Writer")
        .description("The Record Writer to use in order to serialize the data before sending to Kafka")
        .identifiesControllerService(RecordSetWriterFactory.class)
        .expressionLanguageSupported(NONE)
        .required(true)
        .build();

    static final PropertyDescriptor GROUP_ID = new Builder()
        .name("group.id")
            .displayName("Group ID")
            .description("A Group ID is used to identify consumers that are within the same consumer group. Corresponds to Kafka's 'group.id' property.")
            .required(true)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .expressionLanguageSupported(VARIABLE_REGISTRY)
            .build();

    static final PropertyDescriptor AUTO_OFFSET_RESET = new Builder()
        .name("auto.offset.reset")
            .displayName("Offset Reset")
            .description("Allows you to manage the condition when there is no initial offset in Kafka or if the current offset does not exist any "
                    + "more on the server (e.g. because that data has been deleted). Corresponds to Kafka's 'auto.offset.reset' property.")
            .required(true)
            .allowableValues(OFFSET_EARLIEST, OFFSET_LATEST, OFFSET_NONE)
            .defaultValue(OFFSET_LATEST.getValue())
            .build();

    static final PropertyDescriptor MAX_POLL_RECORDS = new Builder()
            .name("max.poll.records")
            .displayName("Max Poll Records")
            .description("Specifies the maximum number of records Kafka should return in a single poll.")
            .required(false)
            .defaultValue("10000")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    static final PropertyDescriptor MAX_UNCOMMITTED_TIME = new Builder()
            .name("max-uncommit-offset-wait")
            .displayName("Max Uncommitted Time")
            .description("Specifies the maximum amount of time allowed to pass before offsets must be committed. "
                    + "This value impacts how often offsets will be committed.  Committing offsets less often increases "
                    + "throughput but also increases the window of potential data duplication in the event of a rebalance "
                    + "or JVM restart between commits.  This value is also related to maximum poll records and the use "
                    + "of a message demarcator.  When using a message demarcator we can have far more uncommitted messages "
                    + "than when we're not as there is much less for us to keep track of in memory.")
            .required(false)
            .defaultValue("1 secs")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();
    static final PropertyDescriptor COMMS_TIMEOUT = new Builder()
        .name("Communications Timeout")
        .displayName("Communications Timeout")
        .description("Specifies the timeout that the consumer should use when communicating with the Kafka Broker")
        .required(true)
        .defaultValue("60 secs")
        .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
        .build();
    static final PropertyDescriptor HONOR_TRANSACTIONS = new Builder()
        .name("honor-transactions")
        .displayName("Honor Transactions")
        .description("Specifies whether or not NiFi should honor transactional guarantees when communicating with Kafka. If false, the Processor will use an \"isolation level\" of "
            + "read_uncomitted. This means that messages will be received as soon as they are written to Kafka but will be pulled, even if the producer cancels the transactions. If "
            + "this value is true, NiFi will not receive any messages for which the producer's transaction was canceled, but this can result in some latency since the consumer must wait "
            + "for the producer to finish its entire transaction instead of pulling as the messages become available.")
        .expressionLanguageSupported(NONE)
        .allowableValues("true", "false")
        .defaultValue("true")
        .required(true)
        .build();
    static final PropertyDescriptor MESSAGE_HEADER_ENCODING = new Builder()
        .name("message-header-encoding")
        .displayName("Message Header Encoding")
        .description("Any message header that is found on a Kafka message will be added to the outbound FlowFile as an attribute. "
            + "This property indicates the Character Encoding to use for deserializing the headers.")
        .addValidator(StandardValidators.CHARACTER_SET_VALIDATOR)
        .defaultValue("UTF-8")
        .required(false)
        .build();
    static final PropertyDescriptor HEADER_NAME_REGEX = new Builder()
        .name("header-name-regex")
        .displayName("Headers to Add as Attributes (Regex)")
        .description("A Regular Expression that is matched against all message headers. "
            + "Any message header whose name matches the regex will be added to the FlowFile as an Attribute. "
            + "If not specified, no Header values will be added as FlowFile attributes. If two messages have a different value for the same header and that header is selected by "
            + "the provided regex, then those two messages must be added to different FlowFiles. As a result, users should be cautious about using a regex like "
            + "\".*\" if messages are expected to have header values that are unique per message, such as an identifier or timestamp, because it will prevent NiFi from bundling "
            + "the messages together efficiently.")
        .addValidator(StandardValidators.REGULAR_EXPRESSION_VALIDATOR)
        .expressionLanguageSupported(NONE)
        .required(false)
        .build();
    static final PropertyDescriptor SEPARATE_BY_KEY = new Builder()
        .name("separate-by-key")
        .displayName("Separate By Key")
        .description("If true, two Records will only be added to the same FlowFile if both of the Kafka Messages have identical keys.")
        .required(false)
        .allowableValues("true", "false")
        .defaultValue("false")
        .build();
    static final PropertyDescriptor KEY_ATTRIBUTE_ENCODING = new PropertyDescriptor.Builder()
        .name("key-attribute-encoding")
        .displayName("Key Attribute Encoding")
        .description("If the <Separate By Key> property is set to true, FlowFiles that are emitted have an attribute named '" + KafkaFlowFileAttribute.KAFKA_KEY +
            "'. This property dictates how the value of the attribute should be encoded.")
        .required(true)
        .defaultValue(KeyEncoding.UTF8.getValue())
        .allowableValues(KeyEncoding.class)
        .build();

    static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("FlowFiles received from Kafka.  Depending on demarcation strategy it is a flow file per message or a bundle of messages grouped by topic and partition.")
            .build();
    static final Relationship REL_PARSE_FAILURE = new Relationship.Builder()
            .name("parse.failure")
            .description("If a message from Kafka cannot be parsed using the configured Record Reader, the contents of the "
                + "message will be routed to this Relationship as its own individual FlowFile.")
            .build();

    static final List<PropertyDescriptor> DESCRIPTORS;
    static final Set<Relationship> RELATIONSHIPS;

    private volatile ConsumerPool consumerPool = null;
    private final Set<ConsumerLease> activeLeases = Collections.synchronizedSet(new HashSet<>());

    static {
        List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(BOOTSTRAP_SERVERS);
        descriptors.add(TOPICS);
        descriptors.add(TOPIC_TYPE);
        descriptors.add(RECORD_READER);
        descriptors.add(RECORD_WRITER);
        descriptors.add(HONOR_TRANSACTIONS);
        descriptors.add(SECURITY_PROTOCOL);
        descriptors.add(SASL_MECHANISM);
        descriptors.add(KERBEROS_CREDENTIALS_SERVICE);
        descriptors.add(KERBEROS_SERVICE_NAME);
        descriptors.add(KERBEROS_PRINCIPAL);
        descriptors.add(KERBEROS_KEYTAB);
        descriptors.add(SASL_USERNAME);
        descriptors.add(SASL_PASSWORD);
        descriptors.add(TOKEN_AUTHENTICATION);
        descriptors.add(AWS_PROFILE_NAME);
        descriptors.add(SSL_CONTEXT_SERVICE);
        descriptors.add(GROUP_ID);
        descriptors.add(SEPARATE_BY_KEY);
        descriptors.add(KEY_ATTRIBUTE_ENCODING);
        descriptors.add(AUTO_OFFSET_RESET);
        descriptors.add(MESSAGE_HEADER_ENCODING);
        descriptors.add(HEADER_NAME_REGEX);
        descriptors.add(MAX_POLL_RECORDS);
        descriptors.add(MAX_UNCOMMITTED_TIME);
        descriptors.add(COMMS_TIMEOUT);
        DESCRIPTORS = Collections.unmodifiableList(descriptors);

        final Set<Relationship> rels = new HashSet<>();
        rels.add(REL_SUCCESS);
        rels.add(REL_PARSE_FAILURE);
        RELATIONSHIPS = Collections.unmodifiableSet(rels);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return DESCRIPTORS;
    }

    @OnStopped
    public void close() {
        final ConsumerPool pool = consumerPool;
        consumerPool = null;

        if (pool != null) {
            pool.close();
        }
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new Builder()
                .description("Specifies the value for '" + propertyDescriptorName + "' Kafka Configuration.")
                .name(propertyDescriptorName)
                .addValidator(new DynamicPropertyValidator(ConsumerConfig.class))
                .dynamic(true)
                .expressionLanguageSupported(VARIABLE_REGISTRY)
                .build();
    }

    @Override
    protected Collection<ValidationResult> customValidate(final ValidationContext validationContext) {
        final Collection<ValidationResult> validationResults = new KafkaClientCustomValidationFunction().apply(validationContext);

        final ValidationResult consumerPartitionsResult = ConsumerPartitionsUtil.validateConsumePartitions(validationContext.getAllProperties());
        validationResults.add(consumerPartitionsResult);

        final boolean explicitPartitionMapping = ConsumerPartitionsUtil.isPartitionAssignmentExplicit(validationContext.getAllProperties());
        if (explicitPartitionMapping) {
            final String topicType = validationContext.getProperty(TOPIC_TYPE).getValue();
            if (TOPIC_PATTERN.getValue().equals(topicType)) {
                validationResults.add(new ValidationResult.Builder()
                    .subject(TOPIC_TYPE.getDisplayName())
                    .input(TOPIC_PATTERN.getDisplayName())
                    .valid(false)
                    .explanation("It is not valid to explicitly assign Topic Partitions and also use a Topic Pattern. "
                        + "Topic Partitions may be assigned only if explicitly specifying topic names also.")
                    .build());
            }
        }

        return validationResults;
    }

    private synchronized ConsumerPool getConsumerPool(final ProcessContext context) {
        ConsumerPool pool = consumerPool;
        if (pool != null) {
            return pool;
        }

        final ConsumerPool consumerPool = createConsumerPool(context, getLogger());

        final boolean explicitAssignment = ConsumerPartitionsUtil.isPartitionAssignmentExplicit(context.getAllProperties());
        if (explicitAssignment) {
            final int numAssignedPartitions = ConsumerPartitionsUtil.getPartitionAssignmentCount(context.getAllProperties());

            // Request from Kafka the number of partitions for the topics that we are consuming from. Then ensure that we have
            // all of the partitions assigned.
            final int partitionCount = consumerPool.getPartitionCount();
            if (partitionCount != numAssignedPartitions) {
                context.yieldForAWhile();
                consumerPool.close();

                throw new ProcessException("Illegal Partition Assignment: There are " + numAssignedPartitions + " partitions statically assigned using the partitions.* property names, but the Kafka" +
                    " topic(s) have " + partitionCount + " partitions");
            }
        }

        this.consumerPool = consumerPool;
        return consumerPool;
    }

    protected ConsumerPool createConsumerPool(final ProcessContext context, final ComponentLog log) {
        final int maxLeases = context.getMaxConcurrentTasks();
        final long maxUncommittedTime = context.getProperty(MAX_UNCOMMITTED_TIME).asTimePeriod(TimeUnit.MILLISECONDS);

        final KafkaPropertyProvider propertyProvider = new StandardKafkaPropertyProvider(ConsumerConfig.class);
        final Map<String, Object> props = propertyProvider.getProperties(context);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.FALSE.toString());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        final String topicListing = context.getProperty(ConsumeKafkaRecord_2_0.TOPICS).evaluateAttributeExpressions().getValue();
        final String topicType = context.getProperty(ConsumeKafkaRecord_2_0.TOPIC_TYPE).evaluateAttributeExpressions().getValue();
        final List<String> topics = new ArrayList<>();
        final String securityProtocol = context.getProperty(SECURITY_PROTOCOL).getValue();
        final String bootstrapServers = context.getProperty(BOOTSTRAP_SERVERS).evaluateAttributeExpressions().getValue();

        final RecordReaderFactory readerFactory = context.getProperty(RECORD_READER).asControllerService(RecordReaderFactory.class);
        final RecordSetWriterFactory writerFactory = context.getProperty(RECORD_WRITER).asControllerService(RecordSetWriterFactory.class);
        final boolean honorTransactions = context.getProperty(HONOR_TRANSACTIONS).asBoolean();
        final int commsTimeoutMillis = context.getProperty(COMMS_TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS).intValue();
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, commsTimeoutMillis);

        final String charsetName = context.getProperty(MESSAGE_HEADER_ENCODING).evaluateAttributeExpressions().getValue();
        final Charset charset = Charset.forName(charsetName);

        final String headerNameRegex = context.getProperty(HEADER_NAME_REGEX).getValue();
        final Pattern headerNamePattern = headerNameRegex == null ? null : Pattern.compile(headerNameRegex);

        final boolean separateByKey = context.getProperty(SEPARATE_BY_KEY).asBoolean();
        final String keyEncoding = context.getProperty(KEY_ATTRIBUTE_ENCODING).getValue();

        final int[] partitionsToConsume;
        try {
            partitionsToConsume = ConsumerPartitionsUtil.getPartitionsForHost(context.getAllProperties(), getLogger());
        } catch (final UnknownHostException uhe) {
            throw new ProcessException("Could not determine localhost's hostname", uhe);
        }

        if (topicType.equals(TOPIC_NAME.getValue())) {
            for (final String topic : topicListing.split(",", 100)) {
                final String trimmedName = topic.trim();
                if (!trimmedName.isEmpty()) {
                    topics.add(trimmedName);
                }
            }

            return new ConsumerPool(maxLeases, readerFactory, writerFactory, props, topics, maxUncommittedTime, securityProtocol,
                bootstrapServers, log, honorTransactions, charset, headerNamePattern, separateByKey, keyEncoding, partitionsToConsume);
        } else if (topicType.equals(TOPIC_PATTERN.getValue())) {
            final Pattern topicPattern = Pattern.compile(topicListing.trim());
            return new ConsumerPool(maxLeases, readerFactory, writerFactory, props, topicPattern, maxUncommittedTime, securityProtocol,
                bootstrapServers, log, honorTransactions, charset, headerNamePattern, separateByKey, keyEncoding, partitionsToConsume);
        } else {
            getLogger().error("Subscription type has an unknown value {}", new Object[] {topicType});
            return null;
        }
    }

    @OnUnscheduled
    public void interruptActiveThreads() {
        // There are known issues with the Kafka client library that result in the client code hanging
        // indefinitely when unable to communicate with the broker. In order to address this, we will wait
        // up to 30 seconds for the Threads to finish and then will call Consumer.wakeup() to trigger the
        // thread to wakeup when it is blocked, waiting on a response.
        final long nanosToWait = TimeUnit.SECONDS.toNanos(5L);
        final long start = System.nanoTime();
        while (System.nanoTime() - start < nanosToWait && !activeLeases.isEmpty()) {
            try {
                Thread.sleep(100L);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (!activeLeases.isEmpty()) {
            int count = 0;
            for (final ConsumerLease lease : activeLeases) {
                getLogger().info("Consumer {} has not finished after waiting 30 seconds; will attempt to wake-up the lease", new Object[] {lease});
                lease.wakeup();
                count++;
            }

            getLogger().info("Woke up {} consumers", new Object[] {count});
        }

        activeLeases.clear();
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        final ConsumerPool pool = getConsumerPool(context);
        if (pool == null) {
            context.yieldForAWhile();
            return;
        }

        try (final ConsumerLease lease = pool.obtainConsumer(session, context)) {
            if (lease == null) {
                context.yieldForAWhile();
                return;
            }

            activeLeases.add(lease);
            try {
                while (this.isScheduled() && lease.continuePolling()) {
                    lease.poll();
                }
                if (this.isScheduled() && !lease.commit()) {
                    context.yieldForAWhile();
                }
            } catch (final WakeupException we) {
                getLogger().warn("Was interrupted while trying to communicate with Kafka with lease {}. "
                    + "Will roll back session and discard any partially received data.", new Object[] {lease});
            } catch (final KafkaException kex) {
                getLogger().error("Exception while interacting with Kafka so will close the lease {} due to {}", lease, kex, kex);
            } catch (final Throwable t) {
                getLogger().error("Exception while processing data from kafka so will close the lease {} due to {}", lease, t, t);
            } finally {
                activeLeases.remove(lease);
            }
        }
    }
}
