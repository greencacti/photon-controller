/*
 * Copyright 2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.photon.controller.deployer.dcp.workflow;

import com.vmware.photon.controller.api.DeploymentState;
import com.vmware.photon.controller.api.UsageTag;
import com.vmware.photon.controller.cloudstore.dcp.CloudStoreDcpHost;
import com.vmware.photon.controller.cloudstore.dcp.entity.DeploymentService;
import com.vmware.photon.controller.common.dcp.InitializationUtils;
import com.vmware.photon.controller.common.dcp.PatchUtils;
import com.vmware.photon.controller.common.dcp.ServiceUtils;
import com.vmware.photon.controller.common.dcp.TaskUtils;
import com.vmware.photon.controller.common.dcp.ValidationUtils;
import com.vmware.photon.controller.common.dcp.validation.DefaultInteger;
import com.vmware.photon.controller.common.dcp.validation.DefaultTaskState;
import com.vmware.photon.controller.common.dcp.validation.Immutable;
import com.vmware.photon.controller.common.dcp.validation.NotNull;
import com.vmware.photon.controller.common.dcp.validation.Positive;
import com.vmware.photon.controller.common.dcp.validation.WriteOnce;
import com.vmware.photon.controller.deployer.DeployerModule;
import com.vmware.photon.controller.deployer.dcp.DeployerDcpServiceHost;
import com.vmware.photon.controller.deployer.dcp.task.AllocateClusterManagerResourcesTaskFactoryService;
import com.vmware.photon.controller.deployer.dcp.task.AllocateClusterManagerResourcesTaskService;
import com.vmware.photon.controller.deployer.dcp.task.CopyStateTaskFactoryService;
import com.vmware.photon.controller.deployer.dcp.task.CopyStateTaskService;
import com.vmware.photon.controller.deployer.dcp.util.ControlFlags;
import com.vmware.photon.controller.deployer.dcp.util.HostUtils;
import com.vmware.photon.controller.deployer.dcp.util.MiscUtils;
import com.vmware.photon.controller.deployer.deployengine.ZookeeperClient;
import com.vmware.photon.controller.deployer.deployengine.ZookeeperClientFactoryProvider;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.Utils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.FutureCallback;
import org.eclipse.jetty.util.BlockingArrayQueue;
import static com.google.common.base.Preconditions.checkState;

import javax.annotation.Nullable;

import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class implements a DCP service representing the end-to-end deployment workflow.
 */
public class DeploymentWorkflowService extends StatefulService {

  public static final String CHAIRMAN_PORT = "13000";
  public static final String ZOOKEEPER_PORT = "2181";

  /**
   * This class defines the state of a {@link DeploymentWorkflowService} task.
   */
  public static class TaskState extends com.vmware.xenon.common.TaskState {

    /**
     * This value represents the current sub-stage for the task.
     */
    public SubStage subStage;

    /**
     * This enum represents the possible sub-states for this task.
     */
    public enum SubStage {
      ADD_HOSTS,
      CREATE_MANAGEMENT_PLANE,
      PROVISION_CLOUD_HOSTS,
      ALLOCATE_CM_RESOURCES,
      MIGRATE_DEPLOYMENT_DATA
    }
  }

  /**
   * This class defines the document state associated with a single
   * {@link DeploymentWorkflowService} instance.
   */
  public static class State extends ServiceDocument {

    /**
     * This value represents the file name of the management VM image.
     */
    @NotNull
    @Immutable
    public String managementVmImageFile;

    /**
     * This value represents the state of the current task.
     */
    @DefaultTaskState(value = TaskState.TaskStage.CREATED)
    public TaskState taskState;

    /**
     * This value represents the states of individual task sub-stages.
     * <p>
     * N.B. This value is not actually immutable, but it should never be set in a patch; instead, it is updated
     * synchronously in the start and patch handlers.
     */
    @Immutable
    public List<TaskState.TaskStage> taskSubStates;

