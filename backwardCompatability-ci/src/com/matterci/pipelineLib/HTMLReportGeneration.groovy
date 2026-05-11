package com.matterci.pipelineLib

class HTMLReportGeneration {

    def resolveControllerRef(testConfigs) {
        def ciConfig = testConfigs?.ci_config
        def ctrl = ciConfig?.clone_sdk_code_stage?.controller_sdk
        if (!ctrl) return "Unknown-Controller"

        if (ctrl.sha) {
            def baseRef = ctrl.branch ?: ctrl.tag ?: (ctrl.pr ? "PR-${ctrl.pr}" : "master")
            return "${baseRef} (${ctrl.sha})"
        }
        return ctrl.tag ?: (ctrl.pr ? "PR-${ctrl.pr}" : ctrl.branch)
    }

    /**
     * Resolves the full DUT details by looking up the appKey in the platforms config.
     * Added debug logging to trace the matching process.
     */
    def formatDutName(def steps, appKey, testConfigs) {
        def apps = testConfigs?.platforms?.raspi?.apps ?: []
        
        steps.echo "--- DEBUG: Formatting DUT Name ---"
        steps.echo "Incoming appKey: ${appKey}"
        steps.echo "Apps found in config: ${apps.collect { it.name }}"

        // Find the matching app entry from the configuration
        def appEntry = apps.find { it.name == appKey || (it.sha && appKey.contains(it.sha)) }
        
        if (!appEntry) {
            steps.echo "DEBUG: No match found in config for ${appKey}. Using fallback cleaning."
            return appKey.contains('-') ? appKey.tokenize('-')[1..-1].join('-') : appKey
        }

        steps.echo "DEBUG: Match found! Name: ${appEntry.name}, Branch: ${appEntry.branch}, SHA: ${appEntry.sha}"

        def ref = appEntry.tag ?: appEntry.branch ?: (appEntry.pr ? "PR-${appEntry.pr}" : "master")
        def shaSuffix = appEntry.sha ? "-${appEntry.sha}" : ""
        
        return "${appEntry.name} - ( ${ref}${shaSuffix} )"
    }

