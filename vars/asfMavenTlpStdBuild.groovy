def call(Map params = [:]) {
  def oses = params.containsKey('os') ? params.os : ['linux', 'windows']
  def jdks = params.containsKey('jdks') ? params.jdks : ['7','8']
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
        continue;
      }
      String stageId = "${os}-jdk${jdk}"
      tasks[stageId] = {
        node(label) {
          stage("Checkout ${stageId}") {
            checkout scm
          }
          stage("Build ${stageId}") {
            withMaven(jdk:jdkName, maven:mvnName, mavenLocalRepo:'.repository') {
              if (isUnix()) {
                sh 'mvn clean verify'
              } else {
                bat 'mvn clean verify'
              }
            }
          }
        }
      }
      first = false
    }
  }
  return parallel(tasks)
}