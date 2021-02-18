Simple Kafka Manager
---

This application uses a simple json file to represent the desired state to setup in Kafka.

Version 1 of the state file is designed against the Kafka 2.4.1 Admin API and is defined as so:

```json
{
  "version": "v1",
   "topicsToDelete" : ["list","of","topic","names", "to", "delete"],
  "topicStates": {
    "nameOfATopic": {
       "config": {
          "kafkaTopicConfigOption" : "configValue"
        },
       "initialPartitions" : 1,
       "initialReplicationFactor" : 1,
       "partitionAssignments": {
        "partitionNumber" : [1, 2, 3]
      }
    }
  }
}

```

`version` to facilitate future schema evolution / behavior changes 

`topicsToDelete` is a list of topic names to delete. Deletes happen before creates so its possible to recreate a topic if desired.

`topicStates` is a map keyed by topic name for the following per topic settings:

`initialPartitions` and `initialReplicationFactor` are only considered if the topic is novel to use as the initial number of partitions 
and replication for the topic.

`partitionAssignments` is considered when the topic is novel and if it already exists. If it is novel then the topic is 
partitioned as assigned replicas as specified. If it already exists then the partitions (pre-existing or novel) are assigned as specified.

`config` is a per topic specification of specific topic config parameters like `retention.ms` to set for that topic.
Only specified config options are checked for equality so only setting what is necessary is all that is required.
Config options not specified will just inherit the broker's default.

So to create two topics `topicA` and `topicB`using all server defaults would be:

```json
{
  "version": "v1",
  "topicStates": {
    "topicA": {},
    "topicB": {}
  }
}
```

Maybe later on you want to modify `topicB` to have a shorter retention time but a higher requirement of minimum in sync replicas:

```json
{
  "version": "v1",
  "topicStates": {
    "topicA": {},
    "topicB": {
      "config" : {
          "min.insync.replicas": "2",
           "retention.ms": "1337"
       }
    }
  }
}
```

`"topicA": {},` could be omitted, but the intent is to reflect state in Kafka you'd like to deploy. To omit it in the state
file that gets processed just means the topic would be effectively ignored.

The integration test resource files can also serve as additional examples. Some test cases have an initial step 1 followed
by a step 2 showing how topic configs can be modified.

## How To Use

1. Download the latest release from the releases section for your platform
2. Make the downloaded binary executable. A known limitation of [github actions](https://github.com/actions/upload-artifact#permission-loss)
3. Run the binary and provide the kafka bootstrap servers string and then the URL to the kafka state file.
   1. Example: `./simple-kafka-manager-[platformName]-latest broker1:9092,broker2:9092,broker3:9092 file:/kafka_state.json`