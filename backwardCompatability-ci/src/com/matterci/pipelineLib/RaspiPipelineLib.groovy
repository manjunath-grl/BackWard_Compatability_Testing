/* groovylint-disable LineLength */
package com.matterci.pipelineLib

import com.matterci.pipelineLib.TestUtils
import com.matterci.pipelineLib.RepoUtils
import com.matterci.pipelineLib.commonPipelineLib
import com.matterci.pipelineLib.JfrogUtils
import com.matterci.pipelineLib.CertificationToolCatalog

import groovy.json.JsonOutput

class RaspiPipelineLib implements Serializable {

    static def listOfUsedRaspis
    static def listOfAvaialbelRaspis
    static def raspiBinariesDirString
    static {
        listOfUsedRaspis = []
        listOfAvaialbelRaspis = []
        raspiBinariesDirString = "raspi_binaries"
    }

    static getCntrlDeviceRaspiNodes(def steps, Map testConfigs){
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
                steps.echo "Selected Controller Node: ${allocatedNodes["controllerNode"]}"
                steps.echo "Selected Device Node: ${allocatedNodes["deviceNode"]}"
            }
        } catch (Exception e) {
            getNodesAssigned = false
            steps.echo "Error occurred during 'Get nodes of controller and device raspi' stage: ${e.getMessage()}"
        }
        return [success: getNodesAssigned, nodesAllocated: allocatedNodes]
    }

    static installDeviceBinaries(def steps,Map testConfigs,String nodeName) {
        def copyArtifactsSuccess = true
        def deviceIP = ''
        def deviceRaspiWorkspace = ''
        def refFolder = ''
        def targetDir = ''
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
                steps.echo "Device IP: ${deviceIP}"
                // steps.echo "${stageName} Device IP: ${deviceIP}"
                deviceRaspiWorkspace = "${steps.env.WORKSPACE}/${steps.env.BUILD_NUMBER}/copied_device_binaries"

                steps.echo "Device workspace: ${deviceRaspiWorkspace}"
                def raspiDecision = testConfigs.ci_config.artifactDecision.platforms.raspi
                steps.ws(deviceRaspiWorkspace) {
                    raspiDecision.apps.each { app ->
                        steps.echo "Downloading binary: ${app.name}"

                        def basePath = JfrogUtils.getResolvedArtifactBasePath(testConfigs,"apps",app.branch,app.sha,app.tag,app.pr)
                            // Download into a ref-specific folder so one controller run can test multiple DUT refs safely.
                        def jfrogPath = "${basePath}/apps/${app.name}/raspi/${app.name}"

                        steps.echo "JFrog path: ${jfrogPath}"
                        refFolder = app.sha ?: app.tag ?: (app.pr ? "PR-${app.pr}" : app.branch)
                        targetDir = "${deviceRaspiWorkspace}/${refFolder}"
                        steps.sh "mkdir -p ${targetDir}"
                        steps.dir(targetDir) {
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
                                ls ${targetDir}/${app.name}* \
                                2>/dev/null | wc -l
                            """,
                            returnStdout: true
                        ).trim()
                        if (binaryCount == "0") {
                            steps.error("Binary missing for ${app.name}")
                        }
                        steps.echo("Downloaded binary for ${app.name}")
                    }
                }
            }
            catch (Exception e) {
                copyArtifactsSuccess = false
                steps.echo("Device binary install failure: ${e.getMessage()}")
            }
            return [success: copyArtifactsSuccess,deviceWorksSpace: deviceRaspiWorkspace,deviceIPAddress: deviceIP,updatedTestConfig: testConfigs]
        }
    }

    static Map buildApps(def steps,Map testConfigs,List testCasesList,String workSpace,String raspiBinariesDir,String appName) {
        def appMapping = [
            "chip-all-clusters-app": [
                build_app  : "linux-arm64-all-clusters-ipv6only",
                output_path: "out/linux-arm64-all-clusters-ipv6only/chip-all-clusters-app",
                app_name   : "chip-all-clusters-app"
            ],
            "chip-lock-app": [
                build_app  : "linux-arm64-lock-ipv6only",
                output_path: "out/linux-arm64-lock-ipv6only/chip-lock-app",
                app_name   : "chip-lock-app"
            ],
            "chip-lighting-app":[
                build_app  : "linux-arm64-light-ipv6only",
                output_path: "out/linux-arm64-light-ipv6only/chip-lighting-app",
                app_name   : "chip-lighting-app"
            ]
        ]

        if (!appMapping.containsKey(appName))
            steps.error("Unsupported app: ${appName}")

        def buildApp   = appMapping[appName].build_app
        def outputPath = appMapping[appName].output_path
        def binaryName = appMapping[appName].app_name

        // raspiBinariesDir already = raspi_binaries/apps
        def appStorePath = "${raspiBinariesDir}/${appName}"

        steps.echo "Building ${appName}"
        steps.echo "Expected output path → ${outputPath}"
        steps.echo "Target storage path → ${appStorePath}"

        def arch = steps.sh(script: "uname -m", returnStdout: true).trim()
        def dockerPlatform = (arch == "x86_64") ? "linux/amd64" : "linux/arm64"
        def dockerImage = testConfigs.ci_config.raspi_pipeline.stages.build_firmware.docker_image

        def dockerCommands = """
            set -ex
            export PATH=/usr/local/bin:\\\$PATH

            docker run --rm \
            --user root \
            --platform=${dockerPlatform} \
            -v ${workSpace}:/home/connectedhome \
            -w /home/connectedhome \
            ${dockerImage}:latest \
            /bin/bash -c "
                set -ex

                git config --global --add safe.directory /home/connectedhome
                git config --global --add safe.directory /home/connectedhome/third_party/pigweed/repo

                ./scripts/checkout_submodules.py --allow-changing-global-git-config --shallow --platform linux
                source scripts/bootstrap.sh
                source scripts/activate.sh
                scripts/build/build_examples.py --target ${buildApp} build

                echo 'Binary inside container:'
                ls -la ${outputPath}
            "
        """
        steps.echo "Executing docker build for ${appName}"

        def status = steps.sh(
            script: dockerCommands,
            returnStatus: true
        )
        if (status != 0)
            return [success:false]

        //Verify binary exists in workspace
        steps.ws(workSpace) {
            steps.sh """
                echo "Verifying binary after docker exit"
                ls -la ${outputPath}
            """
        }
        //Move binary into raspi_binaries/apps/<appName>/
        steps.ws(workSpace) {
            steps.sh """
                mkdir -p ${appStorePath}

                mv ${outputPath} ${appStorePath}/
                echo "Final artifact location:"
                ls -R ${raspiBinariesDir}
            """
        }
        return [success : true, appToTest : binaryName]
    }

    static def buildController(def steps, Map testConfigs, List testCasesList, String workSpace, String raspiBinariesDir){
        def arch = steps.sh(script: "uname -m", returnStdout: true).trim()
        steps.echo "HW arch ${arch}"
        def dockerPlatform = (arch == "x86_64") ? "linux/amd64" : "linux/arm64"
        steps.echo "dockerPlatform arch ${dockerPlatform}"
        steps.echo "This stage Build For Raspi inside Docker is running on: ${steps.env.NODE_NAME}"
        steps.echo "Work space to build controller : ${workSpace}"
        steps.echo "raspi binaries copied into ${raspiBinariesDir}"
        def binariesStorePath = "${raspiBinariesDir}"
        steps.echo "Controller binaries will be stored in ${binariesStorePath}"

        def raspiStages = testConfigs.ci_config?.raspi_pipeline?.stages
        def docker_image = raspiStages.build_firmware?.docker_image ?:"testing_partof_chip_cert_bins_dockerfile"
        // Keep the current behavior as default, but allow Jenkins/YAML to override build flags.
        def buildPythonArgs = commonPipelineLib.getBuildPythonArgs(testConfigs, "-d true -n false -M false")

        def dockerCommands = """#!/bin/bash
            set -ex

            export PATH=/usr/local/bin:\\\$PATH
            echo "PATH=\$PATH"

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
            pw cli-analytics --opt-out
            source scripts/activate.sh
            scripts/build_python.sh -m platform -i out/python_env ${buildPythonArgs}
            \"
        """
        steps.echo "Docker command used to build App ${dockerCommands}"

        def status = steps.sh(
            script: dockerCommands,
            returnStatus: true
        )
        if (status ==0){
            steps.ws("${workSpace}"){
                def copyCommand = """#!/bin/bash
                    set -ex
                    mkdir -p ../${binariesStorePath}
                    mv out/python_lib/controller/python/*.whl ../${binariesStorePath}
                    mv out/python_lib/obj/src/python_testing/matter_testing_infrastructure/matter-testing._build_wheel/matter_testing-*.whl ../${binariesStorePath}
                """
                def cmdStatus = steps.sh(
                    script: copyCommand,
                    returnStatus: true
                )
                return cmdStatus
            }
        }
        return status
    }

    static def buildAndinstallCertBinaries(def steps, Map testConfigs, String workSpace, String raspiBinariesDir, String artifactType, Map appConfig = null) {
        boolean buildSuccess = false
        def status = 0
        def controllerCfg = testConfigs.ci_config?.clone_sdk_code_stage?.controller_sdk

        def repoUrl = controllerCfg?.repoUrl ?: "git@github.com:project-chip/certification-tool.git"
        def branch  = controllerCfg?.branch
        def certBinariesWorkspace = "${steps.env.WORKSPACE}/${steps.env.BUILD_NUMBER}/copied_cert_binaries"
        def hostname = steps.sh(script: "hostname",returnStdout: true).trim()
        steps.echo "Artifact type: ${artifactType}"

        def homedir = "\$HOME"
        def imageSha = ''
        try {
            steps.ws(workSpace) {
                steps.echo "Preparing certification-tool repo"
                status = steps.sh(
                    script: """
                        set -ex
                        export PATH="\$HOME/.local/bin:\$PATH"
                        sudo docker ps -q | xargs -r sudo docker kill
                        if [ ! -d certification-tool ]; then
                            git clone -b "${branch}" "${repoUrl}" --recurse-submodules certification-tool
                        fi

                        cd certification-tool
                        git fetch
                        git checkout "${branch}"
                        git pull --recurse-submodules
                        yes 1 | ./scripts/pi-setup/auto-install.sh || true

                    """,
                    returnStatus: true
                )

                if (status != 0)
                    throw new Exception("certification-tool checkout failed")

                def dutBinariesPath = "${homedir}/apps"

                // certification-tool controller binaries are extracted from the release docker image and then uploaded to JFrog.
                if ( artifactType == "CTRL" ) {
                    imageSha = CertificationToolCatalog.getImageSha(branch)
                    testConfigs.ci_config.controller_sdk_sha = imageSha
                    extractDockerArtifacts(steps, imageSha, certBinariesWorkspace)
                    steps.echo "Uploading certification-tool controller binaries"
                    JfrogUtils.uploadControllerBinary(steps,testConfigs,"raspi",certBinariesWorkspace)
                }

                // certification-tool DUT binaries are already produced on the device node under $HOME/apps.
                if ( artifactType == "DUT" ) {
                    steps.echo "Uploading certification-tool accessory: ${appConfig.name}"
                    JfrogUtils.uploadAppBinary(steps,testConfigs,"raspi",dutBinariesPath,appConfig.name,appConfig.branch,appConfig.sha,appConfig.tag,appConfig.pr)
                }
                buildSuccess = true
            }
        }
        catch (Exception e) {
            buildSuccess = false
            steps.echo("Certification-tool build failed: ${e.getMessage()}")
        }
        return [success: buildSuccess,cntrlWorksSpace: homedir, testConfigs: testConfigs]
    }

    static Map initRaspiOnNetworkTestParams(def steps, Map testConfigs, String cntrlWorkSpace, String deviceWorkSpace, String deviceNodeIPAddress, String appToTest) {
        steps.echo "cntrl workspace passed : ${cntrlWorkSpace}"
        steps.echo "device workspace passed : ${deviceWorkSpace}"
        def localTestParams = TestUtils.deepCopy(testConfigs)
        steps.echo "local Test Params before updating : ${localTestParams}"
        steps.echo "TestConfigs : ${testConfigs}"
        steps.echo "Discriminator used : ${testConfigs.Testcase_runner_config.dut_config.rpi.app_config.discriminator}"
        localTestParams.ci_config.ci_ws_path = "${cntrlWorkSpace}"
        TestUtils.updateOrCreateKeyValue(localTestParams, "Testcase_runner_config.dut_config.rpi.rpi_hostname", "${deviceNodeIPAddress}")
        TestUtils.updateOrCreateKeyValue(localTestParams, "Testcase_runner_config.dut_config.rpi.app_config.discriminator", testConfigs.Testcase_runner_config.dut_config.rpi.app_config.discriminator)
        TestUtils.updateOrCreateKeyValue(localTestParams, "Testcase_runner_config.dut_config.rpi.app_config.matter_app", "${appToTest}")

        //Override commissioning_arg with log-path
        //String updatedCommissioningArg ="${localTestParams.Testcase_runner_config.commissioning_arg} " +"--logs-path ${cntrlWorkSpace}/logs"
        //TestUtils.updateOrCreateKeyValue(localTestParams,"Testcase_runner_config.commissioning_arg",updatedCommissioningArg)

        //steps.echo "Updated commissioning_arg : ${updatedCommissioningArg}"
        def test_params_json = JsonOutput.toJson(localTestParams)
        steps.echo "JSON params ${test_params_json}"
        return localTestParams
    }

    static def extractDockerArtifacts(def steps, String imageSha, String certBinariesWorkspace) {
        def containerName = "chip-cert-temp"
        try {
            steps.sh """
                set -ex
                BASE_DIR="${certBinariesWorkspace}/controller"
                echo "Preparing controller artifact directory: \$BASE_DIR"
                docker rm -f ${containerName} || true

                rm -rf "\$BASE_DIR"
                mkdir -p "\$BASE_DIR"
                mkdir -p "\$BASE_DIR/python_scripts"
                echo "Launching chip-cert-bins:${imageSha}"

                docker run --name ${containerName} -dit \
                    connectedhomeip/chip-cert-bins:${imageSha} bash

                echo "Copying controller wheels"
                docker cp ${containerName}:/root/python_lib/controller/python/. "\$BASE_DIR/" || true
                docker cp ${containerName}:/root/python_lib/obj/src/python_testing/matter_testing_infrastructure/matter-testing._build_wheel/. "\$BASE_DIR/" || true
                docker cp ${containerName}:/root/python_lib/python/obj/scripts/py_matter_idl/matter-idl._build_wheel/. "\$BASE_DIR/" || true
                docker cp ${containerName}:/root/python_lib/python/obj/scripts/py_matter_yamltests/matter-yamltests._build_wheel/. "\$BASE_DIR/" || true
                docker cp ${containerName}:/root/python_lib/obj/scripts/matter_yamltests_distribution._build_wheel/. "\$BASE_DIR/" || true
                docker cp ${containerName}:/root/python_testing/scripts/sdk/. "\$BASE_DIR/python_scripts/" || true
                docker rm -f ${containerName} || true
            """
            steps.echo "Docker artifact extraction successful"
        }
        catch (Exception e) {
            steps.echo("Docker artifact extraction failed: ${e.getMessage()}")
            steps.error("Failed extracting controller artifacts from chip-cert-bins:${imageSha}")
        }
    }
}
