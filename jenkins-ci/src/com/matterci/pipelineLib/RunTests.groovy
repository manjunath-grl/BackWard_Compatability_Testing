package com.matterci.pipelineLib

import groovy.json.*
import com.matterci.pipelineLib.TestUtils

class RunTests {

static runTestCase( def steps, String workspace, Map testParams, String testcaseName) {
        def runTestCaseStage = true
        def defaultTestRunTimeout = 1 //In minutes
        def testRunStatus = 1 // Set the default test run status to 1 (false)
        //TODO Fix: remove this hardcoded path
        def testcase = "matter_qa/src/matter_qa/scripts/reliability_scripts/${testcaseName}.py"
        def testRunTimeout = TestUtils.findKeyValue(testParams, "test_case_config.${testcaseName}.timeout") ?: defaultTestRunTimeout

        steps.echo "Controller Workspace : ${workspace}"
        try{
            steps.ws(workspace) {
                steps.sh """
                        if [ -f admin_storage.json ]; then
                            rm -rf admin_storage.json
                        fi
                    """
                // update ci config to add test results path , get the ws path and platform, commissioning method to that
                def ci_ws_path = TestUtils.findKeyValue(testParams,"ci_config.ci_ws_path")

                def platform_execution = TestUtils.findKeyValue(testParams,"general_configs.platform_execution")
                steps.echo " platform_execution : ${platform_execution} "

                def dut_config_descriminator = TestUtils.findKeyValue(testParams,"dut_config.${platform_execution}.app_config.discriminator")
                steps.echo " dut_config_descriminator : ${dut_config_descriminator}"
                if (dut_config_descriminator == null) {
                    steps.error("Pipeline failed in 'Run Tests on CONTROLLER_NODE' stage. discriminator is null")
                }

                def commissioning_method_list = TestUtils.findKeyValue(testParams, "dut_config.${platform_execution}.commissioning_method")
                steps.echo "commissioning_method_list ${commissioning_method_list}"
                if (commissioning_method_list == null) {
                    steps.error("Pipeline failed in 'Run Tests on CONTROLLER_NODE' stage. commissioning_method_list is null")
                }
                def commissioning_method_string = commissioning_method_list.join(' ')

                steps.echo "commissioning_method_string ${commissioning_method_string}"


                // Define the path to the test results folder in the workspace
                def testResultsDir = "test_results"
                //create this to send the folder to python executor for storing test results.

                def ci_test_results_path_in_ws = "${ci_ws_path}/${testResultsDir}/${platform_execution}/${commissioning_method_list[0]}"

                steps.echo "ci_test_results_path_in_ws is : ${ci_test_results_path_in_ws}"

                TestUtils.updateOrCreateKeyValue(testParams, "ci_config.ci_test_results_path_in_ws", "${ci_test_results_path_in_ws}")

                // Convert testParams to JSON format to pass to python script
                def testParamsJson = JsonOutput.toJson(testParams)

                steps.echo "Result: ${commissioning_method_string}"
                steps.echo "Workspace: ${workspace}"
                steps.echo "Test Script : ${testcase}"
                steps.echo "Test Parameters: ${testParams}"
                steps.echo "JSON Test Parameters: ${testParamsJson}"
                steps.echo "This stage runTests is running on: ${steps.env.NODE_NAME}"

                // Build platform-specific extra CLI args safely
                def commissioningExtraArgs = []

                def platformArgs = ""
                if (platform_execution?.toLowerCase()?.contains("rpi")) {
                    if (commissioning_method_string == "ble-wifi") {
                        platformArgs = TestUtils.buildExtraCLIArgs(steps, testParams, "ci_config.raspi_pipeline.stages.run_tests.ble_wifi.script_extra_command_line_args") ?: ""
                    } else if (commissioning_method_string == "on-network") {
                        platformArgs = TestUtils.buildExtraCLIArgs(steps, testParams, "ci_config.raspi_pipeline.stages.run_tests.on_network.script_extra_command_line_args") ?: ""
                    } else {
                        steps.echo "Unknown commissioning method: '${commissioning_method_string}'. No extra CLI args applied for Raspi."
                    }
                } else if (platform_execution?.toLowerCase()?.contains("nordic")) {
                    platformArgs = TestUtils.buildExtraCLIArgs(steps, testParams, "ci_config.nordic_pipeline.stages.run_tests.script_extra_command_line_args") ?: ""
                } else if (platform_execution?.toLowerCase()?.contains("silabs")) {
                    platformArgs = TestUtils.buildExtraCLIArgs(steps, testParams, "ci_config.silabs_pipeline.stages.run_tests.script_extra_command_line_args") ?: ""
                }

                steps.echo "Resolved extra command-line args: ${platformArgs ?: 'None (default)'}"

                def command = """#!/bin/bash
                        set -ex
                        source .venv/bin/activate
                        sleep 2
                        pwd
                        python3 ${testcase} \\
                        --discriminator ${dut_config_descriminator} \\
                        --passcode 20202021 --storage-path admin_storage.json \\
                        --timeout 90000 --commissioning-method ${commissioning_method_string} \\
                        --logs-path ~/MatterTestsLogs/${steps.env.JOB_NAME}/${steps.env.BUILD_NUMBER} \\
                        --string-arg reliability_tests_arg:matter_qa/src/matter_qa/configs/configFile.yaml \\
                        --trace-to json:log \\
                        --paa-trust-store-path ./connectedhomeip/credentials/development/paa-root-certs/ \\
                        ${platformArgs} \\
                        --json-arg overwrite_reliability_tests_args:'${testParamsJson}'
                """
                // 🖨️ Print the final command
                steps.echo "Running test command:\n${command}"

                // 🧪 Execute the command
                steps.timeout(time: testRunTimeout, unit: 'MINUTES') {
                    testRunStatus = steps.sh(script: command, returnStatus: true)
                }

                // Archive the test results folder as build artifacts
                steps.archiveArtifacts(
                    artifacts: "${testResultsDir}/**/*", // Include all files inside the test results folder
                    allowEmptyArchive: true, // If no files exist, allow empty archive
                    followSymlinks: false
                )

                // Optionally print the path to the test results for reference
                steps.echo "Test results for ${testcaseName} are archived from ${testResultsDir}"
                if (testRunStatus != 0) {
                    runTestCaseStage = false
            }
            }
        }catch (Exception e) {
            runTestCaseStage = false
            steps.echo "Error during runTestCase ${testcase}: ${e.getMessage()}"
        }
        if (!runTestCaseStage) {
            steps.echo "runTestCase ${testcase} stage failed. Taking appropriate action..."
            steps.error("runTestCase on ${testcase} stage failed.")
        }
    }
    def runTests(def steps, String workspace, Map testParams, List testCaseList) {
        def hasFailures = false
        steps.echo "List of TestCases to be run : ${testCaseList}"
        for (testCase in testCaseList) {
            def lastWord = testCase.tokenize('/').last().tokenize('.').first()
            steps.stage("${lastWord}") {
                steps.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {  // Continue on failure
                    RunTests.runTestCase(steps, workspace,testParams, testCase)
                }
                if (steps.currentBuild.result == 'FAILURE') {
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