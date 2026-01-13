package com.matterci.pipelineLib

import com.matterci.pipelineLib.TestUtils
import com.matterci.pipelineLib.TestParamDefaults
import com.matterci.pipelineLib.RepoUtils

import groovy.json.*

class commonPipelineLib implements Serializable {

    static Map installControllerBinaries(def steps, Map testConfigs, String ctrlBinariesDir) {

        def copyArtifactsSuccess = true
        def controllerBinariesWorkspace = "${steps.env.WORKSPACE}/${steps.env.BUILD_NUMBER}/copied_controller_binaries"
        // Determine platform-specific pipeline stages and select appropriate build information.
        // Identify which platform's configuration to use based on 'ctrlBinariesDir':
        //        - "raspi_binaries" → Raspberry Pi pipeline stages
        //        - "nordic_binaries" → Nordic device pipeline stages
        //        - Any other value → throw an error
        // Initialize 'projectName' and 'BUILD_NUMBER' from the current Jenkins environment variables.
        def platformStages = ""
        def copyBuildArtifact = testConfigs.ci_config.copy_build_artifact
        // Use switch statement for platform selection
        switch (ctrlBinariesDir) {
            case "raspi_binaries":
                platformStages = testConfigs.ci_config.raspi_pipeline.stages
                break

            default:
                steps.error("Invalid ctrlBinariesDir: ${ctrlBinariesDir}. Must be 'raspi_binaries'")
        }
        def projectName = steps.env.JOB_NAME
        def BUILD_NUMBER = steps.env.BUILD_NUMBER
        if (copyBuildArtifact.enabled && !platformStages.build_firmware.enabled) {
            projectName = copyBuildArtifact.job_name
            steps.echo "Selected project name from which artifacts will be copied: ${projectName}"
            BUILD_NUMBER = copyBuildArtifact.build_number
            steps.echo "Selected Build number from which artifacts will be copied: ${BUILD_NUMBER}"
        }

        steps.timeout(time: 60, unit: 'MINUTES') {
            try {

                def hostname = steps.sh(script: "hostname", returnStdout: true).trim()
                steps.echo "Hostname is: ${hostname}"

                steps.echo "workspace for controller binaries is ${controllerBinariesWorkspace}"

                steps.ws("${controllerBinariesWorkspace}") {

                    steps.step([
                        $class: 'CopyArtifact',
                        projectName: projectName,
                        selector: steps.specific("${BUILD_NUMBER}"),
                        filter: "${ctrlBinariesDir}/**/*.whl",
                        target: '.'
                    ])

                    def status = RepoUtils.cloneMatterQARepo(steps, testConfigs, "main", controllerBinariesWorkspace, ctrlBinariesDir)

                    if (status != 0) {
                        copyArtifactsSuccess = false
                        steps.echo("Error occurred in shell CMDs in 'Copy and install binaries into ON_NETWORK_CONTROLLER_NODE' stage.")
                    }
                }
            } catch(Exception e) {
                copyArtifactsSuccess = false
                steps.echo "Error occurred during 'Copy and install binaries into ON_NETWORK_CONTROLLER_NODE' stage: ${e.getMessage()}"
            }
            return [success: copyArtifactsSuccess, cntrlWorksSpace: "${controllerBinariesWorkspace}"]
        }
    }

