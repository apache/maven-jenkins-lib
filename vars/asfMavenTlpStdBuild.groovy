#!/usr/bin/env groovy

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

def call(Map params = [:]) {
  def failingFast = null
  try {
    def buildProperties = []
    if (env.BRANCH_NAME == 'master') {
      // set build retention time first
      buildProperties.add(buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '5', daysToKeepStr: '15', numToKeepStr: '10')))
      // ensure a build is done every month
      buildProperties.add(pipelineTriggers([cron('@monthly')]))
    } else {
      buildProperties.add(buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '2', daysToKeepStr: '7', numToKeepStr: '3')))
    }
    properties(buildProperties)

    // now determine the matrix of parallel builds
    def oses = params.containsKey('os') ? params.os : ['linux', 'windows']
    // minimum, LTS, current and next ea
    def jdks = params.containsKey('jdks') ? params.jdks : params.containsKey('jdk') ? params.jdk : ['17','11','8']
    def maven = params.containsKey('maven') ? params.maven : '3.8.x'
    def tmpWs = params.containsKey('tmpWs') ? params.tmpWs : false
    // def failFast = params.containsKey('failFast') ? params.failFast : true
    // Just temporarily
    def failFast = false;
    Map tasks = [failFast: failFast]
    boolean first = true
    for (String os in oses) {
      for (def jdk in jdks) {
        String label = jenkinsEnv.labelForOS(os);
        String jdkName = jenkinsEnv.jdkFromVersion(os, "${jdk}")
        String mvnName = jenkinsEnv.mvnFromVersion(os, "${maven}")
        echo "OS: ${os} JDK: ${jdk} Maven: ${maven} => Label: ${label} JDK: ${jdkName} Maven: ${mvnName}"
        if (label == null || jdkName == null || mvnName == null) {
          echo "Skipping ${os}-jdk${jdk} as unsupported by Jenkins Environment"
          continue;
        }
        def cmd = [
          'mvn', '-V',
          '-P+run-its',
          '-Dmaven.test.failure.ignore=false',
          '-Dfindbugs.failOnError=false',
        ]
        if (!first) {
          cmd += '-Dfindbugs.skip=true'
        }
        cmd += 'clean'
        if (env.BRANCH_NAME == 'master' && jdk == '17' && os == 'linux' ) {
          cmd += 'deploy'
        } else {
          cmd += 'verify -Dpgpverify.skip'
        } 
        def disablePublishers = !first
        first = false
        String stageId = "${os}-jdk${jdk}"
        tasks[stageId] = {
          node("${label}") {
            // without this block $WORKSPACE could not work as expected in parallel tasks (https://issues.jenkins-ci.org/plugins/servlet/mobile#issue/JENKINS-33511)
            withEnv(["NODE_WORKSPACE=${pwd()}"]) {
              def wsDir = env.NODE_WORKSPACE
              if (!isUnix() && tmpWs) {
                wsDir = 'F:\\short\\' + "$BUILD_TAG".replaceAll(/(.+)-maven-box-maven-(.+)/) { "m-${it[2]}" }
              }
              ws( dir : "$wsDir" ) {
                stage("Checkout ${stageId}") {
                  echo "NODE_NAME = ${env.NODE_NAME}"
                  try {
                    dir('m') {
                      checkout scm
                    }
                  } catch (Throwable e) {
                    // First step to keep the workspace clean and safe disk space
                    cleanWs()
                    if (!failFast) {
                      throw e
                    } else if (failingFast == null) {
                      failingFast = stageId
                      echo "[FAIL FAST] This is the first failure and likely root cause"
                      throw e
                    } else {
                      echo "[FAIL FAST] ${failingFast} had first failure, ignoring ${e.message}"
                    }
                  }
                }
                stage("Build ${stageId}") {
                  if (failingFast != null) {
                    cleanWs()
                    echo "[FAIL FAST] ${failingFast} has failed. Skipping ${stageId}."
                  } else try {
                    // mavenSettingsConfig: 'simple-deploy-settings-no-mirror',
                    withMaven(jdk:jdkName, maven:mvnName, mavenLocalRepo:'.repository',
                              options: [
                                artifactsPublisher(disabled: disablePublishers),
                                junitPublisher(ignoreAttachments: false),
                                findbugsPublisher(disabled: disablePublishers),
                                openTasksPublisher(disabled: disablePublishers),
                                dependenciesFingerprintPublisher(disabled: disablePublishers),
    // DISABLED DUE TO INFRA-17514 invokerPublisher(),
                                pipelineGraphPublisher(disabled: disablePublishers)
                              ], publisherStrategy: 'EXPLICIT') {
                    dir ('m') {
                        if (isUnix()) {
                          sh cmd.join(' ')
                        } else {
                          bat cmd.join(' ')
                        }
                      }
                    }
                  } catch (Throwable e) {
                    echo "[FAILURE-004] ${e}"
                    // First step to keep the workspace clean and safe disk space
                    cleanWs()
                    if (!failFast) {
                      throw e
                    } else if (failingFast == null) {
                      failingFast = stageId
                      echo "[FAIL FAST] This is the first failure and likely root cause"
                      throw e
                    } else {
                      echo "[FAIL FAST] ${failingFast} had first failure, ignoring ${e.message}"
                    }
                  } finally {
                    try {
                      cleanWs()
                    } catch(IOException e) {
                      echo "Failed to clean up workspace: ${e}"
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    // run the parallel builds
    parallel(tasks)

    // JENKINS-34376 seems to make it hard to detect the aborted builds
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    // this ambiguous condition means a user probably aborted
    if (e.causes.size() == 0) {
      currentBuild.result = "ABORTED"
    } else {
      currentBuild.result = "FAILURE"
      echo "[FAILURE-002] FlowInterruptedException ${e}"
    }
    throw e
  } catch (hudson.AbortException e) {
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
      currentBuild.result = "ABORTED"
    } else {
      currentBuild.result = "FAILURE"
      echo "[FAILURE-003] AbortException ${e}"
    }
    throw e
  } catch (InterruptedException e) {
    currentBuild.result = "ABORTED"
    throw e
  } catch (Throwable e) {
    currentBuild.result = "FAILURE"
    echo "[FAILURE-001] ${e}"
    throw e
  } finally {
    // notify completion
    if (failingFast != null) {
      echo "***** FAST FAILURE *****\n\nFast failure triggered by ${failingFast}\n\n***** FAST FAILURE *****"
    }
    stage("Notifications") {
      jenkinsNotify()
    }
  }
}
