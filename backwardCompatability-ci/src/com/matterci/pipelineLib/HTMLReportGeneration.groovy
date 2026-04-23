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

<b>Controller Reference:</b> ${controllerRef}<br>
<b>Build Number:</b> ${buildNumber}<br>
<b>Execution Time:</b> ${new Date()}

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

            summaryFiles.split("\\n").each { file ->
                def docs = steps.readYaml(file: file, loadAll: true)
                docs.each { doc ->
                    if (doc?.Type == "Record" && doc["Test Name"] && doc["Test Name"] != "test_run_commissioning") {
                        def testcaseName = doc["Test Name"].replaceFirst("^test_", "")
                        def duration = ((doc["End Time"] - doc["Begin Time"]) / 1000)
                        def statusColor = doc.Result == "PASS" ? "#1e8449" : "#c0392b"
                        if (doc.Result == "PASS")
                            passCount++
                        else
                            failCount++
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

            html += """
</table>
<div style="background-color:#ecf0f1; padding:12px; border-left:6px solid #2c3e50; margin-top:15px; margin-bottom:25px;">
<b>Test Summary</b><br>

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

        steps.publishHTML(target: [
            reportDir: workspace,
            reportFiles: 'BackwardCompatibility_Report.html',
            reportName: 'Backward Compatibility Report',
            keepAll: true,
            alwaysLinkToLastBuild: true,
            allowMissing: false,
            escapeUnderscores: false
        ])
        steps.archiveArtifacts artifacts:"BackwardCompatibility_Report.html",fingerprint: true,allowEmptyArchive: true

        steps.echo "HTML report generated successfully."
    }
}