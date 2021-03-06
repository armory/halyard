/*
 * Copyright 2018 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package com.netflix.spinnaker.halyard.deploy.deployment.v1;

import com.netflix.spinnaker.halyard.config.config.v1.HalconfigDirectoryStructure;
import com.netflix.spinnaker.halyard.config.model.v1.providers.kubernetes.KubernetesAccount;
import com.netflix.spinnaker.halyard.core.RemoteAction;
import com.netflix.spinnaker.halyard.core.tasks.v1.DaemonTaskHandler;
import com.netflix.spinnaker.halyard.deploy.services.v1.GenerateService;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.SpinnakerRuntimeSettings;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.ServiceSettings;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.SpinnakerService;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.SidecarService;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.kubernetes.v2.KubectlServiceProvider;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.kubernetes.v2.KubernetesV2Service;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.kubernetes.v2.KubernetesV2Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.netflix.spinnaker.halyard.backup.services.v1.BackupService;

import java.io.File;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class KubectlDeployer implements Deployer<KubectlServiceProvider,AccountDeploymentDetails<KubernetesAccount>> {
  @Autowired
  BackupService backupService;

  @Autowired
  HalconfigDirectoryStructure directoryStructure;

  @Override
  public RemoteAction deploy(KubectlServiceProvider serviceProvider,
      AccountDeploymentDetails<KubernetesAccount> deploymentDetails,
      GenerateService.ResolvedConfiguration resolvedConfiguration,
      List<SpinnakerService.Type> serviceTypes) {
    List<KubernetesV2Service> services = serviceProvider.getServicesByPriority(serviceTypes);
    services.stream().forEach((service) -> {
      if (service instanceof SidecarService) {
        return;
      }

      ServiceSettings settings = resolvedConfiguration.getServiceSettings((SpinnakerService) service);
      if (settings == null) {
        return;
      }

      if (settings.getEnabled() != null && !settings.getEnabled()) {
        return;
      }

      if (settings.getSkipLifeCycleManagement() != null && settings.getSkipLifeCycleManagement()) {
        return;
      }
      
      DaemonTaskHandler.newStage("Deploying " + service.getServiceName() + " with kubectl");

      KubernetesAccount account = deploymentDetails.getAccount();
      String namespaceDefinition = service.getNamespaceYaml(resolvedConfiguration);
      String serviceDefinition = service.getServiceYaml(resolvedConfiguration);

      if (!KubernetesV2Utils.exists(account, namespaceDefinition)) {
        KubernetesV2Utils.apply(account, namespaceDefinition);
      }

      if (!KubernetesV2Utils.exists(account, serviceDefinition)) {
        KubernetesV2Utils.apply(account, serviceDefinition);
      }

      String resourceDefinition = service.getResourceYaml(deploymentDetails, resolvedConfiguration);
      DaemonTaskHandler.message("Running kubectl apply on the resource definition...");
      KubernetesV2Utils.apply(account, resourceDefinition);
    });
    backupService.createIn(directoryStructure.getBackupTarballsPath());

    return new RemoteAction();
  }

  @Override
  public void rollback(KubectlServiceProvider serviceProvider,
      AccountDeploymentDetails<KubernetesAccount> deploymentDetails,
      SpinnakerRuntimeSettings runtimeSettings,
      List<SpinnakerService.Type> serviceTypes) {

    // Step One:  Look at .backup_tars directory, sort filenames, and take
    //            second-to-last (last is "current")
    String path = directoryStructure.getBackupTarballsPath().toString();
    List<String> backups = Arrays.stream(new File(path).listFiles())
                             .map(f -> f.getName())
                             .filter(f -> f.startsWith("halbackup-"))
                             .sorted()
                             .collect(Collectors.toList());
    if (backups.size() < 2) {
      throw new UnsupportedOperationException("No backups found to roll back to.");
    }
    String filename = backups.get(backups.size() - 2);
    String fullpath = Paths.get(path, filename).toString();

    // Step Two:  Untar that file
    log.info("Restoring " + fullpath);
    backupService.restore(fullpath);

    // Step Three:  Apply config.

    // throw new UnsupportedOperationException("todo(lwander)");
  }

  @Override
  public void collectLogs(KubectlServiceProvider serviceProvider,
      AccountDeploymentDetails<KubernetesAccount> deploymentDetails,
      SpinnakerRuntimeSettings runtimeSettings,
      List<SpinnakerService.Type> serviceTypes) {
    throw new UnsupportedOperationException("todo(lwander)");
  }

  @Override
  public RemoteAction connectCommand(KubectlServiceProvider serviceProvider,
      AccountDeploymentDetails<KubernetesAccount> deploymentDetails,
      SpinnakerRuntimeSettings runtimeSettings,
      List<SpinnakerService.Type> serviceTypes) {
    RemoteAction result = new RemoteAction();

    String connectCommands = String.join(" &\n", serviceTypes.stream()
        .map(t -> serviceProvider.getService(t)
            .connectCommand(deploymentDetails, runtimeSettings))
        .collect(Collectors.toList()));
    result.setScript("#!/bin/bash\n" + connectCommands);
    result.setScriptDescription(
        "The generated script will open connections to the API & UI servers using ssh tunnels");
    result.setAutoRun(false);
    return result;
  }

  @Override
  public void flushInfrastructureCaches(KubectlServiceProvider serviceProvider,
      AccountDeploymentDetails<KubernetesAccount> deploymentDetails,
      SpinnakerRuntimeSettings runtimeSettings) {
    throw new UnsupportedOperationException("todo(lwander)");
  }
}