    static def buildController(def steps, Map testConfigs, String platform, String workSpace, String platformBinariesDir){

        def arch = steps.sh(script: "uname -m", returnStdout: true).trim()
        steps.echo "HW arch ${arch}"
        steps.echo "This stage Build Controller is running on : ${steps.env.NODE_NAME}"
        steps.echo "Work space to build controller : ${workSpace}"
        steps.echo "build binaries copied into ${platformBinariesDir}"
        def cmdStatus = 1

        steps.ws("${workSpace}")
        {
            try{
                cmdStatus = steps.sh(
                    script: """#!/bin/bash
                        set -ex
                        OS_TYPE=\$(uname | tr '[:upper:]' '[:lower:]')
                        if [[ "\$OS_TYPE" == "darwin" ]]; then
                            source ~/.zshrc
                        else
                            export BASH_ENV=~/.bashrc
                            source ~/.bashrc
                        fi
                        git config --global --add safe.directory ${workSpace}
                        git config --global http.version HTTP/1.1
                        git config --global http.postBuffer 524288000
                        git config --global http.lowSpeedLimit 0
                        git config --global http.lowSpeedTime 999999
                        ./scripts/checkout_submodules.py --allow-changing-global-git-config --shallow --platform "\$OS_TYPE"
                        python3 scripts/checkout_submodules.py --shallow --platform $platform
                        source scripts/bootstrap.sh
                        source scripts/activate.sh
                        # TODO: -n false is a temporary workaround needs to be updated it to dynamic bases on the configuration.
                        scripts/build_python.sh -m platform -d true -i out/python_env -n false
                        mv out/python_lib/controller/python/*.whl ../$platformBinariesDir
                        mv out/python_lib/obj/src/python_testing/matter_testing_infrastructure/matter-testing._build_wheel/matter_testing-*.whl ../$platformBinariesDir
                    """,
                    returnStatus: true  // Captures exit code
                )
            }catch (Exception e) {
                steps.echo "Error occurred during building controller on {$platform} stage: ${e.getMessage()}"
            }
        }
        return cmdStatus
    }
    //Transfers logs from the main node to a storage server.
    static def transferLogsToStorageServer(def steps, Map args, Map logTransferConfig) {
        def timeoutMinutes = logTransferConfig?.logTransferTimeoutMinutes ?: 60
        def transferSuccess = false
        def logDir = ""
        def destPath = logDir
        def lastErrorMsg = ""

        steps.timeout(time: timeoutMinutes, unit: 'MINUTES') {
            // Validate required arguments
            if (!args.nodeName || !args.storageServerNode || !args.storageServerPath) {
                steps.error("Log transfer: Missing required arguments. nodeName, storageServerNode, and storageServerPath are required.")
            }

            def retries = logTransferConfig?.retryCount ?: 3
            def delayMinutes = logTransferConfig?.retryDelay ?: 2

            for (int i = 1; i <= retries; i++) {
                try {
                    def agentIp = "unknownIP"
                    def stashName = "logs_to_transfer_${args.logType}_${args.buildId}"
                    def tarName = "logs_to_transfer_${args.buildId}.tar.gz"

                    // --- Archive and stash logs from the main node ---
                    steps.node(args.nodeName) {
                        try {
                            try {
                                agentIp = steps.sh(
                                    script: "hostname -I 2>/dev/null | awk '{print \$1}' || hostname -i || hostname",
                                    returnStdout: true
                                ).trim()
                            } catch (Exception e) {
                                agentIp = "unknownIP"
                            }
                            steps.echo "${args.nodeName} : ${agentIp}"

                            logDir = "${steps.env.HOME}/MatterTestsLogs/${args.jobName}/${args.buildId}"
                            if (!steps.fileExists(logDir)) {
                                lastErrorMsg = "No logs found in ${logDir}, skipping transfer. Logs remain at ${logDir}"
                                throw new Exception(lastErrorMsg)
                            }

                            steps.sh "tar czf ${tarName} -C '${logDir}' ."
                            steps.stash includes: "${tarName}", name: stashName
                        } catch (Exception e) {
                            lastErrorMsg = "Failed to archive/stash logs on ${args.nodeName}: ${e.getMessage()}\nLogs remain at ${logDir}"
                            throw e
                        }
                    }

                    // --- Transfer and extract logs on the storage server node ---
                    destPath = "${args.storageServerPath}/${args.nodeName}_RUN_logs_${args.logType}/${args.jobName}_${args.buildId}"
                    steps.node(args.storageServerNode) {
                        steps.sh "mkdir -p '${destPath}'"
                        steps.unstash stashName
                        steps.sh "tar xzf ${tarName} -C '${destPath}'"
                        steps.echo "Logs successfully transferred to storage server at ${destPath}"
                    }

                    // --- Clean up logs from the main node if transfer succeeded ---
                    steps.node(args.nodeName) {
                        try {
                            steps.sh "rm -rf '${logDir}'"
                            steps.echo "Cleaned up logs for build ${args.buildId} on ${args.nodeName}"
                        } catch (Exception e) {
                            steps.echo "Failed to clean up logs on ${args.nodeName}: ${e.getMessage()}"
                        }
                    }

                    transferSuccess = true
                    break  // exit retry loop if success

                } catch (Exception e) {
                    lastErrorMsg = e.getMessage()
                    steps.echo "Attempt ${i}/${retries} failed: ${lastErrorMsg}"
                    if (i < retries) {
                        steps.echo "Retrying in ${delayMinutes} minutes..."
                        steps.sleep(time: delayMinutes, unit: "MINUTES")
                    }
                }
            }
        }
        return [success: transferSuccess, location: transferSuccess ? destPath : logDir]
    }
}