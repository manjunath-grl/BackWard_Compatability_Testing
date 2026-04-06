package com.matterci.pipelineLib

import groovy.json.*
import com.matterci.pipelineLib.TestUtils
import com.matterci.pipelineLib.commonPipelineLib

class RunTests {

    def runTests(def steps, String workspace, String runnerConfigFile, String logPath) {
        def hasFailures = false
        // Extract app name from log path for better stage labeling
        def stageLabel = logPath.tokenize('/').takeRight(2).join(" - ")
        steps.stage("Run Tests - ${stageLabel}") {
            steps.catchError(buildResult: 'SUCCESS',stageResult: 'FAILURE') {

                def currentDate = new Date().format("dd_MM_yyyy")
                def dateLogPath = "${logPath}/${stageLabel}-${currentDate}"

                steps.echo "Creating log directory: ${dateLogPath}"
                steps.sh """
                    mkdir -p "${dateLogPath}"
                """
                def configData = steps.readYaml(file: runnerConfigFile)

                steps.echo "Test Runner Config: ${configData}"

                def checkMatterQa = configData.Testcase_runner_config.dut_config.rpi.app_config.matter_app

                steps.echo "Matter-QA Path: ${checkMatterQa}"
                def status = steps.sh(
                    script: """#!/bin/bash
                        set -ex
                        cd "${workspace}"
                        if [ ! -d ".venv" ]; then
                            echo "ERROR: Python virtualenv .venv not found"
                            exit 1
                        fi
                        source .venv/bin/activate

                        python3 "\$HOME/testcase_runner.py" \\
                            --runner-test-config "${runnerConfigFile}" \\
                            --log-path "${dateLogPath}"

                        #python3 "${workspace}/matter-qa/src/matter_qa/scripts/testcase_runner.py" \\
                        #    --runner-test-config "${runnerConfigFile}" \\
                        #    --log-path "${dateLogPath}"
                    """,
                    returnStatus: true
                )

                if (status != 0) {
                    steps.echo "Test execution failed for ${stageLabel}"
                    hasFailures = true
                }
            }
        }
        if (hasFailures) {
            steps.currentBuild.result = 'FAILURE'
        }
    }
}