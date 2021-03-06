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

package com.vmware.photon.controller.deployer.dcp.task;

import com.vmware.photon.controller.api.QuotaLineItem;
import com.vmware.photon.controller.api.ResourceTicketCreateSpec;
import com.vmware.photon.controller.api.Task;
import com.vmware.photon.controller.cloudstore.dcp.entity.ResourceTicketServiceFactory;
import com.vmware.photon.controller.cloudstore.dcp.entity.TenantService;
import com.vmware.photon.controller.common.Constants;
import com.vmware.photon.controller.common.dcp.InitializationUtils;
import com.vmware.photon.controller.common.dcp.ServiceUtils;
import com.vmware.photon.controller.common.dcp.TaskUtils;
import com.vmware.photon.controller.common.dcp.ValidationUtils;
import com.vmware.photon.controller.common.dcp.validation.DefaultInteger;
import com.vmware.photon.controller.common.dcp.validation.DefaultTaskState;
import com.vmware.photon.controller.common.dcp.validation.Immutable;
import com.vmware.photon.controller.common.dcp.validation.NotNull;
import com.vmware.photon.controller.common.dcp.validation.Positive;
import com.vmware.photon.controller.deployer.dcp.util.ApiUtils;
import com.vmware.photon.controller.deployer.dcp.util.ControlFlags;
import com.vmware.photon.controller.deployer.dcp.util.HostUtils;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.Utils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.FutureCallback;
import static com.google.common.base.Preconditions.checkState;

import javax.annotation.Nullable;

import java.util.List;

/**
 * This class implements a DCP micro-service which performs the task of
 * creating a resource ticket in a Photon Controller instance.
 */
public class CreateResourceTicketTaskService extends StatefulService {

  /**
   * This class defines the document state associated with a single
   * {@link CreateResourceTicketTaskService} instance.
   */
  public static class State extends ServiceDocument {
    /**
     * This value represents the state of the task.
     */
    @DefaultTaskState(value = TaskState.TaskStage.CREATED)
    public TaskState taskState;

    /**
     * This value represents the link of the tenant service document.
     */
    @NotNull
    @Immutable
    public String tenantServiceLink;

    /**
     * This value represents the list of quota entries which apply to the
     * resource ticket.
     */
    @Immutable
    public List<QuotaLineItem> quotaLineItems;

    /**
     * This value represents the link of the resource ticket service document.
     */
    public String resourceTicketServiceLink;

    /**
     * This value represents the delay interval to use when polling the status
     * of the task object generated by the API call, in milliseconds.
     */
    @Immutable
    @Positive
    public Integer taskPollDelay;

    /**
     * This value allows processing of post and patch operations to be
     * disabled, effectively making all service instances listeners. It is set
     * only in test scenarios.
     */
    @Immutable
    @DefaultInteger(value = 0)
    public Integer controlFlags;
  }

  public CreateResourceTicketTaskService() {
    super(State.class);
    super.toggleOption(ServiceOption.OWNER_SELECTION, true);
    super.toggleOption(ServiceOption.PERSISTENCE, true);
    super.toggleOption(ServiceOption.REPLICATION, true);
  }

