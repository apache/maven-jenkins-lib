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
  Map taskContext = [:]
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
    def jdks = params.containsKey('jdks') ? params.jdks : params.containsKey('jdk') ? params.jdk : ['8','11','17']
    def jdkMin = jdks[0];
    def mavens = params.containsKey('maven') ? params.maven : ['3.6.x','3.8.x']
    // def failFast = params.containsKey('failFast') ? params.failFast : true
    // Just temporarily
    def failFast = false;
    def siteJdks = params.containsKey('siteJdk') ? params.siteJdk : ['8']
    def siteMvn = params.containsKey('siteMvn') ? params.siteMvn : '3.8.x'
    def siteOses = params.containsKey('siteOs') ? params.siteOs : ['linux']
    def tmpWs = params.containsKey('tmpWs') ? params.tmpWs : false
    
    taskContext['failFast'] = failFast;
    taskContext['tmpWs'] = tmpWs;
    taskContext['archives'] = params.archives
    taskContext['siteWithPackage'] = params.containsKey('siteWithPackage') ? params.siteWithPackage : false // workaround for MNG-7289
    taskContext['extraCmd'] = params.containsKey('extraCmd') ? params.extraCmd : ''
    taskContext['ciReportingRunned'] = false	  

    Map tasks = [failFast: failFast]
    boolean first = true
    for (String os in oses) {
      for (def mvn in mavens) {
        def jdk = Math.max( jdkMin as Integer, jenkinsEnv.jdkForMaven( mvn ) as Integer) as String
        jdks = jdks.findAll{ it != jdk }
        doCreateTask( os, jdk, mvn, tasks, first, 'build', taskContext )
      }
      for (def jdk in jdks) {
        def mvn = jenkinsEnv.mavenForJdk(jdk)
        doCreateTask( os, jdk, mvn, tasks, first, 'build', taskContext )
      }
          
      // run with apache-release profile, consider it a dryRun with SNAPSHOTs
      // doCreateTask( os, siteJdk, siteMvn, tasks, first, 'release', taskContext )
    }
    for (String os in siteOses) {	  
      for (def jdk in siteJdks) {
        // doesn't work for multimodules yet
        doCreateTask( os, jdk, siteMvn, tasks, first, 'site', taskContext )
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
    if (taskContext.failingFast != null) {
      echo "***** FAST FAILURE *****\n\nFast failure triggered by ${taskContext.failingFast}\n\n***** FAST FAILURE *****"
    }
    stage("Notifications") {
	  jenkinsNotify()
    }
  }
}

