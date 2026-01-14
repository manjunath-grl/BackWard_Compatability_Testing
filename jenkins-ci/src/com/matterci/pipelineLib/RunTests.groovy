package com.matterci.pipelineLib

import groovy.json.*
import com.matterci.pipelineLib.TestUtils

class RunTests {
    def runTests(def steps, String workspace) {
        def hasFailures = false
        steps.stage("${lastWord}") {
            steps.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {  // Continue on failure
            }
        }
        if (hasFailures) {
            //this will help to mark cumilatave stage as fail bcoz of one the intermediate stage failed
            steps.currentBuild.result = 'FAILURE'
        }
    }
}