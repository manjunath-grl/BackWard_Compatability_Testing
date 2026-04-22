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

        def html = """
<html>
<head>
<title>Backward Compatibility Report</title>
<style>
body {
    font-family: Arial;
    margin: 25px;
}
h1 {
    color: #2c3e50;
}
h2 {
    color: #34495e;
}
table {
    border-collapse: collapse;
    width: 100%;
    margin-bottom: 20px;
}
th {
    background-color: #2c3e50;
    color: white;
    padding: 10px;
}
td {
    border-bottom: 1px solid #ddd;
    padding: 8px;
}
.pass {
    color: green;
    font-weight: bold;
}
.fail {
    color: red;
    font-weight: bold;
}
.summary-box {
    background-color: #ecf0f1;
    padding: 12px;
    margin-bottom: 25px;
}
</style>
</head>
<body>
<h1>Backward Compatibility Report</h1>
<div class="summary-box">
<b>Controller Reference:</b> ${controllerRef}<br>
<b>Build Number:</b> ${buildNumber}<br>
<b>Execution Time:</b> ${new Date()}

</div>
"""
        apps.each { app ->

            def appName = app.name

            def dutRef = resolveDutRef(app)

            def appLogPath = "${logRoot}/${appName}/MatterTest"

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
<h2>DUT: ${appName} (${dutRef})</h2>

<table>

<tr>
<th>Testcase</th>
<th>Status</th>
<th>Duration (sec)</th>
</tr>
"""
            def passCount = 0
            def failCount = 0

            summaryFiles.split("\\n").each { file ->
                def docs = steps.readYaml(file: file, loadAll: true)
                docs.each { doc ->
                    if (doc?.Type == "Record"
                        && doc["Requested Tests"]
                        && doc["Requested Tests"][0] != "test_run_commissioning") {
                        def testcaseName = doc["Requested Tests"][0].replaceFirst("^test_", "")
                        def duration = ((doc["End Time"] - doc["Begin Time"]) / 1000)
                        def statusClass = doc.Result == "PASS" ? "pass" : "fail"
                        if (doc.Result == "PASS")
                            passCount++
                        else
                            failCount++
                        html += """
<tr>

<td>${testcaseName}</td>

<td class="${statusClass}">
${doc.Result}
</td>

<td>${String.format("%.3f", duration)}</td>

</tr>
"""
                    }
                }
            }

            html += """
</table>

<div class="summary-box">

<b>${appName} Summary</b><br>

Passed: ${passCount}<br>
Failed: ${failCount}<br>
Total: ${passCount + failCount}

</div>
"""
        }

        html += """
</body>

</html>
"""
        def reportPath = "${workspace}/BackwardCompatibility_Report.html"
        steps.writeFile(
            file: reportPath,
            text: html
        )

        steps.publishHTML([
            reportDir: workspace,
            reportFiles: 'BackwardCompatibility_Report.html',
            reportName: 'Backward Compatibility Report',
            keepAll: true,
            alwaysLinkToLastBuild: true,
            allowMissing: false
        ])

        steps.echo "HTML report generated successfully."
    }
}