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

    static installDeviceBinaries(def steps, Map testConfigs, String nodeName, String stageName){
        def copyArtifactsSuccess = true
        def raspiStages = testConfigs.ci_config.raspi_pipeline.stages
        def copyBuildArtifact = testConfigs.ci_config.copy_build_artifact
        def projectName= steps.env.JOB_NAME
        def BUILD_NUMBER = steps.env.BUILD_NUMBER
        def appToTest = testConfigs.ci_config.clone_sdk_code_stage.platforms.raspi.app_to_test
        // if ( copyBuildArtifact.enabled && !raspiStages.build_firmware.enabled) {
        //     projectName = copyBuildArtifact.job_name
        //     BUILD_NUMBER = copyBuildArtifact.build_number
        // }

        steps.node(nodeName) {
            def deviceIP=''
            def deviceRaspiWorkspace
            commonPipelineLib.setupJfrog(steps, testConfigs)
            steps.timeout(time: 60, unit: 'MINUTES') {
                try {
                    steps.echo "Running on device node: ${nodeName}"
                    def hostname = steps.sh(script: "hostname", returnStdout: true).trim()
                    steps.echo "Hostname is: ${hostname}"

                    deviceIP = steps.sh(script: "hostname -I | awk '{print \$1}'", returnStdout: true).trim()
                    steps.echo "${stageName} Node Device IP: ${deviceIP}"
                    deviceRaspiWorkspace = "${steps.env.WORKSPACE}/${steps.env.BUILD_NUMBER}/copied_device_binaries"

                    steps.sh 'rm -rf /tmp/chip*'
                    steps.sleep 2
                    steps.echo "raspi workspace on device node is ${deviceRaspiWorkspace}"
                    steps.ws("${deviceWorkspace}") {
                        setupJfrog(steps, testConfigs)
                        def basePath = commonPipelineLib.getResolvedArtifactBasePath(testConfigs)
                        def platformCfg = testConfigs.ci_config.clone_sdk_code_stage.platforms.raspi
                        def appName = platformCfg.app_to_test ?: testConfigs.ci_config.app_to_test
                        def appPath ="${basePath}/apps/${appName}/${platform}/"

                        steps.echo "Downloading App binaries from ${appPath}"
                        steps.sh """
                            set -e
                            jf rt dl \
                            "${appPath}*" \
                            "./" \
                            --flat=true \
                            --insecure-tls=true
                            chmod +x * || true
                        """
                        def chipBinaryCount = steps.sh(
                            script: "ls Chip-${appName}* 2>/dev/null | wc -l",
                            returnStdout: true
                        ).trim()

                        if (chipBinaryCount == "0")
                            steps.error("App binary missing for ${platform}")

                        steps.echo "App binaries downloaded"

                        // if ( copyBuildArtifact.enabled && !raspiStages.build_firmware.enabled) {
                        //     def configData = steps.readYaml(file: "UpdatedTestConfig.yaml")
                        //     def selectedApp = configData?.ci_config.app_to_test ?: testConfigs.ci_config.app_to_test
                        //     appToTest = "chip-${selectedApp}"
                        //     steps.echo "App to test updated from copied config: ${appToTest}"
                        //     //Update controller/apps sdk sha from Previous job yaml file
                        //     testConfigs.ci_config.apps_sdk_sha = configData?.ci_config.apps_sdk_sha
                        //     testConfigs.ci_config.controller_sdk_sha = configData?.ci_config.controller_sdk_sha
                        // }
                    }
                }catch (Exception e) {
                    copyArtifactsSuccess = false
                    steps.echo "Error occurred during 'Copy and install binaries into ON_NETWORK_DEVICE_NODE' stage: ${e.getMessage()}"
                }
                return [success: copyArtifactsSuccess, deviceWorksSpace: "${deviceRaspiWorkspace}", deviceIPAddress: "${deviceIP}", appToTest: "${appToTest}", updatedTestConfig: testConfigs]
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
}