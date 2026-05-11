package com.matterci.pipelineLib

class HTMLReportGeneration {

    /*
    ============================================================
    Resolve Controller Reference
    ============================================================
    */
    def resolveControllerRef(testConfigs) {
        def ctrl = testConfigs?.ci_config?.clone_sdk_code_stage?.controller_sdk
        if (!ctrl) {
            return "Unknown Controller"
        }
        def ref = ctrl.branch ?: ctrl.tag ?: (
            ctrl.pr ? "PR-${ctrl.pr}" : "master"
        )
        if (ctrl.sha) {
            return "${ref} (${ctrl.sha})"
        }
        return ref
    }

    def formatDutName(def steps, String appKey, def testConfigs) {
        def apps = testConfigs?.platforms?.raspi?.apps ?: []
        steps.echo "Resolving DUT Name for: ${appKey}"
        def parts = appKey.tokenize("__")
        def appName = parts.size() > 0 ? parts[0] : ""
        def branch  = parts.size() > 1 ? parts[1] : ""
        def shaPart = parts.size() > 2 ? parts[2] : ""
        def appEntry = apps.find {
            it.name == appName &&
            (branch ? it.branch == branch : true) &&
            (shaPart ? (it.sha?.startsWith(shaPart)) : true)
        }

        if (!appEntry) {
            steps.echo "WARNING: Unable to resolve appKey=${appKey}"
            return appKey
        }
        def ref = appEntry.branch ?: appEntry.tag ?: (
            appEntry.pr ? "PR-${appEntry.pr}" : "master"
        )
        if (appEntry.sha) {
            return "${appEntry.name} ( ${ref} - ${appEntry.sha} )"
        }
        return "${appEntry.name} ( ${ref} )"
    }

