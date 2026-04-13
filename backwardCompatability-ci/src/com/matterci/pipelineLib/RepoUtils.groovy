package com.matterci.pipelineLib
import com.matterci.pipelineLib.commonPipelineLib
import com.matterci.pipelineLib.CertificationToolCatalog

class RepoUtils implements Serializable {

    // static (class) variables to be used by all pipelines
    // Use different directories for the controller and app SDKs
    static def controllerGitCloneDirectory
    static def appGitCloneDirectory

    // WORKSPACE comes from the jenkins file created under the node.
    static def copiedSDKDirectory
    static def archiveFile

    //initialize static (class) variables.
    static {
        controllerGitCloneDirectory = "controller_sdk"
        appGitCloneDirectory = "app_sdk"
        // WORKSPACE comes from the jenkins file created under the node.
        copiedSDKDirectory = "dir_sdk_copied"
        archiveFile = 'matter_repo_archive.tgz'
    }


    static Map cloneSDKRepo(def steps, Map testConfigs, boolean cloneController, boolean cloneApps) {
        def cloneSuccess = true
        steps.echo "Clone stage running on: ${steps.env.NODE_NAME}"
        steps.echo "cloneController cloneSDK : ${cloneController}"
        steps.echo "cloneApps cloneSDK : ${cloneApps}"

        def nodeWorkspace = "${steps.env.WORKSPACE}"
        def buildIDWorkspace = "${nodeWorkspace}/${steps.env.BUILD_ID}"

        def controllerWorkspace = "${buildIDWorkspace}/${controllerGitCloneDirectory}"
        def appWorkspace = "${buildIDWorkspace}/${appGitCloneDirectory}"

        // we have to keep the zip file in the node workspace so that archive artifacts can upload it
        def archivePath = "${nodeWorkspace}/${archiveFile}"

        // Read controller configuration from testConfigs and clone controller SDK
        def controllerConfig = testConfigs?.ci_config?.clone_sdk_code_stage?.controller_sdk
        def cloneControllerSuccess = true
        def controller_sdk_sha = ''

        // Clone the controller SDK
        if (cloneController) {
            if (controllerConfig) {
                def controllerGitRef = controllerConfig?.branch ?: 'master'
                def controllerShaRef = controllerConfig?.sha
                def controllerTagRef = controllerConfig?.tag
                def controllerPrRef = controllerConfig?.pr
                def controllerRepoUrl = 'git@github.com:project-chip/connectedhomeip.git'
                try {
                    steps.timeout(time: 60, unit: 'MINUTES') {
                        steps.ws(controllerWorkspace) {
                            controller_sdk_sha = RepoUtils.cloneGitRepo(steps, controllerRepoUrl, "connectedhomeip", controllerGitRef,controllerShaRef, controllerTagRef, controllerPrRef)
                            steps.echo "controller SDK SHA cloned : ${controller_sdk_sha}"
                            if (!controller_sdk_sha) {
                                throw new Exception("cloning controller SDK failed")
                            }
                            // save controller SDK SHA
                            testConfigs.ci_config.controller_sdk_sha = "${controller_sdk_sha}"
                        }
                    }
                } catch (Exception e) {
                    cloneControllerSuccess = false
                    steps.echo "Error during controller SDK clone: ${e.getMessage()}"
                    steps.error "Error during controller SDK clone"
                }
            } else {
                steps.echo "Skipping controller SDK clone (no controller SDK config provided)."
            }
        }
        else {
            steps.echo "Controller artifacts exist → skip clone"
        }
        // Read app configuration from testConfigs and clone apps SDK
        def cloneAppSuccess = true
        def app_sdk_sha = ''

        // Clone the app SDK
        if (cloneApps) {
            def appRepoUrl = 'git@github.com:project-chip/connectedhomeip.git' // Default to app repo URL
            try {
                steps.timeout(time: 60, unit: 'MINUTES') {
                    steps.ws(appWorkspace) {
                        steps.sh """
                            set -ex
                            git config --global http.version HTTP/1.1
                            git config --global http.postBuffer 524288000
                            git config --global http.lowSpeedLimit 0
                            git config --global http.lowSpeedTime 999999
                            git clone --progress --verbose ${appRepoUrl} .
                        """
                        steps.echo "Apps repo cloned successfully (checkout deferred)"
                    }
                }
            } catch (Exception e) {
                cloneAppSuccess = false
                steps.echo "Error during app SDK clone: ${e.getMessage()}"
                steps.error "Error during app SDK clone"
            }
        } else {
            steps.echo "Skipping app SDK clone (no app SDK config provided)."
        }
        // Determine the cumulative clone success (AND of both flags)
        cloneSuccess = cloneControllerSuccess && cloneAppSuccess
        steps.echo "cloneControllerSuccess: ${cloneControllerSuccess}, cloneAppSuccess: ${cloneAppSuccess}, cumulative cloneSuccess: ${cloneSuccess}"

        if (cloneSuccess){
            steps.sh """
                set +e

                if [ ! -d "${buildIDWorkspace}" ]; then
                    echo "Workspace ${buildIDWorkspace} does not exist. Skipping archive."
                    exit 0
                fi
                cd ${buildIDWorkspace}

                files=""
                [ -d "${controllerGitCloneDirectory}" ] && files="\$files ${controllerGitCloneDirectory}"
                [ -d "${appGitCloneDirectory}" ] && files="\$files ${appGitCloneDirectory}"

                if [ -z "\$files" ]; then
                    echo "No directories found to archive. Skipping tar."
                    exit 0
                fi

                rm -f ${archivePath}

                echo "Creating archive with: \$files"
                tar -czvf ${archivePath} \$files
            """
        }
        return [success: cloneSuccess, archivePath: "${nodeWorkspace}", updatedTestConfigs: testConfigs]
    }