    /**
     * This value represents the control flags for the operation.
     */
    @DefaultInteger(value = 0)
    public Integer controlFlags;

    /**
     * This value represents the interval, in milliseconds, to use when polling
     * the state of a task object returned by an API call.
     */
    @Positive
    public Integer taskPollDelay;

    /**
     * This value represents the link to the {@link DeploymentService.State} entity.
     */
    @WriteOnce
    public String deploymentServiceLink;

  }

  public DeploymentWorkflowService() {
    super(State.class);
    super.toggleOption(ServiceOption.PERSISTENCE, true);
    // It is intentional to leave out the OWNER_SELECTED and REPLICATION options, because
    // this task is only intended to run on the initial deployer, and should not be
    // replicated among the new deployers in the management plane we bring up.
  }

  /**
   * This method is called when a start operation is performed for the current
   * service instance.
   *
   * @param start Supplies the start operation object.
   */
  @Override
  public void handleStart(Operation start) {
    ServiceUtils.logInfo(this, "Handling start for service %s", getSelfLink());
    State startState = start.getBody(State.class);
    InitializationUtils.initialize(startState);

    if (null == startState.taskPollDelay) {
      startState.taskPollDelay = HostUtils.getDeployerContext(this).getTaskPollDelay();
    }

    if (TaskState.TaskStage.CREATED == startState.taskState.stage) {
      checkState(null == startState.taskSubStates);
      startState.taskSubStates = new ArrayList<>(TaskState.SubStage.values().length);
      for (TaskState.SubStage subStage : TaskState.SubStage.values()) {
        startState.taskSubStates.add(subStage.ordinal(), TaskState.TaskStage.CREATED);
      }
    }

    validateState(startState);

    if (TaskState.TaskStage.CREATED == startState.taskState.stage) {
      startState.taskState.stage = TaskState.TaskStage.STARTED;
      startState.taskState.subStage = TaskState.SubStage.ADD_HOSTS;
      startState.taskSubStates.set(0, TaskState.TaskStage.STARTED);
    }

    start.setBody(startState).complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(startState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping start operation processing (disabled)");
      } else if (TaskState.TaskStage.STARTED == startState.taskState.stage) {
        sendStageProgressPatch(startState.taskState);
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  /**
   * This method is called when a patch operation is performed for the current
   * service instance.
   *
   * @param patch Supplies the start operation object.
   */
  @Override
  public void handlePatch(Operation patch) {
    ServiceUtils.logInfo(this, "Handling patch for service %s", getSelfLink());
    State startState = getState(patch);
    State patchState = patch.getBody(State.class);
    validatePatchState(startState, patchState);
    State currentState = applyPatch(startState, patchState);
    validateState(currentState);
    patch.complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(currentState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping patch operation processing (disabled)");
      } else if (TaskState.TaskStage.STARTED == currentState.taskState.stage) {
        processStartedState(currentState);
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  /**
   * This method validates a state object for internal consistency.
   *
   * @param currentState Supplies current state object.
   */
  private void validateState(State currentState) {
    ValidationUtils.validateState(currentState);
    ValidationUtils.validateTaskStage(currentState.taskState);
    validateTaskSubStage(currentState.taskState);

    if (TaskState.TaskStage.STARTED == currentState.taskState.stage) {
      switch (currentState.taskState.subStage) {
        case ADD_HOSTS:
        case CREATE_MANAGEMENT_PLANE:
        case PROVISION_CLOUD_HOSTS:
        case ALLOCATE_CM_RESOURCES:
        case MIGRATE_DEPLOYMENT_DATA:
          break;
        default:
          throw new IllegalStateException("Unknown task sub-stage: " + currentState.taskState.subStage);
      }
    }

    checkState(null != currentState.taskSubStates);
    checkState(TaskState.SubStage.values().length == currentState.taskSubStates.size());
    for (TaskState.SubStage subStage : TaskState.SubStage.values()) {
      try {
        TaskState.TaskStage value = currentState.taskSubStates.get(subStage.ordinal());
        checkState(null != value);
        if (null != currentState.taskState.subStage) {
          if (currentState.taskState.subStage.ordinal() > subStage.ordinal()) {
            checkState(TaskState.TaskStage.FINISHED == value);
          } else if (currentState.taskState.subStage.ordinal() == subStage.ordinal()) {
            checkState(TaskState.TaskStage.STARTED == value);
          } else {
            checkState(TaskState.TaskStage.CREATED == value);
          }
        }
        if (null != currentState.taskState.subStage
            && currentState.taskState.subStage.ordinal() >= subStage.ordinal()) {
          checkState(value != TaskState.TaskStage.CREATED);
        }
      } catch (IndexOutOfBoundsException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private void validateTaskSubStage(TaskState taskState) {
    switch (taskState.stage) {
      case CREATED:
        checkState(null == taskState.subStage);
        break;
      case STARTED:
        checkState(null != taskState.subStage);
        break;
      case FINISHED:
      case FAILED:
      case CANCELLED:
        checkState(null == taskState.subStage);
        break;
    }
  }

  /**
   * This method checks a patch object for validity against a document state object.
   *
   * @param startState Supplies the start state object.
   * @param patchState Supplies the patch state object.
   */
  private void validatePatchState(State startState, State patchState) {
    ValidationUtils.validatePatch(startState, patchState);
    ValidationUtils.validateTaskStage(patchState.taskState);
    validateTaskSubStage(patchState.taskState);
    ValidationUtils.validateTaskStageProgression(startState.taskState, patchState.taskState);

    if (null != startState.taskState.subStage && null != patchState.taskState.subStage) {
      checkState(patchState.taskState.subStage.ordinal() >= startState.taskState.subStage.ordinal());
    }
  }

  /**
   * This method applies a patch to a state object.
   *
   * @param startState Supplies the start state object.
   * @param patchState Supplies the patch state object.
   */
  private State applyPatch(State startState, State patchState) {
    if (patchState.taskState.stage != startState.taskState.stage
        || patchState.taskState.subStage != startState.taskState.subStage) {
      ServiceUtils.logInfo(this, "Moving from %s:%s to stage %s:%s",
          startState.taskState.stage, startState.taskState.subStage,
          patchState.taskState.stage, patchState.taskState.subStage);

      switch (patchState.taskState.stage) {
        case STARTED:
          startState.taskSubStates.set(patchState.taskState.subStage.ordinal(), TaskState.TaskStage.STARTED);
          // fall through
        case FINISHED:
          startState.taskSubStates.set(startState.taskState.subStage.ordinal(), TaskState.TaskStage.FINISHED);
          break;
        case FAILED:
          startState.taskSubStates.set(startState.taskState.subStage.ordinal(), TaskState.TaskStage.FAILED);
          break;
        case CANCELLED:
          startState.taskSubStates.set(startState.taskState.subStage.ordinal(), TaskState.TaskStage.CANCELLED);
          break;
      }
    }

    PatchUtils.patchState(startState, patchState);
    return startState;
  }

  /**
   * This method performs document state updates in response to an operation which
   * sets the state to STARTED.
   *
   * @param currentState Supplies the current state object.
   */
  private void processStartedState(final State currentState) throws Throwable {
    switch (currentState.taskState.subStage) {
      case ADD_HOSTS:
        addHosts(currentState);
        break;
      case CREATE_MANAGEMENT_PLANE:
        batchCreateManagementPlane(currentState);
        break;
      case PROVISION_CLOUD_HOSTS:
        processProvisionCloudHosts(currentState);
        break;
      case ALLOCATE_CM_RESOURCES:
        allocateClusterManagerResources(currentState);
        break;
      case MIGRATE_DEPLOYMENT_DATA:
        migrateDeploymentData(currentState);
    }
  }

  private void addHosts(final State currentState) {
    ServiceUtils.logInfo(this, "Adding management hosts, provisioning cloud hosts..");
    final Service service = this;

    FutureCallback<AddManagementHostWorkflowService.State> callback =
        new FutureCallback<AddManagementHostWorkflowService.State>() {
          @Override
          public void onSuccess(@Nullable AddManagementHostWorkflowService.State result) {
            switch (result.taskState.stage) {
              case FINISHED:
                TaskUtils.sendSelfPatch(service, buildPatch(
                    TaskState.TaskStage.STARTED,
                    TaskState.SubStage.CREATE_MANAGEMENT_PLANE,
                    null));
                break;
              case FAILED:
                State patchState = buildPatch(TaskState.TaskStage.FAILED, null, null);
                patchState.taskState.failure = result.taskState.failure;
                TaskUtils.sendSelfPatch(service, patchState);
                break;
              case CANCELLED:
                TaskUtils.sendSelfPatch(service, buildPatch(TaskState.TaskStage.CANCELLED, null, null));
                break;
            }
          }

          @Override
          public void onFailure(Throwable t) {
            failTask(t);
          }
        };

    AddManagementHostWorkflowService.State startState =
        new AddManagementHostWorkflowService.State();
    startState.deploymentServiceLink = currentState.deploymentServiceLink;
    startState.managementVmImageFile = currentState.managementVmImageFile;
    startState.isNewDeployment = true;

    TaskUtils.startTaskAsync(
        this,
        AddManagementHostWorkflowFactoryService.SELF_LINK,
        startState,
        (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage),
        AddManagementHostWorkflowService.State.class,
        currentState.taskPollDelay,
        callback);
  }

  private void batchCreateManagementPlane(final State currentState) {
    ServiceUtils.logInfo(this, "Bulk provisioning management plane");
    sendRequest(
        HostUtils.getCloudStoreHelper(this)
            .createGet(currentState.deploymentServiceLink)
            .setCompletion(
                (operation, throwable) -> {
                  if (null != throwable) {
                    failTask(throwable);
                    return;
                  }

                  DeploymentService.State deploymentState = operation.getBody(DeploymentService.State.class);
                  try {
                    batchCreateManagementPlane(currentState, deploymentState);
                  } catch (Throwable t) {
                    failTask(t);
                  }
                }
            )
    );
  }

  private void batchCreateManagementPlane(final State currentState, DeploymentService.State deploymentService) {

    final Service service = this;

    FutureCallback<BatchCreateManagementWorkflowService.State> callback =
        new FutureCallback<BatchCreateManagementWorkflowService.State>() {
          @Override
          public void onSuccess(@Nullable BatchCreateManagementWorkflowService.State result) {
            switch (result.taskState.stage) {
              case FINISHED:
                TaskUtils.sendSelfPatch(service, buildPatch(
                    TaskState.TaskStage.STARTED, TaskState.SubStage.PROVISION_CLOUD_HOSTS, null));
                break;
              case FAILED:
                State patchState = buildPatch(TaskState.TaskStage.FAILED, null, null);
                patchState.taskState.failure = result.taskState.failure;
                TaskUtils.sendSelfPatch(service, patchState);
                break;
              case CANCELLED:
                TaskUtils.sendSelfPatch(service, buildPatch(TaskState.TaskStage.CANCELLED, null, null));
                break;
            }
          }

          @Override
          public void onFailure(Throwable t) {
            failTask(t);
          }
        };

    BatchCreateManagementWorkflowService.State startState = new BatchCreateManagementWorkflowService.State();

    startState.imageFile = currentState.managementVmImageFile;
    startState.deploymentServiceLink = currentState.deploymentServiceLink;
    startState.isAuthEnabled = deploymentService.oAuthEnabled;
    startState.taskPollDelay = currentState.taskPollDelay;
    startState.ntpEndpoint = deploymentService.ntpEndpoint;

    TaskUtils.startTaskAsync(
        this,
        BatchCreateManagementWorkflowFactoryService.SELF_LINK,
        startState,
        (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage),
        BatchCreateManagementWorkflowService.State.class,
        currentState.taskPollDelay,
        callback);
  }

  private void allocateClusterManagerResources(final State currentState) throws Throwable {

    ServiceUtils.logInfo(this, "Allocating ClusterManager resources");
    final Service service = this;

    FutureCallback<AllocateClusterManagerResourcesTaskService.State> callback =
        new FutureCallback<AllocateClusterManagerResourcesTaskService.State>() {
          @Override
          public void onSuccess(@Nullable AllocateClusterManagerResourcesTaskService.State result) {
            switch (result.taskState.stage) {
              case FINISHED:
                TaskUtils.sendSelfPatch(service, buildPatch(
                    TaskState.TaskStage.STARTED,
                    TaskState.SubStage.MIGRATE_DEPLOYMENT_DATA,
                    null));
                break;
              case FAILED:
                State patchState = buildPatch(TaskState.TaskStage.FAILED, null, null);
                patchState.taskState.failure = result.taskState.failure;
                TaskUtils.sendSelfPatch(service, patchState);
                break;
              case CANCELLED:
                TaskUtils.sendSelfPatch(service, buildPatch(TaskState.TaskStage.CANCELLED, null, null));
                break;
            }
          }

          @Override
          public void onFailure(Throwable t) {
            failTask(t);
          }
        };

    AllocateClusterManagerResourcesTaskService.State startState =
        new AllocateClusterManagerResourcesTaskService.State();

    TaskUtils.startTaskAsync(
        this,
        AllocateClusterManagerResourcesTaskFactoryService.SELF_LINK,
        startState,
        (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage),
        AllocateClusterManagerResourcesTaskService.State.class,
        currentState.taskPollDelay,
        callback);
  }

  private void migrateDeploymentData(State currentState) {
    ServiceUtils.logInfo(this, "Migrating deployment data");

    sendRequest(
        ((DeployerDcpServiceHost) getHost()).getCloudStoreHelper()
            .createGet(currentState.deploymentServiceLink)
            .setCompletion(
                (operation, throwable) -> {
                  if (throwable != null) {
                    failTask(throwable);
                  }

                  try {
                    DeploymentService.State deploymentState = operation.getBody(DeploymentService.State.class);
                    migrateDeploymentData(currentState, deploymentState.zookeeperQuorum);
                  } catch (Throwable t) {
                    failTask(t);
                  }
                }
            )
    );
  }

  private void migrateDeploymentData(State currentState, String zookeeperQuorum) {
    ZookeeperClient zookeeperClient
        = ((ZookeeperClientFactoryProvider) getHost()).getZookeeperServerSetFactoryBuilder().create();
    Set<InetSocketAddress> localServers = zookeeperClient.getServers(
        HostUtils.getDeployerContext(this).getZookeeperQuorum(),
        DeployerModule.DEPLOYER_SERVICE_NAME);
    Set<InetSocketAddress> remoteServers
        = zookeeperClient.getServers(zookeeperQuorum, DeployerModule.DEPLOYER_SERVICE_NAME);

    Set<Class<?>> servicesToMigrate
        = new HashSet<Class<?>>((Arrays.<Class<?>>asList(DeployerDcpServiceHost.FACTORY_SERVICES_TO_MIGRATE)));

    final AtomicInteger latch = new AtomicInteger(servicesToMigrate.size());
    final List<Throwable> errors = new BlockingArrayQueue<>();
    for (Class<?> factoryClass : servicesToMigrate) {
      CopyStateTaskService.State startState = MiscUtils.createCopyStateStartState(localServers, remoteServers,
          MiscUtils.getSelfLink(factoryClass), null, 1);

      TaskUtils.startTaskAsync(
          this,
          CopyStateTaskFactoryService.SELF_LINK,
          startState,
          state -> TaskUtils.finalTaskStages.contains(state.taskState.stage),
          CopyStateTaskService.State.class,
          currentState.taskPollDelay,
          new FutureCallback<CopyStateTaskService.State>() {
            @Override
            public void onSuccess(@Nullable CopyStateTaskService.State result) {
              switch (result.taskState.stage) {
                case FINISHED:
                  break;
                case FAILED:
                case CANCELLED:
                  errors.add(new Throwable("service: " + result.documentSelfLink + " did not finish."));
                  break;
              }

              if (latch.decrementAndGet() == 0) {
                if (!errors.isEmpty()) {
                  failTask(errors);
                } else {
                  migrateCloudStore(currentState, zookeeperQuorum);
                }
              }
            }

            @Override
            public void onFailure(Throwable t) {
              errors.add(t);
              if (latch.decrementAndGet() == 0) {
                failTask(errors);
              }
            }
          }
      );
    }
  }

  private void migrateCloudStore(State currentState, String zookeeperQuorum) {
    ServiceUtils.logInfo(this, "Migrating data to management plane cloudstore");

    ZookeeperClient zookeeperClient
        = ((ZookeeperClientFactoryProvider) getHost()).getZookeeperServerSetFactoryBuilder().create();
    Set<InetSocketAddress> localServers = zookeeperClient.getServers(
        HostUtils.getDeployerContext(this).getZookeeperQuorum(),
        DeployerModule.CLOUDSTORE_SERVICE_NAME);
    Set<InetSocketAddress> remoteServers
        = zookeeperClient.getServers(zookeeperQuorum, DeployerModule.CLOUDSTORE_SERVICE_NAME);

    Set<Class<?>> servicesToMigrate
        = new HashSet<Class<?>>(Arrays.<Class<?>>asList(CloudStoreDcpHost.FACTORY_SERVICES));
    servicesToMigrate.removeAll(HostUtils.getDeployerContext(this).getMigrationExcludedServices());

    final AtomicInteger latch = new AtomicInteger(servicesToMigrate.size());
    final List<Throwable> errors = new BlockingArrayQueue<>();
    for (Class<?> factoryClass : servicesToMigrate) {
      CopyStateTaskService.State startState = MiscUtils.createCopyStateStartState(localServers, remoteServers,
          MiscUtils.getSelfLink(factoryClass), null);

      TaskUtils.startTaskAsync(
          this,
          CopyStateTaskFactoryService.SELF_LINK,
          startState,
          state -> TaskUtils.finalTaskStages.contains(state.taskState.stage),
          CopyStateTaskService.State.class,
          currentState.taskPollDelay,
          new FutureCallback<CopyStateTaskService.State>() {

            @Override
            public void onSuccess(@Nullable CopyStateTaskService.State result) {
              switch (result.taskState.stage) {
                case FINISHED:
                  break;
                case FAILED:
                case CANCELLED:
                  errors.add(new Throwable(
                      "service: "
                          + result.documentSelfLink
                          + " did not finish. "
                          + result.taskState.failure.message));
                  break;
              }

              if (latch.decrementAndGet() == 0) {
                if (!errors.isEmpty()) {
                  failTask(errors);
                } else {
                  updateDeploymentServiceState(remoteServers, currentState);
                }
              }
            }

            @Override
            public void onFailure(Throwable t) {
              errors.add(t);
              if (latch.decrementAndGet() == 0) {
                failTask(errors);
              }
            }
          }
      );
    }
  }

  private void updateDeploymentServiceState(Set<InetSocketAddress> remoteCloudStoreServers, State currentState) {

    DeploymentService.State deploymentServiceState = new DeploymentService.State();
    deploymentServiceState.state = DeploymentState.READY;

    try {
      sendRequest(Operation
          .createPatch(ServiceUtils.createUriFromServerSet(remoteCloudStoreServers,
              currentState.deploymentServiceLink))
          .setBody(deploymentServiceState)
          .setCompletion(
              (completedOp, failure) -> {
                if (null != failure) {
                  failTask(failure);
                } else {
                  TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FINISHED, null, null));
                }
              }
          ));
    } catch (URISyntaxException e) {
      failTask(e);
    }
  }

  private void processProvisionCloudHosts(final State currentState) throws
      Throwable {

    ServiceUtils.logInfo(this, "Bulk provisioning cloud hosts");

    sendRequest(
        HostUtils.getCloudStoreHelper(this)
            .createGet(currentState.deploymentServiceLink)
            .setCompletion(
                (operation, throwable) -> {
                  if (null != throwable) {
                    failTask(throwable);
                    return;
                  }

                  DeploymentService.State deploymentState = operation.getBody(DeploymentService.State.class);
                  try {
                    processProvisionCloudHosts(currentState, deploymentState);
                  } catch (Throwable t) {
                    failTask(t);
                  }
                }
            )
    );
  }

  private void processProvisionCloudHosts(final State currentState, DeploymentService.State deploymentService) throws
      Throwable {

    checkState(null != deploymentService.chairmanServerList);

    final Service service = this;

    FutureCallback<BulkProvisionHostsWorkflowService.State> callback =
        new FutureCallback<BulkProvisionHostsWorkflowService.State>() {
          @Override
          public void onSuccess(@Nullable BulkProvisionHostsWorkflowService.State result) {
            switch (result.taskState.stage) {
              case FINISHED:
                TaskUtils.sendSelfPatch(service, buildPatch(
                    TaskState.TaskStage.STARTED, TaskState.SubStage.ALLOCATE_CM_RESOURCES, null));
                break;
              case FAILED:
                State patchState = buildPatch(TaskState.TaskStage.FAILED, null, null);
                patchState.taskState.failure = result.taskState.failure;
                TaskUtils.sendSelfPatch(service, patchState);
                break;
              case CANCELLED:
                TaskUtils.sendSelfPatch(service, buildPatch(TaskState.TaskStage.CANCELLED, null, null));
                break;
            }
          }

          @Override
          public void onFailure(Throwable t) {
            failTask(t);
          }
        };

    BulkProvisionHostsWorkflowService.State startState = new BulkProvisionHostsWorkflowService.State();
    startState.deploymentServiceLink = currentState.deploymentServiceLink;
    startState.chairmanServerList = deploymentService.chairmanServerList;
    startState.usageTag = UsageTag.CLOUD.name();
    startState.taskPollDelay = currentState.taskPollDelay;

    TaskUtils.startTaskAsync(
        this,
        BulkProvisionHostsWorkflowFactoryService.SELF_LINK,
        startState,
        (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage),
        BulkProvisionHostsWorkflowService.State.class,
        currentState.taskPollDelay,
        callback);
  }

  /**
   * This method sends a patch operation to the current service instance to
   * move to a new state.
   *
   * @param state
   */
  private void sendStageProgressPatch(TaskState state) {
    ServiceUtils.logInfo(this, "Sending self-patch to stage %s", state.stage, state.subStage);
    TaskUtils.sendSelfPatch(this, buildPatch(state.stage, state.subStage, null));
  }

  /**
   * This method sends a patch operation to the current service instance to
   * move to the FAILED state in response to the specified exception.
   *
   * @param e
   */
  private void failTask(Throwable e) {
    ServiceUtils.logSevere(this, e);
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, null, e));
  }

  private void failTask(Collection<Throwable> failures) {
    failures.forEach((throwable) -> ServiceUtils.logSevere(this, throwable));
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, null, failures.iterator().next()));
  }

  /**
   * This method builds a patch state object which can be used to submit a
   * self-patch.
   *
   * @param patchStage
   * @param patchSubStage
   * @param t
   * @return
   */
  @VisibleForTesting
  protected static State buildPatch(
      TaskState.TaskStage patchStage,
      @Nullable TaskState.SubStage patchSubStage,
      @Nullable Throwable t) {

    State patchState = new State();
    patchState.taskState = new TaskState();
    patchState.taskState.stage = patchStage;
    patchState.taskState.subStage = patchSubStage;

    if (null != t) {
      patchState.taskState.failure = Utils.toServiceErrorResponse(t);
    }

    return patchState;
  }
}
