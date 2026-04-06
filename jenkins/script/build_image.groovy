/*
Apache v2 license
Copyright (C) 2023 Intel Corporation
SPDX-License-Identifier: Apache-2.0
*/

def build_time = new Date()
build_time = build_time.format("MM-dd-yyyy", TimeZone.getTimeZone('UTC'))
def commitId

String getEffectivePushRegistry(scriptEnv) {
	def pushRegistry = scriptEnv.push_registry?.trim()
	return pushRegistry ? pushRegistry : scriptEnv.registry
}

String getRegistryPort(String registry, String defaultPort = '5000') {
	def tokens = registry.tokenize(':')
	return tokens.size() > 1 ? tokens[-1] : defaultPort
}

String getTunnelUser(scriptEnv) {
	def tunnelUser = scriptEnv.registry_tunnel_user?.trim()
	return tunnelUser ? tunnelUser : 'root'
}

String getTunnelKeyOption(scriptEnv) {
	def keyPath = scriptEnv.registry_tunnel_key?.trim()
	return keyPath ? "-i ${keyPath}" : ''
}

String getTunnelDestination(scriptEnv) {
	return "${getTunnelUser(scriptEnv)}@${scriptEnv.registry_tunnel_host}"
}

pipeline {
	agent {
		label 'node1'
	}
	parameters {
	    string(name: 'front_job_id', defaultValue: '', description: 'Related Job in frontend.')
		string(name: 'commit', defaultValue: 'main', description: '')
		string(name: 'registry', defaultValue: 'registry.local:5000', description: '')
		string(name: 'push_registry', defaultValue: '', description: 'registry endpoint used by the local build/push path; falls back to registry when empty')
		string(name: 'registry_tunnel_host', defaultValue: '', description: 'ssh host used to forward push_registry to the remote registry')
		string(name: 'registry_tunnel_user', defaultValue: '', description: 'ssh user for the registry tunnel')
		string(name: 'registry_tunnel_key', defaultValue: '', description: 'ssh private key path for the registry tunnel')
		string(name: 'registry_tunnel_remote_host', defaultValue: 'localhost', description: 'remote registry host reached from the ssh tunnel')
		string(name: 'registry_tunnel_remote_port', defaultValue: '', description: 'remote registry port reached from the ssh tunnel; falls back to registry port when empty')
		string(name: 'platform', defaultValue: 'ICX', description: '')
		string(name: 'workload_list', defaultValue: '', description: 'To build workload list, default "" , means all workload. Separated with ";",e.g: BoringSSL;Bert-Large;CNN;customer/ali/redis')
		string(name: 'repo', defaultValue: 'https://github.com/intel/workload-services-framework', description: 'WSF repo')
		string(name: 'SUT', defaultValue: 'static', description: 'Only support static for now', trim: true)
	}
	stages {
		stage('download one source repo'){
			steps {
				script {
					if (env.commit) {
						revision = "${commit}"
					}
					else
					{
						revision = "master"
					}
					sh "rm -rf validation && git clone ${env.repo} validation && cd validation && git checkout ${revision}"
					commitId = sh(returnStdout: true, script: 'cd validation && git rev-parse HEAD')
					commitId = commitId?.substring(0,8)
					platform = env.platform
					env.EFFECTIVE_PUSH_REGISTRY = getEffectivePushRegistry(env)
					currentBuild.displayName = "${platform}_${build_time}_${BUILD_ID}_${commitId}"
				}
			}
		}
		stage('setup registry tunnel') {
			when {
				expression { return env.registry_tunnel_host?.trim() }
			}
			steps {
				script {
					def localBindHost = 'localhost'
					def bindPort = getRegistryPort(env.EFFECTIVE_PUSH_REGISTRY)
					def remoteHost = env.registry_tunnel_remote_host?.trim() ? env.registry_tunnel_remote_host.trim() : 'localhost'
					def remotePort = env.registry_tunnel_remote_port?.trim() ? env.registry_tunnel_remote_port.trim() : getRegistryPort(env.registry)
					def tunnelCommand = "ssh -fN -M -S ${env.WORKSPACE}/registry-tunnel.sock " +
						"-o ExitOnForwardFailure=yes " +
						"-o StrictHostKeyChecking=no " +
						"-o UserKnownHostsFile=/dev/null " +
						"${getTunnelKeyOption(env)} " +
						"-L ${localBindHost}:${bindPort}:${remoteHost}:${remotePort} " +
						"${getTunnelDestination(env)}"
					sh "rm -f ${env.WORKSPACE}/registry-tunnel.sock"
					sh tunnelCommand
				}
			}
		}
		stage('build all workloads images') {
			steps {
				script {
			    	sh (
						script: "cd validation &&  rm -rf build && mkdir build && cd build && echo accept | cmake -DPLATFORM=${platform} -DBENCHMARK='' -DREGISTRY=${env.EFFECTIVE_PUSH_REGISTRY} -DBACKEND=terraform -DRELEASE=${commitId} -DTERRAFORM_SUT=${env.SUT} -DACCEPT_LICENSE=ALL ../",
						returnStdout: true
					)
					sh (script: "cd validation/build && make build_terraform", returnStdout: true)
			    	if (env.workload_list != '') {
    				    workload_list = env.workload_list
    				    workload_list = workload_list.split(";")
						for (workload in workload_list) {
    					        println ("=================================Build workload: ${workload} image========================================")
    					        sh (returnStdout: true, script: "cd validation/build/workload/${workload} && make")
    					    }
					}
					else {
					    println ("=================================Build all workload images========================================")
					    sh (returnStdout: true, script: "cd validation/build && make")
					}
					println ("=================================Building images finished========================================")
				}
			}
		}
	}
	post {
		always {
			script{
				if (env.registry_tunnel_host?.trim()) {
					sh(
						script: "ssh -S ${env.WORKSPACE}/registry-tunnel.sock -O exit ${getTunnelKeyOption(env)} ${getTunnelDestination(env)} || true",
						returnStatus: true
					)
					sh "rm -f ${env.WORKSPACE}/registry-tunnel.sock"
				}
				cleanWs()
				println "ok"
			}
		}
	}
}
