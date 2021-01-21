package com.criminosis.simple.kafka.manager;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Properties;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "version")
@JsonSubTypes({
        @JsonSubTypes.Type(KafkaStateV1.class)
})
public abstract class KafkaState {

    abstract void execute(Properties kafkaConfig) throws Exception;
}
