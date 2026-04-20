package com.matterci.pipelineLib

class CertificationToolCatalog implements Serializable {

    static final Map RELEASE_TO_IMAGE_SHA = [
        "v2.14+fall2025"           : "ca9d1118e097fe947b2aec1ba84f265d6cf2447e",
        "v2.15-beta2.1+spring2026" : "ead81748828787a656ae05c7d980f908f09ea751",
        "v2.14.1-beta2+winter2026" : "4564cd2e0a0c7059bb99719cfc3de50cefac5d10",
        "v2.15-beta2+spring2026"   : "9b1078da4307f98d362a0b44625a94d649bc1e77",
        "v2.15-beta3+spring2026"   : "c2175a1ee826fe66f1d40afc3fcf8e05689810aa",
        "v2.14-beta2.1+fall2025"   : "a82e43e06e35c707f9016c38ee83712c2ab58966",
        "v2.14-beta2+winter2025"   : "7b245457e2950177398765f28cc37f94dab1a0c2",
        "v2.15-beta2.2+spring2026" : "4bf7cfcdf31d42f1c7b00a5880c37a9c5ac4aa4b",
        "v2.14.1+winter2026"       : "e9ecfc2138887d3221dcc2995ad629c8bd4313e4",
        "v2.15-beta3.1+spring2026" : "96b1d9b9415310d61c844466fe2e1338902f662d",
        "v2.15-beta1+winter2025"   : "bab3aa0773551c0661e17b34b0e97b4e5813b45e",
        "v2.14-beta3+fall2025"     : "f902839abf1de0d17956de34889b6ad997e2c5e4"
    ]

    static boolean isReleaseBranch(String branch) {
        return branch ? RELEASE_TO_IMAGE_SHA.containsKey(branch) : false
    }

    static String getImageSha(String branch) {
        return branch ? RELEASE_TO_IMAGE_SHA[branch] : null
    }
}