def doCreateTask( os, jdk, maven, tasks, first, plan, taskContext )
{
  String label = jenkinsEnv.labelForOS(os);
  String jdkName = jenkinsEnv.jdkFromVersion(os, "${jdk}")
  String mvnName = jenkinsEnv.mvnFromVersion(os, "${maven}")
  echo "OS: ${os} JDK: ${jdk} Maven: ${maven} => Label: ${label} JDK: ${jdkName} Maven: ${mvnName}"
  if (label == null || jdkName == null || mvnName == null) {
    echo "Skipping ${os}-jdk${jdk} as unsupported by Jenkins Environment"
    return;
  }
  def recordReporting = false	
  def cmd = [
    'mvn', '-V',
    '-P+run-its',
    '-Dfindbugs.failOnError=false',
    '-e',
    taskContext.extraCmd  
  ]
  if (!first) {
    cmd += '-Dfindbugs.skip=true'
//  } else { // Requires authorization on SonarQube first
//    cmd += 'sonar:sonar'
  }	  
  if (Integer.parseInt(jdk) >= 11 && !taskContext['ciReportingRunned']) {
    cmd += "-Pci-reporting -Perrorprone" 
    taskContext['ciReportingRunned'] = true	 
    recordReporting = true	  
  }
	

  if (plan == 'build') {
      cmd += 'clean'
      if (env.BRANCH_NAME == 'master' && jdk == '17' && maven == '3.6.x' && os == 'linux' ) {
        cmd += 'deploy'		      
      } else {
        cmd += 'verify -Dpgpverify.skip'      
      }	      
  }
  else if (plan == 'site') {
      if (taskContext.siteWithPackage) {
        cmd += 'package'
      }
      cmd += 'site'
      cmd += '-Preporting'
  }
  else if (plan == 'release') {
      cmd += 'verify'
      cmd += '-Papache-release'
  }
  def disablePublishers = !first
  first = false
  String stageId = "${os}-jdk${jdk}-m${maven}_${plan}"
  tasks[stageId] = {
    node(jenkinsEnv.nodeSelection(label)) {
      def wsDir = pwd()
	  def stageDir = stageId
	  if (os == 'windows' && taskContext.tmpWs) {
//	    wsDir = "$TEMP\\$BUILD_TAG" // or use F:\jenkins\jenkins-slave\workspace or F:\short
	    wsDir = 'F:\\short\\' + "$BUILD_TAG".replaceAll(/(.+)maven-(.+)-plugin(.*)/) { "m-${it[2]}-p${it[3]}" }
		stageDir = "j${jdk}m${maven}" + plan.take(1)
	  }
      ws( dir : "$wsDir" )
      {
        stage("Checkout ${stageId}") {
          echo "NODE_NAME = ${env.NODE_NAME}"
          try {
            dir(stageDir) {
              checkout scm
            }
          } catch (Throwable e) {
            // First step to keep the workspace clean and safe disk space
            cleanWs()

            if (!taskContext.failFast) {
              throw e
            } else if (taskContext.failingFast == null) {
              taskContext.failingFast = stageId
              echo "[FAIL FAST] This is the first failure and likely root cause"
              throw e
            } else {
              echo "[FAIL FAST] ${taskContext.failingFast} had first failure, ignoring ${e.message}"
            }
          } 
        }
        stage("Build ${stageId}") {
          if (taskContext.failingFast != null) {
            cleanWs()
            echo "[FAIL FAST] ${taskContext.failingFast} has failed. Skipping ${stageId}."
          } else try {
            def localRepo = "../.maven_repositories/${env.EXECUTOR_NUMBER}"
            println "Local Repo (${stageId}): ${localRepo}"
            withMaven(jdk:jdkName, maven:mvnName, mavenLocalRepo:localRepo, options: [
              artifactsPublisher(disabled: disablePublishers),
              junitPublisher(ignoreAttachments: false),
              findbugsPublisher(disabled: disablePublishers),
              openTasksPublisher(disabled: disablePublishers),
              dependenciesFingerprintPublisher(disabled: disablePublishers),
              invokerPublisher(),
              pipelineGraphPublisher(disabled: disablePublishers),
              mavenLinkerPublisher(disabled: false)
           ], publisherStrategy: 'EXPLICIT') {
             dir (stageDir) {
               if (isUnix()) {
                 sh 'df -hT'
                 sh cmd.join(' ')
               } else {
                 bat 'wmic logicaldisk get size,freespace,caption'
                 bat cmd.join(' ')
                }
              }
            }
            if(recordReporting) {
              recordIssues id: "${os}-jdk${jdk}", name: "Static Analysis", 
		           aggregatingResults: true, enabledForFailure: true, 
		           tools: [mavenConsole(), java(), checkStyle(), spotBugs(), pmdParser(), errorProne(),taglist()]    
              jacoco inclusionPattern: '**/org/apache/maven/**/*.class',
                     exclusionPattern: '',
                     execPattern: '**/target/jacoco.exec',
                     classPattern: '**/target/classes',
                     sourcePattern: '**/src/main/java'		    
              recordReporting = false;		    
            }
          } catch (Throwable e) {
            archiveDirs(taskContext.archives, stageDir)
            // First step to keep the workspace clean and safe disk space
            cleanWs()
            if (!taskContext.failFast) {
              throw e
            } else if (taskContext.failingFast == null) {
              taskContext.failingFast = stageId
              echo "[FAIL FAST] This is the first failure and likely root cause"
              throw e
            } else {
              echo "[FAIL FAST] ${taskContext.failingFast} had first failure, ignoring ${e.message}"
            }
          } finally {
            cleanWs()
          }  
        }
      }
    }
  }
}

def archiveDirs(archives, stageDir) {
  if (archives != null) {
    dir(stageDir) {
      archives.each { archivePrefix, pathToContent ->
	    zip(zipFile: "${archivePrefix}-${stageDir}.zip", dir: pathToContent, archive: true)
      }
    }
  }
}
