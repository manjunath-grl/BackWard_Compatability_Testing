package com.matterci.pipelineLib

import com.matterci.pipelineLib.CertificationToolCatalog

class JfrogUtils implements Serializable {

    static final String DEFAULT_JFROG_REPO = "matter-Binaries"
    static final String DEFAULT_JFROG_URL = "http://192.168.0.56:8082"
    static final String DEFAULT_JFROG_CREDENTIALS_ID = "artifactory-jenkins-creds"
    static final String DEFAULT_JFROG_SERVER_ID = "artifactory-oss"

    static void setupJfrog(def steps, Map testConfigs) {
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

        def jfUrl = testConfigs.ci_config?.jfrog_config?.jfrog_url ?: DEFAULT_JFROG_URL
        def credId = testConfigs.ci_config?.jfrog_config?.jfrog_creds_id ?: DEFAULT_JFROG_CREDENTIALS_ID
        def serverId = testConfigs.ci_config?.jfrog_config?.jfrog_server_id ?: DEFAULT_JFROG_SERVER_ID

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

    static Map resolveArtifactAndBuildDecision(def steps, Map testConfigs) {
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        def platformsCfg = cloneCfg.platforms ?: [:]
        def controllerCfg = cloneCfg.controller_sdk
        def controllerRepo = resolveRepo(controllerCfg.branch)
        def controllerBasePath = buildBasePath(testConfigs, controllerCfg.branch, controllerCfg.sha, controllerCfg.tag, controllerCfg.pr)

        steps.echo "Controller Repo     = ${controllerRepo}"
        steps.echo "Controller BasePath = ${controllerBasePath}"

        boolean cloneRequired = false
        def platformDecision = [:]

        platformsCfg.each { platformName, platformCfg ->
            if (!platformCfg?.run)
                return

            steps.echo "Processing platform: ${platformName}"

            // Controller artifacts are shared per platform, while apps are resolved per accessory ref.
            def controllerPath = "${controllerBasePath}/controller/${platformCfg.controller_os}/${platformCfg.controller_type}/*.whl"
            boolean controllerExists = fileExists(steps, controllerPath)
            boolean controllerMissing = !controllerExists

            steps.echo "Controller exists: ${controllerExists}"

            if (controllerMissing)
                cloneRequired = true

            def appsDecisionList = []
            (platformCfg.apps ?: []).each { appCfg ->
                def appRepo = resolveRepo(appCfg.branch)
                def appBasePath = buildBasePath(testConfigs, appCfg.branch, appCfg.sha, appCfg.tag, appCfg.pr)
                def appPath = "${appBasePath}/apps/${appCfg.name}/${platformName}/${appCfg.name}*"

                boolean appExists = fileExists(steps, appPath)
                boolean appMissing = !appExists

                steps.echo "App ${appCfg.name} exists: ${appExists}"

                if (appMissing)
                    cloneRequired = true

                appsDecisionList << [
                    name    : appCfg.name,
                    branch  : appCfg.branch,
                    sha     : appCfg.sha,
                    tag     : appCfg.tag,
                    pr      : appCfg.pr,
                    repo    : appRepo,
                    missing : appMissing
                ]
            }

            platformDecision[platformName] = [
                controllerMissing : controllerMissing,
                controllerRepo    : controllerRepo,
                apps              : appsDecisionList
            ]
        }

        def decision = [platforms: platformDecision, cloneRequired: cloneRequired]
        steps.echo "Artifact Decision = ${decision}"
        return decision
    }

    static void uploadControllerBinary(def steps, Map testConfigs, String platform, String binariesDir) {
        setupJfrog(steps, testConfigs)

        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage
        def controllerCfg = cloneCfg.controller_sdk
        def platformCfg = cloneCfg.platforms[platform]
        // Resolve the controller upload path using the same branch/sha/tag/pr rules used by download checks.
        def basePath = getResolvedArtifactBasePath(testConfigs, "controller", controllerCfg.branch, controllerCfg.sha, controllerCfg.tag, controllerCfg.pr)
        steps.echo """
            Controller artifact reference
            -----------------------------
            Branch : ${controllerCfg.branch}
            SHA    : ${controllerCfg.sha}
            Tag    : ${controllerCfg.tag}
            PR     : ${controllerCfg.pr}
        """
        steps.echo "Uploading controller to ${basePath}"
        steps.sh """
            jf rt u \
            "${binariesDir}/controller/*.whl" \
            "${basePath}/controller/${platformCfg.controller_os}/${platformCfg.controller_type}/" \
            --flat=true
        """
    }

    static void uploadAppBinary(def steps, Map testConfigs, String platform, String binariesDir, String appName, String branch = null, String sha = null, String tag = null, String pr = null) {
        setupJfrog(steps, testConfigs)

        def basePath = buildBasePath(testConfigs, branch, sha, tag, pr)
        def isCertificationToolRepo = CertificationToolCatalog.isReleaseBranch(branch)

        steps.echo "Is certification-tool repo: ${isCertificationToolRepo}"

        if (isCertificationToolRepo) {
            // certification-tool exports multiple binaries from the same folder, so upload each discovered file.
            steps.echo "Detected certification-tool repo -> uploading all binaries"
            def binaries = steps.sh(
                script: "ls ${binariesDir} 2>/dev/null || true",
                returnStdout: true
            ).trim().split("\\n").findAll { it }

            if (!binaries) {
                steps.error("No binaries found inside ${binariesDir}")
            }

            binaries.each { binaryName ->
                def binaryPath = "${binariesDir}/${binaryName}"
                def uploadTargetPath = "${basePath}/apps/${binaryName}/${platform}/"
                steps.echo "Uploading binary: ${binaryName}"
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

        def binaryPath = "${binariesDir}/${appName}/${appName}"
        def uploadTargetPath = "${basePath}/apps/${appName}/${platform}/"

        steps.sh """
            set -ex
            ls -la "${binaryPath}"
        """

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

    static String getResolvedArtifactBasePath(Map testConfigs, String component, String branch = null, String sha = null, String tag = null, String pr = null) {
        def cloneCfg = testConfigs.ci_config.clone_sdk_code_stage

        if (!branch) {
            if (component == "controller") {
                branch = cloneCfg.controller_sdk.branch
                sha = cloneCfg.controller_sdk.sha
                tag = cloneCfg.controller_sdk.tag
                pr = cloneCfg.controller_sdk.pr
            } else if (component != "apps") {
                throw new IllegalArgumentException("Invalid component: ${component}")
            } else if (!sha && !tag && !pr) {
                throw new IllegalArgumentException("Apps component requires branch parameter for multi-accessory support")
            }
        }

        return buildBasePath(testConfigs, branch, sha, tag, pr)
    }

    static boolean fileExists(def steps, String pattern) {
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

    private static String buildBasePath(Map testConfigs, String branch = null, String sha = null, String tag = null, String pr = null) {
        def jfRepo = testConfigs.ci_config.jfrog_config.jfrog_repo ?: DEFAULT_JFROG_REPO

        // Keep upload/download/existence checks on the same path convention for every ref type.
        if (CertificationToolCatalog.isReleaseBranch(branch))
            return "${jfRepo}/releases/${branch}"

        if (sha)
            return "${jfRepo}/branches/${branch}/${sha}"

        if (tag)
            return "${jfRepo}/tags/${tag}"

        if (pr)
            return "${jfRepo}/pull-requests/PR-${pr}"

        if (branch)
            return "${jfRepo}/branches/${branch}/${branch}"

        throw new IllegalArgumentException("Unable to determine JFrog base path")
    }

    private static String resolveRepo(String branch) {
        if (!branch)
            return "connectedhomeip"

        return CertificationToolCatalog.isReleaseBranch(branch) ? "certification-tool" : "connectedhomeip"
    }
}
