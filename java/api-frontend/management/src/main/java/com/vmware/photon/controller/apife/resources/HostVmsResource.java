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

package com.vmware.photon.controller.apife.resources;

import com.vmware.photon.controller.api.ResourceList;
import com.vmware.photon.controller.api.Vm;
import com.vmware.photon.controller.api.common.exceptions.external.ExternalException;
import com.vmware.photon.controller.apife.clients.HostFeClient;
import com.vmware.photon.controller.apife.resources.routes.HostResourceRoutes;
import com.vmware.photon.controller.apife.resources.routes.VmResourceRoutes;
import static com.vmware.photon.controller.api.common.Responses.generateResourceListResponse;

import com.google.inject.Inject;
import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import org.glassfish.jersey.server.ContainerRequest;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;

/**
 * This resource is for vm networks related API.
 */
@Path(HostResourceRoutes.HOST_VMS_PATH)
@Api(value = HostResourceRoutes.API)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class HostVmsResource {

  private final HostFeClient hostFeClient;

  @Inject
  public HostVmsResource(HostFeClient hostFeClient) {
    this.hostFeClient = hostFeClient;
  }

  @GET
  @ApiOperation(value = "Find all Vms associated with the Host", response = Vm.class,
      responseContainer = ResourceList.CLASS_NAME)
  public Response get(@Context Request request, @PathParam("id") String id)
      throws ExternalException {
    return generateResourceListResponse(
        Response.Status.OK,
        hostFeClient.listAllVms(id),
        (ContainerRequest) request,
        VmResourceRoutes.VM_PATH);
  }
}
