package org.apache.helix.manager.zk;

/*
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

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.I0Itec.zkclient.ZkConnection;
import org.I0Itec.zkclient.exception.ZkNodeExistsException;
import org.apache.helix.AccessOption;
import org.apache.helix.BaseDataAccessor;
import org.apache.helix.ClusterMessagingService;
import org.apache.helix.ConfigAccessor;
import org.apache.helix.ConfigChangeListener;
import org.apache.helix.ControllerChangeListener;
import org.apache.helix.CurrentStateChangeListener;
import org.apache.helix.ExternalViewChangeListener;
import org.apache.helix.HealthStateChangeListener;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixException;
import org.apache.helix.HelixManagerProperties;
import org.apache.helix.IdealStateChangeListener;
import org.apache.helix.InstanceConfigChangeListener;
import org.apache.helix.InstanceType;
import org.apache.helix.LiveInstanceChangeListener;
import org.apache.helix.LiveInstanceInfoProvider;
import org.apache.helix.MessageListener;
import org.apache.helix.PreConnectCallback;
import org.apache.helix.PropertyKey;
import org.apache.helix.PropertyPathConfig;
import org.apache.helix.PropertyType;
import org.apache.helix.ScopedConfigChangeListener;
import org.apache.helix.ZNRecord;
import org.apache.helix.HelixConstants.ChangeType;
import org.apache.helix.PropertyKey.Builder;
import org.apache.helix.healthcheck.ParticipantHealthReportCollector;
import org.apache.helix.healthcheck.ParticipantHealthReportCollectorImpl;
import org.apache.helix.messaging.handling.MessageHandlerFactory;
import org.apache.helix.model.CurrentState;
import org.apache.helix.model.HelixConfigScope;
import org.apache.helix.model.InstanceConfig;
import org.apache.helix.model.LiveInstance;
import org.apache.helix.model.StateModelDefinition;
import org.apache.helix.model.HelixConfigScope.ConfigScopeProperty;
import org.apache.helix.model.Message.MessageType;
import org.apache.helix.model.builder.HelixConfigScopeBuilder;
import org.apache.helix.participant.DistClusterControllerElection;
import org.apache.helix.participant.HelixStateMachineEngine;
import org.apache.helix.participant.StateMachineEngine;
import org.apache.helix.participant.statemachine.ScheduledTaskStateModelFactory;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.apache.log4j.Logger;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.data.Stat;

public class ParticipantManager extends AbstractManager {

  private static Logger LOG = Logger.getLogger(ParticipantManager.class);
  
  /**
   * state-transition message handler factory for helix-participant
   */
  final StateMachineEngine _stateMachineEngine;

  final ParticipantHealthReportCollectorImpl _participantHealthInfoCollector;
  
  public ParticipantManager(String zkAddress, String clusterName, String instanceName) {
    super(zkAddress, clusterName, instanceName, InstanceType.PARTICIPANT);
    
    _stateMachineEngine = new HelixStateMachineEngine(this);
    _participantHealthInfoCollector = new ParticipantHealthReportCollectorImpl(this, _instanceName);
    
    _timerTasks.add(_participantHealthInfoCollector);
  }
  
  @Override
  public ParticipantHealthReportCollector getHealthReportCollector() {
    checkConnected();
    return _participantHealthInfoCollector;
  }
  
  @Override
  public StateMachineEngine getStateMachineEngine() {
    return _stateMachineEngine;
  }

  @Override
  public void handleNewSession() {
    waitUntilConnected();

    
    /**
     * stop timer tasks, reset all handlers, make sure cleanup completed for previous session
     * disconnect if cleanup fails
     */
    stopTimerTasks();
    resetHandlers();
    
    /**
     * clear write-through cache
     */
    _baseDataAccessor.reset();

    
    /**
     * from here on, we are dealing with new session
     */
    if (!ZKUtil.isClusterSetup(_clusterName, _zkclient)) {
      throw new HelixException("Cluster structure is not set up for cluster: "
          + _clusterName);
    }
    
    /**
     * auto-join
     */
    ParticipantManagerHelper participantHelper 
          = new ParticipantManagerHelper(this, _zkclient, _sessionTimeout);
    participantHelper.joinCluster();

    /**
     * Invoke PreConnectCallbacks
     */
    for (PreConnectCallback callback : _preConnectCallbacks)
    {
      callback.onPreConnect();
    }

    participantHelper.createLiveInstance();
    
    participantHelper.carryOverPreviousCurrentState();
    
    /**
     * setup message listener
     */
    participantHelper.setupMsgHandler();

    /**
     * start health check timer task
     */
    participantHelper.createHealthCheckPath();
    startTimerTasks();
    
    /**
     * init user defined handlers only
     */
    List<CallbackHandler> userHandlers = new ArrayList<CallbackHandler>();
    for (CallbackHandler handler : _handlers) {
      Object listener = handler.getListener();
      if (!listener.equals(_messagingService.getExecutor())
          && !listener.equals(_dataAccessor)) {
        userHandlers.add(handler);
      }
    }
    initHandlers(userHandlers);

  }
  
  /**
   * helix-participant uses a write-through cache for current-state
   * 
   */
  @Override
  BaseDataAccessor<ZNRecord> createBaseDataAccessor(ZkBaseDataAccessor<ZNRecord> baseDataAccessor) {
    String curStatePath = PropertyPathConfig.getPath(PropertyType.CURRENTSTATES,
        _clusterName,
        _instanceName);
      return new ZkCacheBaseDataAccessor<ZNRecord>(baseDataAccessor,
                         Arrays.asList(curStatePath));

  }

  @Override
  public boolean isLeader() {
    return false;
  }

  /**
   * disconnect logic for helix-participant
   */
  void doDisconnect() {
    // nothing for participant
  }
}