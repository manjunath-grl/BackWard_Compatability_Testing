import com.matterci.pipelineLib.RaspiPipelineLib
import com.matterci.pipelineLib.RunTests
import com.matterci.pipelineLib.TestParamDefaults
import com.matterci.pipelineLib.RepoUtils
import com.matterci.pipelineLib.commonPipelineLib



/*Ideally we want to build controller and apps in seperate lib but raspi binaries
are to be built with docker. Docker has some issues when called from sharedLib. So
calling the these APIs inside jenkins files.
*/

def buildAndinstallControllerBinaries(testConfigs, workSpace, raspiBinariesDir) {

    def status = 0

    stage('build controller on raspi') {

        def arch = sh(script: "uname -m", returnStdout: true).trim()
        echo "Running on node: ${env.NODE_NAME}"
        echo "Architecture: ${arch}"
        echo "Workspace: ${workSpace}"

        def raspiStages   = testConfigs.ci_config?.raspi_pipeline?.stages
        def isFreshInstall = raspiStages?.build_controller?.fresh_install ?: false
        def deviceIP = steps.sh(script: "hostname -I | awk '{print \$1}'", returnStdout: true).trim()
        def hostname = steps.sh(script: "hostname", returnStdout: true).trim()
        steps.echo "Hostname is: ${hostname}"

        /* ---- Read repo details from YAML ---- */
        def sdkCfg  = testConfigs.ci_config?.clone_sdk_code_stage?.controller_sdk_config
        def repoUrl = sdkCfg?.repoUrl
        def branch  = sdkCfg?.branch
        def WORKDIR = "/home/${hostname}/certification-tool"

        if (!repoUrl || !branch) {
            error("Repo URL or branch not defined in YAML (clone_sdk_code_stage.controller_sdk_config)")
        }

        ws(workSpace) {

            /* ======================================================
             * Fresh Installation (with reboot)
             * ====================================================== */
            if (isFreshInstall) {

                echo "Fresh install using branch: ${branch}"

                def freshInstallCmd = """#!/bin/bash
                set -ex

                WORKDIR="\$HOME/certification-tool"

                if [ -d "\$WORKDIR" ]; then
                    echo "Removing existing certification-tool directory"
                    rm -rf "\$WORKDIR"
                fi
                cd "\$HOME"

                git clone -b "${branch}" "${repoUrl}" --recurse-submodules
                cd "\$WORKDIR"
                # Auto-select option 1 (restart)
                yes 1 | ./scripts/pi-setup/auto-install.sh || true
                """

                // freshInstallCmd = 0

                // status = sh(
                //     script: freshInstallCmd,
                //     returnStatus: true
                // )
            }

            /* ======================================================
             * Update Existing Installation (no reboot expected)
             * ====================================================== */
            else if (!isFreshInstall) {

                echo "Updating certification-tool to branch: ${branch}"

                def updateCmd = """#!/bin/bash
                set -ex

                WORKDIR="\$HOME/certification-tool"

                if [ ! -d "\$WORKDIR" ]; then
                    echo "certification-tool directory not found, update not possible"
                    exit 1
                fi

                cd "\$WORKDIR"
                git fetch
                git checkout "${branch}"
                git pull --recurse-submodules

                echo "Update completed successfully"
                """

                status = sh(
                    script: updateCmd,
                    returnStatus: true
                )
            }
            else {
                echo "build_controller stage disabled"
                status = 0
            }
        }
    }

    return [
        success         : (status == 0),
        status          : status,
        cntrlWorksSpace : WORKDIR
    ]
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

        def raspiStages = testConfigs.ci_config?.raspi_pipeline?.stages
        def docker_image = raspiStages.build_firmware?.docker_image ?:"testing_partof_chip_cert_bins_dockerfile"

        // TODO add swapfile to docker arguments
        def dockerCommands = """#!/bin/bash
            set -ex
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
                scripts/build_python.sh -m platform -d true -i out/python_env -n false
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
                    mv out/python_lib/controller/python/*.whl ../${raspiBinariesDir}
                    mv out/python_lib/obj/src/python_testing/matter_testing_infrastructure/matter-testing._build_wheel/matter_testing-*.whl ../${raspiBinariesDir}
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

def buildApps(testConfigs, testCasesList, workSpace, raspiBinariesDir){

    // Build apps mapping from test config
    def appMapping = [
        "all-clusters-app": [
            "build_app"  : "linux-arm64-all-clusters-ipv6only",
            "output_path": "out/linux-arm64-all-clusters-ipv6only/chip-all-clusters-app",
            "app_name": "chip-all-clusters-app"
        ],
        "lock-app": [
            "build_app"  : "linux-arm64-lock-ipv6only",
            "output_path": "out/linux-arm64-lock-ipv6only/chip-lock-app",
            "app_name": "chip-lock-app"
        ],
        "lighting-app":[
            "build_app":"linux-arm64-light-ipv6only",
            "output_path":"out/linux-arm64-light-ipv6only/chip-lighting-app",
            "app_name": "chip-lighting-app"
        ]
        // Add more app mappings as needed
    ]
    // Get 'app_to_test' is provided from the YAML config
    def appToTest = testConfigs.ci_config.app_to_test

    // Retrieve the build app and output path from the map
    def buildApp = appMapping[appToTest]?.build_app
    def outputPath = appMapping[appToTest]?.output_path
    def appName = appMapping[appToTest]?.app_name

    stage ('build Apps on raspi'){

        def arch = sh(script: "uname -m", returnStdout: true).trim()
        echo "HW arch ${arch}"
        def dockerPlatform = (arch == "x86_64") ? "linux/amd64" : "linux/arm64"
        echo "dockerPlatform arch ${dockerPlatform}"
        echo "This stage Build Apps For Raspi inside Docker is running on: ${env.NODE_NAME}"
        echo "Work space to build Apps : ${workSpace}"


        if (!buildApp || !outputPath) {
            error "No build configuration found for app: ${appToTest}"
        }

        echo "Building app: ${buildApp}"
        echo "Output path: ${outputPath}"
        //pass this value to subsequent stages
        env.app_output_path = "${outputPath}"
        def raspiStages = testConfigs.ci_config?.raspi_pipeline?.stages
        def docker_image = raspiStages.build_firmware?.docker_image ?:"testing_partof_chip_cert_bins_dockerfile"
        def buildAppSucess = true

        // TODO add swapfile to docker arguments
        def dockerCommands = """#!/bin/bash
            set -ex
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
        echo "Docker command used to build App ${dockerCommands}"

        def status = sh(
            script: dockerCommands,
            returnStatus: true
        )

        if (status ==0){
            ws("${workSpace}")
            {
                // since outputpath is not available inside ws block, using the env var app_output_path here.
                ////TODO: we may need to fix this .. path in the below command
                def copyCommand = """#!/bin/bash
                    mv ${env.app_output_path} ../${raspiBinariesDir}
                """
                status = sh(
                    script: copyCommand,
                    returnStatus: true
                )
            }
        }
        if (status != 0)
            buildAppSucess = false

        return [success: buildAppSucess, appToTest: "${appName}"]
    }

}

