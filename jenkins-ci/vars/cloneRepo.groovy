import com.matterci.pipelineLib.RepoUtils

/* design decision: Jenkins file code is expected to handle jenkins
stages, steps.. etc core logic is in shared lib.
*/
def call (testConfigs) {
    def updatedTestConfigs = testConfigs
    stage ("clone code") {
        //TODO: Fix the naming to come from config
        node(testConfigs.ci_config.clone_sdk_code_stage.node_to_clone_code) {
            def result = RepoUtils.cloneSDKRepo(this,testConfigs)

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
                archiveArtifacts artifacts: 'UpdatedTestConfig.yaml', fingerprint: true
                archiveArtifacts artifacts: "matter_repo_archive.tgz", fingerprint: true
                //}
                return updatedTestConfigs
            } else {
                error("Cloning repo failed. Build stopped.")
            }
        }
    }
}