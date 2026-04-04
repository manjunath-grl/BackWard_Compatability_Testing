/* groovylint-disable LineLength */
package com.matterci.pipelineLib

import com.matterci.pipelineLib.TestUtils
import com.matterci.pipelineLib.TestParamDefaults
import com.matterci.pipelineLib.RepoUtils
import com.matterci.pipelineLib.commonPipelineLib

import groovy.json.*

class RaspiPipelineLib implements Serializable {

    static def listOfUsedRaspis
    static def listOfAvaialbelRaspis
    static def raspiBinariesDirString
    static {
        listOfUsedRaspis = []
        listOfAvaialbelRaspis = []
        raspiBinariesDirString = "raspi_binaries"
    }

    private static String getPlatform(def steps) {
        def arch = steps.sh(script: "uname -m", returnStdout: true).trim()
        steps.echo "Detected host architecture: ${arch}"
        return arch == "x86_64" ? "linux/amd64" : "linux/arm64"
    }
    //TODO: optimize this code to use one single function call

    static getCntrlDeviceRaspiNodes(def steps, String stageName, Map testConfigs){
        def getNodesAssigned = true
        def allocatedNodes = [:]
        def MINIMUM_REQUIRED_NODES = 1

        try {
            steps.timeout(time: 10, unit: 'MINUTES') {

                // Get dedicated controller and device node labels from configuration
                def controllerNodeLabel = testConfigs?.ci_config?.raspi_controller_node ?: "raspi_controller_agent"
                def deviceNodeLabel = testConfigs?.ci_config?.raspi_device_node ?: "raspi_device_agent"

                steps.echo "Looking for Controller nodes with label: ${controllerNodeLabel}"
                steps.echo "Looking for Device nodes with label: ${deviceNodeLabel}"

                // Get list of available controller nodes
                def availableControllerNodes = jenkins.model.Jenkins.instance.nodes.findAll {node ->
                    node.labelString.contains(controllerNodeLabel) && node.toComputer().isOnline() &&
                    node.toComputer().countBusy() == 0 // Ensure node is not running any jobs
                }.collect { it.name }

                // Get list of available device nodes
                def availableDeviceNodes = jenkins.model.Jenkins.instance.nodes.findAll {node ->
                    node.labelString.contains(deviceNodeLabel) && node.toComputer().isOnline() &&
                    node.toComputer().countBusy() == 0 // Ensure node is not running any jobs
                }.collect { it.name }

                steps.echo "Available Controller Nodes: ${availableControllerNodes}"
                steps.echo "Available Device Nodes: ${availableDeviceNodes}"

                if (availableControllerNodes.size() < MINIMUM_REQUIRED_NODES) {
                    throw new RuntimeException("No Controller nodes available with label '${controllerNodeLabel}'!")
                }

                if (availableDeviceNodes.size() < MINIMUM_REQUIRED_NODES) {
                    throw new RuntimeException("No Device nodes available with label '${deviceNodeLabel}'!")
                }

                // If listOfUsedRaspis is null, treat it as an empty list
                listOfUsedRaspis = listOfUsedRaspis ?: []

                // Lock the block of code to prevent multiple pipelines from selecting the same nodes
                steps.lock(resource: 'raspi_lock') { // Lock on a resource to avoid race condition
                    // Filter out the used raspis from available nodes
                    def availableControllerRaspis = availableControllerNodes.findAll { !listOfUsedRaspis.contains(it) }
                    def availableDeviceRaspis = availableDeviceNodes.findAll { !listOfUsedRaspis.contains(it) }

                    steps.echo "Available Controller Raspis (unused): ${availableControllerRaspis}"
                    steps.echo "Available Device Raspis (unused): ${availableDeviceRaspis}"


                    // Find the controller node with the most available memory
                    def controllerNodeStorageMap = [:]
                    availableControllerRaspis.each { nodeName ->
                        try {
                            steps.node(nodeName) {
                                // Get available storage in KB on root filesystem
                                def freeStorage = steps.sh(
                                    script: "df -k / | awk 'NR==2 {print \$4}'",
                                    returnStdout: true
                                ).trim()
                                steps.echo "Controller node ${nodeName} has available storage: ${freeStorage} KB"
                                controllerNodeStorageMap[nodeName] = freeStorage as Long
                            }
                        } catch (Exception e) {
                            steps.echo "Failed to get available storage for node ${nodeName}: ${e.getMessage()}"
                        }
                    }

                    def selectedControllerNode = null
                    if (!controllerNodeStorageMap.isEmpty()) {
                        def maxEntry = controllerNodeStorageMap.entrySet().max { it.value }  // Gives you Map.Entry
                        selectedControllerNode = maxEntry.key
                    } else if (availableControllerRaspis.size() > 0) {
                        selectedControllerNode = availableControllerRaspis[0]
                    }
                    def selectedDeviceNode = null
                    if (!availableDeviceRaspis.isEmpty()) {
                        selectedDeviceNode = availableDeviceRaspis[0]
                    }

                    if (selectedControllerNode && selectedDeviceNode) {
                        listOfUsedRaspis << selectedControllerNode  // Append selected controller raspi
                        listOfUsedRaspis << selectedDeviceNode     // Append selected device raspi
                        allocatedNodes = ["controllerNode": selectedControllerNode, "deviceNode": selectedDeviceNode]
                    } else {
                        throw new RuntimeException("No available dedicated Controller or Device nodes after filtering used nodes!")
                    }
                }
                steps.echo "Selected ${stageName} Controller Node: ${allocatedNodes["controllerNode"]}"
                steps.echo "Selected ${stageName} Device Node: ${allocatedNodes["deviceNode"]}"
            }
        } catch (Exception e) {
            getNodesAssigned = false
            steps.echo "Error occurred during 'Get nodes of controller and device raspi' stage: ${e.getMessage()}"
        }
        return [success: getNodesAssigned, nodesAllocated: allocatedNodes]
    }

