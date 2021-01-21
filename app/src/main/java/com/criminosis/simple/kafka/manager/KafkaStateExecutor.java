package com.criminosis.simple.kafka.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Properties;
import java.util.function.Supplier;

public class KafkaStateExecutor {
    private static final Logger logger = LoggerFactory.getLogger(KafkaStateExecutor.class);

    private final Properties kafkaConfig;
    private final Supplier<InputStream> kafkaStateSource;

    public KafkaStateExecutor(Properties kafkaConfig, Supplier<InputStream> kafkaStateSource) {
        this.kafkaConfig = kafkaConfig;
        this.kafkaStateSource = kafkaStateSource;
    }

    public void run() {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream stateStream = kafkaStateSource.get()) {
            KafkaState kafkaState = objectMapper.readValue(stateStream, KafkaState.class);
            kafkaState.execute(kafkaConfig);
        } catch (Exception e) {
            logger.error("Failed to execute kafka state due", e);
        }
    }
}
