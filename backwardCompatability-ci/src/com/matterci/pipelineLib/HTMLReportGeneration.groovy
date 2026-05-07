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

        // Global Counters for Summary Dashboard
        int totalPassed = 0
        int totalFailed = 0

        def state = [durationMatrix: [:], testcaseExecutionOrder: []]
        def chartJSUrl = "https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"

        def htmlHeader = """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <script src="${chartJSUrl}"></script>
    <style>
        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 20px; background-color: #f8f9fa; color: #333; width: 950px; }
        .card { background: white; padding: 15px; border-radius: 6px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); margin-bottom: 20px; page-break-inside: avoid; }
        .dashboard { display: flex; gap: 15px; margin-bottom: 20px; }
        .stat-box { flex: 1; padding: 15px; border-radius: 6px; text-align: center; color: white; font-weight: bold; }
        table { border-collapse: collapse; width: 100%; margin-bottom: 15px; table-layout: fixed; border: 1px solid #dee2e6; }
        th { background-color: #343a40; color: white; padding: 8px; text-align: left; font-size: 13px; }
        td { padding: 6px; border-bottom: 1px solid #dee2e6; font-size: 12px; word-wrap: break-word; }
        .status-pass { color: #28a745; font-weight: bold; }
        .status-fail { color: #dc3545; font-weight: bold; }
        .pie-container { width: 300px; height: 180px; margin: 0 auto; }
        .line-container { width: 850px; height: 400px; margin: 10px auto; }
        h1, h2, h3 { margin-top: 0; color: #2c3e50; }
    </style>
</head>
<body>
    <h1>Backward Compatibility Report</h1>
    
    <div class="card" style="border-left: 5px solid #007bff;">
        <strong>Controller SDK:</strong> ${controllerRef}<br>
        <strong>Build ID:</strong> ${buildNumber}
    </div>
"""

        def dutCardsHtml = ""
        masterData.each { appKey, testCases ->
            def chartId = "pie_" + appKey.replaceAll(/[^a-zA-Z0-9]/, '_')
            int passCount = 0
            int failCount = 0
            
            def tableRows = ""
            testCases.each { name, data ->
                if (data.result == 'PASS') { passCount++; totalPassed++; } 
                else { failCount++; totalFailed++; }
                
                if (!state.testcaseExecutionOrder.contains(name)) state.testcaseExecutionOrder.add(name)
                if (!state.durationMatrix[name]) state.durationMatrix[name] = [:]
                state.durationMatrix[name][appKey] = data.duration

                tableRows += """
                <tr>
                    <td>${name}</td>
                    <td class="${data.result == 'PASS' ? 'status-pass' : 'status-fail'}">${data.result}</td>
                    <td>${data.duration}s</td>
                    <td style="color:#6c757d; font-size:11px;">${data.error ?: '-'}</td>
                </tr>"""
            }

            def dutResult = failCount == 0 ? "PASS" : "FAIL"
            def dutClass = failCount == 0 ? "status-pass" : "status-fail"

            dutCardsHtml += """
            <div class="card">
                <div style="display:flex; justify-content: space-between; align-items: center; border-bottom: 2px solid #eee; margin-bottom:10px; padding-bottom:5px;">
                    <h2 style="margin:0;">DUT: ${appKey}</h2>
                    <span class="${dutClass}" style="font-size: 1.2em; border: 2px solid; padding: 2px 10px; border-radius: 4px;">Overall: ${dutResult}</span>
                </div>
                <table>
                    <thead>
                        <tr>
                            <th style="width: 30%;">Testcase</th>
                            <th style="width: 15%;">Status</th>
                            <th style="width: 15%;">Duration</th>
                            <th style="width: 40%;">Error Details</th>
                        </tr>
                    </thead>
                    <tbody>${tableRows}</tbody>
                </table>

                <div class="pie-container">
                    <canvas id="${chartId}" width="300" height="180"></canvas>
                </div>
            </div>
            <script>
                new Chart(document.getElementById('${chartId}'), {
                    type: 'pie',
                    data: {
                        labels: ['Pass', 'Fail'],
                        datasets: [{ data: [${passCount}, ${failCount}], backgroundColor: ['#28a745', '#dc3545'] }]
                    },
                    options: { 
                        animation: false, responsive: false, maintainAspectRatio: false,
                        plugins: { legend: { position: 'right', labels: { boxWidth: 12, font: { size: 10 } } } }
                    }
                });
            </script>"""
        }

        // Dashboard Calculation
        int totalTests = totalPassed + totalFailed
        double passRate = totalTests > 0 ? (totalPassed / totalTests) * 100 : 0
        def overallDashboard = """
        <div class="dashboard">
            <div class="stat-box" style="background-color: #28a745;">Total Passed: ${totalPassed}</div>
            <div class="stat-box" style="background-color: #dc3545;">Total Failed: ${totalFailed}</div>
            <div class="stat-box" style="background-color: #007bff;">Pass Rate: ${passRate.round(1)}%</div>
        </div>
        """

        // Performance Comparison Logic
        def labels = state.testcaseExecutionOrder.collect { "'${it}'" }.join(",")
        def datasets = masterData.collect { appKey, testCases ->
            def values = state.testcaseExecutionOrder.collect { state.durationMatrix[it][appKey] ?: 0 }.join(",")
            return "{ label: '${appKey}', data: [${values}], fill: false, tension: 0.1, borderWidth: 2 }"
        }.join(",")

        def comparisonHtml = """
        <div class="card">
            <h2>Performance Trend Comparison</h2>
            <div class="line-container">
                <canvas id="compChart" width="850" height="400"></canvas>
            </div>
        </div>
        <script>
            new Chart(document.getElementById('compChart'), {
                type: 'line',
                data: { labels: [${labels}], datasets: [${datasets}] },
                options: { 
                    animation: false, responsive: false, maintainAspectRatio: false,
                    scales: { 
                        y: { beginAtZero: true, title: { display: true, text: 'Seconds' } },
                        x: { ticks: { font: { size: 10 }, maxRotation: 45 } }
                    },
                    plugins: { legend: { position: 'bottom', labels: { boxWidth: 15 } } }
                }
            });
        </script>
        """

        def finalHtml = htmlHeader + overallDashboard + dutCardsHtml + comparisonHtml + "</body></html>"

        steps.ws(logDir) {
            steps.writeFile(file: "BackwardCompatibility_Report.html", text: finalHtml)
            try {
                steps.sh "wkhtmltopdf --enable-javascript --javascript-delay 8000 --enable-local-file-access --viewport-size 1024x768 BackwardCompatibility_Report.html BackwardCompatibility_Report.pdf"
            } catch (Exception e) {
                steps.echo "PDF Failed: ${e.message}"
            }
            steps.publishHTML(target: [reportDir: '.', reportFiles: 'BackwardCompatibility_Report.html', reportName: 'Compatibility Report'])
            steps.archiveArtifacts artifacts: '*.pdf, *.html', allowEmptyArchive: true
        }
    }
}