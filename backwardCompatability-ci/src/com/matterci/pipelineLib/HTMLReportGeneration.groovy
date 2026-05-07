package com.matterci.pipelineLib

class HTMLReportGeneration {

    // helper to format the name like: chip-evse-app (master-04ff812)
    def formatDutName(appKey, testConfigs) {
        def ctrl = testConfigs.ci_config.clone_sdk_code_stage.controller_sdk
        def ref = ctrl.tag ?: ctrl.branch ?: (ctrl.pr ? "PR-${ctrl.pr}" : "unknown")
        def sha = ctrl.sha ? ctrl.sha.take(7) : "no-sha"
        return "${appKey} (${ref}-${sha})"
    }

    def generateReport(def steps, String logDir, String buildNumber, def testConfigs) {
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
    <script>window.status = 'loading';</script>
    <style>
        body { font-family: 'Segoe UI', Tahoma, sans-serif; margin: 0; padding: 20px; background-color: #f4f7f9; display: flex; flex-direction: column; align-items: center; }
        .report-wrapper { width: 95%; max-width: 1400px; }
        .card { background: white; padding: 25px; border-radius: 10px; box-shadow: 0 4px 6px rgba(0,0,0,0.05); margin-bottom: 30px; width: 100%; box-sizing: border-box; }
        .header-strip { background: #2c3e50; color: white; padding: 15px 20px; border-radius: 8px; margin-bottom: 25px; font-size: 1.1em; }
        .dut-header-row { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; margin-bottom: 20px; border-bottom: 2px solid #3498db; padding-bottom: 15px; }
        .summary-text-stats { flex: 1; min-width: 280px; }
        .pie-chart-box { width: 300px; height: 180px; }
        .comparison-chart-box { width: 100%; height: 500px; margin-top: 20px; }
        .stat-item { font-size: 15px; margin-bottom: 8px; color: #34495e; }
        .stat-label { font-weight: 600; width: 100px; display: inline-block; }
        table { border-collapse: collapse; width: 100%; table-layout: fixed; margin-top: 15px; }
        th { background-color: #f8f9fa; color: #2c3e50; padding: 12px; text-align: left; font-size: 13px; border-bottom: 2px solid #dee2e6; }
        td { padding: 10px; border-bottom: 1px solid #eee; font-size: 13px; }
        .status-pass { color: #27ae60; font-weight: bold; }
        .status-fail { color: #e74c3c; font-weight: bold; }
        h1 { color: #2c3e50; margin-bottom: 5px; }
        h2 { color: #2980b9; margin: 0; font-size: 1.3em; }
        @media print {
            body { padding: 0; background: white; }
            .report-wrapper { width: 950px; }
            .card { box-shadow: none; border: 1px solid #eee; page-break-inside: avoid; }
            .comparison-chart-box { height: 400px !important; }
        }
    </style>
</head>
<body>
    <div class="report-wrapper">
        <h1>Backward Compatibility Report</h1>
        <div class="header-strip">
            <strong>Execution Summary</strong> | Build ID: #${buildNumber}
        </div>
"""

        def dutContent = ""
        masterData.each { appKey, testCases ->
            // Use the NEW formatted name
            def formattedName = formatDutName(appKey, testConfigs)
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
                    <td><strong>${name}</strong></td>
                    <td class="${data.result == 'PASS' ? 'status-pass' : 'status-fail'}">${data.result}</td>
                    <td>${data.duration}s</td>
                    <td style="color:#7f8c8d; font-size: 0.85em;">${data.error ?: '-'}</td>
                </tr>"""
            }

            double rate = (passCount + failCount) > 0 ? (passCount / (passCount + failCount) * 100).toBigDecimal().setScale(1, java.math.RoundingMode.HALF_UP).doubleValue() : 0

            dutContent += """
            <div class="card">
                <div class="dut-header-row">
                    <div class="summary-text-stats">
                        <h2>DUT: ${formattedName}</h2>
                        <div class="stat-item"><span class="stat-label">Passed:</span> <span class="status-pass">${passCount}</span></div>
                        <div class="stat-item"><span class="stat-label">Failed:</span> <span class="status-fail">${failCount}</span></div>
                        <div class="stat-item"><span class="stat-label">Total Tests:</span> <span>${passCount + failCount}</span></div>
                        <div class="stat-item"><span class="stat-label">Pass Rate:</span> <span>${rate}%</span></div>
                    </div>
                    <div class="pie-chart-box">
                        <canvas id="${chartId}"></canvas>
                    </div>
                </div>
                <table>
                    <thead>
                        <tr>
                            <th style="width: 30%;">Testcase Name</th>
                            <th style="width: 15%;">Status</th>
                            <th style="width: 15%;">Duration</th>
                            <th style="width: 40%;">Error / Details</th>
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
                        datasets: [{ data: [${passCount}, ${failCount}], backgroundColor: ['#2ecc71', '#e74c3c'], borderWidth: 2 }]
                    },
                    options: { 
                        animation: false, responsive: true, maintainAspectRatio: false,
                        plugins: { legend: { position: 'right', labels: { boxWidth: 12, font: { size: 11 } } } }
                    }
                });
            </script>"""
        }

        // IMPROVED Performance Comparison Graph
        def labels = state.testcaseExecutionOrder.collect { "'${it}'" }.join(",")
        def datasetList = []
        masterData.eachWithIndex { appKey, testCases, idx ->
            def formattedName = formatDutName(appKey, testConfigs)
            def values = state.testcaseExecutionOrder.collect { state.durationMatrix[it][appKey] ?: 0 }.join(",")
            // Added point styles for better visual distinction
            datasetList << "{ label: '${formattedName}', data: [${values}], fill: false, tension: 0.3, borderWidth: 3, pointRadius: 5, pointHoverRadius: 8 }"
        }
        def datasets = datasetList.join(",")

        def comparisonHtml = """
        <div class="card">
            <h2 style="margin-bottom: 15px; color: #2c3e50; border-left: 5px solid #2980b9; padding-left: 10px;">
                Performance Trend Comparison (All DUTs)
            </h2>
            <p style="font-size: 13px; color: #7f8c8d; margin-bottom: 20px;">
                Comparing execution duration (seconds) across all test runs to identify latency bottlenecks.
            </p>
            <div class="comparison-chart-box">
                <canvas id="perfChart"></canvas>
            </div>
        </div>
        <script>
            new Chart(document.getElementById('perfChart'), {
                type: 'line',
                data: { labels: [${labels}], datasets: [${datasets}] },
                options: { 
                    animation: false, responsive: true, maintainAspectRatio: false,
                    scales: { 
                        y: { 
                            beginAtZero: true, 
                            title: { display: true, text: 'Execution Time (Seconds)', font: { weight: 'bold', size: 14 } },
                            grid: { color: '#ebedef' }
                        },
                        x: { 
                            ticks: { autoSkip: false, maxRotation: 45, font: { size: 11 } },
                            grid: { display: false }
                        }
                    },
                    plugins: { 
                        legend: { position: 'bottom', labels: { padding: 25, usePointStyle: true, font: { size: 12 } } },
                        tooltip: { mode: 'index', intersect: false }
                    }
                }
            });
        </script>
        """

        def footer = """
        <script>
            window.onload = function() {
                setTimeout(function() { window.status = 'ready'; }, 3000); 
            };
        </script>
        </div></body></html>"""

        def finalHtml = htmlHeader + dutContent + comparisonHtml + footer

        steps.ws(logDir) {
            steps.writeFile(file: "BackwardCompatibility_Report.html", text: finalHtml)
            try {
                steps.sh """
                    wkhtmltopdf --enable-javascript --javascript-delay 15000 --window-status ready \
                    --no-stop-slow-scripts --enable-local-file-access --viewport-size 1280x1024 \
                    BackwardCompatibility_Report.html BackwardCompatibility_Report.pdf
                """
            } catch (Exception e) { steps.echo "PDF Failed: ${e.message}" }
            steps.publishHTML(target: [reportDir: '.', reportFiles: 'BackwardCompatibility_Report.html', reportName: 'Compatibility Report'])
            steps.archiveArtifacts artifacts: '*.pdf, *.html', allowEmptyArchive: true
        }
    }
}