/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package kafka.api

import java.util
import kafka.coordinator.GroupCoordinator
import org.apache.kafka.clients.consumer._
import org.apache.kafka.clients.producer.{ProducerConfig, ProducerRecord}
import org.apache.kafka.common.record.TimestampType
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.{PartitionInfo, TopicPartition}
import kafka.utils.{TestUtils, Logging, ShutdownableThread}
import kafka.server.KafkaConfig
import java.util.ArrayList
import org.junit.Assert._
import org.junit.{Before, Test}
import scala.collection.JavaConverters._
import scala.collection.mutable.Buffer
import org.apache.kafka.common.internals.TopicConstants

/**
 * Integration tests for the new consumer that cover basic usage as well as server failures
 */
abstract class BaseConsumerTest extends IntegrationTestHarness with Logging {

  val producerCount = 1
  val consumerCount = 2
  val serverCount = 3

  val topic = "topic"
  val part = 0
  val tp = new TopicPartition(topic, part)
  val part2 = 1
  val tp2 = new TopicPartition(topic, part2)

  // configure the servers and clients
  this.serverConfig.setProperty(KafkaConfig.ControlledShutdownEnableProp, "false") // speed up shutdown
  this.serverConfig.setProperty(KafkaConfig.OffsetsTopicReplicationFactorProp, "3") // don't want to lose offset
  this.serverConfig.setProperty(KafkaConfig.OffsetsTopicPartitionsProp, "1")
  this.serverConfig.setProperty(KafkaConfig.GroupMinSessionTimeoutMsProp, "100") // set small enough session timeout
  this.serverConfig.setProperty(KafkaConfig.GroupMaxSessionTimeoutMsProp, "30000")
  this.producerConfig.setProperty(ProducerConfig.ACKS_CONFIG, "all")
  this.consumerConfig.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "my-test")
  this.consumerConfig.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
  this.consumerConfig.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
  this.consumerConfig.setProperty(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "100")

  @Before
  override def setUp() {
    super.setUp()

    // create the test topic with all the brokers as replicas
    TestUtils.createTopic(this.zkUtils, topic, 2, serverCount, this.servers)
  }

  @Test
  def testSimpleConsumption() {
    val numRecords = 10000
    sendRecords(numRecords)

    assertEquals(0, this.consumers(0).assignment.size)
    this.consumers(0).assign(List(tp).asJava)
    assertEquals(1, this.consumers(0).assignment.size)

    this.consumers(0).seek(tp, 0)
    consumeAndVerifyRecords(consumer = this.consumers(0), numRecords = numRecords, startingOffset = 0)

    // check async commit callbacks
    val commitCallback = new CountConsumerCommitCallback()
    this.consumers(0).commitAsync(commitCallback)

    // shouldn't make progress until poll is invoked
    Thread.sleep(10)
    assertEquals(0, commitCallback.successCount)
    awaitCommitCallback(this.consumers(0), commitCallback)
  }

  @Test
  def testAutoCommitOnRebalance() {
    val topic2 = "topic2"
    TestUtils.createTopic(this.zkUtils, topic2, 2, serverCount, this.servers)

    this.consumerConfig.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
    val consumer0 = new KafkaConsumer(this.consumerConfig, new ByteArrayDeserializer(), new ByteArrayDeserializer())

    val numRecords = 10000
    sendRecords(numRecords)

    val rebalanceListener = new ConsumerRebalanceListener {
      override def onPartitionsAssigned(partitions: util.Collection[TopicPartition]) = {
        // keep partitions paused in this test so that we can verify the commits based on specific seeks
        consumer0.pause(partitions)
      }

      override def onPartitionsRevoked(partitions: util.Collection[TopicPartition]) = {}
    }

    consumer0.subscribe(List(topic).asJava, rebalanceListener)

    val assignment = Set(tp, tp2)
    TestUtils.waitUntilTrue(() => {
      consumer0.poll(50)
      consumer0.assignment() == assignment.asJava
    }, s"Expected partitions ${assignment.asJava} but actually got ${consumer0.assignment()}")

    consumer0.seek(tp, 300)
    consumer0.seek(tp2, 500)

    // change subscription to trigger rebalance
    consumer0.subscribe(List(topic, topic2).asJava, rebalanceListener)

    val newAssignment = Set(tp, tp2, new TopicPartition(topic2, 0), new TopicPartition(topic2, 1))
    TestUtils.waitUntilTrue(() => {
      val records = consumer0.poll(50)
      consumer0.assignment() == newAssignment.asJava
    }, s"Expected partitions ${newAssignment.asJava} but actually got ${consumer0.assignment()}")

    // after rebalancing, we should have reset to the committed positions
    assertEquals(300, consumer0.committed(tp).offset)
    assertEquals(500, consumer0.committed(tp2).offset)
  }

  @Test
  def testCommitSpecifiedOffsets() {
    sendRecords(5, tp)
    sendRecords(7, tp2)

    this.consumers(0).assign(List(tp, tp2).asJava)

    // Need to poll to join the group
    this.consumers(0).poll(50)
    val pos1 = this.consumers(0).position(tp)
    val pos2 = this.consumers(0).position(tp2)
    this.consumers(0).commitSync(Map[TopicPartition, OffsetAndMetadata]((tp, new OffsetAndMetadata(3L))).asJava)
    assertEquals(3, this.consumers(0).committed(tp).offset)
    assertNull(this.consumers(0).committed(tp2))

    // Positions should not change
    assertEquals(pos1, this.consumers(0).position(tp))
    assertEquals(pos2, this.consumers(0).position(tp2))
    this.consumers(0).commitSync(Map[TopicPartition, OffsetAndMetadata]((tp2, new OffsetAndMetadata(5L))).asJava)
    assertEquals(3, this.consumers(0).committed(tp).offset)
    assertEquals(5, this.consumers(0).committed(tp2).offset)

    // Using async should pick up the committed changes after commit completes
    val commitCallback = new CountConsumerCommitCallback()
    this.consumers(0).commitAsync(Map[TopicPartition, OffsetAndMetadata]((tp2, new OffsetAndMetadata(7L))).asJava, commitCallback)
    awaitCommitCallback(this.consumers(0), commitCallback)
    assertEquals(7, this.consumers(0).committed(tp2).offset)
  }

  @Test
  def testListTopics() {
    val numParts = 2
    val topic1 = "part-test-topic-1"
    val topic2 = "part-test-topic-2"
    val topic3 = "part-test-topic-3"
    TestUtils.createTopic(this.zkUtils, topic1, numParts, 1, this.servers)
    TestUtils.createTopic(this.zkUtils, topic2, numParts, 1, this.servers)
    TestUtils.createTopic(this.zkUtils, topic3, numParts, 1, this.servers)

    val topics = this.consumers.head.listTopics()
    assertNotNull(topics)
    assertEquals(5, topics.size())
    assertEquals(5, topics.keySet().size())
    assertEquals(2, topics.get(topic1).size)
    assertEquals(2, topics.get(topic2).size)
    assertEquals(2, topics.get(topic3).size)
  }

  @Test
  def testPartitionReassignmentCallback() {
    val listener = new TestConsumerReassignmentListener()
    this.consumerConfig.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "100") // timeout quickly to avoid slow test
    this.consumerConfig.setProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "30")
    val consumer0 = new KafkaConsumer(this.consumerConfig, new ByteArrayDeserializer(), new ByteArrayDeserializer())
    consumer0.subscribe(List(topic).asJava, listener)

    // the initial subscription should cause a callback execution
    while (listener.callsToAssigned == 0)
      consumer0.poll(50)

    // get metadata for the topic
    var parts: Seq[PartitionInfo] = null
    while (parts == null)
      parts = consumer0.partitionsFor(TopicConstants.GROUP_METADATA_TOPIC_NAME).asScala
    assertEquals(1, parts.size)
    assertNotNull(parts(0).leader())

    // shutdown the coordinator
    val coordinator = parts(0).leader().id()
    this.servers(coordinator).shutdown()

    // this should cause another callback execution
    while (listener.callsToAssigned < 2)
      consumer0.poll(50)

    assertEquals(2, listener.callsToAssigned)

    // only expect one revocation since revoke is not invoked on initial membership
    assertEquals(2, listener.callsToRevoked)

    consumer0.close()
  }

  @Test
  def testUnsubscribeTopic() {

    this.consumerConfig.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "100") // timeout quickly to avoid slow test
    this.consumerConfig.setProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "30")
    val consumer0 = new KafkaConsumer(this.consumerConfig, new ByteArrayDeserializer(), new ByteArrayDeserializer())

    try {
      val listener = new TestConsumerReassignmentListener()
      consumer0.subscribe(List(topic).asJava, listener)

      // the initial subscription should cause a callback execution
      while (listener.callsToAssigned == 0)
        consumer0.poll(50)

      consumer0.subscribe(List[String]().asJava)
      assertEquals(0, consumer0.assignment.size())
    } finally {
      consumer0.close()
    }
  }

  @Test
  def testPauseStateNotPreservedByRebalance() {
    this.consumerConfig.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "100") // timeout quickly to avoid slow test
    this.consumerConfig.setProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "30")
    val consumer0 = new KafkaConsumer(this.consumerConfig, new ByteArrayDeserializer(), new ByteArrayDeserializer())

    sendRecords(5)
    consumer0.subscribe(List(topic).asJava)
    consumeAndVerifyRecords(consumer = consumer0, numRecords = 5, startingOffset = 0)
    consumer0.pause(List(tp).asJava)

    // subscribe to a new topic to trigger a rebalance
    consumer0.subscribe(List("topic2").asJava)

    // after rebalance, our position should be reset and our pause state lost,
    // so we should be able to consume from the beginning
    consumeAndVerifyRecords(consumer = consumer0, numRecords = 0, startingOffset = 5)
  }

  protected class TestConsumerReassignmentListener extends ConsumerRebalanceListener {
    var callsToAssigned = 0
    var callsToRevoked = 0

    def onPartitionsAssigned(partitions: java.util.Collection[TopicPartition]) {
      info("onPartitionsAssigned called.")
      callsToAssigned += 1
    }

    def onPartitionsRevoked(partitions: java.util.Collection[TopicPartition]) {
      info("onPartitionsRevoked called.")
      callsToRevoked += 1
    }
  }

  protected def sendRecords(numRecords: Int): Unit = {
    sendRecords(numRecords, tp)
  }

  protected def sendRecords(numRecords: Int, tp: TopicPartition) {
    (0 until numRecords).foreach { i =>
      this.producers(0).send(new ProducerRecord(tp.topic(), tp.partition(), i.toLong, s"key $i".getBytes, s"value $i".getBytes))
    }
    this.producers(0).flush()
  }

  protected def consumeAndVerifyRecords(consumer: Consumer[Array[Byte], Array[Byte]],
                                        numRecords: Int,
                                        startingOffset: Int,
                                        startingKeyAndValueIndex: Int = 0,
                                        startingTimestamp: Long = 0L,
                                        timestampType: TimestampType = TimestampType.CREATE_TIME,
                                        tp: TopicPartition = tp,
                                        maxPollRecords: Int = Int.MaxValue) {
    val records = consumeRecords(consumer, numRecords, maxPollRecords = maxPollRecords)
    val now = System.currentTimeMillis()
    for (i <- 0 until numRecords) {
      val record = records.get(i)
      val offset = startingOffset + i
      assertEquals(tp.topic, record.topic)
      assertEquals(tp.partition, record.partition)
      if (timestampType == TimestampType.CREATE_TIME) {
        assertEquals(timestampType, record.timestampType)
        val timestamp = startingTimestamp + i
        assertEquals(timestamp.toLong, record.timestamp)
      } else
        assertTrue(s"Got unexpected timestamp ${record.timestamp}. Timestamp should be between [$startingTimestamp, $now}]",
          record.timestamp >= startingTimestamp && record.timestamp <= now)
      assertEquals(offset.toLong, record.offset)
      val keyAndValueIndex = startingKeyAndValueIndex + i
      assertEquals(s"key $keyAndValueIndex", new String(record.key))
      assertEquals(s"value $keyAndValueIndex", new String(record.value))
      // this is true only because K and V are byte arrays
      assertEquals(s"key $keyAndValueIndex".length, record.serializedKeySize)
      assertEquals(s"value $keyAndValueIndex".length, record.serializedValueSize)
    }
  }

  protected def consumeRecords[K, V](consumer: Consumer[K, V],
                                     numRecords: Int,
                                     maxPollRecords: Int = Int.MaxValue): ArrayList[ConsumerRecord[K, V]] = {
    val records = new ArrayList[ConsumerRecord[K, V]]
    val maxIters = numRecords * 300
    var iters = 0
    while (records.size < numRecords) {
      val polledRecords = consumer.poll(50).asScala
      assertTrue(polledRecords.size <= maxPollRecords)
      for (record <- polledRecords)
        records.add(record)
      if (iters > maxIters)
        throw new IllegalStateException("Failed to consume the expected records after " + iters + " iterations.")
      iters += 1
    }
    records
  }

  protected def awaitCommitCallback[K, V](consumer: Consumer[K, V],
                                          commitCallback: CountConsumerCommitCallback,
                                          count: Int = 1): Unit = {
    val startCount = commitCallback.successCount
    val started = System.currentTimeMillis()
    while (commitCallback.successCount < startCount + count && System.currentTimeMillis() - started < 10000)
      consumer.poll(50)
    assertEquals(startCount + count, commitCallback.successCount)
  }

  protected class CountConsumerCommitCallback extends OffsetCommitCallback {
    var successCount = 0
    var failCount = 0

    override def onComplete(offsets: util.Map[TopicPartition, OffsetAndMetadata], exception: Exception): Unit = {
      if (exception == null)
        successCount += 1
      else
        failCount += 1
    }
  }

  protected class ConsumerAssignmentPoller(consumer: Consumer[Array[Byte], Array[Byte]],
                                           topicsToSubscribe: List[String]) extends ShutdownableThread("daemon-consumer-assignment", false)
  {
    @volatile private var partitionAssignment: Set[TopicPartition] = Set.empty[TopicPartition]
    private var topicsSubscription = topicsToSubscribe
    @volatile private var subscriptionChanged = false

    val rebalanceListener = new ConsumerRebalanceListener {
      override def onPartitionsAssigned(partitions: util.Collection[TopicPartition]) = {
        partitionAssignment = collection.immutable.Set(consumer.assignment().asScala.toArray: _*)
      }

      override def onPartitionsRevoked(partitions: util.Collection[TopicPartition]) = {
        partitionAssignment = Set.empty[TopicPartition]
      }
    }
    consumer.subscribe(topicsToSubscribe.asJava, rebalanceListener)

    def consumerAssignment(): Set[TopicPartition] = {
      partitionAssignment
    }

    /**
     * Subscribe consumer to a new set of topics.
     * Since this method most likely be called from a different thread, this function
     * just "schedules" the subscription change, and actual call to consumer.subscribe is done
     * in the doWork() method
     *
     * This method does not allow to change subscription until doWork processes the previous call
     * to this method. This is just to avoid race conditions and enough functionality for testing purposes
     * @param newTopicsToSubscribe
     */
    def subscribe(newTopicsToSubscribe: List[String]): Unit = {
      if (subscriptionChanged) {
        throw new IllegalStateException("Do not call subscribe until the previous subsribe request is processed.")
      }
      topicsSubscription = newTopicsToSubscribe
      subscriptionChanged = true
    }

    def isSubscribeRequestProcessed(): Boolean = {
      !subscriptionChanged
    }

    override def doWork(): Unit = {
      if (subscriptionChanged) {
        consumer.subscribe(topicsSubscription.asJava, rebalanceListener)
        subscriptionChanged = false
      }
      consumer.poll(50)
    }
  }

  /**
   * Check whether partition assignment is valid
   * Assumes partition assignment is valid iff
   * 1. Every consumer got assigned at least one partition
   * 2. Each partition is assigned to only one consumer
   * 3. Every partition is assigned to one of the consumers
   *
   * @param assignments set of consumer assignments; one per each consumer
   * @param partitions set of partitions that consumers subscribed to
   * @return true if partition assignment is valid
   */
  def isPartitionAssignmentValid(assignments: Buffer[Set[TopicPartition]],
                                 partitions: Set[TopicPartition]): Boolean = {
    val allNonEmptyAssignments = assignments forall (assignment => assignment.size > 0)
    if (!allNonEmptyAssignments) {
      // at least one consumer got empty assignment
      return false
    }

    // make sure that sum of all partitions to all consumers equals total number of partitions
    val totalPartitionsInAssignments = (0 /: assignments) (_ + _.size)
    if (totalPartitionsInAssignments != partitions.size) {
      // either same partitions got assigned to more than one consumer or some
      // partitions were not assigned
      return false
    }

    // The above checks could miss the case where one or more partitions were assigned to more
    // than one consumer and the same number of partitions were missing from assignments.
    // Make sure that all unique assignments are the same as 'partitions'
    val uniqueAssignedPartitions = (Set[TopicPartition]() /: assignments) (_ ++ _)
    uniqueAssignedPartitions == partitions
  }

}