    //construct the MatterQA repo clone command, to be used in several pipelines
    // controllerDir is different for raspi and nordic
    static int cloneMatterQARepo(def steps, Map testConfigs, String branch, String controllerDir, String ctrlBinariesDir) {

        def qaRepoSha = ''
        def cmdStatus
        def decision = testConfigs.ci_config.artifactDecision
        def controllerCfg = testConfigs.ci_config?.clone_sdk_code_stage?.controller_sdk
        def branchSHA  = controllerCfg?.branch
        def repoSha = 'master'
        def connectedhomeIPSha = decision.platforms.values().any {it.controllerMissing && it.controllerRepo == "connectedhomeip"}
        def certControllerSHA = decision.platforms.values().any {it.controllerRepo != "connectedhomeip"}

        steps.echo "controllerDir  : ${controllerDir}"
        steps.echo "ctrlBinariesDir : ${ctrlBinariesDir}"

        def wheelsDir = "${controllerDir}/${ctrlBinariesDir}"

        def setupCommand = """#!/bin/bash
            set -e
            shopt -s nullglob

            echo "ControllerDir: ${wheelsDir}"
            WHEEL_PATH="${wheelsDir}"

            echo "Looking for wheels in: \$WHEEL_PATH"
            ls -la "\$WHEEL_PATH"
            sudo rm -rf /tmp/chip* || true

            python3 -m venv .venv
            source .venv/bin/activate

            cd "\$WHEEL_PATH"
            echo "Installing wheels sequentially..."
            for wheel in *.whl; do
                echo "Installing \$wheel"
                pip3 install "\$wheel" || true
            done
            echo "Installed python whl files from ${wheelsDir}"
        """

        cmdStatus = steps.sh(script: setupCommand, returnStatus: true)

        steps.echo ">>> cmdStatus for wheel files = ${cmdStatus}"
        if (cmdStatus == 0){
            //create matter_qa repo and install all whl files
            steps.ws("${controllerDir}/matter_qa")
            {
                def qaRepoConfig = testConfigs?.ci_config?.clone_sdk_code_stage?.matter_qa_repo_git_config

                if (qaRepoConfig) {
                    def qaRepoGitRef = qaRepoConfig?.branch ?: 'main'
                    def qaRepoShaRef = qaRepoConfig?.sha
                    def qaRepoTagRef = qaRepoConfig?.tag
                    def qaRepoPrRef = qaRepoConfig?.pr
                    def qaRepoUrl = qaRepoConfig?.repoUrl ?: 'git@github.com:CHIP-Specifications/matter-qa.git' // Default to app repo URL

                    //Since the caller is calling this function in ws , we dont need that step here.
                    try {
                        steps.timeout(time: 60, unit: 'MINUTES') {
                            // Clone the app SDK
                            qaRepoSha = cloneGitRepo(steps, qaRepoUrl, "matter_qa", qaRepoGitRef, qaRepoShaRef, qaRepoTagRef, qaRepoPrRef)
                            if (!qaRepoSha) {
                                throw new Exception("cloning Matter QA repo failed")
                            }
                        }
                    } catch (Exception e) {
                        cmdStatus = 1
                        steps.echo "Error during Matter QA repo clone: ${e.getMessage()}"
                    }
                } else {
                    steps.echo "Skipping cloning QA repo (no app QA repo config provided)."
                }
                // Echo and set the environment variable
                steps.echo "QA repo SHA ${qaRepoSha}"
                steps.env.qa_repo_git_sha = "${qaRepoSha}"
                testConfigs.ci_config.qa_repo_git_sha = "${qaRepoSha}"
            }
            // Determine the correct SHA to use for the sparse checkout based on the decision engine's output
            if (connectedhomeIPSha) {
                repoSha = testConfigs.ci_config.controller_sdk_sha
            }
            else if (certControllerSHA) {
                repoSha = CertificationToolCatalog.getImageSha(branchSHA)
            }

            steps.echo "Using controller SDK SHA for sparse checkout: ${repoSha}"
            setupCommand = """#!/bin/bash
                cd ${controllerDir}
                source .venv/bin/activate
                cd ./matter_qa/
                pip install .
                cd ..
                git clone --filter=blob:none --no-checkout --sparse git@github.com:project-chip/connectedhomeip.git connectedhomeip
                cd connectedhomeip
                git sparse-checkout init --cone
                git sparse-checkout set src scripts credentials data_model
                git fetch --depth 1 origin ${repoSha}
                git checkout FETCH_HEAD
                cd ..
            """
            cmdStatus = steps.sh(script: setupCommand,returnStatus: true)
        }
        return cmdStatus
    }

