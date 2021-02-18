package com.criminosis.simple.kafka.manager;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.Callable;

//TODO it'd be nice if we could inject the version via reading the manifest file
@CommandLine.Command(name = "simple-kafka-manager", mixinStandardHelpOptions = true, version = "Simple Kafka Manager TBD",
        description = "Processes the specified state file upon the targeted Kafka cluster")
public class Main implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    @CommandLine.Parameters(index = "0", description = "The state file URL (for example \"file:/path/to/state.json\")")
    private URL stateUrl;

    @CommandLine.Parameters(index = "1", description = "The targeted kafka cluster's \"bootstrap.servers\"")
    private String boostrapServers;

    public static void main(String[] args) throws MalformedURLException {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    private static void fail(String reason) {
        logger.error(reason);
        throw new IllegalArgumentException(reason);
    }

    @Override
    public Integer call() throws Exception {
        if (boostrapServers == null || boostrapServers.isBlank()) {
            fail("Blank bootstrap servers" + boostrapServers);
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
        return 0;
    }
}
