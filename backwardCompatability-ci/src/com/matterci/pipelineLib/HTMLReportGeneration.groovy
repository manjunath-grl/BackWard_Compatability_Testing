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

        def htmlHeader = """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <script src="${chartJSUrl}"></script>
    <style>
        body { font-family: 'Segoe UI', Arial, sans-serif; margin: 20px; background-color: #f8f9fa; width: 980px; }
        .card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-bottom: 25px; page-break-inside: avoid; }
        
        /* Flex layout for Side-by-Side Summary */
        .dut-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 15px; }
        .summary-stats { flex: 1; padding-top: 10px; }
        .chart-box { width: 250px; height: 150px; }
        
        .stat-line { font-size: 14px; margin-bottom: 8px; color: #444; }
        .stat-value { font-weight: bold; float: right; margin-right: 40px; }

        table { border-collapse: collapse; width: 100%; margin-top: 10px; table-layout: fixed; }
        th { background-color: #2c3e50; color: white; padding: 10px; text-align: left; font-size: 13px; }
        td { padding: 8px; border-bottom: 1px solid #eee; font-size: 12px; word-wrap: break-word; }
        
        .status-pass { color: #27ae60; font-weight: bold; }
        .status-fail { color: #e74c3c; font-weight: bold; }
        
        .line-container { width: 900px; height: 400px; margin: 20px auto; }
        h1 { color: #2c3e50; border-bottom: 2px solid #2c3e50; padding-bottom: 10px; }
    </style>
</head>
<body>
    <h1>Backward Compatibility Report</h1>
    <div class="card" style="background: #2c3e50; color: white;">
        <strong>Controller:</strong> ${controllerRef} | <strong>Build:</strong> #${buildNumber}
    </div>
"""

        def dutContent = ""
        masterData.each { appKey, testCases ->
            def chartId = "pie_" + appKey.replaceAll(/[^a-zA-Z0-9]/, '_')
            int passCount = 0
            int failCount = 0
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
                    <td style="color:#7f8c8d; font-style:italic;">${data.error ?: '-'}</td>
                </tr>"""
            }

            int total = passCount + failCount
            double rate = total > 0 ? (passCount / total * 100).round(1) : 0

            dutContent += """
            <div class="card">
                <h2>DUT: ${appKey}</h2>
                <div class="dut-header">
                    <div class="summary-stats">
                        <div class="stat-line">Passed: <span class="stat-value status-pass">${passCount}</span></div>
                        <div class="stat-line">Failed: <span class="stat-value status-fail">${failCount}</span></div>
                        <div class="stat-line">Total Tests: <span class="stat-value">${total}</span></div>
                        <div class="stat-line">Pass Rate: <span class="stat-value">${rate}%</span></div>
                    </div>
                    <div class="chart-box">
                        <canvas id="${chartId}" width="250" height="150"></canvas>
                    </div>
                </div>
                
                <table>
                    <thead>
                        <tr>
                            <th style="width: 30%;">Testcase</th>
                            <th style="width: 15%;">Status</th>
                            <th style="width: 15%;">Duration</th>
                            <th style="width: 40%;">Details</th>
                        </tr>
                    </thead>
                    <tbody>${tableRows}</tbody>
                </table>
            </div>
            <script>
                new Chart(document.getElementById('${chartId}'), {
                    type: 'pie',
                    data: {
                        labels: ['Pass', 'Fail'],
                        datasets: [{ data: [${passCount}, ${failCount}], backgroundColor: ['#27ae60', '#e74c3c'], borderWidth: 1 }]
                    },
                    options: { 
                        animation: false, responsive: false, maintainAspectRatio: false,
                        plugins: { legend: { position: 'right', labels: { boxWidth: 10, font: { size: 10 } } } }
                    }
                });
            </script>"""
        }

        // Comparison Section
        def labels = state.testcaseExecutionOrder.collect { "'${it}'" }.join(",")
        def datasets = masterData.collect { appKey, testCases ->
            def values = state.testcaseExecutionOrder.collect { state.durationMatrix[it][appKey] ?: 0 }.join(",")
            return "{ label: '${appKey}', data: [${values}], fill: false, tension: 0.1, borderWidth: 2 }"
        }.join(",")

        def comparisonHtml = """
        <div class="card">
            <h2>Performance Comparison (All Runs)</h2>
            <div class="line-container">
                <canvas id="compChart" width="900" height="400"></canvas>
            </div>
        </div>
        <script>
            new Chart(document.getElementById('compChart'), {
                type: 'line',
                data: { labels: [${labels}], datasets: [${datasets}] },
                options: { 
                    animation: false, responsive: false, maintainAspectRatio: false,
                    scales: { 
                        y: { beginAtZero: true, title: { display: true, text: 'Duration (sec)' } },
                        x: { ticks: { autoSkip: false, maxRotation: 45 } }
                    },
                    plugins: { legend: { position: 'bottom' } }
                }
            });
        </script>
        """

        def finalHtml = htmlHeader + dutContent + comparisonHtml + "</body></html>"

        steps.ws(logDir) {
            steps.writeFile(file: "BackwardCompatibility_Report.html", text: finalHtml)
            try {
                // Increased delay to 20000 (20s) to ensure the big comparison chart renders
                steps.sh "wkhtmltopdf --enable-javascript --javascript-delay 20000 --no-stop-slow-scripts --enable-local-file-access --viewport-size 1024x768 BackwardCompatibility_Report.html BackwardCompatibility_Report.pdf"
            } catch (Exception e) {
                steps.echo "PDF Generation Error: ${e.message}"
            }
            steps.publishHTML(target: [reportDir: '.', reportFiles: 'BackwardCompatibility_Report.html', reportName: 'Compatibility Report'])
            steps.archiveArtifacts artifacts: '*.pdf, *.html', allowEmptyArchive: true
        }
    }
}