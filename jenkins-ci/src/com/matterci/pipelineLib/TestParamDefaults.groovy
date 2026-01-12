package com.matterci.pipelineLib

class TestParamDefaults {
    static Map build( Map args = [:]) {
        def mongodbServerAddress = args.get('mongodbServerAddress', '127.0.0.1')
        def numberOfIterations = args.get('numberOfIterations', 1)
        def controller_sdk_sha = args.get('controller_sdk_sha','')
        def ci_ws_path = args.get('ciWorkSpace','')
        def ci_job_name = args.get('job_name','')
        def ci_build_id = args.get('build_id','')

        return [
            "dut_config": [
                "rpi": [
                    "rpi_hostname": "some_ip_address",
                    "download_var_logs": false,
                    "app_config": [
                        "discriminator": "some_descriminator",
                        "matter_app": "some_app"
                    ],
                    "commissioning_method": ["some_commissioning_method"]
                ]
            ],
            "general_configs": [
                "mongodb_server_config": ["server_url": "${mongodbServerAddress}"],
                "controller_sdk_sha" : "${controller_sdk_sha}",
                "logging_config": [
                    "copy_var_log": false
                ],
                "number_of_iterations": numberOfIterations,
                "platform_execution": "rpi"
            ],
            "otbr_device_config": [
                "capture_tcpdump_on_otbr": false
            ],
            "ci_config": [
                "ci_job_name":ci_job_name,
                "ci_job_build_id":ci_build_id,
                "ci_ws_path":"${ci_ws_path}"]
        ]
    }
}