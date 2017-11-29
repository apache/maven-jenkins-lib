def call(Map params = [:]) {
  def oses = params.containsKey('os') ? params.os : ['linux', 'windows']
  def jdks = params.containsKey('jdks') ? params.jdks : ['7','8','9']
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
      String stageId = "${os}-jdk${jdk}"
      tasks[stageId] = {
        node(label) {
          stage("Checkout ${stageId}") {
            dir('m') {
              checkout scm
            }
          }
          stage("Build ${stageId}") {
            withMaven(jdk:jdkName, maven:mvnName, mavenLocalRepo:'.repository') {
              dir ('m') {
                if (isUnix()) {
                  sh 'mvn clean verify -Dmaven.test.failure.ignore=true -Dfindbugs.failOnError=false'
                } else {
                  bat 'mvn clean verify -Dmaven.test.failure.ignore=true -Dfindbugs.failOnError=false'
                }
              }
            }
          }
        }
      }
      first = false
    }
  }
  def buildRetention
  if (env.BRANCH_NAME == 'master') {
    buildRetention = buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '3', daysToKeepStr: '21', numToKeepStr: '25'))
  } else {
    buildRetention = buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '1', daysToKeepStr: '7', numToKeepStr: '5'))
  }
  properties([buildRetention])
  try {
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
    stage("Notifications") {
      jenkinsNotify()      
    }    
  }
}