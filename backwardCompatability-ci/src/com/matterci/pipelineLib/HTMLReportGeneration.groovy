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

    def resolveDutRef(app) {
        if (app.sha) {
            def baseRef = app.branch ?: app.tag ?: (app.pr ? "PR-${app.pr}" : "master")
            return "${baseRef} (${app.sha})"
        }
        return app.tag ?: (app.pr ? "PR-${app.pr}" : app.branch)
    }


    def generateReport(def steps, String workspace, String buildNumber, def testConfigs, List apps) {
        def controllerRef = resolveControllerRef(testConfigs)
        def logRoot = "${workspace}"
        steps.echo "Generating HTML report from: ${logRoot}"
        def durationMatrix = [:]
        def testcaseExecutionOrder = []

        def html = """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<title>Backward Compatibility Report</title>
</head>

<body style="font-family:Arial; margin:25px; background-color:#f7f9fc;">

<h1 style="color:#1f2d3d;">
Backward Compatibility Report
</h1>

<div style="background-color:#ecf0f1; padding:12px; border-left:6px solid #2c3e50; margin-bottom:25px;">

<h2 style="color:#2c3e50; margin-top:35px;">
<b>Controller Reference:</b> ${controllerRef}<br>
<b>Build Number:</b> ${buildNumber}<br>
<b>Execution Time:</b> ${new Date()}
</h2>

</div>
"""
        apps.each { app ->
            def appName = app.name
            def dutRef = resolveDutRef(app)
            def safeRef = dutRef.replaceAll("[^a-zA-Z0-9._+-]", "_")
            def appLogPath = "${logRoot}/${appName}_${safeRef}/MatterTest"

            steps.echo "Processing DUT logs: ${appName}"

            def summaryFiles = steps.sh(
                script: """
                if [ -d "${appLogPath}" ]; then
                    find "${appLogPath}" -name test_summary.yaml
                fi
                """,
                returnStdout: true
            ).trim()

            if (!summaryFiles) {
                steps.echo "No summary files found for ${appName}"
                return
            }

            html += """
<h2 style="color:#2c3e50; margin-top:35px;">
DUT: ${appName} (${dutRef})
</h2>

<table style="border-collapse:collapse; width:100%; background-color:white;">

<tr style="background-color:#2c3e50; color:white;">

<th style="padding:10px; text-align:left;">Testcase</th>
<th style="padding:10px; text-align:left;">Status</th>
<th style="padding:10px; text-align:left;">Duration (sec)</th>

</tr>
"""
            def passCount = 0
            def failCount = 0
            def failureDetails = []

            summaryFiles.split("\\n").each { file ->
                def docs = steps.readYaml(file: file, loadAll: true)
                def orderedDocs = docs.findAll {
                    it?.Type == "Record" &&
                    it["Test Name"] &&
                    it["Test Name"] != "test_run_commissioning"
                }
                .sort { it["Begin Time"] }

                orderedDocs.each { doc ->
                    if (doc?.Type == "Record" && doc["Test Name"] && doc["Test Name"] != "test_run_commissioning") {
                        def testcaseName = doc["Test Name"].replaceFirst("^test_", "")
                        def duration = ((doc["End Time"] - doc["Begin Time"]) / 1000)
                        // Store testcase order only once
                        if (!testcaseExecutionOrder.contains(testcaseName)) {
                            testcaseExecutionOrder.add(testcaseName)
                        }

                        // Store duration per app per testcase
                        def appKey = appName + "_" + dutRef
                        durationMatrix[testcaseName] = durationMatrix[testcaseName] ?: [:]
                        durationMatrix[testcaseName][appKey] = duration
                        def statusColor = doc.Result == "PASS" ? "#1e8449" : "#c0392b"
                        if (doc.Result == "PASS") {
                            passCount++
                        } else {
                            failCount++
                            def reason = doc.Stacktrace ?: doc.Details ?: doc["Termination Signal Type"] ?: doc["Extra Errors"] ?: "Test failed without specific reason"
                            failureDetails.add("${testcaseName} → ${reason.toString()}")
                        }
                        html += """
<tr>
<td style="padding:8px; border-bottom:1px solid #ddd;">
${testcaseName}
</td>
<td style="padding:8px; border-bottom:1px solid #ddd; color:${statusColor}; font-weight:bold;">
${doc.Result}
</td>
<td style="padding:8px; border-bottom:1px solid #ddd;">
${String.format("%.3f", duration)}
</td>

</tr>
"""
                    }
                }
            }

            def chartId = "summaryChart_" + appName + "_" + dutRef
            html += """
</table>

<div style="
background:white;
padding:15px;
border-left:6px solid #2c3e50;
margin-top:15px;
margin-bottom:15px;
border-radius:6px;
box-shadow:0 2px 6px rgba(0,0,0,0.08);
display:flex;
align-items:center;
gap:40px;
">

<div>

<b>Test Summary</b><br>

Passed: ${passCount}<br>
Failed: ${failCount}<br>
Total: ${passCount + failCount}

</div>

<div style="width:160px;height:160px">
<canvas id="${chartId}"></canvas>
</div>

</div>

/* Chart.js minimal embedded loader */
<script>
(function () {

function loadChart(callback) {

if (typeof Chart !== "undefined") {
callback();
return;
}

var localScript = document.createElement("script");
localScript.src = "js/chart.js";

localScript.onload = callback;

localScript.onerror = function () {

var cdnScript = document.createElement("script");
cdnScript.src =
"https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.0/chart.umd.min.js";

cdnScript.onload = callback;

document.head.appendChild(cdnScript);

};

document.head.appendChild(localScript);

}

window.loadChartJS = loadChart;

})();
</script>
<script>

loadChartJS(function () {

new Chart(
document.getElementById("${chartId}"),
{

type:"pie",

data:{
labels:["PASS","FAIL"],
datasets:[{
data:[${passCount},${failCount}],
backgroundColor:["#27ae60","#e74c3c"]
}]
},

options:{
responsive:true,
maintainAspectRatio:false,
plugins:{
legend:{position:"bottom"}
}
}

});

});

</script>
"""
        if (failureDetails && failureDetails.size() > 0) {
            html += """
<div style="background-color:#fdecea; padding:12px; border-left:6px solid #c0392b; margin-bottom:25px;">
<b>Failure Details</b><br>
"""
            failureDetails.each { failure ->
            html += """
${failure}<br>
"""
                }

        html += """
</div>
"""
            }
        }

        // Sort testcases based on execution order
        testcaseExecutionOrder.sort()
        def labels = testcaseExecutionOrder.collect { "\"${it}\"" }
        // Prepare datasets per app
        def datasets = apps.collect { app ->
            def values = testcaseExecutionOrder.collect {
                durationMatrix[it]?.get(app.name + "_" + resolveDutRef(app)) ?: null
            }

            return """
            {
            label: "${app.name} (${resolveDutRef(app)})",
            data: ${values},
            fill:false,
            tension:0.35,
            pointRadius:5,
            pointHoverRadius:8,
            borderWidth:2
            }
            """
            }.join(",")

        html += """
<h2 style="margin-top:45px; color:#2c3e50;">
Testcase Execution Duration Comparison Across DUT Apps
</h2>

<div style="
background:white;
padding:20px;
border-radius:8px;
box-shadow:0 2px 8px rgba(0,0,0,0.08);
">

<div style="width:900px; height:400px;">
<canvas id="durationComparisonChart"></canvas>
</div>

</div>

<script>
/* Chart.js minimal embedded loader */
</script>
<script src="js/chart.js"></script>

<script>
loadChartJS(function () {

new Chart(document.getElementById('durationComparisonChart'), {

type: 'line',

data: {

labels: [${labels.join(",")}],

datasets: [

${datasets}

]

},

options: {
responsive: true,
maintainAspectRatio:false,
interaction: {
mode: 'index',
intersect: false
},

plugins: {

title: {
display: true,
text: 'Test Execution Duration Comparison Across DUT Versions'
},

legend: {
position: 'top'
}

},

scales: {

x: {
title: {
display: true,
text: 'Testcases'
}
},

y: {
title: {
display: true,
text: 'Duration (seconds)'
},
beginAtZero: true
}

}

}

});

});

</script>

</body>
</html>
"""
        steps.ws(workspace) {
            steps.sh "mkdir -p reports"
            steps.sh """
                mkdir -p reports/js
                curl -L https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.0/chart.umd.min.js \
                -o reports/js/chart.js
                """
            steps.writeFile(
                file: "reports/BackwardCompatibility_Report.html",
                text: html
            )
            steps.publishHTML(target: [
                reportDir: 'reports',
                reportFiles: 'BackwardCompatibility_Report.html',
                reportName: 'Backward Compatibility Report',
                reportTitles: 'Backward Compatibility Report',
                keepAll: true,
                alwaysLinkToLastBuild: true,
                allowMissing: false,
                escapeUnderscores: false
            ])

            steps.sh """
                mkdir -p Report
                cp reports/BackwardCompatibility_Report.html Report/BackwardCompatibility_Report.html
            """
            steps.archiveArtifacts(
                artifacts: "Report/BackwardCompatibility_Report.html",
                fingerprint: true,
                allowEmptyArchive: true
            )

            steps.echo "HTML report generated successfully."
        }
    }
}