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

package com.spotify.helios.rollingupdate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import com.spotify.helios.common.descriptors.DeploymentGroup;
import com.spotify.helios.common.descriptors.HostStatus;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.RolloutTask;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.emptyList;

public class DefaultRolloutPlanner implements RolloutPlanner {

  private final DeploymentGroup deploymentGroup;

  private DefaultRolloutPlanner(final DeploymentGroup deploymentGroup) {
    this.deploymentGroup = checkNotNull(deploymentGroup, "deploymentGroup");
  }

  public static DefaultRolloutPlanner of(final DeploymentGroup deploymentGroup) {
    return new DefaultRolloutPlanner(deploymentGroup);
  }

  @Override
  public List<RolloutTask> plan(final Map<String, HostStatus> hostsAndStatuses) {
    // we only care about hosts that are UP
    final List<String> hosts = Lists.newArrayList();
    for (final Map.Entry<String, HostStatus> entry : hostsAndStatuses.entrySet()) {
      if (entry.getValue().getStatus().equals(HostStatus.Status.UP)) {
        hosts.add(entry.getKey());
      }
    }

    // TOOD(staffan): Fix this hack
    final List<String> canaryHosts;
    if (deploymentGroup.getCanaryJobId() != null) {
      canaryHosts = hosts.subList(0, 1);
    } else {
      canaryHosts = emptyList();
    }


    // generate the rollout tasks
    final List<RolloutTask> rolloutTasks = Lists.newArrayList();
    final int parallelism = deploymentGroup.getRolloutOptions() != null ?
                            deploymentGroup.getRolloutOptions().getParallelism() : 1;
    final boolean overlap = deploymentGroup.getRolloutOptions() != null &&
                            deploymentGroup.getRolloutOptions().getOverlap();

    for (final List<String> partition : Lists.partition(hosts, parallelism)) {
      rolloutTasks.addAll(overlap ? rolloutTasksWithOverlap(partition, canaryHosts)
                                  : rolloutTasks(partition, canaryHosts));
    }

    return ImmutableList.copyOf(rolloutTasks);
  }

  private List<RolloutTask> rolloutTasks(final List<String> hosts,
                                         final List<String> canaryHosts) {
    final ImmutableList.Builder<RolloutTask> result = ImmutableList.builder();
    for (final String host : hosts) {
      final JobId job;
      if (canaryHosts.contains(host)) {
        job = deploymentGroup.getCanaryJobId();
      } else {
        job = deploymentGroup.getJobId();
      }

      result.add(RolloutTask.of(RolloutTask.Action.UNDEPLOY_OLD_JOBS, host));
      result.add(RolloutTask.of(RolloutTask.Action.DEPLOY_NEW_JOB, host, job));
    }
    for (final String host : hosts) {
      result.add(RolloutTask.of(RolloutTask.Action.AWAIT_RUNNING, host));
    }
    return result.build();
  }

  private List<RolloutTask> rolloutTasksWithOverlap(final List<String> hosts,
                                                    final List<String> canaryHosts) {
    final ImmutableList.Builder<RolloutTask> result = ImmutableList.builder();
    for (final String host : hosts) {
      final JobId job;
      if (canaryHosts.contains(host)) {
        job = deploymentGroup.getCanaryJobId();
      } else {
        job = deploymentGroup.getJobId();
      }

      result.add(RolloutTask.of(RolloutTask.Action.DEPLOY_NEW_JOB, host, job));
    }
    for (final String host : hosts) {
      result.add(RolloutTask.of(RolloutTask.Action.AWAIT_RUNNING, host));
    }
    for (final String host : hosts) {
      result.add(RolloutTask.of(RolloutTask.Action.UNDEPLOY_OLD_JOBS, host));
    }
    return result.build();
  }

}
