/*
 * Copyright 2013 Twitter, Inc. and other contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jenkinsci.plugins.mesos;

import java.net.URL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.MesosNativeLibrary;
import org.apache.mesos.Protos.CommandInfo;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.Filters;
import org.apache.mesos.Protos.FrameworkID;
import org.apache.mesos.Protos.FrameworkInfo;
import org.apache.mesos.Protos.MasterInfo;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.Resource;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.Status;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.TaskStatus;
import org.apache.mesos.Protos.Value;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;

public class JenkinsScheduler implements Scheduler {
  private static final String SLAVE_JAR_URI_SUFFIX = "jnlpJars/slave.jar";

  // We allocate 10% more memory to the Mesos task to account for the JVM overhead.
  private static final double JVM_MEM_OVERHEAD_FACTOR = 0.1;

  private static final String SLAVE_COMMAND_FORMAT =
      "java -DHUDSON_HOME=jenkins -server -Xmx%dm -Xms16m -XX:+UseConcMarkSweepGC " +
      "-Djava.net.preferIPv4Stack=true -jar slave.jar  -jnlpUrl %s";

  private Queue<Request> requests;
  private Map<TaskID, Result> results;
  private volatile MesosSchedulerDriver driver;
  private final String jenkinsMaster;
  private final String mesosMaster;

  private static final Logger LOGGER = Logger.getLogger(JenkinsScheduler.class.getName());

  public JenkinsScheduler(String jenkinsMaster, String mesosMaster) {
    LOGGER.info("JenkinsScheduler instantiated with jenkins " + jenkinsMaster +
        " and mesos " + mesosMaster);

    this.jenkinsMaster = jenkinsMaster;
    this.mesosMaster = mesosMaster;

    requests = new LinkedList<Request>();
    results = new HashMap<TaskID, Result>();
  }

  public synchronized void init() {
    // Start the framework.
    new Thread(new Runnable() {
      @Override
      public void run() {
        // Have Mesos fill in the current user.
        FrameworkInfo framework = FrameworkInfo.newBuilder().setUser("")
            .setName("Jenkins Framework").build();

        driver = new MesosSchedulerDriver(JenkinsScheduler.this, framework, mesosMaster);

        if (driver.run() != Status.DRIVER_STOPPED) {
          LOGGER.severe("The mesos driver was aborted!");
        }

        driver = null;
      }
    }).start();
  }

  public synchronized void stop() {
    driver.stop();
  }

  public synchronized boolean isRunning() {
    return driver != null;
  }

  public synchronized void requestJenkinsSlave(Mesos.SlaveRequest request, Mesos.SlaveResult result) {
    LOGGER.info("Enqueuing jenkins slave request");
    requests.add(new Request(request, result));
  }

  /**
   * @param slaveName the slave name in jenkins
   * @return the jnlp url for the slave: http://[master]/computer/[slaveName]/slave-agent.jnlp
   */
  private String getJnlpUrl(String slaveName) {
    return joinPaths(joinPaths(joinPaths(jenkinsMaster, "computer"), slaveName), "slave-agent.jnlp");
  }

  private static String joinPaths(String prefix, String suffix) {
    if (prefix.endsWith("/"))   prefix = prefix.substring(0, prefix.length()-1);
    if (suffix.startsWith("/")) suffix = suffix.substring(1, suffix.length());

    return prefix + '/' + suffix;
  }

  public synchronized void terminateJenkinsSlave(String name) {
    LOGGER.info("Terminating jenkins slave " + name);

    TaskID taskId = TaskID.newBuilder().setValue(name).build();

    if (results.containsKey(taskId)) {
      LOGGER.info("Killing mesos task " + taskId);
      driver.killTask(taskId);
    } else {
        // This is handling the situation that a slave was provisioned but it never
        // got scheduled because of resource scarcity and jenkins later tries to remove
        // the offline slave but since it was not scheduled we have to remove it from
        // the request queue. The method has been also synchronized because there is a race
        // between this removal request from jenkins and a resource getting freed up in mesos
        // resulting in scheduling the slave and resulting in orphaned task/slave not monitored
        // by Jenkins.
        for(Iterator<Request> li = requests.iterator(); li.hasNext();) {
           Request request = li.next();
           if(request.request.slave.name.equals(name)) {
             LOGGER.info("Removing enqueued mesos task " + name);
             li.remove();
             return;
           }
        }
        LOGGER.warning("Asked to kill unknown mesos task " + taskId);
    }

  }

  @Override
  public void registered(SchedulerDriver driver, FrameworkID frameworkId, MasterInfo masterInfo) {
    LOGGER.info("Framework registered! ID = " + frameworkId.getValue());
  }

  @Override
  public void reregistered(SchedulerDriver driver, MasterInfo masterInfo) {
    LOGGER.info("Framework re-registered");
  }

  @Override
  public void disconnected(SchedulerDriver driver) {
    LOGGER.info("Framework disconnected!");
  }

  @Override
  public synchronized void resourceOffers(SchedulerDriver driver, List<Offer> offers) {
    LOGGER.info("Received offers " + offers.size());
    for (Offer offer : offers) {
      boolean matched = false;
      for (Request request : requests) {
        if (matches(offer, request)) {
          matched = true;
          LOGGER.info("Offer matched! Creating mesos task");
          createMesosTask(offer, request);
          requests.remove(request);
          break;
        }
      }

      if (!matched) {
        driver.declineOffer(offer.getId());
      }
    }
  }

  private boolean matches(Offer offer, Request request) {
    double cpus = -1;
    double mem = -1;

    for (Resource resource : offer.getResourcesList()) {
      if (resource.getName().equals("cpus")) {
        if (resource.getType().equals(Value.Type.SCALAR)) {
          cpus = resource.getScalar().getValue();
        } else {
          LOGGER.severe("Cpus resource was not a scalar: " + resource.getType().toString());
        }
      } else if (resource.getName().equals("mem")) {
        if (resource.getType().equals(Value.Type.SCALAR)) {
          mem = resource.getScalar().getValue();
        } else {
          LOGGER.severe("Mem resource was not a scalar: " + resource.getType().toString());
        }
      } else if (resource.getName().equals("disk")) {
        LOGGER.warning("Ignoring disk resources from offer");
      } else if (resource.getName().equals("ports")) {
        LOGGER.info("Ignoring ports resources from offer");
      } else {
        LOGGER.warning("Ignoring unknown resource type: " + resource.getName());
      }
    }

    if (cpus < 0) LOGGER.severe("No cpus resource present");
    if (mem < 0)  LOGGER.severe("No mem resource present");

    // Check for sufficient cpu and memory resources in the offer.
    double requestedCpus = request.request.cpus;
    double requestedMem = (1 + JVM_MEM_OVERHEAD_FACTOR) * request.request.mem;

    if (requestedCpus <= cpus && requestedMem <= mem) {
      return true;
    } else {
      LOGGER.info(
          "Offer not sufficient for slave request:\n" +
          offer.getResourcesList().toString() +
          "\nRequested for Jenkins slave:\n" +
          "  cpus: " + requestedCpus + "\n" +
          "  mem:  " + requestedMem);
      return false;
    }
  }

  private void createMesosTask(Offer offer, Request request) {
    TaskID taskId = TaskID.newBuilder().setValue(request.request.slave.name).build();

    LOGGER.info("Launching task " + taskId.getValue() + " with URI " +
                joinPaths(jenkinsMaster, SLAVE_JAR_URI_SUFFIX));

    TaskInfo task = TaskInfo
        .newBuilder()
        .setName("task " + taskId.getValue())
        .setTaskId(taskId)
        .setSlaveId(offer.getSlaveId())
        .addResources(
            Resource
                .newBuilder()
                .setName("cpus")
                .setType(Value.Type.SCALAR)
                .setScalar(
                    Value.Scalar.newBuilder()
                        .setValue(request.request.cpus).build()).build())
        .addResources(
            Resource
                .newBuilder()
                .setName("mem")
                .setType(Value.Type.SCALAR)
                .setScalar(
                    Value.Scalar
                        .newBuilder()
                        .setValue((1 + JVM_MEM_OVERHEAD_FACTOR) * request.request.mem)
                        .build()).build())
        .setCommand(
            CommandInfo
                .newBuilder()
                .setValue(
                    String.format(SLAVE_COMMAND_FORMAT, request.request.mem,
                        getJnlpUrl(request.request.slave.name)))
                .addUris(
                    CommandInfo.URI.newBuilder().setValue(
                        joinPaths(jenkinsMaster, SLAVE_JAR_URI_SUFFIX)))).build();

    List<TaskInfo> tasks = new ArrayList<TaskInfo>();
    tasks.add(task);
    Filters filters = Filters.newBuilder().setRefuseSeconds(1).build();
    driver.launchTasks(offer.getId(), tasks, filters);

    results.put(taskId, new Result(request.result, new Mesos.JenkinsSlave(offer.getSlaveId()
        .getValue())));
  }

  @Override
  public void offerRescinded(SchedulerDriver driver, OfferID offerId) {
    LOGGER.info("Rescinded offer " + offerId);
  }

  @Override
  public void statusUpdate(SchedulerDriver driver, TaskStatus status) {
    TaskID taskId = status.getTaskId();
    LOGGER.info("Status update: task " + taskId + " is in state " + status.getState());

    if (!results.containsKey(taskId)) {
      throw new IllegalStateException("Unknown taskId: " + taskId);
    }

    Result result = results.get(taskId);

    switch (status.getState()) {
    case TASK_STAGING:
    case TASK_STARTING:
      break;
    case TASK_RUNNING:
      result.result.running(result.slave);
      break;
    case TASK_FINISHED:
      result.result.finished(result.slave);
      break;
    case TASK_FAILED:
    case TASK_KILLED:
    case TASK_LOST:
      result.result.failed(result.slave);
      break;
    default:
      throw new IllegalStateException("Invalid State: " + status.getState());
    }
  }

  @Override
  public void frameworkMessage(SchedulerDriver driver, ExecutorID executorId,
      SlaveID slaveId, byte[] data) {
    LOGGER.info("Received framework message from executor " + executorId
        + " of slave " + slaveId);
  }

  @Override
  public void slaveLost(SchedulerDriver driver, SlaveID slaveId) {
    LOGGER.info("Slave " + slaveId + " lost!");
  }

  @Override
  public void executorLost(SchedulerDriver driver, ExecutorID executorId,
      SlaveID slaveId, int status) {
    LOGGER.info("Executor " + executorId + " of slave " + slaveId + " lost!");
  }

  @Override
  public void error(SchedulerDriver driver, String message) {
    LOGGER.severe(message);
  }

  private class Result {
    private final Mesos.SlaveResult result;
    private final Mesos.JenkinsSlave slave;

    private Result(Mesos.SlaveResult result, Mesos.JenkinsSlave slave) {
      this.result = result;
      this.slave = slave;
    }
  }

  private class Request {
    private final Mesos.SlaveRequest request;
    private final Mesos.SlaveResult result;

    public Request(Mesos.SlaveRequest request, Mesos.SlaveResult result) {
      this.request = request;
      this.result = result;
    }
  }
}
