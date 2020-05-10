package com.criminosis.simple.kafka.manager;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;

public class KafkaStateV1IT {

    @ClassRule
    public static final GenericContainer zookeeper = createZookeeperContainer();

    private static List<KafkaContainer> kafkaCluster;

    private static Properties kafkaProps;

    @BeforeClass
    public static void afterZKIsReady() {
        kafkaCluster = IntStream.range(0, 3)
                .mapToObj(brokerId ->
                        new KafkaContainer("5.4.2")
                                .withNetwork(zookeeper.getNetwork())
                                .withExternalZookeeper("zookeeper:2181")
                                .withEnv("SERVER_CONFLUENT_SUPPORT_METRICS_ENABLE", Boolean.FALSE.toString())
                                .withEnv("CONFLUENT_SUPPORT_METRICS_ENABLE", "0")
                                .withEnv("KAFKA_BROKER_ID", Integer.toString(brokerId))
                                .dependsOn(zookeeper))
                .collect(Collectors.toUnmodifiableList());
        kafkaCluster.parallelStream().forEach(GenericContainer::start);

        kafkaProps = new Properties();
        String bootstrapConfig = kafkaCluster.stream()
                .map(KafkaContainer::getBootstrapServers)
                .collect(Collectors.joining(","));
        kafkaProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapConfig);
    }

    @AfterClass
    public static void cleanup() {
        kafkaCluster.forEach(GenericContainer::stop);
    }

    @Test
    public void simpleCreateAndDelete() throws ExecutionException, InterruptedException {
        runFile("simple_create_and_delete_step1.json");
        assertReplicaAndPartitions("simpleTopicToDelete", 1, 1);
        runFile("simple_create_and_delete_step2.json");
        try (Admin admin = createAdmin()) {
            Set<String> topicsThatExist = admin.listTopics().names().get();
            assertThat("Topic should not be present", !topicsThatExist.contains("simpleTopicToDelete"));
        }
    }

    @Test
    public void simpleCreateMultiplePartitions() throws ExecutionException, InterruptedException {
        runFile("simple_create_multiple_partitions.json");
        assertReplicaAndPartitions("simpleTopicMultiplePartitions", 3, 1);
    }

    @Test
    public void simpleCreateMultipleReplicas() throws ExecutionException, InterruptedException {
        runFile("simple_create_multiple_replicas.json");
        assertReplicaAndPartitions("simpleTopicMultipleReplicas", 1, 3);
    }

    @Test
    public void simpleCreate() throws ExecutionException, InterruptedException {
        runFile("simple_create.json");
        assertReplicaAndPartitions("simpleTopic", 1, 1);
    }

    @Test
    public void reassignTopicPartitionsAndReplicaCounts() throws ExecutionException, InterruptedException {
        runFile("reassign_partitions_step1.json");
        String topicName = "reassignAndReplicasPartitions";
        try (Admin admin = createAdmin()) {
            assertReplicaAndPartitions(admin, topicName, 3, 1);
            assertPartitionAssignments(admin, topicName, Map.of(
                    0, List.of(0),
                    1, List.of(1),
                    2, List.of(2)
            ));
        }

        runFile("reassign_partitions_step2.json");
        try (Admin admin = createAdmin()) {
            assertReplicaAndPartitions(admin, topicName, 3, 2);
            assertPartitionAssignments(admin, topicName, Map.of(
                    0, List.of(1, 2),
                    1, List.of(2, 0),
                    2, List.of(0, 1)
            ));
        }
    }

    @Test
    public void increasePartitions() throws ExecutionException, InterruptedException {
        runFile("increase_partitions_step1.json");
        String topicName = "increasePartitionsTopic";
        assertReplicaAndPartitions(topicName, 2, 1);
        runFile("increase_partitions_step2.json");
        assertPartitionAssignments(topicName, Map.of(
                0, List.of(1, 2),
                1, List.of(2, 0),
                2, List.of(0, 1)
        ));
    }

    @Test
    public void changeReplicaCount() throws ExecutionException, InterruptedException {
        runFile("increase_replica_step1.json");
        String topicName = "increaseReplicaTopic";
        assertPartitionAssignments(topicName, Map.of(
                0, List.of(0, 1),
                1, List.of(1, 2),
                2, List.of(2, 0)
        ));
        runFile("increase_replica_step2.json");
        assertPartitionAssignments(topicName, Map.of(
                0, List.of(0, 1, 2),
                1, List.of(1, 2, 0),
                2, List.of(2, 0, 1)
        ));
    }

    @Test
    public void createAndRecreate() throws ExecutionException, InterruptedException {
        runFile("topic_recreate_step1.json");
        String topicName = "topicToRecreate";
        assertReplicaAndPartitions(topicName, 1, 1);

        try (Producer<String, String> producer = new KafkaProducer<>(kafkaProps, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topicName, "someValue"));
        }

        //Make sure we can see the message
        Properties consumerProps = new Properties();
        kafkaProps.forEach(consumerProps::put);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, topicName);
        try (Consumer<String, String> consumer = new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topicName));
            await().pollInSameThread().atMost(30, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).until(() -> !consumer.poll(Duration.ofMillis(1)).isEmpty());
        }

        //now we recreate the topic
        runFile("topic_recreate_step2.json");
        assertReplicaAndPartitions(topicName, 1, 1);

        //Now make sure we no longer see the message to vet we actually recreated the topic
        try (Consumer<String, String> consumer = new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topicName));
            try {
                consumer.seek(new TopicPartition(topicName, 0), 0);
                fail("We should have thrown -- new topic no offset should be present to seek");
            } catch (IllegalStateException e) {
                //doNothing() this is expected if the topic is actually novel
            }
            await().pollInSameThread().atMost(10, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).until(() -> consumer.poll(Duration.ZERO).isEmpty());
        }
    }

    @Test
    public void alterTopicConfig() throws ExecutionException, InterruptedException {
        runFile("alter_config_step1.json");
        String topicName = "alterConfigTopic";
        assertReplicaAndPartitions(topicName, 1, 1);
        //We'd first expect the broker's defaults
        assertTopicConfiguration(topicName, Map.of(
                TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1",
                TopicConfig.COMPRESSION_TYPE_CONFIG, "producer",
                TopicConfig.RETENTION_MS_CONFIG, "604800000"
        ));

        //now we assert the defaults we'd expect now
        runFile("alter_config_step2.json");
        assertTopicConfiguration(topicName, Map.of(
                TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2",
                TopicConfig.COMPRESSION_TYPE_CONFIG, "zstd",
                TopicConfig.RETENTION_MS_CONFIG, "1337"
        ));
    }

    private static void assertTopicConfiguration(String topicName, Map<String, String> expectedTopicConfig) throws ExecutionException, InterruptedException {
        try (Admin admin = createAdmin()) {
            assertTopicConfiguration(admin, topicName, expectedTopicConfig);
        }
    }

    private static void assertTopicConfiguration(Admin admin, String topicName, Map<String, String> expectedTopicConfig) throws ExecutionException, InterruptedException {
        ConfigResource request = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
        Config config = admin.describeConfigs(Set.of(request)).all().get().get(request);
        expectedTopicConfig.forEach((configKey, expectedValue) -> assertThat("For field: " + configKey, config.get(configKey).value(), is(expectedValue)));
    }

    private static void assertReplicaAndPartitions(String topicName, int expectedPartitionCount, int expectedReplicaCount) throws ExecutionException, InterruptedException {
        try (Admin admin = createAdmin()) {
            assertReplicaAndPartitions(admin, topicName, expectedPartitionCount, expectedReplicaCount);
        }
    }

    private static void assertReplicaAndPartitions(Admin admin, String topicName, int expectedPartitionCount, int expectedReplicaCount)
            throws ExecutionException, InterruptedException {
        var result = getDescriptionOf(admin, topicName);
        var partitionInfo = result.partitions();
        assertThat(partitionInfo, hasSize(expectedPartitionCount));
        List<Integer> replicasForPartitions = partitionInfo.stream().map(it -> it.replicas().size()).collect(Collectors.toUnmodifiableList());
        assertThat(String.format("Expected replica count of %d. Found: %s", expectedReplicaCount, replicasForPartitions), replicasForPartitions.stream().allMatch(it -> it == expectedReplicaCount));
    }

    private static void assertPartitionAssignments(String topicName, Map<Integer, List<Integer>> brokerAssignmentsByPartition) throws ExecutionException, InterruptedException {
        try (Admin admin = createAdmin()) {
            assertPartitionAssignments(admin, topicName, brokerAssignmentsByPartition);
        }
    }

    private static void assertPartitionAssignments(Admin admin, String topicName, Map<Integer, List<Integer>> brokerAssignmentsByPartition) throws ExecutionException, InterruptedException {
        TopicDescription description = getDescriptionOf(admin, topicName);
        for (TopicPartitionInfo partitionInfo : description.partitions()) {
            List<Integer> expectedAssignments = brokerAssignmentsByPartition.get(partitionInfo.partition());
            List<Integer> actual = partitionInfo.replicas().stream().map(Node::id).collect(Collectors.toUnmodifiableList());
            assertThat(actual, is(expectedAssignments));
        }
    }

    private static TopicDescription getDescriptionOf(Admin admin, String topicName) throws ExecutionException, InterruptedException {
        return admin.describeTopics(Set.of(topicName)).all().get().get(topicName);
    }

    private static Admin createAdmin() {
        return AdminClient.create(kafkaProps);
    }

    private static void runFile(String resource) {
        new KafkaStateExecutor(kafkaProps, getResource(resource)).run();
    }

    private static Supplier<InputStream> getResource(String resourceToOpen) {
        return () -> KafkaStateV1IT.class.getClassLoader().getResourceAsStream(resourceToOpen);
    }

    private static GenericContainer createZookeeperContainer() {
        GenericContainer zookeeper = new GenericContainer<>("zookeeper:3.6.0")
                .withNetwork(Network.SHARED)
                .withNetworkAliases("zookeeper");
        return zookeeper;
    }
}