  @Override
  public void handleStart(Operation startOperation) {
    ServiceUtils.logInfo(this, "Starting service %s", getSelfLink());
    State startState = startOperation.getBody(State.class);
    InitializationUtils.initialize(startState);

    if (null == startState.taskPollDelay) {
      startState.taskPollDelay = HostUtils.getDeployerContext(this).getTaskPollDelay();
    }

    validateState(startState);

    if (TaskState.TaskStage.CREATED == startState.taskState.stage) {
      startState.taskState.stage = TaskState.TaskStage.STARTED;
    }

    startOperation.setBody(startState).complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(startState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping start operation processing (disabled)");
      } else if (TaskState.TaskStage.STARTED == startState.taskState.stage) {
        sendStageProgressPatch(startState.taskState.stage);
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  @Override
  public void handlePatch(Operation patchOperation) {
    ServiceUtils.logInfo(this, "Handling patch for service %s", getSelfLink());
    State startState = getState(patchOperation);
    State patchState = patchOperation.getBody(State.class);
    validatePatchState(startState, patchState);
    State currentState = applyPatch(startState, patchState);
    validateState(currentState);
    patchOperation.complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(currentState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping patch operation processing (disabled)");
      } else if (TaskState.TaskStage.STARTED == currentState.taskState.stage) {
        getTenantEntity(currentState);
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  private void validateState(State currentState) {
    ValidationUtils.validateState(currentState);
    ValidationUtils.validateTaskStage(currentState.taskState);

    if (currentState.taskState.stage == TaskState.TaskStage.FINISHED) {
      checkState(null != currentState.resourceTicketServiceLink,
          "resource ticket service link cannot be null in FINISHED state");
    }
  }

  private void validatePatchState(State currentState, State patchState) {
    ValidationUtils.validatePatch(currentState, patchState);
    ValidationUtils.validateTaskStage(patchState.taskState);
    ValidationUtils.validateTaskStageProgression(currentState.taskState, patchState.taskState);
  }

  private void getTenantEntity(final State currentState) {

    sendRequest(
        HostUtils.getCloudStoreHelper(this)
            .createGet(currentState.tenantServiceLink)
            .setCompletion((operation, throwable) -> {
              if (null != throwable) {
                failTask(throwable);
                return;
              }

              try {
                TenantService.State tenantState = operation.getBody(TenantService.State.class);
                createResourceTicket(currentState, tenantState);
              } catch (Throwable t) {
                failTask(t);
              }
            }));
  }

  private void createResourceTicket(final State currentState, final TenantService.State tenantState) {

    FutureCallback<Task> callback = new FutureCallback<Task>() {
      @Override
      public void onSuccess(@Nullable Task result) {
        try {
          processTask(currentState, result);
        } catch (Throwable t) {
          failTask(t);
        }
      }

      @Override
      public void onFailure(Throwable t) {
        failTask(t);
      }
    };

    String resourceTicketName = Constants.RESOURCE_TICKET_NAME;
    ResourceTicketCreateSpec createSpec = new ResourceTicketCreateSpec();
    createSpec.setName(resourceTicketName);

    if (currentState.quotaLineItems != null) {
      createSpec.setLimits(currentState.quotaLineItems);
    }

    try {
      HostUtils.getApiClient(this).getTenantsApi().createResourceTicketAsync(
          ServiceUtils.getIDFromDocumentSelfLink(tenantState.documentSelfLink), createSpec,
          callback);
    } catch (Throwable t) {
      failTask(t);
    }
  }

  private void processTask(final State currentState, final Task task) {
    FutureCallback<Task> pollTaskCallback = new FutureCallback<Task>() {
      @Override
      public void onSuccess(@Nullable Task result) {
        try {
          State patchState = buildPatch(TaskState.TaskStage.FINISHED, null);
          patchState.resourceTicketServiceLink = ResourceTicketServiceFactory.SELF_LINK + "/"
              + result.getEntity().getId();
          TaskUtils.sendSelfPatch(CreateResourceTicketTaskService.this, patchState);
        } catch (Throwable t) {
          failTask(t);
        }
      }

      @Override
      public void onFailure(Throwable t) {
        failTask(t);
      }
    };

    ApiUtils.pollTaskAsync(task,
        HostUtils.getApiClient(this),
        this,
        currentState.taskPollDelay,
        pollTaskCallback);
  }

  private State applyPatch(State startState, State patchState) {
    if (patchState.taskState.stage != startState.taskState.stage) {
      ServiceUtils.logInfo(this, "Moving to stage %s", patchState.taskState.stage);
      startState.taskState = patchState.taskState;
    }

    if (patchState.resourceTicketServiceLink != null) {
      startState.resourceTicketServiceLink = patchState.resourceTicketServiceLink;
    }

    return startState;
  }

  private void sendStageProgressPatch(TaskState.TaskStage stage) {
    ServiceUtils.logInfo(this, "Sending stage progress patch %s", stage);
    TaskUtils.sendSelfPatch(this, buildPatch(stage, null));
  }

  private void failTask(Throwable t) {
    ServiceUtils.logSevere(this, t);
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, t));
  }

  @VisibleForTesting
  protected State buildPatch(TaskState.TaskStage stage, @Nullable Throwable t) {
    State patchState = new State();
    patchState.taskState = new TaskState();
    patchState.taskState.stage = stage;

    if (null != t) {
      patchState.taskState.failure = Utils.toServiceErrorResponse(t);
    }

    return patchState;
  }
}
