package com.matterci.pipelineLib

import com.matterci.pipelineLib.RepoUtils
import com.matterci.pipelineLib.JfrogUtils
import com.matterci.pipelineLib.CertificationToolCatalog

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
                    def basePath = JfrogUtils.getResolvedArtifactBasePath(testConfigs,"controller",branch,sha,tag,pr)
                    def platformCfg = testConfigs.ci_config.clone_sdk_code_stage.platforms[platform]
                    def controllerPath = "${basePath}/controller/${platformCfg.controller_os}/${platformCfg.controller_type}/"

                    steps.echo("Downloading controller binaries from: ${controllerPath}")
                    steps.sh """
                        set -e
                        jf rt dl \
                        "${controllerPath}*" \
                        "${ctrlBinariesDir}/" \
                        --flat=true \
                        --insecure-tls=true
                    """
                    //Validate download success
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
                    
                    //Clone Matter-QA repo for test execution environment
                    def status = RepoUtils.cloneMatterQARepo(steps,testConfigs,"main",controllerBinariesWorkspace,ctrlBinariesDir)

                    if (status != 0) {
                        copyArtifactsSuccess = false
                        steps.echo("cloneMatterQARepo execution failed")
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

    static String getBuildPythonArgs(Map testConfigs, String defaultArgs) {
        // Default args preserve current pipeline behavior until the YAML explicitly overrides them.
        return testConfigs?.ci_config?.raspi_pipeline?.stages?.build_firmware?.build_python_args ?: defaultArgs
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

    static boolean isReleaseBranch(String branch) {
        return CertificationToolCatalog.isReleaseBranch(branch)
    }

    static String resolveRepo(String branch) {
        if (!branch)
            return "connectedhomeip"

        if (CertificationToolCatalog.isReleaseBranch(branch)) {
            return "certification-tool"
        }
        return "connectedhomeip"
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


    static void validateSdkConfig(def steps, Map testConfigs) {
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        def controllerCfg = cloneCfg.controller_sdk
        def platformsCfg = cloneCfg.platforms
        def appRef

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

                appRef = resolveEffectiveRef(appCfg)
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

    static Map overrideDockerImageForRelease(def steps, Map testConfigs) {
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        def controllerCfg = cloneCfg.controller_sdk

        if (!controllerCfg?.branch)
            return testConfigs

        def branch = controllerCfg.branch

        if (!isReleaseBranch(branch))
            return testConfigs

        if (!CertificationToolCatalog.isReleaseBranch(branch))
            return testConfigs

        def imageSha = CertificationToolCatalog.getImageSha(branch)
        def raspiStages = testConfigs.ci_config?.raspi_pipeline?.stages

        if (raspiStages?.build_firmware) {
            raspiStages.build_firmware.chip_cert_bins = imageSha
            steps.echo "Docker image overridden using certification-tool release map:"
            steps.echo "Branch: ${branch}"
            steps.echo "Image SHA: ${imageSha}"
        }
        return testConfigs
    }

    def waitForNodeAfterReboot(steps,int timeoutMinutes = 10) {
        steps.echo "Waiting for node to come back after reboot..."
        timeout(time: timeoutMinutes, unit: 'MINUTES') {
            waitUntil {
                try {
                    steps.sh(script: "echo node-up", returnStatus: true) == 0
                } catch (e) {
                    sleep 10
                    return false
                }
            }
        }
        steps.echo "Node is back online"
    }
}
