package com.matterci.pipelineLib

class HTMLReportGeneration {

    def resolveDutRef(app) {
        if (app.sha) {
            def baseRef = app.branch ?: app.tag ?: (app.pr ? "PR-${app.pr}" : "master")
            return "${baseRef} (${app.sha})"
        }
        return app.tag ?: (app.pr ? "PR-${app.pr}" : app.branch)
    }

    def resolveControllerRef(testConfigs) {
        def ctrl = testConfigs.ci_config.clone_sdk_code_stage.controller_sdk
        if (ctrl.sha) {
            def baseRef = ctrl.branch ?: ctrl.tag ?: (ctrl.pr ? "PR-${ctrl.pr}" : "master")
            return "${baseRef} (${ctrl.sha})"
        }
        return ctrl.tag ?: (ctrl.pr ? "PR-${ctrl.pr}" : ctrl.branch)
    }

    def generateReport(def steps, String logDir, String buildNumber, def testConfigs) {
        def controllerRef = resolveControllerRef(testConfigs)
        
        def jsonFiles = []
        steps.dir(logDir) {
            jsonFiles = steps.findFiles(glob: "**/execution_results.json")
        }
        
        if (jsonFiles.length == 0) {
            steps.error "No execution_results.json files found. Ensure Python runner is creating them."
        }

        def masterData = [:]
        jsonFiles.each { file ->
            def data = steps.readJSON(file: "${logDir}/${file.path}")
            masterData << data
        }

        // --- FIX: Encapsulate state into a single 'state' map ---
        def state = [
            durationMatrix: [:],
            testcaseExecutionOrder: []
        ]
        
        def chartJSUrl = "https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.0/chart.umd.min.js"

        def html = """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Backward Compatibility Report</title>
    <script src="${chartJSUrl}"></script>
</head>
<body style="font-family:Arial; margin:25px; background-color:#f7f9fc;">
    <h1 style="color:#1f2d3d;">Backward Compatibility Report</h1>
    <div style="background-color:#ecf0f1; padding:12px; border-left:6px solid #2c3e50; margin-bottom:25px;">
        <h2 style="color:#2c3e50;"><b>Controller:</b> ${controllerRef}<br><b>Build:</b> ${buildNumber}</h2>
    </div>
"""

        masterData.each { appKey, testCases ->
            def chartId = "chart_" + appKey.replaceAll(/[^a-zA-Z0-9]/, '_')
            
            html += """<h2 style="color:#2c3e50; margin-top:35px;">DUT: ${appKey}</h2>
            <table style="border-collapse:collapse; width:100%; background-color:white;">
                <tr style="background-color:#2c3e50; color:white;">
                    <th style="padding:10px;">Testcase</th>
                    <th style="padding:10px;">Status</th>
                    <th style="padding:10px;">Duration (sec)</th>
                </tr>"""

            testCases.each { testName, data ->
                // Access via the state object
                if (!state.testcaseExecutionOrder.contains(testName)) {
                    state.testcaseExecutionOrder.add(testName)
                }
                
                if (!state.durationMatrix[testName]) {
                    state.durationMatrix[testName] = [:]
                }
                state.durationMatrix[testName][appKey] = data.duration

                html += """<tr>
                    <td style="padding:8px; border-bottom:1px solid #ddd;">${testName}</td>
                    <td style="padding:8px; border-bottom:1px solid #ddd; color:${data.result == 'PASS' ? '#1e8449' : '#c0392b'}; font-weight:bold;">${data.result}</td>
                    <td style="padding:8px; border-bottom:1px solid #ddd;">${data.duration}</td>
                </tr>"""
            }
            html += "</table><div style='height:150px;'><canvas id='${chartId}'></canvas></div>"
        }

        // Global Charting - Access via state object
        def labels = state.testcaseExecutionOrder.collect { "'${it}'" }.join(",")
        def datasets = masterData.collect { appKey, testCases ->
            def values = state.testcaseExecutionOrder.collect { state.durationMatrix[it] ? (state.durationMatrix[it][appKey] ?: 0) : 0 }.join(",")
            return "{ label: '${appKey}', data: [${values}], fill: false, tension: 0.1, borderWidth: 2 }"
        }.join(",")

        html += """<h2 style="margin-top:50px;">Comparison</h2>
        <div style="background:white; height:400px;"><canvas id="compChart"></canvas></div>
        <script>
            new Chart(document.getElementById('compChart'), {
                type: 'line',
                data: { labels: [${labels}], datasets: [${datasets}] },
                options: { responsive: true, maintainAspectRatio: false }
            });
        </script>
</body></html>"""

        // Write files
        steps.ws(logDir) {
            steps.writeFile(file: "BackwardCompatibility_Report.html", text: html)
            steps.sh "wkhtmltopdf --enable-javascript --javascript-delay 3000 BackwardCompatibility_Report.html BackwardCompatibility_Report.pdf"
            steps.publishHTML(target: [reportDir: '.', reportFiles: 'BackwardCompatibility_Report.html', reportName: 'Compatibility Report'])
        }
    }
}