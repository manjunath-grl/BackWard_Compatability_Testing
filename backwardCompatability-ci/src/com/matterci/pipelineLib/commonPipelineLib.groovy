package com.matterci.pipelineLib

import com.matterci.pipelineLib.TestUtils
import com.matterci.pipelineLib.TestParamDefaults
import com.matterci.pipelineLib.RepoUtils

import groovy.json.*

class commonPipelineLib implements Serializable {

    static Map installControllerBinaries(def steps,Map testConfigs,String platform,String ctrlBinariesDir) {
        def copyArtifactsSuccess = true
        def controllerBinariesWorkspace = "${steps.env.WORKSPACE}/${steps.env.BUILD_NUMBER}/copied_controller_binaries"
        def controllerCfg = testConfigs.ci_config.clone_sdk_code_stage.controller_sdk
        def branch = controllerCfg.branch
        def sha = controllerCfg.sha
        def tag = controllerCfg.tag
        def pr = controllerCfg.pr

        steps.timeout(time: 60, unit: 'MINUTES') {
            try {
                def hostname = steps.sh(script: "hostname", returnStdout: true).trim()
                steps.echo "Hostname: ${hostname}"
                steps.echo("Controller binaries workspace: ${controllerBinariesWorkspace}")

                steps.ws(controllerBinariesWorkspace) {
                    //Resolve correct JFrog base path
                    def basePath = commonPipelineLib.getResolvedArtifactBasePath(testConfigs,"controller",branch,sha,tag,pr)
                    def platformCfg = testConfigs.ci_config.clone_sdk_code_stage.platforms[platform]
                    def controllerPath = "${basePath}/controller/${platformCfg.controller_os}/${platformCfg.controller_type}/"

                    steps.echo("Downloading controller binaries from: ${controllerPath}")
                    setupJfrog(steps, testConfigs)
                    steps.sh """
                        set -e
                        jf rt dl \
                        "${controllerPath}*.whl" \
                        "${ctrlBinariesDir}/" \
                        --flat=true \
                        --insecure-tls=true
                    """
                    /*
                    Validate download success
                    */
                    def wheelCount =
                        steps.sh(
                            script: """
                                cd ${ctrlBinariesDir}
                                ls *.whl 2>/dev/null | wc -l
                            """,
                            returnStdout: true
                        ).trim()

                    if (wheelCount == "0") {
                        steps.error("No controller wheel files found in ${controllerPath}")
                    }
                    steps.echo("Controller binaries successfully downloaded")
                    /*
                    Clone Matter-QA repo for test execution environment
                    */
                    def status = RepoUtils.cloneMatterQARepo(steps,testConfigs,"main",controllerBinariesWorkspace,ctrlBinariesDir)

                    if (status != 0) {
                        copyArtifactsSuccess = false
                        steps.echo(
                            "cloneMatterQARepo execution failed"
                        )
                    }
                }
            }
            catch (Exception e) {
                copyArtifactsSuccess = false
                steps.echo("Error in installControllerBinaries(): ${e.getMessage()}")
            }
            return [success: copyArtifactsSuccess,cntrlWorksSpace: controllerBinariesWorkspace]
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
        steps.echo "Setting up JFrog CLI..."

        def hasSystemJf = steps.sh(
            script: "test -x /opt/jfrog/bin/jf",
            returnStatus: true
        ) == 0
        if (hasSystemJf) {
            steps.echo "Using system-installed jf"
            steps.env.PATH = "/opt/jfrog/bin:${steps.env.PATH}"
        } else {
            steps.echo "System jf not found. Using Jenkins tool installer..."
            def jfHome = steps.tool 'jfrog-cli'
            steps.env.PATH = "${jfHome}:${steps.env.PATH}"
        }

        steps.sh """
            set -ex
            which jf
            jf --version
        """

        def jfUrl = testConfigs.ci_config?.jfrog_config?.jfrog_url?: "http://192.168.0.56:8082"
        def credId = testConfigs.ci_config?.jfrog_config?.jfrog_creds_id?: "artifactory-jenkins-creds"
        def serverId = testConfigs.ci_config?.jfrog_config?.jfrog_server_id?: "artifactory-oss"

        steps.echo "Configuring JFrog CLI for server: ${serverId}"

        steps.withCredentials([
            steps.usernamePassword(
                credentialsId: credId,
                usernameVariable: 'JF_USER',
                passwordVariable: 'JF_PASSWORD'
            )
        ]) {
            steps.sh """
                set -ex
                jf c add ${serverId} \
                --url=${jfUrl} \
                --user=\$JF_USER \
                --password=\$JF_PASSWORD \
                --interactive=false \
                --overwrite \
                --insecure-tls=true
            """
            steps.sh "jf c use ${serverId}"
        }
    }

    static boolean isReleaseBranch(String branch) {
        if (!branch)
            return false

        // certification-tool mapped releases
        if (CERTIFICATION_TOOL_RELEASE_MAP.containsKey(branch))
            return true

        // connectedhomeip releases
        // if (branch.startsWith("v"))
        //     return true

        // if (branch.startsWith("ccb"))
        //     return true

        return false
    }

    static String resolveRepo(String branch) {
        if (!branch)
            return "connectedhomeip"

        if (CERTIFICATION_TOOL_RELEASE_MAP.containsKey(branch)) {
            return "certification-tool"
        }
        return "connectedhomeip"
    }
    
    static Map resolveArtifactAndBuildDecision(def steps, Map testConfigs) {
        setupJfrog(steps, testConfigs)

        def jfRepo = testConfigs.ci_config.jfrog_config.jfrog_repo ?: "matter-binaries"
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        def platformsCfg = cloneCfg.platforms ?: [:]
        def controllerCfg = cloneCfg.controller_sdk
        def controllerBranch = controllerCfg.branch
        def controllerSha    = controllerCfg.sha
        def controllerTag    = controllerCfg.tag
        def controllerPr     = controllerCfg.pr
        def controllerRepo = resolveRepo(controllerBranch)

        def controllerBasePath =
            isReleaseBranch(controllerBranch)
            ? "${jfRepo}/releases/${controllerBranch}"
            : "${jfRepo}/branches/${controllerBranch}/${controllerSha}"

        steps.echo "Controller Repo     = ${controllerRepo}"
        steps.echo "Controller BasePath = ${controllerBasePath}"

        boolean cloneRequired = false
        def platformDecision = [:]

        platformsCfg.each { platformName, platformCfg ->

            if (!platformCfg?.run)
                return

            steps.echo "Processing platform: ${platformName}"

            def controllerPath = "${controllerBasePath}/controller/${platformCfg.controller_os}/${platformCfg.controller_type}/*.whl"

            boolean controllerExists = jfrogFileExists(steps, controllerPath)
            boolean controllerMissing = !controllerExists

            steps.echo "Controller exists: ${controllerExists}"

            if (controllerMissing)
                cloneRequired = true

            def appsDecisionList = []
            def appsList = platformCfg.apps ?: []

            appsList.each { appCfg ->
                def appName = appCfg.name
                def branch  = appCfg.branch
                def sha     = appCfg.sha
                def tag     = appCfg.tag
                def pr      = appCfg.pr

                def repo = resolveRepo(branch)
                def appBasePath =
                    isReleaseBranch(branch)
                    ? "${jfRepo}/releases/${branch}"
                    : "${jfRepo}/branches/${branch}/${sha}"

                def appPath = "${appBasePath}/apps/${appName}/${platformName}/${appName}"

                boolean appExists = jfrogFileExists(steps, appPath)
                boolean appMissing = !appExists

                steps.echo "App ${appName} exists: ${appExists}"

                if (appMissing)
                    cloneRequired = true

                appsDecisionList << [
                    name    : appName,
                    branch  : branch,
                    sha     : sha,
                    tag     : tag,
                    pr      : pr,
                    repo    : repo,
                    missing : appMissing
                ]
            }

            platformDecision[platformName] = [
                controllerMissing : controllerMissing,
                controllerRepo    : controllerRepo,
                apps              : appsDecisionList
            ]
        }

        def decision = [platforms     : platformDecision, cloneRequired : cloneRequired ]
        steps.echo "Artifact Decision = ${decision}"
        return decision
    }

    static void uploadControllerBinary(def steps, Map testConfigs, String platform, String binariesDir) {
        setupJfrog(steps, testConfigs)
        def jfRepo = testConfigs.ci_config.jfrog_config.jfrog_repo ?: "matter-binaries"
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        def controllerCfg = cloneCfg.controller_sdk
        def branch = controllerCfg.branch
        def sha = controllerCfg.sha

        def basePath =
            isReleaseBranch(branch)
            ? "${jfRepo}/releases/${branch}"
            : "${jfRepo}/branches/${branch}/${sha}"

        def platformCfg = cloneCfg.platforms[platform]

        steps.echo "Uploading controller to ${basePath}"

        steps.sh """
            jf rt u \
            "${binariesDir}/controller/*.whl" \
            "${basePath}/controller/${platformCfg.controller_os}/${platformCfg.controller_type}/" \
            --flat=true
        """
    }

    static void uploadAppBinary(def steps, Map testConfigs, String platform, String binariesDir, String appName, String branch = null, String sha = null, String tag = null, String pr  = null) {
        setupJfrog(steps, testConfigs)
        def jfRepo =testConfigs.ci_config.jfrog_config.jfrog_repo ?: "matter-binaries"

        //Detect certification-tool workspace
        def isCertificationToolRepo = isReleaseBranch(branch)
        steps.echo "Is certification-tool repo: ${isCertificationToolRepo}"

        //Resolve artifact base path
        def basePath

        if (isReleaseBranch(branch)) {
            basePath = "${jfRepo}/releases/${branch}"
        }
        else if (sha) {
            basePath = "${jfRepo}/branches/${branch}/${sha}"
        }
        else if (tag) {
            basePath = "${jfRepo}/tags/${tag}"
        }
        else if (pr) {
            basePath = "${jfRepo}/pull-requests/PR-${pr}"
        }
        else {
            steps.error("Unable to determine artifact upload path")
        }

        //certification-tool logic Upload ALL binaries present in apps folder
        if (isCertificationToolRepo) {
            steps.echo "Detected certification-tool repo → uploading ALL binaries"
            def binaries = steps.sh(
                script: """
                    ls ${binariesDir} 2>/dev/null || true
                """,
                returnStdout: true
            ).trim().split("\\n")

            if (!binaries || binaries[0] == "") {
                steps.error("No binaries found inside ${binariesDir}")
            }

            binaries.each { binaryName ->
                def binaryPath = "${binariesDir}/${binaryName}"
                steps.echo "Uploading binary: ${binaryName}"
                def uploadTargetPath = "${basePath}/${binaryName}/${platform}/"
                steps.sh """
                    set -ex
                    jf rt u \
                    "${binaryPath}" \
                    "${uploadTargetPath}" \
                    --flat=true
                """
            }
            steps.echo "All certification-tool binaries uploaded successfully"
            return
        }

        //connectedhomeip logic Upload single requested binary
        def binaryPath = "${binariesDir}/${appName}/${appName}"


        steps.sh """
            set -ex
            ls -la "${binaryPath}"
        """
        def uploadTargetPath = "${basePath}/apps/${appName}/${platform}/"

        steps.echo """
        Uploading connectedhomeip binary
        -------------------------------
        App Name     : ${appName}
        Platform     : ${platform}
        Upload From  : ${binaryPath}
        Upload To    : ${uploadTargetPath}
        """

        steps.sh """
            set -ex
            jf rt u \
            "${binaryPath}" \
            "${uploadTargetPath}" \
            --flat=true
        """
        steps.echo "Upload completed successfully for ${appName}"
    }


    static Map resolveBranchSHA(def steps, Map testConfigs) {
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        def controllerCfg = cloneCfg.controller_sdk
        def platformsCfg = cloneCfg.platforms

        // helper closure
        def resolveSHA = { cfg, name ->

            if (!cfg?.branch)
                return

            def branch = cfg.branch
            if (isReleaseBranch(branch)) {
                steps.echo "${name}: Release branch detected (${branch}) → SHA not required"
                return
            }

            if (cfg.sha?.trim()) {
                steps.echo "${name}: SHA already provided → ${cfg.sha}"
                return
            }

            def repoUrl = "git@github.com:project-chip/connectedhomeip.git"
            steps.echo "${name}: Resolving SHA for branch ${branch}"
            def sha = steps.sh(
                script: """
                    git ls-remote ${repoUrl} refs/heads/${branch} | awk '{print \$1}'
                """,
                returnStdout: true
            ).trim()

            if (!sha)
                steps.error("Failed resolving SHA for ${name}")

            cfg.sha = sha
            steps.echo "${name}: Resolved SHA = ${sha}"
        }

        // resolve controller SHA
        resolveSHA(controllerCfg, "Controller SDK")

        // resolve SHA for each accessory app
        platformsCfg.each { platformName, platformCfg ->
            platformCfg.apps?.each { appCfg ->
                resolveSHA(appCfg, "App SDK (${appCfg.name})")
            }
        }
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

    static String getResolvedArtifactBasePath(Map testConfigs,String component,String branch = null,String sha = null,String tag = null,String pr = null) {
        def jfRepo = testConfigs.ci_config.jfrog_config.jfrog_repo ?: "matter-binaries"
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        /*
        Resolve branch + SHA depending on component
        */
        if (!branch) {
            if (component == "controller") {
                branch = cloneCfg.controller_sdk.branch
                sha    = cloneCfg.controller_sdk.sha
            }
            else if (component == "apps") {
                throw new IllegalArgumentException(
                    "Apps component requires branch parameter for multi-accessory support"
                )
            }
            else {
                throw new IllegalArgumentException(
                    "Invalid component: ${component}"
                )
            }
        }
        /*
        Release branch handling
        */
        if (isReleaseBranch(branch)) {

            return "${jfRepo}/releases/${branch}"
        }
        /*
        SHA resolution fallback logic
        */
        def effectiveRef =sha ?: tag ?: (pr ? "PR-${pr}" : branch)
        return "${jfRepo}/branches/${branch}/${effectiveRef}"
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
        def controllerCfg = cloneCfg.controller_sdk
        def platformsCfg = cloneCfg.platforms

        // helper closure to resolve effective ref
        def resolveEffectiveRef = { cfg ->
            return cfg.sha ?: cfg.tag ?: (cfg.pr ? "PR-${cfg.pr}" : cfg.branch)
        }

        def controllerRef = resolveEffectiveRef(controllerCfg)

        if (!controllerRef) {
            steps.error("Controller SDK must provide at least one reference: branch OR sha OR tag OR pr")
        }

        platformsCfg.each { platformName, platformCfg ->

            platformCfg.apps?.each { appCfg ->

                def appRef = resolveEffectiveRef(appCfg)

                if (!appRef) {
                    steps.error("""
                    Missing SDK reference for accessory: ${appCfg.name}
                    Provide one of:
                    branch OR sha OR tag OR pr
                    """)
                }

                // prevent identical controller + accessory reference
                if (controllerRef == appRef) {

                    steps.error("""
                    Controller SDK reference cannot match accessory reference.

                    Controller ref:
                    ${controllerRef}

                    Accessory:
                    ${appCfg.name}
                    ref:
                    ${appRef}

                    Backward compatibility requires controller and accessory to use different SDK sources.
                    """)
                }
            }
        }

        steps.echo "SDK configuration validation passed"
    }

    static Map CERTIFICATION_TOOL_RELEASE_MAP = [
        "v2.14+fall2025" : "ca9d1118e097fe947b2aec1ba84f265d6cf2447e",
        "v2.15-beta2.1+spring2026" : "ead81748828787a656ae05c7d980f908f09ea751",
        "v2.14.1-beta2+winter2026" : "4564cd2e0a0c7059bb99719cfc3de50cefac5d10",
        "v2.15-beta2+spring2026" : "9b1078da4307f98d362a0b44625a94d649bc1e77",
        "v2.15-beta3+spring2026" : "c2175a1ee826fe66f1d40afc3fcf8e05689810aa"
    ]

    static void overrideDockerImageForRelease(def steps, Map testConfigs) {
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        def controllerCfg = cloneCfg.controller_sdk

        if (!controllerCfg?.branch)
            return

        def branch = controllerCfg.branch

        if (!isReleaseBranch(branch))
            return

        if (!CERTIFICATION_TOOL_RELEASE_MAP.containsKey(branch))
            return

        def imageSha = CERTIFICATION_TOOL_RELEASE_MAP[branch]
        def raspiStages = testConfigs.ci_config?.raspi_pipeline?.stages

        if (raspiStages?.build_firmware) {
            raspiStages.build_firmware.chip_cert_bins = imageSha
            steps.echo "Docker image overridden using certification-tool release map:"
            steps.echo "Branch: ${branch}"
            steps.echo "Image SHA: ${imageSha}"
        }
    }

    static String resolveCertDockerSha(Map testConfigs) {
        def controllerBranch = testConfigs.ci_config.clone_sdk_code_stage.controller_sdk.branch
        def raspiStages = testConfigs.ci_config.raspi_pipeline?.stages

        if (CERTIFICATION_TOOL_RELEASE_MAP.containsKey(controllerBranch)) {
            return CERTIFICATION_TOOL_RELEASE_MAP[controllerBranch]
        }
        def fallbackSha = raspiStages?.build_firmware?.chip_cert_bins

        if (fallbackSha) {
            return fallbackSha
        }
        throw new IllegalStateException("Unable to resolve chip-cert-bins docker image SHA for branch: ${controllerBranch}")
    }
}