def call(testConfigs, testCasesList) {
    def buildSuccess = true
    def raspiStages = testConfigs.ci_config?.raspi_pipeline?.stages
    def copyBuildArtifact = testConfigs.ci_config?.copy_build_artifact
    def raspiBinariesDirString = "raspi_binaries"
    def appToTest = "chip-all-clusters-app" //TODO remove this, initializing to test
    def controllerBuildWorkSpace = ''
    def appsBuildWorkSpace = ''
    def logTransferConfig = testConfigs.execution_log_transfer_config

    //TODO: Not scalable, Fix this code to download cloned code in the above step
    if (raspiStages?.build_firmware?.enabled){
        stage('Build For Raspi inside Docker') {
            node(raspiStages.build_firmware.node) {
                try {
                        def sdkFrmArtifactsResult = RepoUtils.getSDKCodeFromBuildArtifacts(this, raspiBinariesDirString)
                        if (sdkFrmArtifactsResult.success) {
                            controllerBuildWorkSpace = sdkFrmArtifactsResult.cntrlBuildWorkSpace
                            appsBuildWorkSpace = sdkFrmArtifactsResult.appsBuildWorkSpace
                        }else{
                            error(" getSDKCodeFromArtifacts failed. Build stopped.")
                        }
                        //only runs if it is connectedhomeip repo
                        if (testConfigs?.ci_config?.clone_sdk_code_stage?.controller_sdk_config?.controller_repo == "connectedhomeip") {
                            def buildCntrlResult = buildController(testConfigs, testCasesList, controllerBuildWorkSpace,raspiBinariesDirString)
                            if (buildCntrlResult != 0) {
                                buildSuccess = false
                                error("Building Controller failed with status ${status}")
                            }
                        }

                        def buildAppResult = buildApps(testConfigs, testCasesList, appsBuildWorkSpace, raspiBinariesDirString)
                        if (!buildAppResult.success)
                            error("Building Apps failed with status ${status}")
                        else {
                            appToTest = buildAppResult.appToTest
                            //looks like we need to store appToTest in env to access it parallel block otherwise its having null value
                            env.appToTest = buildAppResult.appToTest
                            echo "app to test : ${appToTest}"
                        }
                        // just get into the parent directory of raspi_binaries and upload it.
                        ws("${sdkFrmArtifactsResult.workSpaceSDKCopied}") {
                            // Archive all contents inside the current directory (which should be raspi_binaries)
                            archiveArtifacts artifacts: "${raspiBinariesDirString}/**", fingerprint: true, allowEmptyArchive: true
                        }
                }catch (Exception e) {
                    buildSuccess = false
                    echo "Error occurred during 'Build For Raspi inside Docker' stage: ${e.getMessage()}"
                    error("Pipeline failed in 'Build For Raspi inside Docker' stage.")
                }
            }
        }
    }
    if (raspiStages.run_tests.enabled) {
        stage('Run Automated Tests on Raspi') {
            def cntlWorkSpace = ''
            def cntrlNode = ''
            def deviceNode = ''
            def deviceNodeIPAddress = ''

            stage ('Get nodes of controller and device raspi') {
                def result = RaspiPipelineLib.getCntrlDeviceRaspiNodes(this, "On-Network", testConfigs)
                if (!result.success)
                    error("Get nodes of controller and device raspi failed for on-network")
                else {
                    cntrlNode = result.nodesAllocated["controllerNode"]
                    deviceNode = result.nodesAllocated["deviceNode"]
                }
            }
            //Runs only if it is certification-tool repo
            if (testConfigs?.ci_config?.clone_sdk_code_stage?.controller_sdk_config?.controller_repo == "certification-tool"){
                stage ('Copy and install binaries into ON_NETWORK_RASPI_CONTROLLER_NODE'){
                    node("${cntrlNode}"){
                        controllerBuildWorkSpace = "${env.WORKSPACE}/controller_sdk"
                        echo "Controller build work space : ${controllerBuildWorkSpace}"
                        def result = buildAndinstallControllerBinaries(testConfigs, controllerBuildWorkSpace, raspiBinariesDirString)
                        if (!result.success)
                            error("Copy and install binaries into ON_NETWORK_RASPI_CONTROLLER_NODE failed")
                        else
                            cntlWorkSpace = result.cntrlWorksSpace
                    }
                }
            }
            //else runs if it is connectedhomeip repo
            else{
                stage ('Copy and install binaries into ON_NETWORK_RASPI_CONTROLLER_NODE'){
                    node("${cntrlNode}"){
                        def result = commonPipelineLib.installControllerBinaries(this, testConfigs, raspiBinariesDirString)
                        if (!result.success)
                            error("Copy and install binaries into ON_NETWORK_RASPI_CONTROLLER_NODE failed")
                        else
                            cntlWorkSpace = result.cntrlWorksSpace
                    }
                }
            }
            stage ('Copy and install binaries into ON_NETWORK_DEVICE_NODE'){
                def result = RaspiPipelineLib.installDeviceBinaries(this, testConfigs, deviceNode, "On-Network")
                if (!result.success)
                    error("Copy and install binaries into ON_NETWORK_DEVICE_NODE failed ")
                else {
                        deviceNodeIPAddress = result.deviceIPAddress
                        deviceWorkSpace = result.deviceWorksSpace
                        if (copyBuildArtifact.enabled && !raspiStages.build_firmware.enabled) {
                            env.appToTest = result.appToTest
                            testConfigs = result.updatedTestConfig
                            echo "Updated testConfigs : ${testConfigs}"
                        }
                    }
            }
            // stage ('Run Tests on ON_NETWORK_RASPI_CONTROLLER_NODE') {
            //     echo "Run Tests"
            //     node (cntrlNode) {
            //         echo "on-network controller workspace is : ${cntlWorkSpace}"
            //         def localTestParams = RaspiPipelineLib.initRaspiOnNetworkTestParams(this, testConfigs, cntlWorkSpace, deviceWorkSpace, deviceNodeIPAddress, env.appToTest)
            //         def raspi_onnetwork = new RunTests()
            //         raspi_onnetwork.runTests(this, cntlWorkSpace,localTestParams,testCasesList)
            //     }
            // }
            if (logTransferConfig?.enableLogsTransfer && logTransferConfig?.storageServerNode && logTransferConfig?.storageServerPath) {
                stage ('Transfer Logs to server storage') {
                    def verifySucessTransfer = commonPipelineLib.transferLogsToStorageServer(
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
                    if (!verifySucessTransfer.success) {
                        error "Log transfer to the storage server failed after retries. Logs are still at: ${verifySucessTransfer.location}"
                    } else {
                                echo "Log transfer to the storage server completed successfully at: ${verifySucessTransfer.location}"
                    }
                }
            }
        }
    }
}