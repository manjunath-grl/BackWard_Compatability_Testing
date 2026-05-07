package com.matterci.pipelineLib

class HTMLReportGeneration {

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
        def chartJSUrl = "https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"

        def html = """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <script src="${chartJSUrl}"></script>
    <style>
        body { font-family: Arial, sans-serif; margin: 30px; background-color: #f4f7f6; width: 1000px; }
        .card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-bottom: 30px; page-break-inside: avoid; }
        table { border-collapse: collapse; width: 100%; margin-bottom: 20px; table-layout: fixed; }
        th { background-color: #2c3e50; color: white; padding: 10px; text-align: left; font-size: 14px; }
        td { padding: 8px; border-bottom: 1px solid #eee; font-size: 13px; word-wrap: break-word; }
        .status-pass { color: #27ae60; font-weight: bold; }
        .status-fail { color: #e74c3c; font-weight: bold; }
        .error-text { color: #7f8c8d; font-size: 0.85em; font-style: italic; }
        
        /* Container sizes for PDF stability */
        .pie-container { width: 400px; height: 250px; margin: 10px auto; }
        .line-container { width: 900px; height: 450px; margin: 20px auto; }
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
            
            // Generate Table Rows first
            def tableRows = ""
            testCases.each { name, data ->
                if (data.result == 'PASS') passCount++ else failCount++
                if (!state.testcaseExecutionOrder.contains(name)) state.testcaseExecutionOrder.add(name)
                if (!state.durationMatrix[name]) state.durationMatrix[name] = [:]
                state.durationMatrix[name][appKey] = data.duration

                tableRows += """
                <tr>
                    <td>${name}</td>
                    <td class="${data.result == 'PASS' ? 'status-pass' : 'status-fail'}">${data.result}</td>
                    <td>${data.duration}s</td>
                    <td class="error-text">${data.error ?: '-'}</td>
                </tr>"""
            }

            html += """
            <div class="card">
                <h2>DUT: ${appKey}</h2>
                <table>
                    <thead>
                        <tr>
                            <th style="width: 25%;">Testcase</th>
                            <th style="width: 15%;">Status</th>
                            <th style="width: 15%;">Duration</th>
                            <th style="width: 45%;">Error / Details</th>
                        </tr>
                    </thead>
                    <tbody>${tableRows}</tbody>
                </table>

                <h3 style="text-align:center;">Test Execution Summary</h3>
                <div class="pie-container">
                    <canvas id="${chartId}" width="400" height="250"></canvas>
                </div>
            </div>
            <script>
                new Chart(document.getElementById('${chartId}'), {
                    type: 'pie',
                    data: {
                        labels: ['Pass', 'Fail'],
                        datasets: [{ data: [${passCount}, ${failCount}], backgroundColor: ['#27ae60', '#e74c3c'] }]
                    },
                    options: { 
                        animation: false, 
                        responsive: false,
                        maintainAspectRatio: false,
                        plugins: { legend: { position: 'bottom' } }
                    }
                });
            </script>"""
        }

        // Comparison Section (Big Chart)
        def labels = state.testcaseExecutionOrder.collect { "'${it}'" }.join(",")
        def datasets = masterData.collect { appKey, testCases ->
            def values = state.testcaseExecutionOrder.collect { state.durationMatrix[it][appKey] ?: 0 }.join(",")
            return "{ label: '${appKey}', data: [${values}], fill: false, tension: 0.1, borderWidth: 2 }"
        }.join(",")

        html += """
        <div class="card">
            <h2>Performance Comparison (All DUTs)</h2>
            <div class="line-container">
                <canvas id="compChart" width="900" height="450"></canvas>
            </div>
        </div>
        <script>
            new Chart(document.getElementById('compChart'), {
                type: 'line',
                data: { labels: [${labels}], datasets: [${datasets}] },
                options: { 
                    animation: false, 
                    responsive: false,
                    maintainAspectRatio: false,
                    scales: { 
                        y: { beginAtZero: true, title: { display: true, text: 'Duration (sec)' } },
                        x: { ticks: { autoSkip: false, maxRotation: 45, minRotation: 45 } }
                    },
                    plugins: { legend: { position: 'bottom' } }
                }
            });
        </script>
</body></html>"""

        steps.ws(logDir) {
            steps.writeFile(file: "BackwardCompatibility_Report.html", text: html)
            try {
                // PDF generation with specific fixes for Chart.js rendering
                steps.sh "wkhtmltopdf --enable-javascript --javascript-delay 5000 --no-stop-slow-scripts --enable-local-file-access --smart-indexing --viewport-size 1024x768 BackwardCompatibility_Report.html BackwardCompatibility_Report.pdf"
            } catch (Exception e) {
                steps.echo "PDF Failed: ${e.message}"
            }
            steps.publishHTML(target: [reportDir: '.', reportFiles: 'BackwardCompatibility_Report.html', reportName: 'Compatibility Report'])
            steps.archiveArtifacts artifacts: '*.pdf, *.html', allowEmptyArchive: true
        }
    }
}