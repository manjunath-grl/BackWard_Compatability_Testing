package com.matterci.pipelineLib

import com.matterci.pipelineLib.TestUtils
import com.matterci.pipelineLib.TestParamDefaults
import com.matterci.pipelineLib.RepoUtils

import groovy.json.*

class commonPipelineLib implements Serializable {

    static Map installControllerBinaries(def steps, Map testConfigs, String platform, String ctrlBinariesDir) {

        def copyArtifactsSuccess = true
        def controllerBinariesWorkspace = "${steps.env.WORKSPACE}/${steps.env.BUILD_NUMBER}/copied_controller_binaries"
        def copyBuildArtifact = testConfigs.ci_config?.copy_build_artifact
        def repoName = testConfigs.ci_config?.jfrog_config.jfrog_repo_name ?: "Jenkins-Binaries"
        def projectName = steps.env.JOB_NAME
        def buildNumber = steps.env.BUILD_NUMBER
        def platformStages = ""

        // -------- Platform Validation --------
        switch (ctrlBinariesDir) {
            case "raspi_binaries":
                platformStages = testConfigs.ci_config.raspi_pipeline.stages
                break
            default:
                steps.error("Invalid ctrlBinariesDir: ${ctrlBinariesDir}. Must be 'raspi_binaries'")
        }

        // -------- Handle copy_build_artifact --------
        // if (copyBuildArtifact?.enabled && !platformStages.build_firmware.enabled) {
        //     if (!copyBuildArtifact?.job_name || !copyBuildArtifact?.build_number) {
        //         steps.error("copy_build_artifact enabled but job_name/build_number missing")
        //     }

        //     projectName = copyBuildArtifact.job_name
        //     buildNumber = copyBuildArtifact.build_number
        //     steps.echo "Using configured job: ${projectName}"
        //     steps.echo "Using configured build: ${buildNumber}"
        // }

        steps.timeout(time: 60, unit: 'MINUTES') {
            try {
                def hostname = steps.sh(script: "hostname", returnStdout: true).trim()
                steps.echo "Hostname is: ${hostname}"
                steps.echo "Controller binaries workspace: ${controllerBinariesWorkspace}"

                steps.ws("${controllerBinariesWorkspace}") {
                    setupJfrog(steps, testConfigs)
                    def basePath = commonPipelineLib.getResolvedArtifactBasePath(testConfigs, "controller")
                    def platformCfg = testConfigs.ci_config.clone_sdk_code_stage.platforms[platform]
                    def controllerPath = "${basePath}/controller/${platformCfg.controller_os}/${platformCfg.controller_type}/"

                    steps.echo "Downloading Controller from ${controllerPath}"
                    steps.sh """
                        set -e
                        jf rt dl \
                        "${controllerPath}*.whl" \
                        "${ctrlBinariesDir}/" \
                        --flat=true \
                        --insecure-tls=true
                    """

                    def count = steps.sh(
                        script: "cd ${ctrlBinariesDir} && ls *.whl 2>/dev/null | wc -l",
                        returnStdout: true
                    ).trim()

                    if (count == "0")
                        steps.error("No controller wheel files found in ${controllerPath}")

                    steps.echo "Controller binaries downloaded"
                    // -------- Continue Existing Logic --------
                    def status = RepoUtils.cloneMatterQARepo(steps,testConfigs,"main",controllerBinariesWorkspace,ctrlBinariesDir)

                    if (status != 0) {
                        copyArtifactsSuccess = false
                        steps.echo("Error during cloneMatterQARepo execution.")
                    }
                }

            } catch (Exception e) {
                copyArtifactsSuccess = false
                steps.echo "Error in 'installControllerBinaries': ${e.getMessage()}"
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

    /**
    * One-time setup for JFrog CLI using YAML configs and System PATH.
    */
    static def setupJfrog(def steps, Map testConfigs) {
        // 1. Force the manual installation path into the environment
        steps.env.PATH = "/opt/jfrog/bin:${steps.env.PATH}"
        def jfHome = steps.tool 'jfrog-cli'
        steps.env.PATH = "${jfHome}:${steps.env.PATH}"

        // 2. Fetch arguments from YAML (with defaults as fallback)
        def jfUrl    = testConfigs.ci_config?.jfrog_config?.jfrog_url ?: "http://192.168.0.56:8082"
        def credId   = testConfigs.ci_config?.jfrog_config?.jfrog_creds_id ?: "artifactory-jenkins-creds"
        def serverId = testConfigs.ci_config?.jfrog_config?.jfrog_server_id ?: "artifactory-oss"

        steps.echo "Configuring JFrog CLI for server: ${serverId}"

        // 3. Authenticate and Configure
        steps.withCredentials([steps.usernamePassword(credentialsId: credId, 
                                                    passwordVariable: 'JF_PASSWORD', 
                                                    usernameVariable: 'JF_USER')]) {
            steps.sh """
                jf c add ${serverId} \
                --url=${jfUrl} \
                --user=${steps.env.JF_USER} \
                --password=${steps.env.JF_PASSWORD} \
                --insecure-tls=true \
                --overwrite \
                --interactive=false
            """
            steps.sh "jf c use ${serverId}"
        }
    }

    static boolean isReleaseBranch(String branch) {
        if (!branch) return false
        return (
            branch ==~ /^v.*(-branch|-sve|-sve-branch)$/ ||   // connectedhomeIp
            branch ==~ /^v[0-9].*/ ||                         // v2.15-beta2 etc
            branch ==~ /.*v[0-9]+\.[0-9]+.*\+.*.*/            // contains vX.Y+season
        )
    }
    
    static Map resolveArtifactAndBuildDecision(def steps, Map testConfigs) {
        setupJfrog(steps, testConfigs)
        def jfRepo = testConfigs.ci_config.jfrog_config.jfrog_repo ?: "matter-binaries"
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        def platformsCfg = cloneCfg.platforms ?: [:]
        def controllerCfg = cloneCfg.controller_sdk_config
        def appsCfg = cloneCfg.apps_sdk_config

        // CONTROLLER BASE PATH
        def controllerBasePath =
            isReleaseBranch(controllerCfg.branch)
            ? "${jfRepo}/releases/${controllerCfg.branch}"
            : "${jfRepo}/branches/${controllerCfg.branch}/${controllerCfg.sha}"

        // APPS BASE PATH
        def appsBasePath =
            isReleaseBranch(appsCfg.branch)
            ? "${jfRepo}/releases/${appsCfg.branch}"
            : "${jfRepo}/branches/${appsCfg.branch}/${appsCfg.sha}"

        steps.echo "Controller BasePath = ${controllerBasePath}"
        steps.echo "Apps BasePath       = ${appsBasePath}"

        boolean cloneRequired = false
        def platformDecision = [:]

        def checkPlatform = { String platformName, Map cfg ->
            def appName = cfg.app_to_test ?: testConfigs.ci_config.app_to_test
            def controllerPath ="${controllerBasePath}/controller/${cfg.controller_os}/${cfg.controller_type}/*.whl"
            def appPath ="${appsBasePath}/apps/${appName}/${platformName}/chip-${appName}*"

            boolean controllerExists = jfrogFileExists(steps, controllerPath)
            boolean appExists = jfrogFileExists(steps, appPath)

            def controllerRepo = testConfigs.ci_config.clone_sdk_code_stage.controller_sdk_config.controller_repo
            steps.echo "Controller Repo = ${controllerRepo}"

            // override rule
            boolean controllerMissing = !controllerExists
            if (controllerRepo == "certification-tool") {
                // certification-tool never requires connectedhomeip clone
                steps.echo "Certification tool repo detected"
                if (controllerExists) {
                    controllerMissing = false
                } else {
                    controllerMissing = true
                }
            }

            if (controllerRepo == "connectedhomeip") {
                steps.echo "ConnectedHomeIP repo detected"
                if (controllerExists) {
                    controllerMissing = false
                } else {
                    controllerMissing = true
                }
            }

            if (controllerRepo == "connectedhomeip") {
                if (controllerMissing || !appExists) {
                    cloneRequired = true
                }
            }

            if (controllerRepo == "certification-tool") {
                if (controllerMissing) {
                    cloneRequired = false   // build only certification-tool
                }
            }

            platformDecision[platformName] = [
                controllerMissing : controllerMissing,
                appsMissing       : !appExists
            ]
        }

        platformsCfg.each { pname, pcfg ->
            if (!pcfg?.run) return

            if (!pcfg.variants) {
                checkPlatform(pname, pcfg)
            } else {
                pcfg.variants.each { vname, vcfg ->
                    if (vcfg?.run)
                        checkPlatform(vname, vcfg)
                }
            }
        }

        def decision = [
            platforms     : platformDecision,
            cloneRequired : cloneRequired
        ]

        steps.echo "Artifact Decision = ${decision}"
        return decision
    }

    static void uploadPlatformBinaries(def steps, Map testConfigs, String platform, String binariesDir, boolean uploadController, boolean uploadApps) {
        setupJfrog(steps, testConfigs)

        def jfRepo = testConfigs.ci_config.jfrog_config.jfrog_repo ?: "matter-binaries"
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        def controllerBranch = cloneCfg.controller_sdk_config.branch
        def controllerSha = cloneCfg.controller_sdk_config.sha
        def appsBranch = cloneCfg.apps_sdk_config.branch
        def appsSha = cloneCfg.apps_sdk_config.sha

        def controllerBasePath =
            isReleaseBranch(controllerBranch)
            ? "${jfRepo}/releases/${controllerBranch}"
            : "${jfRepo}/branches/${controllerBranch}/${controllerSha}"

        def appsBasePath =
            isReleaseBranch(appsBranch)
            ? "${jfRepo}/releases/${appsBranch}"
            : "${jfRepo}/branches/${appsBranch}/${appsSha}"

        def platformCfg = cloneCfg.platforms[platform]

        if (!platformCfg) {
            cloneCfg.platforms.each { p, cfg ->
                if (cfg.variants?.containsKey(platform))
                    platformCfg = cfg.variants[platform]
            }
        }
        if (uploadController) {
            steps.echo "Uploading controller for ${platform}"
            steps.sh """
                jf rt u \
                "${binariesDir}/controller/*.whl" \
                "${controllerBasePath}/controller/${platformCfg.controller_os}/${platformCfg.controller_type}/" \
                --flat=true
            """
        }
        if (uploadApps) {
            def appName = platformCfg.app_to_test ?: testConfigs.ci_config.app_to_test
            steps.echo "Uploading app ${appName} for ${platform}"
            steps.sh """
                jf rt u \
                "${binariesDir}/apps/*" \
                "${appsBasePath}/apps/${appName}/${platform}/" \
                --flat=true
            """
        }
    }

    static Map resolveBranchSHA(def steps, Map testConfigs) {
        def cloneCfg =testConfigs.ci_config.clone_sdk_code_stage
        def controllerCfg = cloneCfg.controller_sdk_config
        def appsCfg = cloneCfg.apps_sdk_config

        // Helper Closure
        def resolveSHA = { cfg, name ->
            if (!cfg?.branch)
                return
            def branch = cfg.branch

            // RELEASE BRANCH CHECK
            if (isReleaseBranch(branch)) {
                steps.echo "${name}: Release branch detected (${branch}) → SHA not required"
                return
            }

            // SHA already provided
            if (cfg.sha?.trim()) {
                steps.echo "${name}: SHA already provided → ${cfg.sha}"
                return
            }

            // Resolve SHA using git ls-remote
            steps.echo "${name}: Resolving SHA for branch ${branch}"
            def repoUrl = cfg.repoUrl
            def sha = steps.sh(
                script: """
                    git ls-remote ${repoUrl} refs/heads/${branch} | awk '{print \$1}'
                """,
                returnStdout: true
            ).trim()

            if (!sha) {
                steps.error("Failed resolving SHA for ${name}")
            }
            cfg.sha = sha
            steps.echo "${name}: Resolved SHA = ${sha}"
        }
        resolveSHA(controllerCfg, "Controller SDK")
        resolveSHA(appsCfg, "Apps SDK")

        return testConfigs
    }

    static boolean jfrogFileExists(def steps, String pattern) {
        steps.echo "Checking artifact: ${pattern}"
        def status = steps.sh(
            script: """
                set +e
                jf rt s "${pattern}" \
                --count=true \
                --insecure-tls=true > result.txt 2>/dev/null

                COUNT=\$(grep -o '[0-9]*' result.txt | head -1)

                if [ "\$COUNT" = "0" ] || [ -z "\$COUNT" ]; then
                    exit 1
                fi
            """,
            returnStatus: true
        )
        return status == 0
    }

    static String getResolvedArtifactBasePath(Map testConfigs, String component) {
        def jfRepo = testConfigs.ci_config.jfrog_config.jfrog_repo ?: "matter-binaries"
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        def branch
        def sha
        if (component == "controller") {
            branch = cloneCfg.controller_sdk_config.branch
            sha    = cloneCfg.controller_sdk_config.sha
        }
        else if (component == "apps") {
            branch = cloneCfg.apps_sdk_config.branch
            sha    = cloneCfg.apps_sdk_config.sha
        }
        else {
            throw new IllegalArgumentException("Invalid component: ${component}")
        }
        if (isReleaseBranch(branch)) {
            return "${jfRepo}/releases/${branch}"
        }

        return "${jfRepo}/branches/${branch}/${sha}"
    }

    static boolean jfrogPathExists(def steps, String path) {
        steps.echo "Checking JFrog path existence: ${path}"
        try {
            def status = steps.sh(
                script: """
                    set +e
                    export PATH="/opt/jfrog/bin:\$HOME/.local/bin:\$PATH"

                    jf rt s "${path}" \
                        --count=true \
                        --insecure-tls=true > result.txt 2>/dev/null

                    COUNT=\$(cat result.txt | grep -o '[0-9]*' | head -1)

                    if [ -z "\$COUNT" ] || [ "\$COUNT" = "0" ]; then
                        exit 1
                    fi
                """,
                returnStatus: true
            )
            if (status == 0) {
                steps.echo "Artifact FOUND: ${path}"
                return true
            }
            steps.echo "Artifact NOT FOUND: ${path}"
            return false
        } catch (Exception e) {
            steps.echo "JFrog check failed: ${e.message}"
            return false
        }
    }

    static void validateSdkConfig(def steps, Map testConfigs) {
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        def ctrl = cloneCfg.controller_sdk_config
        def apps = cloneCfg.apps_sdk_config

        if (ctrl.branch == apps.branch && ctrl.sha == apps.sha) {
            steps.error("""
                Controller SDK and Apps SDK cannot use the same branch and SHA.
                Controller:
                branch: ${ctrl.branch}
                sha:    ${ctrl.sha}

                Apps:
                branch: ${apps.branch}
                sha:    ${apps.sha}

                Please provide different sources for controller and apps.
                """)
        }
    }

    static Map RELEASE_DOCKER_MAP = [
        "v2.14+fall2025" : "ca9d1118e097fe947b2aec1ba84f265d6cf2447e",
        "v2.15-beta2.1+spring2026" : "ead81748828787a656ae05c7d980f908f09ea751",
        "v2.14.1-beta2+winter2026" : "4564cd2e0a0c7059bb99719cfc3de50cefac5d10",
        "v2.15-beta2+spring2026" : "9b1078da4307f98d362a0b44625a94d649bc1e77",
    ]

    static void overrideDockerImageForRelease(Map testConfigs) {
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        def branch = cloneCfg.apps_sdk_config.branch

        if (!isReleaseBranch(branch))
            return

        def imageSha = RELEASE_DOCKER_MAP[branch]
        if (!imageSha)
            return

        def raspiStages = testConfigs.ci_config?.raspi_pipeline?.stages
        if (raspiStages?.build_firmware) {
            raspiStages.build_firmware.chip_cert_bins = imageSha
        }
    }
}