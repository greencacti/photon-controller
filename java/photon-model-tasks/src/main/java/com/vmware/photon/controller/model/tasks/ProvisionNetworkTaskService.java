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

package com.vmware.photon.controller.model.tasks;

import com.vmware.photon.controller.model.adapterapi.NetworkInstanceRequest;
import com.vmware.photon.controller.model.adapterapi.NetworkInstanceRequest.InstanceRequestType;

import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;

import java.net.URI;
import java.util.List;

/**
 * Task implementing the provision network resource work flow. Utilizes sub tasks and services
 * provided in the compute host description to perform various sub stages.
 * <p>
 * This is service is not replicated or partitioned since its created by a higher level task, which
 * is partitioned. So this task executes in isolation, per node. It does however talk to replicated
 * DCP services, so it sets the operation.setTargetReplicated(true) to guard against services not
 * yet available on the current node.
 */
public class ProvisionNetworkTaskService extends StatefulService {

  /**
   * SubStage.
   */
  public enum SubStage {
    CREATED,
    PROVISIONING_NETWORK,
    FINISHED,
    FAILED
  }

  /**
   * Represent state of a provision task.
   */
  public static class ProvisionNetworkTaskState extends ServiceDocument {

    /**
     * The type of an instance request. Required
     */
    public InstanceRequestType requestType;

    /**
     *  RegionID -- this is needed for AWS auth -- currently
     *  RegionID only exists in the ComputeDescription.
     *
     *  Including here, so that the provisioning of the network can
     *  be completely isolated from provisioning compute
     *
     *  Long term I'd recommend that all items required for authentication
     *  be encapsulated in the authentication service
     *
     */
    public String regionID;

    /**
     * Link to secrets.  Required
     */
    public String authCredentialsLink;

    /**
     * The pool which this resource is a part of. Required
     */
    public String resourcePoolLink;

    /**
     * The description of the network instance being realized. Required
     */
    public String networkDescriptionLink;

    /**
     * The network adapter to use to create the network. Required
     */
    public URI networkServiceReference;

    /**
     * Tracks the task state. Set by run-time.
     */
    public TaskState taskInfo = new TaskState();

    /**
     * Tracks the sub stage (creating network or firewall).  Set by the run-time.
     */
    public SubStage taskSubStage;

    /**
     * For testing. If set, the request will not actuate any computes directly but will patch back
     * success.
     */
    public boolean isMockRequest = false;

    /**
     * A list of tenant links which can access this task.
     */
    public List<String> tenantLinks;

    public void validate() throws Exception {
      if (this.requestType == null) {
        throw new IllegalArgumentException("requestType required");
      }

      if (this.authCredentialsLink == null || this.authCredentialsLink.isEmpty()) {
        throw new IllegalArgumentException("authCredentialsLink required");
      }

      if (this.resourcePoolLink == null || this.resourcePoolLink.isEmpty()) {
        throw new IllegalArgumentException("resourcePoolLink required");
      }

      if (this.networkDescriptionLink == null || this.networkDescriptionLink.isEmpty()) {
        throw new IllegalArgumentException("networkDescriptionLink required");
      }

      if (this.networkServiceReference == null) {
        throw new IllegalArgumentException("networkServiceReference required");
      }

      if (this.regionID == null) {
        throw new IllegalArgumentException("region id required");
      }

    }
  }

  public ProvisionNetworkTaskService() {
    super(ProvisionNetworkTaskState.class);
    super.toggleOption(ServiceOption.PERSISTENCE, true);
    super.toggleOption(ServiceOption.REPLICATION, true);
    super.toggleOption(ServiceOption.OWNER_SELECTION, true);
  }

  @Override
  public void handleStart(Operation start) {
    if (!start.hasBody()) {
      start.fail(new IllegalArgumentException("body is required"));
      return;
    }

    ProvisionNetworkTaskState state = start.getBody(ProvisionNetworkTaskState.class);
    try {
      state.validate();
    } catch (Exception e) {
      start.fail(e);
    }

    state.taskInfo.stage = TaskState.TaskStage.CREATED;
    state.taskSubStage = SubStage.CREATED;
    start.complete();

    // start the task
    sendSelfPatch(TaskState.TaskStage.CREATED, null);
  }

