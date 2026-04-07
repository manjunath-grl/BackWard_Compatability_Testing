import com.matterci.pipelineLib.RaspiPipelineLib
import com.matterci.pipelineLib.RunTests
import com.matterci.pipelineLib.TestParamDefaults
import com.matterci.pipelineLib.RepoUtils
import com.matterci.pipelineLib.commonPipelineLib



/*Ideally we want to build controller and apps in seperate lib but raspi binaries
are to be built with docker. Docker has some issues when called from sharedLib. So
calling the these APIs inside jenkins files.
*/

def waitForNodeAfterReboot(int timeoutMinutes = 10) {
    echo "Waiting for node to come back after reboot..."

    timeout(time: timeoutMinutes, unit: 'MINUTES') {
        waitUntil {
            try {
                sh(script: "echo node-up", returnStatus: true) == 0
            } catch (e) {
                sleep 10
                return false
            }
        }
    }
    echo "Node is back online"
}


def extractDockerArtifacts(def steps, String imageSha, String certBinariesWorkspace) {
    def containerName = "chip-cert-temp"
    try {

        sh """
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


def buildAndinstallCertBinaries(def steps,Map testConfigs,String workSpace,String raspiBinariesDir,String artifactType,Map appConfig = null) {
    boolean buildSuccess = false
    def status = 0
    def homedir = ""
    def raspiStages = testConfigs.ci_config?.raspi_pipeline?.stages
    def controllerCfg = testConfigs.ci_config?.clone_sdk_code_stage?.controller_sdk

    def repoUrl = controllerCfg?.repoUrl ?: "git@github.com:project-chip/certification-tool.git"
    def branch  = controllerCfg?.branch

    def certBinariesWorkspace = "${steps.env.WORKSPACE}/${steps.env.BUILD_NUMBER}/copied_cert_binaries"

    def hostname = steps.sh(script: "hostname",returnStdout: true).trim()

    steps.echo "Artifact type: ${artifactType}"

    //def WORKDIR = "/home/${hostname}/certification-tool"
    homedir = "/home/${hostname}"
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

            //load controller binaries
            if ( artifactType == "CTRL" ) {
                //tract controller + accessory binaries from docker
                imageSha = commonPipelineLib.resolveCertDockerSha(testConfigs)
                extractDockerArtifacts(this, imageSha, certBinariesWorkspace)
                steps.echo "Uploading certification-tool controller binaries"
                commonPipelineLib.uploadControllerBinary(steps,testConfigs,"raspi",certBinariesWorkspace)

                //Clone Matter-QA repo (required for test execution)
                def matterCloneStatus = RepoUtils.cloneMatterQARepo(steps,testConfigs,"main",certBinariesWorkspace,"controller")
                if (matterCloneStatus != 0)
                    throw new Exception("Matter-QA clone failed")
            }

            //load accessory binary
            if ( artifactType == "DUT" ) {
                steps.echo "Uploading certification-tool accessory: ${appConfig.name}"
                commonPipelineLib.uploadAppBinary(steps,testConfigs,"raspi",dutBinariesPath,appConfig.name,appConfig.branch,appConfig.sha,appConfig.tag,appConfig.pr)
            }

            buildSuccess = true
        }
    }
    catch (Exception e) {
        buildSuccess = false
        steps.echo(
            "Certification-tool build failed: ${e.getMessage()}"
        )
    }
    return [success: buildSuccess,cntrlWorksSpace: homedir]
}


def buildController(testConfigs, testCasesList, workSpace, raspiBinariesDir){

    stage ('build controller on raspi'){

        def arch = sh(script: "uname -m", returnStdout: true).trim()
        echo "HW arch ${arch}"
        def dockerPlatform = (arch == "x86_64") ? "linux/amd64" : "linux/arm64"
        echo "dockerPlatform arch ${dockerPlatform}"
        echo "This stage Build For Raspi inside Docker is running on: ${env.NODE_NAME}"
        echo "Work space to build controller : ${workSpace}"
        echo "raspi binaries copied into ${raspiBinariesDir}"
        def binariesStorePath = "${raspiBinariesDir}/controller"
        echo "Controller binaries will be stored in ${binariesStorePath}"

        def raspiStages = testConfigs.ci_config?.raspi_pipeline?.stages
        def docker_image = raspiStages.build_firmware?.docker_image ?:"testing_partof_chip_cert_bins_dockerfile"

        // TODO add swapfile to docker arguments
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
                pw cli-analytics --opt-out
                source scripts/activate.sh
                # TODO: -n false is a temporary workaround needs to be updated it to dynamic bases on the configuration.
                scripts/build_python.sh -m platform -d true -i out/python_env -n false -M false
            \"
        """
        echo "Docker command used to build App ${dockerCommands}"

        def status = sh(
            script: dockerCommands,
            returnStatus: true
        )
        //TODO: we may need to fix this .. path in the below command
        if (status ==0){
            ws("${workSpace}")
            {
                def copyCommand = """#!/bin/bash
                    set -ex
                    mkdir -p ../${binariesStorePath}
                    mv out/python_lib/controller/python/*.whl ../${binariesStorePath}
                    mv out/python_lib/obj/src/python_testing/matter_testing_infrastructure/matter-testing._build_wheel/matter_testing-*.whl ../${binariesStorePath}
                """
                def cmdStatus = sh(
                    script: copyCommand,
                    returnStatus: true
                )

                return cmdStatus
            }
        }
        return status
    }
}