    // Helper function to handle git cloning for both controller and app, caller to handle null gitSha
    static def cloneGitRepo(def steps, String repoUrl, String dirToClone, String gitRef, String shaRef, String tagRef, String prRef) {

        def gitSha = ''

        try {
            steps.echo "Running git clone for repository: ${repoUrl}"
            // Debugging step to check steps object
            steps.echo "Steps object: ${steps}"

            if (gitRef == 'master' && shaRef == "" &&  tagRef == "" && prRef == "" ) {
                steps.sh """
                    set -ex
                    git config --global http.version HTTP/1.1
                    git config --global http.postBuffer 524288000
                    git config --global http.lowSpeedLimit 0
                    git config --global http.lowSpeedTime 999999
                    git clone --depth=1 --progress --verbose ${repoUrl} .
                """
            }
            else {
                // clone the full repo
                steps.sh """
                set -ex
                git config --global http.version HTTP/1.1
                git config --global http.postBuffer 524288000
                git config --global http.lowSpeedLimit 0
                git config --global http.lowSpeedTime 999999
                git clone --progress --verbose ${repoUrl} .
                """

                if (gitRef != 'master') {
                    steps.sh """
                        set -ex
                        git checkout ${gitRef}  # Checkout the branch
                        echo "Checked out branch ${gitRef}"
                    """
                }
                // If a SHA is provided, checkout that specific SHA
                if (shaRef) {
                    steps.sh """
                        set -ex
                        git checkout ${shaRef}  # Checkout the specific commit SHA
                        echo "Checked out SHA ${shaRef} on branch ${gitRef}"
                    """
                } else if (tagRef) {
                    // If a tag is provided, checkout that specific tag
                    steps.sh """
                        set -ex
                        git checkout tags/${tagRef}  # Checkout the specified tag
                        echo "Checked out tag ${tagRef}"
                    """
                } else if (prRef) {
                    // Handle PR reference
                    steps.sh """
                        set -ex
                        git fetch origin pull/${prRef}/head:pr-${prRef}
                        git checkout pr-${prRef}
                    """
                }
            }

            // Return the Git SHA of the repository after checkout
            gitSha = steps.sh(script: "git rev-parse HEAD", returnStdout: true).trim()
            steps.echo "Git SHA: ${gitSha}"

        } catch (Exception e) {
            steps.echo "Error during git clone or checkout: ${e.getMessage()}"
        }
        return gitSha
    }