  @Override
  public void handlePatch(Operation patch) {
    if (!patch.hasBody()) {
      patch.fail(new IllegalArgumentException("body is required"));
      return;
    }

    ProvisionNetworkTaskState currState = getState(patch);
    ProvisionNetworkTaskState patchState = patch.getBody(ProvisionNetworkTaskState.class);

    if (TaskState.isFailed(patchState.taskInfo)) {
      currState.taskInfo = patchState.taskInfo;
    }

    switch (patchState.taskInfo.stage) {
      case CREATED:
        currState.taskSubStage = nextStage(currState);

        handleSubStages(currState);
        logInfo("%s %s on %s started",
            "Network",
            currState.requestType.toString(),
            currState.networkDescriptionLink);
        break;
      case STARTED:
        currState.taskInfo.stage = TaskState.TaskStage.STARTED;
        break;
      case FINISHED:
        SubStage nextStage = nextStage(currState);
        if (nextStage == SubStage.FINISHED) {
          currState.taskInfo.stage = TaskState.TaskStage.FINISHED;
          logInfo("task is complete");
        } else {
          sendSelfPatch(TaskState.TaskStage.CREATED, null);
        }
        break;
      case FAILED:
        logWarning("Task failed with %s", Utils.toJsonHtml(currState.taskInfo.failure));
        break;
      case CANCELLED:
        break;
      default:
        break;
    }

    patch.complete();
  }

  private SubStage nextStage(ProvisionNetworkTaskState state) {
    return state.requestType == InstanceRequestType.CREATE ? nextSubStageOnCreate(state.taskSubStage)
        :
        nextSubstageOnDelete(state.taskSubStage);
  }

  private SubStage nextSubStageOnCreate(SubStage currStage) {
    return SubStage.values()[currStage.ordinal() + 1];
  }

  // deletes follow the inverse order;
  private SubStage nextSubstageOnDelete(SubStage currStage) {
    if (currStage == SubStage.CREATED) {
      return SubStage.PROVISIONING_NETWORK;
    } else if (currStage == SubStage.PROVISIONING_NETWORK) {
      return SubStage.FINISHED;
    } else {
      return SubStage.values()[currStage.ordinal() + 1];
    }
  }

  private void handleSubStages(ProvisionNetworkTaskState currState) {
    switch (currState.taskSubStage) {
      case PROVISIONING_NETWORK:
        patchAdapter(currState);
        break;
      case FINISHED:
        sendSelfPatch(TaskState.TaskStage.FINISHED, null);
        break;
      case FAILED:
        break;
      default:
        break;
    }
  }

  private NetworkInstanceRequest toReq(ProvisionNetworkTaskState state) {
    NetworkInstanceRequest req = new NetworkInstanceRequest();
    req.requestType = state.requestType;
    req.authCredentialsLink = state.authCredentialsLink;
    req.resourcePoolLink = state.resourcePoolLink;
    req.networkReference = UriUtils.buildUri(this.getHost(), state.networkDescriptionLink);
    req.provisioningTaskReference = this.getUri();
    req.isMockRequest = state.isMockRequest;

    return req;
  }

  private void patchAdapter(ProvisionNetworkTaskState state) {
    NetworkInstanceRequest req = toReq(state);

    sendRequest(Operation.createPatch(state.networkServiceReference)
        .setBody(req)
        .setCompletion((o, e) -> {
          if (e != null) {
            sendSelfPatch(TaskState.TaskStage.FAILED, e);
          }
        }));
  }

  private void sendSelfPatch(TaskState.TaskStage stage, Throwable e) {
    ProvisionNetworkTaskState body = new ProvisionNetworkTaskState();
    body.taskInfo = new TaskState();
    if (e == null) {
      body.taskInfo.stage = stage;
    } else {
      body.taskInfo.stage = TaskState.TaskStage.FAILED;
      body.taskInfo.failure = Utils.toServiceErrorResponse(e);
      logWarning("Patching to failed: %s", Utils.toString(e));
    }

    sendSelfPatch(body);
  }

  private void sendSelfPatch(ProvisionNetworkTaskState body) {
    Operation patch = Operation
        .createPatch(getUri())
        .setBody(body)
        .setCompletion(
            (o, ex) -> {
              if (ex != null) {
                logWarning("Self patch failed: %s", Utils.toString(ex));
              }
            });
    sendRequest(patch);
  }
}
