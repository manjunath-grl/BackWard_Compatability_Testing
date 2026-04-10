import com.matterci.pipelineLib.RepoUtils
import com.matterci.pipelineLib.commonPipelineLib

/*
Design decision:
Jenkinsfile handles stages
Shared lib handles cloning logic
*/

def call(testConfigs, decision) {
    def updatedTestConfigs = testConfigs
    stage("clone code") {
        node(testConfigs.ci_config.clone_sdk_code_stage.node_to_clone_code) {
            //Detect controller clone requirement
            def controllerMissing = decision.platforms.values().any {it.controllerMissing && it.controllerRepo == "connectedhomeip"}

            //Detect connectedhomeip apps clone requirement
            def connectedhomeipAppsMissing = decision.platforms.values().any { platform ->
                    platform.apps.any { app ->
                        app.missing &&
                        app.repo == "connectedhomeip"
                    }
                }
            echo "decision controllerMissing: ${controllerMissing}"
            echo "decision connectedhomeipAppsMissing: ${connectedhomeipAppsMissing}"

            //certification-tool apps NEVER cloned here
            def result = RepoUtils.cloneSDKRepo(this, testConfigs,controllerMissing,connectedhomeipAppsMissing)

            if (result.success) {
                updatedTestConfigs = result.updatedTestConfigs
                echo "Archiving cloned repo from: ${result.archivePath}"
                def mergedYaml = writeYaml returnText: true, data: updatedTestConfigs
                writeFile file: 'UpdatedTestConfig.yaml', text: mergedYaml
                archiveArtifacts artifacts:"matter_repo_archive.tgz",fingerprint: true,allowEmptyArchive: true
                return updatedTestConfigs
            }
            else {
                error("Cloning repo failed. Build stopped.")
            }
        }
    }
}