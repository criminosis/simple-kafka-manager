module simple.kafka.manager {
    requires com.fasterxml.jackson.annotation;
    requires org.slf4j;
    requires com.fasterxml.jackson.databind;
    requires kafka.clients;
    requires awaitility;

    exports com.criminosis.simple.kafka.manager to com.fasterxml.jackson.databind;
    opens com.criminosis.simple.kafka.manager to com.fasterxml.jackson.databind;
}