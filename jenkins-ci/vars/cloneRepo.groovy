import com.matterci.pipelineLib.RepoUtils
import com.matterci.pipelineLib.commonPipelineLib

/* design decision: Jenkins file code is expected to handle jenkins
stages, steps.. etc core logic is in shared lib.
*/
def call (testConfigs, decision) {
    def updatedTestConfigs = testConfigs
    stage ("clone code") {
        //TODO: Fix the naming to come from config
        node(testConfigs.ci_config.clone_sdk_code_stage.node_to_clone_code) {
            def controllerMissing = decision.platforms.values().any { it.controllerMissing }
            def appsMissing = decision.platforms.values().any { it.appsMissing }

            echo "decision controllerMissing: ${controllerMissing}"
            echo "decision appsMissing: ${appsMissing}"

            def result = RepoUtils.cloneSDKRepo(this,testConfigs,controllerMissing,appsMissing)
            if (result.success) {
                updatedTestConfigs = result.updatedTestConfigs
                echo "Archiving cloned repo from: ${result.archivePath}"
                //TODO: Fix this workspace issue properly.
                //ws(result.archivePath)
                //{
                // Convert Map to YAML text
                def mergedYaml = writeYaml returnText: true, data: updatedTestConfigs
                // Save to file
                writeFile file: 'UpdatedTestConfig.yaml', text: mergedYaml
                // Archive it
                //archiveArtifacts artifacts: 'UpdatedTestConfig.yaml', fingerprint: true
                archiveArtifacts artifacts: "matter_repo_archive.tgz", fingerprint: true, allowEmptyArchive: true
                // def jfrogRepoName = testConfigs.ci_config.jfrog_config.jfrog_repo_name
                // def jobName  = env.JOB_NAME
                // def buildNum = env.BUILD_NUMBER
                // def targetPath = "${jfrogRepoName}/${jobName}/${buildNum}/"
                // sh """
                //     set -e
                //     jf rt u \
                //     "UpdatedTestConfig.yaml" \
                //     "${targetPath}" \
                //     --flat=false \
                //     --build-name=${jobName} \
                //     --build-number=${buildNum}
                // """
                //  echo "JFrog upload verified successfully."
                //}
                return updatedTestConfigs
            } else {
                error("Cloning repo failed. Build stopped.")
            }
        }
    }
}