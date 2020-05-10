package com.criminosis.simple.kafka.manager;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws MalformedURLException {
        if (args.length < 2) {
            fail("Expected \"bootstrap.servers\" and a URL to kafka state file (for example \"file:/path/to/state.json\"");
        }

        String boostrapServers = args[0];
        if (boostrapServers.isBlank()) {
            fail("Blank bootstrap servers" + boostrapServers);
        }

        URL stateUrl;
        try {
            stateUrl = new URL(args[1]);
        } catch (MalformedURLException e) {
            logger.error("Malformed kafka state file url", e);
            throw e;
        }

        Properties kafkaProps = new Properties();
        kafkaProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, boostrapServers);
        new KafkaStateExecutor(kafkaProps, () -> {
            try {
                return stateUrl.openStream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).run();
    }

    private static void fail(String reason) {
        logger.error(reason);
        throw new IllegalArgumentException(reason);
    }
}
