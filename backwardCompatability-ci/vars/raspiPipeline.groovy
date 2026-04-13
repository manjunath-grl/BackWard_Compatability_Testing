import com.matterci.pipelineLib.RaspiPipelineLib
import com.matterci.pipelineLib.RunTests
import com.matterci.pipelineLib.RepoUtils
import com.matterci.pipelineLib.commonPipelineLib
import com.matterci.pipelineLib.JfrogUtils


def call(testConfigs, testCasesList) {
    def buildSuccess = true
    def raspiStages = testConfigs.ci_config?.raspi_pipeline?.stages
    def raspiBinariesDirString = "raspi_binaries"
    def controllerBuildWorkSpace = ''
    def appsBuildWorkSpace = ''
    def logTransferConfig = testConfigs.execution_log_transfer_config
    def decision = testConfigs.ci_config.artifactDecision
    def raspiDecision = decision.platforms["raspi"]
    def controllerBranch = testConfigs.ci_config.clone_sdk_code_stage.controller_sdk.branch
    def controlleRepo = commonPipelineLib.resolveRepo(controllerBranch)
    def appStorePath = ''
    def binariesStorePath = ''
    def binaryUploadPath = ''

    def controllerBuilt = false
    def controllerMissing = decision.platforms.values().any {it.controllerMissing && it.controllerRepo == "connectedhomeip"}
    echo "Controller Repo : ${controlleRepo}"
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
            Controller Repo: ${controlleRepo}
            Controller Missing: ${raspiDecision.controllerMissing}
            Certification Apps Missing: ${certificationAppsMissing}
            Controller Built Earlier: ${controllerBuilt}
      """

    if ( controllerMissing || connectedhomeipAppsMissing ) {
        stage('Build For Raspi inside Docker') {
            node(raspiStages.build_firmware.node) {
                try {
                    def sdkFrmArtifactsResult =RepoUtils.getSDKCodeFromBuildArtifacts(this,raspiBinariesDirString)
                    if (!sdkFrmArtifactsResult.success)
                        error("SDK artifact retrieval failed")
                    controllerBuildWorkSpace = sdkFrmArtifactsResult.cntrlBuildWorkSpace
                    appsBuildWorkSpace = sdkFrmArtifactsResult.appsBuildWorkSpace

                    appStorePath = "${appsBuildWorkSpace}/../${raspiBinariesDirString}/apps"
                    binariesStorePath = "${raspiBinariesDirString}/controller"
                    binaryUploadPath = "${controllerBuildWorkSpace}/../${raspiBinariesDirString}"

                    // Build the connectedhomeip controller once, then reuse the uploaded wheels during install.
                    stage ('Build Controller on raspi'){
                        if ( controllerMissing ) {
                            def buildCntrlResult = RaspiPipelineLib.buildController(this,testConfigs,testCasesList,controllerBuildWorkSpace,binariesStorePath)
                            if (buildCntrlResult != 0)
                                error("Controller build failed")

                            controllerBuilt = true
                            JfrogUtils.uploadControllerBinary(this,testConfigs,"raspi",binaryUploadPath)
                        }
                    }

                    // Each DUT app may point to a different SDK ref, so checkout/build/upload runs per accessory.
                    stage ('Build Apps on raspi'){
                        raspiDecision.apps.each { app ->
                            if (!app.missing)
                                return
                            if (app.repo != "connectedhomeip")
                                return
                            echo "Building connectedhomeip app: ${app.name}"

                            RepoUtils.checkoutGitRef(this,appsBuildWorkSpace,app.branch,app.sha,app.tag,app.pr)
                            stage ('Building app: ' + app.name){
                                def buildAppResult = RaspiPipelineLib.buildApps(this,testConfigs,testCasesList,appsBuildWorkSpace,appStorePath,app.name)
                                if (!buildAppResult.success)
                                    error("App build failed: ${app.name}")

                                JfrogUtils.uploadAppBinary(this,testConfigs,"raspi",appStorePath,app.name,app.branch,app.sha,app.tag,app.pr)
                            }
                        }
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
            def deviceBuildWorkSpace = ''
            def certificationControllerMissing = decision.platforms.values().any {it.controllerMissing && it.controllerRepo == "certification-tool"}
            
            stage('Get nodes of controller and device raspi') {
                def result = RaspiPipelineLib.getCntrlDeviceRaspiNodes(this, testConfigs)
                if (!result.success)
                    error("Get nodes of controller and device raspi failed")

                cntrlNode = result.nodesAllocated["controllerNode"]
                deviceNode = result.nodesAllocated["deviceNode"]
            }

            //Certification-tool build (controller + apps)
            stage('Certification-tool build for Controller') {
                if (certificationControllerMissing ) {
                    stage('Build certification-tool controller binaries') {

                        node("${cntrlNode}") {
                            controllerBuildWorkSpace ="${env.WORKSPACE}/controller_sdk"
                            echo "Building certification-tool controller"
                            def result = RaspiPipelineLib.buildAndinstallCertBinaries(this, testConfigs, controllerBuildWorkSpace, raspiBinariesDirString, "CTRL")
                            if (!result.success)
                                error("Certification-tool controller build failed")
                            testConfigs = result.testConfigs
                            cntlWorkSpace = result.cntrlWorksSpace
                        }
                    }
                }
            }

            //Build certification-tool accessories (loop)
            stage('Certification-tool build for Apps') {
                raspiDecision.apps.each { app ->

                    if (!app.missing)
                        return

                    if (app.repo != "certification-tool")
                        return

                    stage("Build certification-tool app: ${app.name}") {
                        node("${deviceNode}") {
                            deviceBuildWorkSpace ="${env.WORKSPACE}/controller_sdk"
                            echo "Building certification-tool accessory: ${app.name}"
                            def result = RaspiPipelineLib.buildAndinstallCertBinaries(this, testConfigs, deviceBuildWorkSpace, raspiBinariesDirString, "DUT", app )
                            if (!result.success)
                                error("Certification-tool app build failed: ${app.name}")
                        }
                    }
                }
            }

            //Install controller binaries
            stage('Install controller binaries into controller node') {
                node("${cntrlNode}") {
                    JfrogUtils.setupJfrog(this, testConfigs)
                    def result = commonPipelineLib.installControllerBinaries(this,testConfigs,"raspi",raspiBinariesDirString)
                    if (!result.success)
                        error("Controller install failed")

                    cntlWorkSpace = result.cntrlWorksSpace
                }
            }

            // Install all DUT binaries only after every missing app has been built or confirmed in JFrog.
            stage('Install DUT binaries into DEVICE_NODE') {
                node("${deviceNode}") {
                    JfrogUtils.setupJfrog(this, testConfigs)
                    def result =RaspiPipelineLib.installDeviceBinaries(this,testConfigs,deviceNode)
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
                    echo "Apps list: ${raspiDecision.apps}"

                    raspiDecision.apps.each { app ->
                        def refFolder = app.sha ?: app.tag ?: (app.pr ? "PR-${app.pr}" : app.branch)
                        def refPath = "${deviceWorkSpace}/${refFolder}/${app.name}"
                        echo "Running tests for app: ${app.name}"
                        echo "Reference folder: ${refFolder}"

                        def localTestParams = RaspiPipelineLib.initRaspiOnNetworkTestParams(this,testConfigs,cntlWorkSpace,deviceWorkSpace,deviceNodeIPAddress,refPath)
                        def runnerConfigPath = "${cntlWorkSpace}/runnerConfig_${refFolder}_${app.name}.yaml"
                        def mergedYaml = writeYaml(returnText: true, data: localTestParams)
                        writeFile(file: runnerConfigPath,text: mergedYaml)

                        testrun.runTests(this, ctrlPath, runnerConfigPath, app)
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
