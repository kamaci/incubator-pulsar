/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.storm;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.storm.MessageToValuesMapper;
import org.apache.pulsar.storm.PulsarSpout;
import org.apache.pulsar.storm.PulsarSpoutConfiguration;
import org.testng.Assert;
import static org.testng.Assert.fail;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.testng.collections.Maps;

import org.apache.pulsar.client.api.ClientConfiguration;
import org.apache.pulsar.client.api.ConsumerConfiguration;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerConsumerBase;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.common.policies.data.PersistentTopicStats;

import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Values;

public class PulsarSpoutTest extends ProducerConsumerBase {

    public final String serviceUrl = "http://127.0.0.1:" + BROKER_WEBSERVICE_PORT;
    public final String topic = "persistent://my-property/use/my-ns/my-topic1";
    public final String subscriptionName = "my-subscriber-name";

    protected PulsarSpoutConfiguration pulsarSpoutConf;
    protected ConsumerConfiguration consumerConf;
    protected PulsarSpout spout;
    protected MockSpoutOutputCollector mockCollector;
    protected Producer producer;

    @Override
    @BeforeMethod
    public void beforeMethod(Method m) throws Exception {
        super.beforeMethod(m);
        setup();
    }

    @Override
    protected void setup() throws Exception {
        super.internalSetup();
        super.producerBaseSetup();

        pulsarSpoutConf = new PulsarSpoutConfiguration();
        pulsarSpoutConf.setServiceUrl(serviceUrl);
        pulsarSpoutConf.setTopic(topic);
        pulsarSpoutConf.setSubscriptionName(subscriptionName);
        pulsarSpoutConf.setMessageToValuesMapper(messageToValuesMapper);
        pulsarSpoutConf.setFailedRetriesTimeout(1, TimeUnit.SECONDS);
        pulsarSpoutConf.setMaxFailedRetries(2);
        pulsarSpoutConf.setSharedConsumerEnabled(true);
        pulsarSpoutConf.setMetricsTimeIntervalInSecs(60);
        consumerConf = new ConsumerConfiguration();
        consumerConf.setSubscriptionType(SubscriptionType.Shared);
        spout = new PulsarSpout(pulsarSpoutConf, new ClientConfiguration(), consumerConf);
        mockCollector = new MockSpoutOutputCollector();
        SpoutOutputCollector collector = new SpoutOutputCollector(mockCollector);
        TopologyContext context = mock(TopologyContext.class);
        when(context.getThisComponentId()).thenReturn("test-spout-" + methodName);
        when(context.getThisTaskId()).thenReturn(0);
        spout.open(Maps.newHashMap(), context, collector);
        producer = pulsarClient.createProducer(topic);
    }

    @AfterMethod
    public void cleanup() throws Exception {
        producer.close();
        spout.close();
        super.internalCleanup();
    }

    @SuppressWarnings("serial")
    public static MessageToValuesMapper messageToValuesMapper = new MessageToValuesMapper() {

        @Override
        public Values toValues(Message msg) {
            if ("message to be dropped".equals(new String(msg.getData()))) {
                return null;
            }
            return new Values(new String(msg.getData()));
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
        }
    };

    @Test
    public void testBasic() throws Exception {
        String msgContent = "hello world";
        producer.send(msgContent.getBytes());
        spout.nextTuple();
        Assert.assertTrue(mockCollector.emitted());
        Assert.assertTrue(msgContent.equals(mockCollector.getTupleData()));
        spout.ack(mockCollector.getLastMessage());
    }

    @Test
    public void testRedeliverOnFail() throws Exception {
        String msgContent = "hello world";
        producer.send(msgContent.getBytes());
        spout.nextTuple();
        spout.fail(mockCollector.getLastMessage());
        mockCollector.reset();
        Thread.sleep(150);
        spout.nextTuple();
        Assert.assertTrue(mockCollector.emitted());
        Assert.assertTrue(msgContent.equals(mockCollector.getTupleData()));
        spout.ack(mockCollector.getLastMessage());
    }

    @Test
    public void testNoRedeliverOnAck() throws Exception {
        String msgContent = "hello world";
        producer.send(msgContent.getBytes());
        spout.nextTuple();
        spout.ack(mockCollector.getLastMessage());
        mockCollector.reset();
        spout.nextTuple();
        Assert.assertFalse(mockCollector.emitted());
        Assert.assertNull(mockCollector.getTupleData());
    }

