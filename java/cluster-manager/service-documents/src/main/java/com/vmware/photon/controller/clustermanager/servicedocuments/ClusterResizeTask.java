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

package com.vmware.photon.controller.clustermanager.servicedocuments;

import com.vmware.photon.controller.common.dcp.validation.DefaultInteger;
import com.vmware.photon.controller.common.dcp.validation.DefaultTaskState;
import com.vmware.photon.controller.common.dcp.validation.Immutable;
import com.vmware.photon.controller.common.dcp.validation.NotBlank;
import com.vmware.photon.controller.common.dcp.validation.NotNull;
import com.vmware.xenon.common.ServiceDocument;

/**
 * This class defines the document state associated with a single
 * ClusterResizeTaskService instance.
 */
public class ClusterResizeTask extends ServiceDocument {

  /**
   * The state of the current task.
   */
  @DefaultTaskState(value = TaskState.TaskStage.CREATED)
  public TaskState taskState;

  /**
   * This value represents control flags influencing the behavior of the task.
   */
  @DefaultInteger(0)
  @Immutable
  public Integer controlFlags;

  /**
   * The identifier of the cluster.
   */
  @NotBlank
  @Immutable
  public String clusterId;

  /**
   * The desired number of slave VMs.
   */
  @NotNull
  @Immutable
  public Integer newSlaveCount;

  /**
   * This class defines the state of a KubernetesClusterResizeTaskService task.
   */
  public static class TaskState extends com.vmware.xenon.common.TaskState {
    /**
     * The current sub-stage of the task.
     */
    public SubStage subStage;

    /**
     * The sub-states for this this.
     */
    public enum SubStage {
      INITIALIZE_CLUSTER,
      RESIZE_CLUSTER
    }
  }
}
