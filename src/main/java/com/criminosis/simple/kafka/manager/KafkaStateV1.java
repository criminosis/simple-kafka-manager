package com.criminosis.simple.kafka.manager;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;

@JsonTypeName("v1")
public class KafkaStateV1 extends KafkaState {
    private static final Logger logger = LoggerFactory.getLogger(KafkaStateV1.class);

    private final List<String> topicsToDelete;
    private final Map<String, TopicState> topicState;

    public KafkaStateV1(@JsonProperty("topicsToDelete") List<String> topicsToDelete,
                        @JsonProperty("topicStates") Map<String, TopicState> topicState) {
        this.topicState = topicState;
        this.topicsToDelete = topicsToDelete;
    }

    @Override
    void execute(Properties kafkaConfig) throws Exception {
        if (!kafkaConfig.containsKey(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG)) {
            throw new IllegalArgumentException("Kafka config does not specify " + AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);
        }
        logger.info("Targeting kafka cluster:" + kafkaConfig.get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG));

        try (Admin adminClient = Admin.create(kafkaConfig)) {
            Set<String> existingTopics = adminClient.listTopics().names().get();
            logger.info("Existing topics: " + existingTopics);

            //First do deletes if the topic exists
            Set<String> topicsThatExistToDelete = Optional.ofNullable(topicsToDelete).orElse(List.of())
                    .stream()
                    .filter(existingTopics::contains)
                    .collect(Collectors.toUnmodifiableSet());

            if (!topicsThatExistToDelete.isEmpty()) {
                logger.info("Deleting topics:" + topicsThatExistToDelete);
                adminClient.deleteTopics(topicsThatExistToDelete).all().get();
                existingTopics.removeAll(topicsThatExistToDelete); //they've been deleted so remove them from our exists set
            }

            //Now do creates
            for (Map.Entry<String, TopicState> topicStateToSyncByTopicName : topicState.entrySet()) {
                String topicName = topicStateToSyncByTopicName.getKey();
                TopicState topicConfigToSync = topicStateToSyncByTopicName.getValue();

                if (existingTopics.contains(topicName)) {
                    ConfigResource topicConfigRequest = new ConfigResource(ConfigResource.Type.TOPIC, topicName);

                    //Update any config differences
                    List<AlterConfigOp> postTopicCreationOps = determineConfigFieldsToSet(adminClient, topicName, topicConfigToSync);
                    if (!postTopicCreationOps.isEmpty()) {
                        logger.info("Updating topic config: " + postTopicCreationOps);
                        adminClient.incrementalAlterConfigs(Map.of(topicConfigRequest, postTopicCreationOps)).all().get();
                    }

                    //Now update partition assignments to brokers (if different)
                    Map<TopicPartition, Optional<NewPartitionReassignment>> newAssignments = determineNewAssignments(adminClient, topicName, topicConfigToSync);
                    if (!newAssignments.isEmpty()) {
                        logger.info("Altering topic partitions:" + newAssignments);
                        adminClient.alterPartitionReassignments(newAssignments).all().get();
                        waitForTopicAssignmentsToComplete(adminClient);
                    }
                } else {
                    //We need to create the topic with the specified overrides
                    //If partitionAssignments is not null then use those for topic creation
                    //else use initialPartitions and initialReplicationFactor defaulting them to their defaults if either is null

                    final NewTopic newTopic;
                    if (topicConfigToSync.partitionAssignments != null && !topicConfigToSync.partitionAssignments.isEmpty()) {
                        newTopic = new NewTopic(topicName, topicConfigToSync.partitionAssignments);
                    } else {
                        Optional<Integer> initialPartitions = Optional.ofNullable(topicConfigToSync.initialPartitions);
                        Optional<Short> initialReplication = Optional.ofNullable(topicConfigToSync.initialReplicationFactor);
                        newTopic = new NewTopic(topicName, initialPartitions, initialReplication);
                    }

                    logger.info("Creating topic: " + newTopic.toString());
                    adminClient.createTopics(Collections.singleton(newTopic.configs(topicConfigToSync.config))).all().get();
                    waitForTopicAssignmentsToComplete(adminClient);
                }
            }
        }
    }

    private static void waitForTopicAssignmentsToComplete(Admin admin) {
        await().atMost(5, TimeUnit.MINUTES).pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    logger.info("Waiting for topic assignments to complete...");
                    return admin.listPartitionReassignments().reassignments().get().isEmpty();
                });
    }

    private static Map<TopicPartition, Optional<NewPartitionReassignment>> determineNewAssignments(Admin adminClient, String topicName, TopicState topicConfigToSync) throws ExecutionException, InterruptedException {
        List<TopicPartitionInfo> assignments = adminClient.describeTopics(Set.of(topicName)).all().get().get(topicName).partitions();
        Map<TopicPartition, Optional<NewPartitionReassignment>> newAssignments = new HashMap<>();
        Map<Integer, List<Integer>> assignmentsFromStateFile = Optional.ofNullable(topicConfigToSync.partitionAssignments).orElse(Collections.emptyMap());
        for (TopicPartitionInfo anAssignment : assignments) {
            int partitionId = anAssignment.partition();
            List<Integer> brokersHostingThisPartition = anAssignment.replicas().stream().map(Node::id).collect(Collectors.toUnmodifiableList());
            List<Integer> desiredAssignments = assignmentsFromStateFile.get(partitionId);
            if (desiredAssignments != null
                    && !desiredAssignments.isEmpty()
                    && !desiredAssignments.equals(brokersHostingThisPartition)) {
                newAssignments.put(new TopicPartition(topicName, partitionId),
                        Optional.of(new NewPartitionReassignment(desiredAssignments)));
            }
        }
        return newAssignments;
    }

    private static List<AlterConfigOp> determineConfigFieldsToSet(Admin adminClient,
                                                                  String topicName,
                                                                  TopicState topicConfigToSync)
            throws ExecutionException, InterruptedException {
        ConfigResource topicConfigRequest = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
        Config topicConfig = adminClient.describeConfigs(Collections.singleton(topicConfigRequest))
                .values().get(topicConfigRequest).get();
        Map<String, String> configFromStateFile = Optional.ofNullable(topicConfigToSync.config).orElse(Collections.emptyMap());

        return configFromStateFile.entrySet().stream()
                //Only pass the tuples that have values different than what's set for things that aren't read only
                .filter(configTuple -> !configTuple.getValue().equals(topicConfig.get(configTuple.getKey()).value()))
                .filter(configTuple -> !topicConfig.get(configTuple.getKey()).isReadOnly())
                .map(configTuple -> new AlterConfigOp(
                        new ConfigEntry(configTuple.getKey(), configTuple.getValue()), AlterConfigOp.OpType.SET))
                .collect(Collectors.toUnmodifiableList());
    }

    static class TopicState {
        private final Map<String, String> config;
        private final Integer initialPartitions;
        private final Short initialReplicationFactor;
        private final Map<Integer, List<Integer>> partitionAssignments;

        TopicState(@JsonProperty("config") Map<String, String> config,
                   @JsonProperty("initialPartitions") Integer initialPartitions,
                   @JsonProperty("initialReplicationFactor") Short initialReplicationFactor,
                   @JsonProperty("partitionAssignments") Map<Integer, List<Integer>> partitionAssignments) {
            this.config = config;
            this.initialPartitions = initialPartitions;
            this.initialReplicationFactor = initialReplicationFactor;
            this.partitionAssignments = partitionAssignments;
        }
    }
}