    def generateReport(def steps, String logDir, String buildNumber, def testConfigs) {
        def controllerRef = resolveControllerRef(testConfigs)
        def jsonFiles = []
        
        steps.echo "--- DEBUG: Starting Report Generation ---"
        steps.echo "Log Directory: ${logDir}"

        steps.dir(logDir) {
            jsonFiles = steps.findFiles(glob: "**/execution_results.json")
        }
        
        if (jsonFiles.length == 0) {
            steps.error "No execution_results.json files found in ${logDir}"
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
        body { font-family: 'Segoe UI', Tahoma, Arial, sans-serif; margin: 0; padding: 20px; background-color: #f4f7f9; }
        .report-wrapper { width: 95%; max-width: 1200px; margin: 0 auto; }
        .header-strip { background: #2c3e50; color: white; padding: 20px; border-radius: 8px; margin-bottom: 25px; font-size: 14px; }
        .card { background: white; padding: 25px; border-radius: 10px; box-shadow: 0 4px 6px rgba(0,0,0,0.05); margin-bottom: 30px; page-break-inside: avoid; border: 1px solid #e1e4e8; }
        .dut-header-row { display: flex; justify-content: space-between; align-items: center; border-bottom: 2px solid #3498db; padding-bottom: 15px; margin-bottom: 20px; }
        .pie-chart-box { width: 300px; height: 180px; }
        .comparison-chart-box { width: 100%; height: 450px; margin-top: 20px; }
        table { border-collapse: collapse; width: 100%; margin-top: 15px; table-layout: fixed; }
        th { background-color: #f8f9fa; color: #2c3e50; padding: 12px; text-align: left; border-bottom: 2px solid #dee2e6; font-size: 13px; }
        td { padding: 10px; border-bottom: 1px solid #eee; font-size: 12px; word-wrap: break-word; }
        .status-pass { color: #27ae60; font-weight: bold; }
        .status-fail { color: #e74c3c; font-weight: bold; }
        h1 { color: #2c3e50; }
        h2 { color: #2980b9; margin: 0; }
    </style>
</head>
<body>
    <div class="report-wrapper">
        <h1>Backward Compatibility Report</h1>
        <div class="header-strip">
            <strong>Controller SDK:</strong> ${controllerRef} &nbsp;|&nbsp; <strong>Build ID:</strong> #${buildNumber}
        </div>
"""

        def dutContent = ""
        masterData.each { appKey, testCases ->
            // Updated call to include 'steps' for logging
            def formattedName = formatDutName(steps, appKey, testConfigs)
            def chartId = "pie_" + appKey.replaceAll(/[^a-zA-Z0-9]/, '_')
            int passCount = 0
            int failCount = 0
            def tableRows = ""

            testCases.each { name, data ->
                if (data.result == 'PASS') passCount++ else failCount++
                if (!state.testcaseExecutionOrder.contains(name)) state.testcaseExecutionOrder.add(name)
                if (!state.durationMatrix[name]) state.durationMatrix[name] = [:]
                state.durationMatrix[name][appKey] = data.duration
                tableRows += "<tr><td><b>${name}</b></td><td class='${data.result == 'PASS' ? 'status-pass' : 'status-fail'}'>${data.result}</td><td>${data.duration}s</td><td>${data.error ?: '-'}</td></tr>"
            }

            double rate = (passCount + failCount) > 0 ? (passCount / (passCount + failCount) * 100).toBigDecimal().setScale(1, java.math.RoundingMode.HALF_UP).doubleValue() : 0

            dutContent += """
            <div class="card">
                <div class="dut-header-row">
                    <div>
                        <h2>DUT: ${formattedName}</h2>
                        <div style="font-size:15px; margin-top:10px;">
                            <b>Passed:</b> <span class="status-pass">${passCount}</span> | 
                            <b>Failed:</b> <span class="status-fail">${failCount}</span> | 
                            <b>Pass Rate:</b> ${rate}%
                        </div>
                    </div>
                    <div class="pie-chart-box"><canvas id="${chartId}"></canvas></div>
                </div>
                <table>
                    <thead><tr><th style="width:30%">Testcase</th><th style="width:15%">Status</th><th style="width:15%">Duration</th><th style="width:40%">Details</th></tr></thead>
                    <tbody>${tableRows}</tbody>
                </table>
            </div>
            <script>
                new Chart(document.getElementById('${chartId}'), {
                    type: 'pie',
                    data: { labels: ['Pass', 'Fail'], datasets: [{ data: [${passCount}, ${failCount}], backgroundColor: ['#2ecc71', '#e74c3c'] }] },
                    options: { animation: false, responsive: true, maintainAspectRatio: false, plugins: { legend: { position: 'right' } } }
                });
            </script>"""
        }

        def labels = state.testcaseExecutionOrder.collect { "'${it}'" }.join(",")
        def datasetsList = []
        masterData.each { appKey, testCases ->
            def values = state.testcaseExecutionOrder.collect { state.durationMatrix[it][appKey] ?: 0 }.join(",")
            // Updated call to include 'steps' for logging
            def dName = formatDutName(steps, appKey, testConfigs)
            datasetsList.add("{ label: '${dName}', data: [${values}], fill: false, tension: 0.2, borderWidth: 3, pointRadius: 4 }")
        }
        def datasets = datasetsList.join(",")

        def comparisonHtml = """
        <div class="card">
            <h2>Performance Trend Comparison (All DUTs)</h2>
            <div class="comparison-chart-box"><canvas id="compChart"></canvas></div>
        </div>
        <script>
            new Chart(document.getElementById('compChart'), {
                type: 'line',
                data: { labels: [${labels}], datasets: [${datasets}] },
                options: { 
                    animation: false, responsive: true, maintainAspectRatio: false,
                    scales: { y: { beginAtZero: true, title: { display: true, text: 'Seconds' } } },
                    plugins: { legend: { position: 'bottom' } }
                }
            });
        </script>
        """ 

        def finalHtml = htmlHeader + dutContent + comparisonHtml + "</div></body></html>"

        steps.ws(logDir) {
            steps.writeFile(file: "BackwardCompatibility_Report.html", text: finalHtml)
            
            steps.echo "Generating PDF using wkhtmltopdf..."
            steps.sh "wkhtmltopdf --enable-javascript --javascript-delay 15000 BackwardCompatibility_Report.html BackwardCompatibility_Report.pdf"
            
            steps.publishHTML(target: [reportDir: '.', reportFiles: 'BackwardCompatibility_Report.html', reportName: 'Compatibility Report'])
            steps.archiveArtifacts artifacts: '*.pdf, *.html', allowEmptyArchive: true
        }
    }
}