package com.matterci.pipelineLib

import groovy.json.*
import com.matterci.pipelineLib.TestUtils

class RunTests {
    def runTests(def steps, String workspace, String yamlPath, String logPath) {
        def hasFailures = false
        steps.stage("Run Tests") {
            steps.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {  // Continue on failure
                def currentDate = new Date().format("dd_MM_yyyy")
                def dateLogPath = "${logPath}/LOGS_${currentDate}"
                // Create log path if it doesn't exist
                steps.sh(script: "mkdir -p ${logPath}", returnStatus: true)
                // Create LOGS_date subfolder
                steps.sh(script: "mkdir -p ${dateLogPath}", returnStatus: true)
                // Clear the LOGS_date folder
                steps.sh(script: "rm -rf ${dateLogPath}/*", returnStatus: true)
                // Run the Python script
                def status = steps.sh(script: """
                set -ex
                cd "\$HOME"
                source .venv/bin/activate
                python3 \"\$HOME/testcase_runner.py\" --runner-test-config ${yamlPath} --log-path ${dateLogPath}", returnStatus: true)
                """, returnStatus: true)
                if (status != 0) {
                    hasFailures = true
                }
            }
        }
        if (hasFailures) {
            //this will help to mark cumilatave stage as fail bcoz of one the intermediate stage failed
            steps.currentBuild.result = 'FAILURE'
        }
    }
}