    /*
    ============================================================
    Generate HTML + PDF Report
    ============================================================
    */
    def generateReport(def steps,String logDir,String buildNumber,def testConfigs) {
        steps.echo "Starting Backward Compatibility Report"
        def controllerRef = resolveControllerRef(testConfigs)
        def jsonFiles = []
        steps.dir(logDir) {
            jsonFiles = steps.findFiles(glob: "**/execution_results.json")
        }
        if (!jsonFiles || jsonFiles.size() == 0) {
            steps.error "No execution_results.json files found"
        }
        def masterData = [:]
        jsonFiles.each { file ->
            steps.echo "Reading JSON: ${file.path}"
            def data = steps.readJSON(
                file: "${logDir}/${file.path}"
            )
            masterData << data
        }

        def state = [ durationMatrix: [:],testcaseExecutionOrder: [] ]
        def html = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<title>Backward Compatibility Report</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
<style>
body {
    font-family: Arial, sans-serif;
    background: #f4f7fa;
    margin: 0;
    padding: 20px;
}
.container {
    width: 95%;
    margin: auto;
}
.main-title {
    color: #2c3e50;
    margin-bottom: 20px;
}
.header-box {
    background: #2c3e50;
    color: white;
    padding: 20px;
    border-radius: 8px;
    margin-bottom: 25px;
}
.card {
    background: white;
    border-radius: 10px;
    padding: 25px;
    margin-bottom: 30px;
    box-shadow: 0px 2px 8px rgba(0,0,0,0.08);
    page-break-inside: avoid;
}
.dut-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
}
.chart-box {
    width: 280px;
    height: 180px;
}
.big-chart {
    width: 100%;
    height: 450px;
}
table {
    width: 100%;
    border-collapse: collapse;
    margin-top: 20px;
}
th {
    background: #f1f3f5;
    padding: 12px;
    text-align: left;
    border-bottom: 2px solid #dee2e6;
}
td {
    padding: 10px;
    border-bottom: 1px solid #e9ecef;
    word-break: break-word;
}
.pass {
    color: green;
    font-weight: bold;
}
.fail {
    color: red;
    font-weight: bold;
}
.summary {
    margin-top: 10px;
    font-size: 15px;
}
.chart-image {
    width: 280px;
}
</style>
</head>
<body>

<div class="container">
<h1 class="main-title">
Backward Compatibility Report
</h1>
<div class="header-box">
<b>Controller SDK:</b> ${controllerRef}
&nbsp;&nbsp; | &nbsp;&nbsp;
<b>Build ID:</b> #${buildNumber}
</div>
"""
        masterData.each { appKey, testCases ->
            def formattedName = formatDutName(steps,appKey,testConfigs)
            def chartId = "chart_" + appKey.replaceAll(/[^a-zA-Z0-9_]/,"_")
            int passCount = 0
            int failCount = 0
            def rows = ""
            testCases.each { tcName, tcData ->
                if (tcData.result == "PASS") {
                    passCount++
                } else {
                    failCount++
                }
                if (!state.testcaseExecutionOrder.contains(tcName)) {
                    state.testcaseExecutionOrder.add(tcName)
                }
                if (!state.durationMatrix[tcName]) {
                    state.durationMatrix[tcName] = [:]
                }
                state.durationMatrix[tcName][appKey] = tcData.duration

                rows += """
<tr>
<td><b>${tcName}</b></td>

<td class="${tcData.result == 'PASS' ? 'pass' : 'fail'}">
${tcData.result}
</td>

<td>${tcData.duration}s</td>

<td>${tcData.error ?: "-"}</td>
</tr>
"""
            }
            double passRate =
                (passCount + failCount) > 0 ?
                ((passCount * 100.0) /
                (passCount + failCount)).round(1)
                : 0

            html += """
<div class="card">
<div class="dut-row">
<div>
<h2>
DUT: ${formattedName}
</h2>
<div class="summary">
<b>Passed:</b>
<span class="pass">${passCount}</span>

|

<b>Failed:</b>
<span class="fail">${failCount}</span>

|

<b>Pass Rate:</b>
${passRate}%

</div>
</div>
<div class="chart-box">
<canvas id="${chartId}"></canvas>
</div>
</div>
<table>
<thead>
<tr>
<th width="30%">Testcase</th>
<th width="15%">Status</th>
<th width="15%">Duration</th>
<th width="40%">Details</th>
</tr>
</thead>

<tbody>
${rows}
</tbody>
</table>
</div>
<script>

new Chart(
    document.getElementById("${chartId}"),
    {
        type: "pie",
        data: {
            labels: ["Pass", "Fail"],
            datasets: [{
                data: [${passCount}, ${failCount}],
                backgroundColor: [
                    "#2ecc71",
                    "#e74c3c"
                ]
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: false,
            plugins: {
                legend: {
                    position: "right"
                }
            }
        }
    }
);
</script>
"""
        }

        /*
        ============================================================
        Comparison Chart
        ============================================================
        */

        def labels =state.testcaseExecutionOrder.collect {"'${it}'"}.join(",")
        def datasets = []
        masterData.each { appKey, tcData ->
            def vals = state.testcaseExecutionOrder.collect { state.durationMatrix[it][appKey] ?: 0 }.join(",")
            def label = formatDutName(steps,appKey,testConfigs)
            datasets.add("""
{
label: "${label}",
data: [${vals}],
fill: false,
tension: 0.2,
borderWidth: 3,
pointRadius: 4
}
""")
        }
        html += """
<div class="card">

<h2>
Performance Comparison
</h2>
<div class="big-chart">
<canvas id="comparisonChart"></canvas>
</div>
</div>
<script>
new Chart(
    document.getElementById("comparisonChart"),
    {
        type: "line",
        data: {
            labels: [${labels}],
            datasets: [
                ${datasets.join(",")}
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: false,
            scales: {
                y: {
                    beginAtZero: true,
                    title: {
                        display: true,
                        text: "Seconds"
                    }
                }
            },
            plugins: {
                legend: {
                    position: "bottom"
                }
            }
        }
    }
);

</script>
"""
        /*
        ============================================================
        Convert Charts To Images
        IMPORTANT FOR PDF
        ============================================================
        */
        html += """
<script>
window.onload = function() {
    setTimeout(function() {
        document.querySelectorAll("canvas").forEach(function(canvas) {
            try {
                const image = document.createElement("img");
                image.src = canvas.toDataURL("image/png");
                image.className = "chart-image";
                canvas.parentNode.appendChild(image);
                canvas.style.display = "none";
            } catch(e) {
                console.log("Chart conversion failed", e);
            }
        });
    }, 3000);
};

</script>
"""
        /*
        ============================================================
        HTML Footer
        ============================================================
        */
        html += """
</div>
</body>
</html>
"""
        /*
        ============================================================
        Write HTML
        ============================================================
        */
        steps.ws(logDir) {
            steps.writeFile(
                file: "BackwardCompatibility_Report.html",
                text: html
            )
            /*
            ============================================================
            Generate PDF
            ============================================================
            */
            steps.echo "Generating PDF using Playwright..."
            steps.writeFile(
                file: "generate_pdf.js",
                text: """
            const { chromium } = require('playwright');
            (async () => {
                const browser = await chromium.launch({
                    headless: true
                });
                const page = await browser.newPage();
                await page.goto(
                    'file://${logDir}/BackwardCompatibility_Report.html',
                    {
                        waitUntil: 'networkidle'
                    }
                );
                // Wait for chart rendering
                await page.waitForTimeout(5000);
                await page.pdf({
                    path: 'BackwardCompatibility_Report.pdf',
                    format: 'A4',
                    printBackground: true,
                    margin: {
                        top: '20px',
                        bottom: '20px',
                        left: '15px',
                        right: '15px'
                    }
                });
                await browser.close();
            })();
            """
            )
            steps.sh "node generate_pdf.js"
            steps.echo "Playwright PDF generation successful"
            /*
            ============================================================
            Publish
            ============================================================
            */
            steps.publishHTML(
                target: [
                    reportDir: '.',
                    reportFiles: 'BackwardCompatibility_Report.html',
                    reportName: 'Backward Compatibility Report',
                    keepAll: true,
                    alwaysLinkToLastBuild: true,
                    allowMissing: false
                ]
            )
            steps.archiveArtifacts(
                artifacts: '*.html, *.pdf',
                allowEmptyArchive: true
            )
            steps.echo "========================================"
            steps.echo "Report Generation Completed"
            steps.echo "========================================"
        }
    }
}