package com.matterci.pipelineLib

import com.matterci.pipelineLib.TestUtils

class ConfigLoader {

    static Map appendCIConfig(def steps , Map testConfigs){

        testConfigs.ci_config.ci_job_name = steps.env.JOB_NAME
        testConfigs.ci_config.ci_job_build_id = steps.env.BUILD_ID.toInteger()

        def controller_sdk_config = testConfigs?.ci_config?.clone_sdk_code_stage?.controller_sdk_config
        def apps_sdk_config = testConfigs?.ci_config?.clone_sdk_code_stage?.apps_sdk_config
        def matter_qa_repo_git_config = testConfigs?.ci_config?.clone_sdk_code_stage?.matter_qa_repo_git_config

        // Pass the SDK SHA info to the python tests
        // Update ci_sdk values if not null
        if (controller_sdk_config?.branch) {
            testConfigs.ci_config.ci_sdk_branch = controller_sdk_config.branch
        }
        if (controller_sdk_config?.sha) {
            testConfigs.ci_config.ci_sdk_sha = controller_sdk_config.sha
        }
        if (controller_sdk_config?.tag) {
            testConfigs.ci_config.ci_sdk_tag = controller_sdk_config.tag
        }
        if (controller_sdk_config?.pr) {
            testConfigs.ci_config.ci_sdk_pr = controller_sdk_config.pr

        }

        // Update ci_apps values if not null
        if (apps_sdk_config?.branch) {
            testConfigs.ci_config.ci_apps_branch = apps_sdk_config.branch
        }
        if (apps_sdk_config?.sha) {
            testConfigs.ci_config.ci_apps_sha = apps_sdk_config.sha
        }
        if (apps_sdk_config?.tag) {
            testConfigs.ci_config.ci_apps_tag = apps_sdk_config.tag
        }
        if (apps_sdk_config?.pr) {
            testConfigs.ci_config.ci_apps_pr = apps_sdk_config.pr
        }

        // Update matter_qa_repo_git_config values if not null
        if (matter_qa_repo_git_config?.branch) {
            testConfigs.ci_config.ci_qa_repo_branch = matter_qa_repo_git_config.branch
        }
        if (matter_qa_repo_git_config?.sha) {
            testConfigs.ci_config.ci_qa_repo_sha = matter_qa_repo_git_config.sha
        }
        if (matter_qa_repo_git_config?.tag) {
            testConfigs.ci_config.ci_qa_repo_tag = matter_qa_repo_git_config.tag
        }
        if (matter_qa_repo_git_config?.pr) {
            testConfigs.ci_config.ci_qa_repo_pr = matter_qa_repo_git_config.pr
        }
        steps.echo "updated config with CI parameters ${testConfigs}"

        return testConfigs
    }

    static Map loadMergedConfig(def steps) {
        steps.echo "🔄 Loading configuration from two separate files (CI + TestRun configs)"

        // Load default config from shared library resources
        def defaultCiYamlText = steps.libraryResource('defaultCiConfig.yaml')
        def defaultTestRunYamlText = steps.libraryResource('defaultTestRunConfig.yaml')
        Map defaultCiConfig = steps.readYaml(text: defaultCiYamlText)
        Map defaultTestRunConfig = steps.readYaml(text: defaultTestRunYamlText)

        // Merge default configs
        Map defaultConfig = ConfigLoader.deepMergeConfigs(defaultCiConfig, defaultTestRunConfig)
        steps.echo "✅ Merged default configs with combined keys: ${defaultConfig.keySet()}"

        // Load user YAML files
        Map uploadedConfig = [:]

        // File parameters get full paths in env
        def ciConfigPath = steps.env.ci_config_file
        def testRunConfigPath = steps.env.test_run_config_file

        steps.echo "📄 CI config file path: ${ciConfigPath}"
        steps.echo "📄 Test run config file path: ${testRunConfigPath}"

        // Load CI config file
        if (ciConfigPath && steps.fileExists(ciConfigPath)) {
            try{
                Map ciConfig = steps.readYaml(file: ciConfigPath)
                uploadedConfig = ConfigLoader.deepMergeConfigs(uploadedConfig, ciConfig)
                steps.echo "✅ Successfully loaded and merged CI config file."
            }catch (Exception e) {
                steps.echo "⚠️ Error loading CI config file: ${e.message}"
            }
        } else {
            steps.echo "ℹ️ CI config file not provided or doesn't exist, using default CI config"
        }

        // Load Test config file
        if (testRunConfigPath && steps.fileExists(testRunConfigPath)) {
            try{
                Map testRunConfig = steps.readYaml(file: testRunConfigPath)
                uploadedConfig = ConfigLoader.deepMergeConfigs(uploadedConfig, testRunConfig)
                steps.echo "✅ Successfully loaded and merged Test run config file."
            }catch (Exception e) {
                steps.echo "⚠️ Error loading Test run config file: ${e.message}"
            }
        } else {
            steps.echo "ℹ️ Test config file not provided or doesn't exist, using default Test run config"
        }

        steps.echo "📋 Final uploaded config keys: ${uploadedConfig.keySet()}"

        // Merge user config on top of default
        def mergedConfig = ConfigLoader.deepMergeConfigs(defaultConfig, uploadedConfig)
        steps.echo "✅ Final merged config with keys: ${mergedConfig.keySet()}"

        // Override with Jenkins job parameters
        mergedConfig = ConfigLoader.overrideWithJenkinsParams(steps, mergedConfig)

        // Append CI config
        mergedConfig = ConfigLoader.appendCIConfig(steps, mergedConfig)

        steps.echo "🎯 Configuration loading completed successfully!"

        return mergedConfig

    }

    static Map deepMergeConfigs(Map baseMap, Map overrideMap) {
        Map merged = [:]
        merged.putAll(baseMap)

        overrideMap.each { key, value ->
            if (merged[key] instanceof Map && value instanceof Map) {
                // Recursively merge nested maps
                merged[key] = deepMergeConfigs(merged[key], value)
            } else if (merged[key] instanceof List && value instanceof List) {
                // If both are lists, merge them by adding the items from overrideMap to baseMap
                merged[key] = (merged[key] + value).unique()
            } else {
                // Override or add
                merged[key] = value
            }
        }
        return merged
    }
    static Map overrideWithJenkinsParams(def steps, Map config) {
        steps.echo "Overriding config with Jenkins UI parameters if present"

        if (steps.params.number_of_iterations) {
            int iterations = steps.params.number_of_iterations.toInteger()
            config.general_configs.number_of_iterations = iterations
            steps.echo "Overridden number_of_iterations = ${iterations}"
        }

        // Similarly you can add more parameters if you want, e.g.
        // if (steps.params.wifi_ssid) { config.network_config.wifi_ssid = steps.params.wifi_ssid }

        return config
    }


    static List<String> getTestCaseList(Map config) {
        return config.test_case_config?.keySet()?.toList() ?: []
    }
}