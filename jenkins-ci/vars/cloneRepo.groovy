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
                def mergedYaml = writeYaml returnText: true, data: updatedTestConfigs
                writeFile file: 'UpdatedTestConfig.yaml', text: mergedYaml
                archiveArtifacts artifacts: "matter_repo_archive.tgz", fingerprint: true, allowEmptyArchive: true
                return updatedTestConfigs
            } else {
                error("Cloning repo failed. Build stopped.")
            }
        }
    }
}