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
        /* Adaptive Layout */
        body { 
            font-family: 'Segoe UI', Tahoma, Arial, sans-serif; 
            margin: 0; 
            padding: 20px;
            background-color: #f4f7f9; 
            display: flex;
            flex-direction: column;
            align-items: center;
        }
        
        .report-wrapper {
            width: 95%;
            max-width: 1400px;
        }

        .card { 
            background: white; 
            padding: 25px; 
            border-radius: 10px; 
            box-shadow: 0 4px 6px rgba(0,0,0,0.05); 
            margin-bottom: 30px; 
            width: 100%;
            box-sizing: border-box;
        }
        
        .header-strip {
            background: #2c3e50;
            color: white;
            padding: 15px 20px;
            border-radius: 8px;
            margin-bottom: 25px;
        }

        .dut-header-row { 
            display: flex; 
            flex-wrap: wrap;
            justify-content: space-between; 
            align-items: center; 
            margin-bottom: 20px;
            border-bottom: 1px solid #eee;
            padding-bottom: 15px;
        }

        .summary-text-stats { flex: 1; min-width: 250px; }
        
        .pie-chart-box { 
            width: 300px; 
            height: 180px; 
            position: relative;
        }
        
        .comparison-chart-box { 
            width: 100%; 
            height: 450px; 
            position: relative;
            margin-top: 20px;
        }

        .stat-item { font-size: 15px; margin-bottom: 10px; color: #34495e; }
        .stat-label { font-weight: 600; min-width: 120px; display: inline-block; }

        table { border-collapse: collapse; width: 100%; table-layout: fixed; margin-top: 15px; }
        th { background-color: #ecf0f1; color: #2c3e50; padding: 12px; text-align: left; font-size: 13px; border-bottom: 2px solid #bdc3c7; }
        td { padding: 10px; border-bottom: 1px solid #eee; font-size: 13px; word-wrap: break-word; }
        
        .status-pass { color: #27ae60; font-weight: bold; }
        .status-fail { color: #e74c3c; font-weight: bold; }
        
        h1 { color: #2c3e50; margin-bottom: 10px; }
        h2 { color: #2980b9; margin: 0; }

        /* PDF / Print Specific Optimization */
        @media print {
            body { padding: 0; background: white; }
            .report-wrapper { width: 950px; }
            .card { box-shadow: none; border: 1px solid #eee; page-break-inside: avoid; }
            .pie-chart-box { width: 280px !important; height: 160px !important; }
            .comparison-chart-box { width: 900px !important; height: 400px !important; }
        }
    </style>
</head>
<body>
    <div class="report-wrapper">
        <h1>Backward Compatibility Report</h1>
        <div class="header-strip">
            <strong>Controller SDK:</strong> ${controllerRef} &nbsp;&nbsp;|&nbsp;&nbsp; <strong>Build ID:</strong> #${buildNumber}
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
                    <td style="color:#95a5a6; font-size: 0.9em;">${data.error ?: '-'}</td>
                </tr>"""
            }

            int total = passCount + failCount
            // Using setScale to round to 1 decimal place safely in Jenkins
            double rate = total > 0 ? (passCount / total * 100).toBigDecimal().setScale(1, java.math.RoundingMode.HALF_UP).doubleValue() : 0

            dutContent += """
            <div class="card">
                <div class="dut-header-row">
                    <div class="summary-text-stats">
                        <h2>DUT: ${appKey}</h2>
                        <div class="stat-item"><span class="stat-label">Passed:</span> <span class="status-pass">${passCount}</span></div>
                        <div class="stat-item"><span class="stat-label">Failed:</span> <span class="status-fail">${failCount}</span></div>
                        <div class="stat-item"><span class="stat-label">Total Tests:</span> <span>${total}</span></div>
                        <div class="stat-item"><span class="stat-label">Pass Rate:</span> <span>${rate}%</span></div>
                    </div>
                    <div class="pie-chart-box">
                        <canvas id="${chartId}"></canvas>
                    </div>
                </div>
                
                <table>
                    <thead>
                        <tr>
                            <th style="width: 35%;">Testcase Name</th>
                            <th style="width: 15%;">Status</th>
                            <th style="width: 15%;">Duration</th>
                            <th style="width: 35%;">Error / Details</th>
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
                        animation: false, responsive: true, maintainAspectRatio: false,
                        plugins: { legend: { position: 'right', labels: { boxWidth: 12, font: { size: 11 } } } }
                    }
                });
            </script>"""
        }

        // Performance Comparison Logic
        def labels = state.testcaseExecutionOrder.collect { "'${it}'" }.join(",")
        def datasets = masterData.collect { appKey, testCases ->
            def values = state.testcaseExecutionOrder.collect { state.durationMatrix[it][appKey] ?: 0 }.join(",")
            return "{ label: '${appKey}', data: [${values}], fill: false, tension: 0.1, borderWidth: 2.5 }"
        }.join(",")

        def comparisonHtml = """
        <div class="card">
            <h2>Performance Trend Comparison (All DUTs)</h2>
            <div class="comparison-chart-box">
                <canvas id="compChart"></canvas>
            </div>
        </div>
        <script>
            new Chart(document.getElementById('compChart'), {
                type: 'line',
                data: { labels: [${labels}], datasets: [${datasets}] },
                options: { 
                    animation: false, responsive: true, maintainAspectRatio: false,
                    scales: { 
                        y: { beginAtZero: true, title: { display: true, text: 'Execution Time (Seconds)', font: { weight: 'bold' } } },
                        x: { ticks: { autoSkip: false, maxRotation: 45 } }
                    },
                    plugins: { legend: { position: 'bottom', labels: { padding: 20 } } }
                }
            });
        </script>
        """

        def finalHtml = htmlHeader + dutContent + comparisonHtml + "</div></body></html>"

        steps.ws(logDir) {
            steps.writeFile(file: "BackwardCompatibility_Report.html", text: finalHtml)
            try {
                // High delay ensures the comparison chart is fully built in the PDF
                steps.sh "wkhtmltopdf --enable-javascript --javascript-delay 15000 --no-stop-slow-scripts --enable-local-file-access --viewport-size 1024x768 BackwardCompatibility_Report.html BackwardCompatibility_Report.pdf"
            } catch (Exception e) {
                steps.echo "PDF Failed: ${e.message}"
            }
            steps.publishHTML(target: [reportDir: '.', reportFiles: 'BackwardCompatibility_Report.html', reportName: 'Compatibility Report'])
            steps.archiveArtifacts artifacts: '*.pdf, *.html', allowEmptyArchive: true
        }
    }
}