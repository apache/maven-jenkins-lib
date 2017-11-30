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
  try {
    // set build retention time first
    def buildRetention
    if (env.BRANCH_NAME == 'master') {
      buildRetention = buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '3', daysToKeepStr: '21', numToKeepStr: '25'))
    } else {
      buildRetention = buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '1', daysToKeepStr: '7', numToKeepStr: '5'))
    }
    properties([buildRetention])

    // now determine the matrix of parallel builds
    def oses = params.containsKey('os') ? params.os : ['linux', 'windows']
    def jdks = params.containsKey('jdks') ? params.jdks : params.containsKey('jdk') ? params.jdk : ['7','8','9']
    def maven = params.containsKey('maven') ? params.maven : '3.x.x'
    def failFast = params.containsKey('failFast') ? params.failFast : true
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
          'mvn',
          '-P+run-its',
          '-Dmaven.test.failure.ignore=true',
          '-Dfindbugs.failOnError=false',
        ]
        if (!first) {
          cmd += '-Dfindbugs.skip=true'
        }
        cmd += 'clean'
        cmd += 'verify'
        String stageId = "${os}-jdk${jdk}"
        tasks[stageId] = {
          node(label) {
            stage("Checkout ${stageId}") {
              dir('m') {
                checkout scm
              }
            }
            stage("Build ${stageId}") {
              withMaven(jdk:jdkName, maven:mvnName, mavenLocalRepo:'.repository', options: [
                artifactsPublisher(disabled: !first),
                junitPublisher(ignoreAttachments: false),
                findbugsPublisher(disabled: !first),
                openTasksPublisher(disabled: !first),
                dependenciesFingerprintPublisher(),
                invokerPublisher(),
                pipelineGraphPublisher()
              ]) {
                dir ('m') {
                  if (isUnix()) {
                    sh cmd.join(' ')
                  } else {
                    bat cmd.join(' ')
                  }
                }
              }
            }
          }
        }
        first = false
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
    }
    throw e
  } catch (hudson.AbortException e) {
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
      currentBuild.result = "ABORTED"
    } else {
      currentBuild.result = "FAILURE"
    }
    throw e
  } catch (InterruptedException e) {
    currentBuild.result = "ABORTED"
    throw e
  } catch (Throwable e) {
    currentBuild.result = "FAILURE"
    throw e
  } finally {
    // notify completion
    stage("Notifications") {
      jenkinsNotify()      
    }    
  }
}