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

package org.apache.maven.jenkins

class GitCheckoutHelper implements Serializable {

  /**
    * Performs a Git checkout with the specified configuration and fetch depth.
    *
    * @param script The Jenkins pipeline script context (usually 'this').
    * @param scmConfig The SCM configuration for the Git checkout (set in the Jenkins job configuration).
    * @param fetchDepth The depth for shallow cloning (null for using the default from scmConfig).
    */
  static void checkoutScm(def script, def scmConfig, def fetchDepth) {
    if (fetchDepth == null || "${fetchDepth}".trim().isEmpty()) {
      // simple checkout (with the job's scm configuration) if no explicit fetch depth is specified
      script.checkout(scmConfig)
      return
    }

    // otherwise enrich default config to take into account the fetch depth for a (non-)shallow clone
    def fetchDepthValue = "${fetchDepth}".toInteger()
    def shallowClone = fetchDepthValue > 0

    // https://plugins.jenkins.io/git/#plugin-content-checkout-with-a-shallow-clone-to-reduce-data-traffic
    def extensions = (scmConfig.extensions ?: []).findAll { extension ->
      def extensionType = extension instanceof Map ? extension['$class'] : extension?.getClass()?.name
      extensionType != 'CloneOption' && extensionType != 'hudson.plugins.git.extensions.impl.CloneOption'
    }
    def cloneOptions = [
      $class: 'CloneOption',
      shallow: shallowClone,
      noTags: true
    ]
    if (shallowClone) {
      cloneOptions.depth = fetchDepthValue
    }
    extensions += [cloneOptions]

    script.checkout([
      $class: 'GitSCM',
      branches: scmConfig.branches,
      doGenerateSubmoduleConfigurations: scmConfig.doGenerateSubmoduleConfigurations,
      extensions: extensions,
      submoduleCfg: scmConfig.submoduleCfg,
      userRemoteConfigs: scmConfig.userRemoteConfigs
    ])
  }
}