    static installDeviceBinaries(def steps,Map testConfigs,String nodeName,String stageName) {

        def copyArtifactsSuccess = true
        steps.node(nodeName) {
            def deviceIP = ''
            def deviceRaspiWorkspace = ''
            commonPipelineLib.setupJfrog(steps, testConfigs)
            steps.timeout(time: 60, unit: 'MINUTES') {
                try {
                    steps.echo "Running on device node: ${nodeName}"
                    def hostname = steps.sh(
                        script: "hostname",
                        returnStdout: true
                    ).trim()
                    deviceIP = steps.sh(
                        script: "hostname -I | awk '{print \$1}'",
                        returnStdout: true
                    ).trim()

                    steps.echo "${stageName} Device IP: ${deviceIP}"
                    deviceRaspiWorkspace = "${steps.env.WORKSPACE}/${steps.env.BUILD_NUMBER}/copied_device_binaries"

                    steps.echo "Device workspace: ${deviceRaspiWorkspace}"
                    def raspiDecision = testConfigs.ci_config.artifactDecision.platforms.raspi
                    steps.ws(deviceRaspiWorkspace) {
                        raspiDecision.apps.each { app ->
                            steps.echo "Downloading binary: ${app.name}"

                            def basePath =commonPipelineLib.getResolvedArtifactBasePath(testConfigs,"apps",app.branch,app.sha,app.tag,app.pr)
                            def jfrogPath = "${basePath}/apps/${app.name}/raspi/*"

                            steps.echo "JFrog path: ${jfrogPath}"

                            steps.dir(deviceRaspiWorkspace) {
                                steps.sh """
                                    set -e
                                    jf rt dl \
                                    "${jfrogPath}" \
                                    "./" \
                                    --flat=true \
                                    --insecure-tls=true

                                    chmod +x * || true
                                """
                            }

                            def binaryCount = steps.sh(
                                script: """
                                    ls ${deviceRaspiWorkspace}/chip-${app.name}* \
                                    2>/dev/null | wc -l
                                """,
                                returnStdout: true
                            ).trim()

                            if (binaryCount == "0") {
                                steps.error(
                                    "Binary missing for ${app.name}"
                                )
                            }
                            steps.echo(
                                "Downloaded binary for ${app.name}"
                            )
                        }
                    }

                }
                catch (Exception e) {
                    copyArtifactsSuccess = false
                    steps.echo(
                        "Device binary install failure: ${e.getMessage()}"
                    )
                }
                return [
                    success: copyArtifactsSuccess,
                    deviceWorksSpace: deviceRaspiWorkspace,
                    deviceIPAddress: deviceIP,
                    updatedTestConfig: testConfigs
                ]
            }
        }
    }