def call(testConfigs, testCasesList) {
    def buildSuccess = true
    def raspiStages = testConfigs.ci_config?.raspi_pipeline?.stages
    def raspiBinariesDirString = "raspi_binaries"
    def appToTest = "chip-all-clusters-app" //TODO remove this, initializing to test
    def controllerBuildWorkSpace = ''
    def appsBuildWorkSpace = ''
    def logTransferConfig = testConfigs.execution_log_transfer_config
    def decision = testConfigs.ci_config.artifactDecision
    def raspiDecision = decision.platforms["raspi"]
    def platformCfg =testConfigs.ci_config.clone_sdk_code_stage.platforms.raspi
    def controllerBranch =testConfigs.ci_config.clone_sdk_code_stage.controller_sdk.branch
    def controllerRepo =commonPipelineLib.resolveRepo(controllerBranch)
    def controllerMissing =raspiDecision.controllerMissing
    def appStorePath = ''
    def binariesStorePath = ''

    def controllerBuilt = false
    echo "Controller Repo : ${controllerRepo}"
    echo "Controller Missing : ${controllerMissing}"

    def connectedhomeipAppsMissing =
        raspiDecision.apps.any {
            it.missing && it.repo == "connectedhomeip"
        }
    def certificationAppsMissing =
        raspiDecision.apps.any {
            it.missing && it.repo == "certification-tool"
        }
    
    echo """
            Controller Repo: ${controllerRepo}
            Controller Missing: ${raspiDecision.controllerMissing}
            Certification Apps Missing: ${certificationAppsMissing}
            Controller Built Earlier: ${controllerBuilt}
      """

    if (( controllerMissing && controllerRepo == "connectedhomeip" ) || connectedhomeipAppsMissing) {
        stage('Build For Raspi inside Docker') {
            node(raspiStages.build_firmware.node) {
                try {
                    def sdkFrmArtifactsResult =RepoUtils.getSDKCodeFromBuildArtifacts(this,raspiBinariesDirString)
                    if (!sdkFrmArtifactsResult.success)
                        error("SDK artifact retrieval failed")
                    controllerBuildWorkSpace = sdkFrmArtifactsResult.cntrlBuildWorkSpace
                    appsBuildWorkSpace = sdkFrmArtifactsResult.appsBuildWorkSpace

                    appStorePath = "${appsBuildWorkSpace}/../${raspiBinariesDirString}/apps"
                    binariesStorePath = "${controllerBuildWorkSpace}/../${raspiBinariesDirString}/controller"

                    //BUILD CONTROLLER (connectedhomeip only)
                    if (controllerRepo == "connectedhomeip" && controllerMissing ) {
                        def buildCntrlResult = buildController(testConfigs,testCasesList,controllerBuildWorkSpace,binariesStorePath)
                        if (buildCntrlResult != 0)
                            error("Controller build failed")

                        controllerBuilt = true
                        commonPipelineLib.uploadControllerBinary(this,testConfigs,"raspi",binariesStorePath)
                    }

                    //BUILD APPS (connectedhomeip only)
                    raspiDecision.apps.each { app ->
                        if (!app.missing)
                            return
                        if (app.repo != "connectedhomeip")
                            return
                        echo "Building connectedhomeip app: ${app.name}"

                        RepoUtils.checkoutGitRef(this,appsBuildWorkSpace,app.branch,app.sha,app.tag,app.pr)
                        //app.sha = resolvedSha
                        def buildAppResult = RaspiPipelineLib.buildApps(this,testConfigs,testCasesList,appsBuildWorkSpace,appStorePath,app.name)

                        if (!buildAppResult.success)
                            error("App build failed: ${app.name}")

                        commonPipelineLib.uploadAppBinary(this,testConfigs,"raspi",appStorePath,app.name,app.branch)
                    }
                } catch (Exception e) {
                    buildSuccess = false
                    echo "Docker build stage failed: ${e.getMessage()}"
                    error("Pipeline failed during docker build stage")
                }
            }
        }
    }
    if (raspiStages.run_tests.enabled) {
        stage('Install binaries and Run tests on Raspi') {
            def cntlWorkSpace = ''
            def cntrlNode = ''
            def deviceNode = ''
            def deviceNodeIPAddress = ''
            def deviceWorkSpace = ''

            def certificationControllerMissing = controllerRepo == "certification-tool" && raspiDecision.controllerMissing

            stage('Get nodes of controller and device raspi') {
                def result = RaspiPipelineLib.getCntrlDeviceRaspiNodes(this,"On-Network",testConfigs)

                if (!result.success)
                    error("Get nodes of controller and device raspi failed")

                cntrlNode = result.nodesAllocated["controllerNode"]
                deviceNode = result.nodesAllocated["deviceNode"]
            }

            //Certification-tool build (controller + apps)
            if (controllerRepo == "certification-tool" && raspiDecision.controllerMissing ) {
                stage('Build certification-tool controller binaries') {

                    node("${cntrlNode}") {
                        controllerBuildWorkSpace ="${env.WORKSPACE}/controller_sdk"
                        echo "Building certification-tool controller"
                        def result = buildAndinstallCertBinaries(this, testConfigs, controllerBuildWorkSpace, raspiBinariesDirString, "CTRL")
                        if (!result.success)
                            error("Certification-tool controller build failed")

                        cntlWorkSpace = result.cntrlWorksSpace
                    }
                }
            }

            //Build certification-tool accessories (loop)
            raspiDecision.apps.each { app ->

                if (!app.missing)
                    return

                if (app.repo != "certification-tool")
                    return

                stage("Build certification-tool app: ${app.name}") {
                    node("${deviceNode}") {
                        deviceBuildWorkSpace ="${env.WORKSPACE}/controller_sdk"
                        echo "Building certification-tool accessory: ${app.name}"
                        def result = buildAndinstallCertBinaries(this, testConfigs, deviceBuildWorkSpace, raspiBinariesDirString, "DUT" )
                        if (!result.success)
                            error("Certification-tool app build failed: ${app.name}")
                    }
                }
            }

            //Install controller binaries
            if (!raspiDecision.controllerMissing || controllerBuilt ) {
                stage('Install controller binaries into controller node') {

                    node("${cntrlNode}") {
                        def result = commonPipelineLib.installControllerBinaries(this,testConfigs,"raspi",raspiBinariesDirString)
                        if (!result.success)
                            error("Controller install failed")

                        cntlWorkSpace = result.cntrlWorksSpace
                    }
                }
            }

            //Install ALL DUT binaries after ALL builds complete
            stage('Install DUT binaries into DEVICE_NODE') {
                node("${deviceNode}") {
                    def result =RaspiPipelineLib.installDeviceBinaries(this,testConfigs,deviceNode,"On-Network")
                    if (!result.success)
                        error("Device binary install failed")

                    deviceNodeIPAddress = result.deviceIPAddress
                    deviceWorkSpace = result.deviceWorksSpace
                }
            }
            stage('Run Tests on RASPI_CONTROLLER_NODE') {
                node(cntrlNode) {
                    echo "controller workspace: ${cntlWorkSpace}"
                    def testrun = new RunTests()
                    def ctrlPath = "${cntlWorkSpace}"

                    raspiDecision.apps.findAll { !it.missing }.each { app ->
                        def refFolder = app.sha ?: app.tag ?: (app.pr ? "PR-${app.pr}" : app.branch)
                        def refPath = "${deviceWorkSpace}/${refFolder}/chip-${app.name}"
                        echo "Running tests for app: ${app.name}"
                        echo "Reference folder: ${refFolder}"

                        def localTestParams = RaspiPipelineLib.initRaspiOnNetworkTestParams(this,testConfigs,cntlWorkSpace,deviceWorkSpace,deviceNodeIPAddress,refPath)
                        def runnerConfigPath = "${cntlWorkSpace}/runnerConfig_${refFolder}_${app.name}.yaml"
                        def mergedYaml = writeYaml(returnText: true, data: localTestParams)
                        writeFile(file: runnerConfigPath,text: mergedYaml)

                        testrun.runTests(this, ctrlPath, runnerConfigPath, app.name)
                    }
                }
            }

            //Transfer logs
            if (logTransferConfig?.enableLogsTransfer &&logTransferConfig?.storageServerNode &&logTransferConfig?.storageServerPath) {
                stage('Transfer Logs to server storage') {
                    def verifySucessTransfer =commonPipelineLib.transferLogsToStorageServer(
                            this,
                            [
                                nodeName: cntrlNode,
                                storageServerNode: logTransferConfig.storageServerNode,
                                storageServerPath: logTransferConfig.storageServerPath,
                                jobName: env.JOB_NAME,
                                buildId: env.BUILD_ID,
                                logType: "RASPI-ON-NETWORK"
                            ],
                            logTransferConfig
                        )
                    if (!verifySucessTransfer.success)
                        error("Log transfer failed. Logs remain at: " +verifySucessTransfer.location)
                    echo "Logs transferred successfully: ${verifySucessTransfer.location}"
                }
            }
        }
    }
}