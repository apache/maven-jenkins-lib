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

    if (!params.containsKey('maven4xBuild')) {
        params.maven4xBuild = true
    }

    if (!params.containsKey('jdks')) {
        params.jdks = ['17', '21']
    }

    if (!params.containsKey('maven')) {
        params.maven = ['4.0.x']
    }

    if (!params.containsKey('siteJdk')) {
        params.siteJdk = '21'
    }

    if (!params.containsKey('siteMvn')) {
        params.siteMvn = '4.0.x'
    }

    asfMavenTlpPlgnBuild(params)
}
