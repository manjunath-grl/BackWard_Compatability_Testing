package com.matterci.pipelineLib

import groovy.json.*

/**
 * Utility class for test-related operations, including key finding, updating, and CLI argument building.
 */
class TestUtils {

    /**
     * Executes a shell command (deprecated, use Jenkins steps instead).
     * @param cmd Shell command string
     */
    static def execute_shell_command(String cmd) {
            def shell = '/bin/bash'
            sh """$shell -c "{$cmd}" """
        }

    static def findKeyValue(map, String keyPath) {
        def keys = keyPath.tokenize('.')
        def current = map

        for (key in keys) {
            if (current instanceof Map && current.containsKey(key)) {
                current = current[key]
            } else {
                return null  // Key path is invalid
            }
        }
        return current
    }
    // Function to update a nested key
    static def updateOrCreateKeyValue(Map map, String keyPath, value) {
        def keys = keyPath.tokenize('.')
        def current = map

        for (int i = 0; i < keys.size() - 1; i++) {
            def key = keys[i]
            if (!current.containsKey(key) || !(current[key] instanceof Map)) {
                current[key] = [:]  // Ensure path exists as a nested map
            }
            current = current[key]  // Navigate to the next level
        }

        // Set the final key with the new value (REPLACE instead of append)
        current[keys[-1]] = value
    }

    static def deepCopy(map) {
        // Convert map to JSON string
        def jsonString = JsonOutput.toJson(map)
        // Convert JSON back to a new map
        return new JsonSlurperClassic().parseText(jsonString)
    }

    /**
    * Converts YAML-style script_extra_command_line_args into a safe CLI string.
    * Supports:
    * - Map: { key: value }
    * - List: [{key: value}, ...]
    * - Null / empty: returns ""
    */
    static String buildExtraCLIArgs(def steps, Map testParams, String path) {
        // Define keys to be ignored
        def ignoredKeys = [
            "discriminator",
            "passcode",
            "storage-path",
            "timeout",
            "commissioning-method",
            "logs-path",
            "string-arg",
            "trace-to",
            "paa-trust-store-path"
        ] as Set

        def extraArgsConfig = findKeyValue(testParams, path)
        def cliArgs = ""

        if (extraArgsConfig == null) {
            steps.echo "No extra command line arguments found for path: ${path}"
            return cliArgs
        }

        def argList = []

        // Normalize to list of key-value pairs
        if (extraArgsConfig instanceof Map) {
            extraArgsConfig.each { k, v ->
                if (k && v != null && v.toString().trim()) {
                    argList << [key: k, value: v.toString()]
                }
            }
        } else if (extraArgsConfig instanceof List) {
            extraArgsConfig.each { item ->
                if (item instanceof Map && item.key && item.value) {
                    argList << [key: item.key, value: item.value.toString()]
                }
            }
        }

        // Print ignored keys found in config (for debugging)
        def ignoredFound = argList.findAll { it.key in ignoredKeys }*.key
        if (!ignoredFound.isEmpty()) {
            steps.echo "Ignoring predefined CLI keys: ${ignoredFound.join(', ')}"
        }

        // Filter out ignored keys before constructing CLI args
        argList = argList.findAll { !(it.key in ignoredKeys) }

        // Build CLI argument string
        argList.each { arg ->
            cliArgs += " --${arg.key} ${arg.value}"
        }

        steps.echo "Dynamic CLI arguments: ${cliArgs.trim()}"
        return cliArgs.trim()
    }

    /**
     * Executes a closure with automatic retry on failure
     * CRITICAL: Use ONLY for explicitly identified flaky operations
     * Has safeguards to prevent infinite loops
     *
     * @param steps Jenkins pipeline steps context
     * @param maxAttempts Maximum number of attempts (must be >= 1, <= 5)
     * @param delaySeconds Delay between retries in seconds
     * @param closure The operation to execute
     * @return Result of successful execution
     */
    static def withRetry(def steps, int maxAttempts, int delaySeconds, Closure closure) {
        // Safeguard: Cap maxAttempts to prevent infinite loops
        if (maxAttempts < 1) maxAttempts = 1
        if (maxAttempts > 5) maxAttempts = 5

        def lastException = null

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                steps.echo "Attempt ${attempt}/${maxAttempts}"
                def result = closure()
                if (attempt > 1) {
                    steps.echo "SUCCESS on attempt ${attempt} after ${attempt - 1} retries"
                }
                return result
            } catch (Exception e) {
                lastException = e
                steps.echo "ERROR on attempt ${attempt}/${maxAttempts}: ${e.getMessage()}"

                if (attempt < maxAttempts) {
                    steps.echo "Retrying in ${delaySeconds} seconds..."
                    steps.sleep(time: delaySeconds, unit: 'SECONDS')
                }
            }
        }

        // All attempts exhausted
        steps.echo "FATAL: All ${maxAttempts} attempts failed. Last error: ${lastException.getMessage()}"
        throw lastException
    }
}
