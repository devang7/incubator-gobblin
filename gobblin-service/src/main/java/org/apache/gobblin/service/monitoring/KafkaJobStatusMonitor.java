/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.service.monitoring;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import kafka.message.MessageAndMetadata;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.gobblin.metastore.FileContextBasedFsStateStore;
import org.apache.gobblin.metastore.FileContextBasedFsStateStoreFactory;
import org.apache.gobblin.metastore.StateStore;
import org.apache.gobblin.metastore.util.StateStoreCleanerRunnable;
import org.apache.gobblin.metrics.event.TimingEvent;
import org.apache.gobblin.runtime.kafka.HighLevelConsumer;
import org.apache.gobblin.util.ConfigUtils;


/**
 * A Kafka monitor that tracks {@link org.apache.gobblin.metrics.GobblinTrackingEvent}s reporting statuses of
 * running jobs. The job statuses are stored as {@link org.apache.gobblin.configuration.State} objects in
 * a {@link FileContextBasedFsStateStore}.
 */
@Slf4j
public abstract class KafkaJobStatusMonitor extends HighLevelConsumer<byte[], byte[]> {
  static final String JOB_STATUS_MONITOR_PREFIX = "jobStatusMonitor";
  public static final String JOB_STATUS_MONITOR_ENABLED_KEY =  JOB_STATUS_MONITOR_PREFIX + ".enabled";
  //We use table suffix that is different from the Gobblin job state store suffix of jst to avoid confusion.
  //gst refers to the state store suffix for GaaS-orchestrated Gobblin jobs.
  public static final String STATE_STORE_TABLE_SUFFIX = "gst";

  static final String JOB_STATUS_MONITOR_TOPIC_KEY = "topic";
  static final String JOB_STATUS_MONITOR_NUM_THREADS_KEY = "numThreads";
  static final String JOB_STATUS_MONITOR_CLASS_KEY = "class";
  static final String DEFAULT_JOB_STATUS_MONITOR_CLASS = KafkaAvroJobStatusMonitor.class.getName();
  static final String STATE_STORE_FACTORY_CLASS_KEY = "stateStoreFactoryClass";

  private static final String KAFKA_AUTO_OFFSET_RESET_KEY = "auto.offset.reset";
  private static final String KAFKA_AUTO_OFFSET_RESET_SMALLEST = "smallest";
  private static final String KAFKA_AUTO_OFFSET_RESET_LARGEST = "largest";

  @Getter
  private final StateStore<org.apache.gobblin.configuration.State> stateStore;
  private final ScheduledExecutorService scheduledExecutorService;
  private static final Config DEFAULTS = ConfigFactory.parseMap(ImmutableMap.of(
      KAFKA_AUTO_OFFSET_RESET_KEY, KAFKA_AUTO_OFFSET_RESET_SMALLEST));

  public KafkaJobStatusMonitor(String topic, Config config, int numThreads)
      throws ReflectiveOperationException {
    super(topic, config.withFallback(DEFAULTS), numThreads);
    String stateStoreFactoryClass = ConfigUtils.getString(config, STATE_STORE_FACTORY_CLASS_KEY, FileContextBasedFsStateStoreFactory.class.getName());

    this.stateStore =
        ((StateStore.Factory) Class.forName(stateStoreFactoryClass).newInstance()).createStateStore(config, org.apache.gobblin.configuration.State.class);
    this.scheduledExecutorService = Executors.newScheduledThreadPool(1);
  }

  @Override
  protected void startUp() {
    super.startUp();
    log.info("Scheduling state store cleaner..");
    scheduledExecutorService.scheduleAtFixedRate(new StateStoreCleanerRunnable(this.config), 300, 86400L, TimeUnit.SECONDS);
  }

  @Override
  public void shutDown() {
     super.shutDown();
     this.scheduledExecutorService.shutdown();
     try {
       this.scheduledExecutorService.awaitTermination(30, TimeUnit.SECONDS);
     } catch (InterruptedException e) {
       log.error("Exception {} encountered when shutting down state store cleaner", e);
     }
  }

  @Override
  protected void createMetrics() {
    super.createMetrics();
  }

  @Override
  protected void processMessage(MessageAndMetadata<byte[],byte[]> message) {
    try {
      org.apache.gobblin.configuration.State jobStatus = parseJobStatus(message.message());
      if (jobStatus != null) {
        addJobStatusToStateStore(jobStatus);
      }
    } catch (IOException ioe) {
      String messageStr = new String(message.message(), Charsets.UTF_8);
      log.error(String.format("Failed to parse kafka message with offset %d: %s.", message.offset(), messageStr), ioe);
    }
  }

  /**
   * Persist job status to the underlying {@link StateStore}.
   * @param jobStatus
   * @throws IOException
   */
  private void addJobStatusToStateStore(org.apache.gobblin.configuration.State jobStatus)
      throws IOException {
    String flowName = jobStatus.getProp(TimingEvent.FlowEventConstants.FLOW_NAME_FIELD);
    String flowGroup = jobStatus.getProp(TimingEvent.FlowEventConstants.FLOW_GROUP_FIELD);
    String storeName = Joiner.on(JobStatusRetriever.STATE_STORE_KEY_SEPARATION_CHARACTER).join(flowGroup, flowName);
    String flowExecutionId = jobStatus.getProp(TimingEvent.FlowEventConstants.FLOW_EXECUTION_ID_FIELD);
    String jobName = jobStatus.getProp(TimingEvent.FlowEventConstants.JOB_NAME_FIELD,JobStatusRetriever.NA_KEY);
    String jobGroup = jobStatus.getProp(TimingEvent.FlowEventConstants.JOB_GROUP_FIELD, JobStatusRetriever.NA_KEY);
    String tableName = Joiner.on(JobStatusRetriever.STATE_STORE_KEY_SEPARATION_CHARACTER).join(flowExecutionId, jobGroup, jobName, STATE_STORE_TABLE_SUFFIX);
    this.stateStore.put(storeName, tableName, jobStatus);
  }

  public abstract org.apache.gobblin.configuration.State parseJobStatus(byte[] message) throws IOException;
}