    @Test
    public void testLimitedRedeliveriesOnTimeout() throws Exception {
        String msgContent = "chuck norris";
        producer.send(msgContent.getBytes());

        long startTime = System.currentTimeMillis();
        while (startTime + pulsarSpoutConf.getFailedRetriesTimeout(TimeUnit.MILLISECONDS) > System
                .currentTimeMillis()) {
            mockCollector.reset();
            spout.nextTuple();
            Assert.assertTrue(mockCollector.emitted());
            Assert.assertTrue(msgContent.equals(mockCollector.getTupleData()));
            spout.fail(mockCollector.getLastMessage());
            // wait to avoid backoff
            Thread.sleep(500);
        }
        spout.nextTuple();
        spout.fail(mockCollector.getLastMessage());
        mockCollector.reset();
        Thread.sleep(500);
        spout.nextTuple();
        Assert.assertFalse(mockCollector.emitted());
        Assert.assertNull(mockCollector.getTupleData());
    }

    @Test
    public void testLimitedRedeliveriesOnCount() throws Exception {
        String msgContent = "hello world";
        producer.send(msgContent.getBytes());

        spout.nextTuple();
        Assert.assertTrue(mockCollector.emitted());
        Assert.assertTrue(msgContent.equals(mockCollector.getTupleData()));
        spout.fail(mockCollector.getLastMessage());

        mockCollector.reset();
        Thread.sleep(150);

        spout.nextTuple();
        Assert.assertTrue(mockCollector.emitted());
        Assert.assertTrue(msgContent.equals(mockCollector.getTupleData()));
        spout.fail(mockCollector.getLastMessage());

        mockCollector.reset();
        Thread.sleep(300);

        spout.nextTuple();
        Assert.assertTrue(mockCollector.emitted());
        Assert.assertTrue(msgContent.equals(mockCollector.getTupleData()));
        spout.fail(mockCollector.getLastMessage());

        mockCollector.reset();
        Thread.sleep(500);
        spout.nextTuple();
        Assert.assertFalse(mockCollector.emitted());
        Assert.assertNull(mockCollector.getTupleData());
    }

    @Test
    public void testBackoffOnRetry() throws Exception {
        String msgContent = "chuck norris";
        producer.send(msgContent.getBytes());
        spout.nextTuple();
        spout.fail(mockCollector.getLastMessage());
        mockCollector.reset();
        // due to backoff we should not get the message again immediately
        spout.nextTuple();
        Assert.assertFalse(mockCollector.emitted());
        Assert.assertNull(mockCollector.getTupleData());
        Thread.sleep(100);
        spout.nextTuple();
        Assert.assertTrue(mockCollector.emitted());
        Assert.assertTrue(msgContent.equals(mockCollector.getTupleData()));
        spout.ack(mockCollector.getLastMessage());
    }

    @Test
    public void testMessageDrop() throws Exception {
        String msgContent = "message to be dropped";
        producer.send(msgContent.getBytes());
        spout.nextTuple();
        Assert.assertFalse(mockCollector.emitted());
        Assert.assertNull(mockCollector.getTupleData());
    }