    static String checkoutGitRef(def steps,String workspaceDir,String branch,String sha,String tag,String pr) {
        steps.echo "Checking out reference inside ${workspaceDir}"
        def resolvedSha = sha
        steps.ws(workspaceDir) {
            steps.sh """
                set -ex
                echo "Cleaning previous build artifacts..."

                git reset --hard
                git clean -xfd
                rm -rf out
                rm -rf .environment
                rm -rf third_party/pigweed/.environment
            """
            if (sha) {
                steps.echo "Checking out SHA: ${sha}"
                steps.sh """
                    set -ex
                    git checkout ${sha}
                """
            }
            else if (tag) {
                steps.echo "Checking out TAG: ${tag}"
                steps.sh """
                    set -ex
                    git checkout tags/${tag}
                """
            }
            else if (pr) {
                steps.echo "Checking out PR: ${pr}"
                steps.sh """
                    set -ex
                    git fetch origin pull/${pr}/head:pr-${pr}
                    git checkout pr-${pr}
                """
            }
            else if (branch) {
                steps.echo "Checking out BRANCH: ${branch}"
                steps.sh """
                    set -ex
                    git checkout ${branch}
                    git pull
                """
            }

            //Resolve actual checked-out commit SHA
            resolvedSha = steps.sh(
                script: "git rev-parse HEAD",
                returnStdout: true
            ).trim()
        }
        steps.echo "Resolved checkout SHA: ${resolvedSha}"
        return resolvedSha
    }

    //caller to provide the platformBinariesDirString
    static Map getSDKCodeFromBuildArtifacts(def steps, String platformBinariesDirString){

        def nodeWorkspace = "${steps.env.WORKSPACE}"
        def buildIDWorkspace = "${nodeWorkspace}/${steps.env.BUILD_ID}"
        def workSpaceToCopySDK = "${buildIDWorkspace}/${RepoUtils.copiedSDKDirectory}"  //copiedSDKDirectory static variable in this class
        def platformBinariesDir = "${workSpaceToCopySDK}/${platformBinariesDirString}"

        def controllerBuildWorkSpace = "${workSpaceToCopySDK}/${RepoUtils.controllerGitCloneDirectory}"
        def appsBuildWorkSpace = "${workSpaceToCopySDK}/${RepoUtils.appGitCloneDirectory}"

        def cmdStatus = ''
        def unzipSuccesful = true

        try{
            steps.echo "workspace to download SDK code: ${workSpaceToCopySDK} "
            steps.timeout(time: 120, unit: 'MINUTES') {

                //get the SDK code from Build Artifacts
                steps.echo "copied SDK directory is : ${RepoUtils.copiedSDKDirectory}"

                steps.ws("${workSpaceToCopySDK}"){
                    steps.step([
                        $class: 'CopyArtifact',
                        projectName: "${steps.env.JOB_NAME}",
                        selector: steps.specific("${steps.env.BUILD_NUMBER}"),
                        filter: '**/matter_repo_archive.tgz',
                        target: '.'
                    ])

                    cmdStatus = steps.sh(
                                script: """#!/bin/bash
                                    set -ex
                                    tar -xzf matter_repo_archive.tgz
                                    mkdir -p ${platformBinariesDir}
                                    """,
                                    returnStatus: true)
                    steps.echo "status of the command tar -xzf matter_repo_archive.tgz is : ${cmdStatus}"

                    if (cmdStatus !=0){
                        unzipSuccesful = false
                        steps.error(" getSDKCodeFromArtifacts failed. Build stopped.")
                    }
                }
            }
        }catch (Exception e) {
            unzipSuccesful = false
            steps.echo "Error during downloading artifacts : ${e.getMessage()}"
        }
        return [success: unzipSuccesful, cntrlBuildWorkSpace: "${controllerBuildWorkSpace}", appsBuildWorkSpace: "${appsBuildWorkSpace}" , workSpaceSDKCopied: "${workSpaceToCopySDK}" ]
    }
}
