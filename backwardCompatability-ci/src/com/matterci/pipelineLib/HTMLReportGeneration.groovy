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
            steps.error "No execution_results.json files found."
        }

        def masterData = [:]
        jsonFiles.each { file ->
            def data = steps.readJSON(file: "${logDir}/${file.path}")
            masterData << data
        }

        def state = [durationMatrix: [:], testcaseExecutionOrder: []]
        
        // Use a stable UMD version of Chart.js
        def chartJSUrl = "https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"

        def html = """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <script src="${chartJSUrl}"></script>
    <style>
        body { font-family: Arial, sans-serif; margin: 30px; background-color: #f4f7f6; }
        .card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-bottom: 30px; }
        table { border-collapse: collapse; width: 100%; margin-top: 10px; table-layout: fixed; }
        th { background-color: #2c3e50; color: white; padding: 12px; text-align: left; }
        td { padding: 10px; border-bottom: 1px solid #eee; word-wrap: break-word; }
        .status-pass { color: #27ae60; font-weight: bold; }
        .status-fail { color: #e74c3c; font-weight: bold; }
        .error-text { color: #7f8c8d; font-size: 0.85em; font-style: italic; }
        .chart-container { display: flex; align-items: center; justify-content: space-around; flex-wrap: wrap; }
    </style>
</head>
<body>
    <h1>Backward Compatibility Report</h1>
    <div class="card" style="border-left: 5px solid #2c3e50;">
        <strong>Controller:</strong> ${controllerRef}<br>
        <strong>Build Number:</strong> ${buildNumber}
    </div>
"""

        masterData.each { appKey, testCases ->
            def chartId = "pie_" + appKey.replaceAll(/[^a-zA-Z0-9]/, '_')
            int passCount = 0
            int failCount = 0
            
            testCases.each { name, data ->
                if (data.result == 'PASS') passCount++ else failCount++
                if (!state.testcaseExecutionOrder.contains(name)) state.testcaseExecutionOrder.add(name)
                if (!state.durationMatrix[name]) state.durationMatrix[name] = [:]
                state.durationMatrix[name][appKey] = data.duration
            }

            html += """
            <div class="card">
                <h2>DUT: ${appKey}</h2>
                <div class="chart-container">
                    <div style="width: 300px; height: 300px;">
                        <canvas id="${chartId}"></canvas>
                    </div>
                    <div>
                        <p><b>Total Tests:</b> ${passCount + failCount}</p>
                        <p class="status-pass"><b>Passed:</b> ${passCount}</p>
                        <p class="status-fail"><b>Failed:</b> ${failCount}</p>
                    </div>
                </div>
                
                <table>
                    <thead>
                        <tr>
                            <th style="width: 25%;">Testcase</th>
                            <th style="width: 10%;">Status</th>
                            <th style="width: 15%;">Duration</th>
                            <th style="width: 50%;">Error / Details</th>
                        </tr>
                    </thead>
                    <tbody>"""
            
            testCases.each { name, data ->
                html += """
                        <tr>
                            <td>${name}</td>
                            <td class="${data.result == 'PASS' ? 'status-pass' : 'status-fail'}">${data.result}</td>
                            <td>${data.duration}s</td>
                            <td class="error-text">${data.error ?: '-'}</td>
                        </tr>"""
            }
            
            html += """</tbody></table>
            </div>
            <script>
                new Chart(document.getElementById('${chartId}'), {
                    type: 'pie',
                    data: {
                        labels: ['Pass', 'Fail'],
                        datasets: [{ data: [${passCount}, ${failCount}], backgroundColor: ['#27ae60', '#e74c3c'] }]
                    },
                    options: { animation: false, responsive: false }
                });
            </script>"""
        }

        // Comparison Section
        def labels = state.testcaseExecutionOrder.collect { "'${it}'" }.join(",")
        def datasets = masterData.collect { appKey, testCases ->
            def values = state.testcaseExecutionOrder.collect { state.durationMatrix[it][appKey] ?: 0 }.join(",")
            return "{ label: '${appKey}', data: [${values}], fill: false, tension: 0.1, borderWidth: 2 }"
        }.join(",")

        html += """
        <div class="card">
            <h2>Performance Comparison</h2>
            <div style="width: 800px; height: 400px;">
                <canvas id="compChart"></canvas>
            </div>
        </div>
        <script>
            new Chart(document.getElementById('compChart'), {
                type: 'line',
                data: { labels: [${labels}], datasets: [${datasets}] },
                options: { 
                    animation: false, 
                    responsive: false,
                    scales: { y: { beginAtZero: true, title: { display: true, text: 'Duration (sec)' } } }
                }
            });
        </script>
</body></html>"""

        steps.ws(logDir) {
            steps.writeFile(file: "BackwardCompatibility_Report.html", text: html)
            try {
                steps.sh "sudo apt-get update && sudo apt-get install -y wkhtmltopdf"
                // The delay is kept at 5000ms to ensure scripts load even on slow nodes
                steps.sh "wkhtmltopdf --enable-javascript --javascript-delay 5000 --no-stop-slow-scripts --enable-local-file-access BackwardCompatibility_Report.html BackwardCompatibility_Report.pdf"
            } catch (Exception e) {
                steps.echo "PDF Failed: ${e.message}"
            }
            steps.publishHTML(target: [reportDir: '.', reportFiles: 'BackwardCompatibility_Report.html', reportName: 'Compatibility Report'])
            steps.archiveArtifacts artifacts: '*.pdf, *.html', allowEmptyArchive: true
        }
    }
}