    @SuppressWarnings({ "rawtypes" })
    @Test
    public void testMetrics() throws Exception {
        spout.resetMetrics();
        String msgContent = "hello world";
        producer.send(msgContent.getBytes());
        spout.nextTuple();
        Map metrics = spout.getMetrics();
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_MESSAGES_RECEIVED)).longValue(), 1);
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_MESSAGES_EMITTED)).longValue(), 1);
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_PENDING_FAILED_MESSAGES)).longValue(), 0);
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_PENDING_ACKS)).longValue(), 1);
        Assert.assertEquals(((Double) metrics.get(PulsarSpout.CONSUMER_RATE)).doubleValue(),
                1.0 / pulsarSpoutConf.getMetricsTimeIntervalInSecs());
        Assert.assertEquals(((Double) metrics.get(PulsarSpout.CONSUMER_THROUGHPUT_BYTES)).doubleValue(),
                ((double) msgContent.getBytes().length) / pulsarSpoutConf.getMetricsTimeIntervalInSecs());
        spout.fail(mockCollector.getLastMessage());
        metrics = spout.getMetrics();
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_MESSAGES_RECEIVED)).longValue(), 1);
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_MESSAGES_EMITTED)).longValue(), 1);
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_PENDING_FAILED_MESSAGES)).longValue(), 1);
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_PENDING_ACKS)).longValue(), 0);
        Thread.sleep(150);
        spout.nextTuple();
        metrics = spout.getMetrics();
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_MESSAGES_RECEIVED)).longValue(), 1);
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_MESSAGES_EMITTED)).longValue(), 2);
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_PENDING_FAILED_MESSAGES)).longValue(), 1);
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_PENDING_ACKS)).longValue(), 1);
        spout.ack(mockCollector.getLastMessage());
        metrics = (Map) spout.getValueAndReset();
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_MESSAGES_RECEIVED)).longValue(), 1);
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_MESSAGES_EMITTED)).longValue(), 2);
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_PENDING_FAILED_MESSAGES)).longValue(), 0);
        Assert.assertEquals(((Long) metrics.get(PulsarSpout.NO_OF_PENDING_ACKS)).longValue(), 0);
    }

    @Test
    public void testSharedConsumer() throws Exception {
        PersistentTopicStats topicStats = admin.persistentTopics().getStats(topic);
        Assert.assertEquals(topicStats.subscriptions.get(subscriptionName).consumers.size(), 1);
        PulsarSpout otherSpout = new PulsarSpout(pulsarSpoutConf, new ClientConfiguration(), consumerConf);
        MockSpoutOutputCollector otherMockCollector = new MockSpoutOutputCollector();
        SpoutOutputCollector collector = new SpoutOutputCollector(otherMockCollector);
        TopologyContext context = mock(TopologyContext.class);
        when(context.getThisComponentId()).thenReturn("test-spout-" + methodName);
        when(context.getThisTaskId()).thenReturn(1);
        otherSpout.open(Maps.newHashMap(), context, collector);

        topicStats = admin.persistentTopics().getStats(topic);
        Assert.assertEquals(topicStats.subscriptions.get(subscriptionName).consumers.size(), 1);

        otherSpout.close();

        topicStats = admin.persistentTopics().getStats(topic);
        Assert.assertEquals(topicStats.subscriptions.get(subscriptionName).consumers.size(), 1);
    }

    @Test
    public void testNoSharedConsumer() throws Exception {
        PersistentTopicStats topicStats = admin.persistentTopics().getStats(topic);
        Assert.assertEquals(topicStats.subscriptions.get(subscriptionName).consumers.size(), 1);
        pulsarSpoutConf.setSharedConsumerEnabled(false);
        PulsarSpout otherSpout = new PulsarSpout(pulsarSpoutConf, new ClientConfiguration(), consumerConf);
        MockSpoutOutputCollector otherMockCollector = new MockSpoutOutputCollector();
        SpoutOutputCollector collector = new SpoutOutputCollector(otherMockCollector);
        TopologyContext context = mock(TopologyContext.class);
        when(context.getThisComponentId()).thenReturn("test-spout-" + methodName);
        when(context.getThisTaskId()).thenReturn(1);
        otherSpout.open(Maps.newHashMap(), context, collector);

        topicStats = admin.persistentTopics().getStats(topic);
        Assert.assertEquals(topicStats.subscriptions.get(subscriptionName).consumers.size(), 2);

        otherSpout.close();

        topicStats = admin.persistentTopics().getStats(topic);
        Assert.assertEquals(topicStats.subscriptions.get(subscriptionName).consumers.size(), 1);
    }

    @Test
    public void testSerializability() throws Exception {
        // test serializability with no auth
        PulsarSpout spoutWithNoAuth = new PulsarSpout(pulsarSpoutConf, new ClientConfiguration());
        TestUtil.testSerializability(spoutWithNoAuth);
    }
    
    @Test
    public void testFailedConsumer() throws Exception {
        PulsarSpoutConfiguration pulsarSpoutConf = new PulsarSpoutConfiguration();
        pulsarSpoutConf.setServiceUrl(serviceUrl);
        pulsarSpoutConf.setTopic("persistent://invalidTopic");
        pulsarSpoutConf.setSubscriptionName(subscriptionName);
        pulsarSpoutConf.setMessageToValuesMapper(messageToValuesMapper);
        pulsarSpoutConf.setFailedRetriesTimeout(1, TimeUnit.SECONDS);
        pulsarSpoutConf.setMaxFailedRetries(2);
        pulsarSpoutConf.setSharedConsumerEnabled(false);
        pulsarSpoutConf.setMetricsTimeIntervalInSecs(60);
        ConsumerConfiguration consumerConf = new ConsumerConfiguration();
        consumerConf.setSubscriptionType(SubscriptionType.Shared);
        PulsarSpout spout = new PulsarSpout(pulsarSpoutConf, new ClientConfiguration(), consumerConf);
        MockSpoutOutputCollector mockCollector = new MockSpoutOutputCollector();
        SpoutOutputCollector collector = new SpoutOutputCollector(mockCollector);
        TopologyContext context = mock(TopologyContext.class);
        when(context.getThisComponentId()).thenReturn("new-test" + methodName);
        when(context.getThisTaskId()).thenReturn(0);
        try {
            spout.open(Maps.newHashMap(), context, collector);
            fail("should have failed as consumer creation failed");
        } catch (IllegalStateException e) {
            // Ok
        }
    }
}