    static Map initRaspiOnNetworkTestParams(def steps,Map testConfigs,String cntrlWorkSpace, String deviceWorkSpace, String deviceNodeIPAddress, String appToTest) {
        // Files under vars/ (like vars/TestParamDefaults.groovy) are exposed as global script steps, not class methods.
        // but I changed into seperate class , as calling jenkins throwing error when calling TestParamDefaults multiple times in same class

        steps.echo "cntrl workspace passed : ${cntrlWorkSpace}"
        steps.echo "device workspace passed : ${deviceWorkSpace}"
        def localTestParams = TestUtils.deepCopy(testConfigs)
        steps.echo "local Test Params before updating : ${localTestParams}"
        steps.echo "TestConfigs : ${testConfigs}"
        steps.echo "Discriminator used : ${testConfigs.Testcase_runner_config.dut_config.rpi.app_config.discriminator}"
        // This will be used to append test_results folder in the run tests method
        localTestParams.ci_config.ci_ws_path = "${cntrlWorkSpace}"

        //TestUtils.updateOrCreateKeyValue(localTestParams,"testConfigs.Testcase_runner_config.platform" ,"rpi")
        //get the controller name from the Jenkins steps.
        TestUtils.updateOrCreateKeyValue(localTestParams, "Testcase_runner_config.dut_config.rpi.rpi_hostname", "${deviceNodeIPAddress}")
        TestUtils.updateOrCreateKeyValue(localTestParams, "Testcase_runner_config.dut_config.rpi.app_config.discriminator", testConfigs.Testcase_runner_config.dut_config.rpi.app_config.discriminator)
        //TODO:Fix it such that we can pass app also from the config
        TestUtils.updateOrCreateKeyValue(localTestParams, "Testcase_runner_config.dut_config.rpi.app_config.matter_app", "${deviceWorkSpace}/${appToTest}")
        //TestUtils.updateOrCreateKeyValue(localTestParams, "Testcase_runner_config.python_scripts.matter_app", "${deviceWorkSpace}/${RaspiPipelineLib.raspiBinariesDirString}/${appToTest} --wifi")

        //TestUtils.updateOrCreateKeyValue(localTestParams, "Testcase_runner_config.dut_config.rpi.commissioning_method",["on-network"])

        //TestUtils.updateOrCreateKeyValue(localTestParams,"test_case_config.TC_Darwin_Pair.manual_code", testConfigs.Testcase_runner_config.dut_config.rpi.on_network_manual_code)
        //TestUtils.updateOrCreateKeyValue(localTestParams,"test_case_config.TC_Android_Pair.manual_code", testConfigs.Testcase_runner_config.dut_config.rpi.on_network_manual_code)

        steps.echo "discriminator params ${localTestParams.Testcase_runner_config.dut_config.rpi.app_config.discriminator}"
        steps.echo "updated local params ${localTestParams}"

        def test_params_json = JsonOutput.toJson(localTestParams)
        steps.echo "JSON params ${test_params_json}"

        return localTestParams
    }

    static Map buildApps(def steps, Map testConfigs, List testCasesList, String workSpace, String raspiBinariesDir, String appName) {
        def appMapping = [
            "all-clusters-app": [
                build_app : "linux-arm64-all-clusters-ipv6only",
                output_path: "out/linux-arm64-all-clusters-ipv6only/chip-all-clusters-app",
                app_name : "chip-all-clusters-app"
            ],
            "lock-app": [
                build_app : "linux-arm64-lock-ipv6only",
                output_path: "out/linux-arm64-lock-ipv6only/chip-lock-app",
                app_name : "chip-lock-app"
            ],
            "lighting-app":[
                build_app : "linux-arm64-light-ipv6only",
                output_path: "out/linux-arm64-light-ipv6only/chip-lighting-app",
                app_name : "chip-lighting-app"
            ]
        ]

        if (!appMapping.containsKey(appName))
            steps.error("Unsupported app: ${appName}")

        def buildApp  = appMapping[appName].build_app
        def outputPath = appMapping[appName].output_path
        def binaryName = appMapping[appName].app_name
        def appStorePath = "${raspiBinariesDir}/apps/${appName}"
        steps.echo "Building ${appName}"
        steps.echo "Output → ${outputPath}"
        def buildAppSucess = true

        def arch = steps.sh(script: "uname -m", returnStdout: true).trim()
        def dockerPlatform = (arch == "x86_64") ? "linux/amd64" : "linux/arm64"
        def dockerImage = testConfigs.ci_config.raspi_pipeline.stages.build_firmware.docker_image

        def dockerCommands = """#!/bin/bash
                set -ex

                export PATH=/usr/local/bin:$PATH
                echo "PATH=$PATH"
                which docker
                docker --version
                
                docker run --rm --user root --platform=${dockerPlatform} -v ${workSpace}:/home/connectedhome \\
                -w /home/connectedhome ${docker_image}:latest \\
                /bin/bash -c \"
                    set -ex  # Stop execution on first error
                    git config --global --add safe.directory /home/connectedhome
                    git config --global --add safe.directory /home/connectedhome/third_party/pigweed/repo
                    git config --global http.version HTTP/1.1
                    git config --global http.postBuffer 524288000
                    git config --global http.lowSpeedLimit 0
                    git config --global http.lowSpeedTime 999999
                    ./scripts/checkout_submodules.py --allow-changing-global-git-config --shallow --platform linux
                    source scripts/bootstrap.sh
                    source scripts/activate.sh
                    scripts/build/build_examples.py --target ${buildApp} build
                \"
            """
            steps.echo "Docker command used to build App ${dockerCommands}"

            def status = sh(
                script: dockerCommands,
                returnStatus: true
            )

            if (status ==0){
                steps.ws(workSpace) {
                    steps.sh """
                        mkdir -p ${appStorePath}
                        mv ${outputPath} ${appStorePath}/
                    """
                }
            }
        return [ success : true, appToTest : binaryName]
    }
}