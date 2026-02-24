package com.matterci.pipelineLib

import groovy.json.*
import com.matterci.pipelineLib.TestUtils

class RunTests {

    def runTests(def steps, String workspace, String runnerConfigFile, String logPath) {

        def hasFailures = false

        steps.stage("Run Tests") {

            steps.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {

                def currentDate = new Date().format("dd_MM_yyyy")
                def dateLogPath = "${logPath}/LOGS_${currentDate}"

                // Create base log path
                steps.sh("mkdir -p ${logPath}")
                steps.sh("mkdir -p ${dateLogPath}")
                steps.sh("rm -rf ${dateLogPath}/*")
                def configData = steps.readYaml(file: runnerConfigFile)
                steps.echo "Matter-QA Path: ${configData.Testcase_runner_config.dut_config.rpi.app_config.matter_ap}"
                // Run Python test runner
                def status = steps.sh(
                    script: """#!/bin/bash
                        set -ex
                        cd "\$HOME"

                        if [ ! -d ".venv" ]; then
                            echo "ERROR: Python virtualenv .venv not found"
                            exit 1
                        fi

                        source .venv/bin/activate

                        python3 "\$HOME/testcase_runner.py" \\
                            --runner-test-config "${runnerConfigFile}" \\
                            --log-path "${dateLogPath}"
                    """,
                    returnStatus: true
                )

                if (status != 0) {
                    hasFailures = true
                }
            }
        }

        // Mark overall build as FAILURE if any test failed
        if (hasFailures) {
            steps.currentBuild.result = 'FAILURE'
        }
    }
}