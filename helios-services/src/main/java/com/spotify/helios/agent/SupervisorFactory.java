/*
 * Copyright (c) 2014 Spotify AB.
 *
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

package com.spotify.helios.agent;

import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.TaskStatus;
import com.spotify.helios.serviceregistration.ServiceRegistrar;
import com.spotify.helios.servicescommon.statistics.SupervisorMetrics;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Creates job supervisors.
 *
 * @see Supervisor
 */
public class SupervisorFactory {

  private final AgentModel model;
  private final DockerClientFactory dockerClientFactory;
  private final String namespace;
  private final Map<String, String> envVars;
  private final ServiceRegistrar registrar;
  private final ContainerDecorator containerDecorator;
  private final String host;
  private final SupervisorMetrics metrics;
  private final String defaultRegistrationDomain;
  private final List<String> dns;

  public SupervisorFactory(final AgentModel model, final DockerClientFactory dockerClientFactory,
                           final Map<String, String> envVars,
                           final ServiceRegistrar registrar,
                           final ContainerDecorator containerDecorator,
                           final String host,
                           final SupervisorMetrics supervisorMetrics,
                           final String namespace,
                           final String defaultRegistrationDomain,
                           final List<String> dns) {
    this.dockerClientFactory = dockerClientFactory;
    this.namespace = namespace;
    this.model = checkNotNull(model, "model");
    this.envVars = checkNotNull(envVars, "envVars");
    this.registrar = registrar;
    this.containerDecorator = containerDecorator;
    this.host = host;
    this.metrics = supervisorMetrics;
    this.defaultRegistrationDomain = checkNotNull(defaultRegistrationDomain,
                                                  "defaultRegistrationDomain");
    this.dns = checkNotNull(dns, "dns");
  }

  /**
   * Create a new application container.
   *
   * @return A new container.
   */
  public Supervisor create(final Job job, final String existingContainerId,
                           final Map<String, Integer> ports,
                           final Supervisor.Listener listener) {
    final RestartPolicy policy = RestartPolicy.newBuilder().build();
    final TaskConfig taskConfig = TaskConfig.builder()
        .host(host)
        .job(job)
        .ports(ports)
        .envVars(envVars)
        .containerDecorator(containerDecorator)
        .namespace(namespace)
        .defaultRegistrationDomain(defaultRegistrationDomain)
        .dns(dns)
        .build();

    final TaskStatus.Builder taskStatus = TaskStatus.newBuilder()
        .setJob(job)
        .setEnv(taskConfig.containerEnv())
        .setPorts(taskConfig.ports());
    final StatusUpdater statusUpdater = new DefaultStatusUpdater(model, taskStatus);
    final FlapController flapController = FlapController.create();
    final TaskMonitor taskMonitor = new TaskMonitor(job.getId(), flapController, statusUpdater);

    final TaskRunnerFactory runnerFactory = TaskRunnerFactory.builder()
        .config(taskConfig)
        .registrar(registrar)
        .dockerClientFactory(dockerClientFactory)
        .listener(taskMonitor)
        .build();

    return Supervisor.newBuilder()
        .setJob(job)
        .setExistingContainerId(existingContainerId)
        .setDockerClientFactory(dockerClientFactory)
        .setRestartPolicy(policy)
        .setMetrics(metrics)
        .setListener(listener)
        .setRunnerFactory(runnerFactory)
        .setStatusUpdater(statusUpdater)
        .setMonitor(taskMonitor)
        .build();
  }
}
