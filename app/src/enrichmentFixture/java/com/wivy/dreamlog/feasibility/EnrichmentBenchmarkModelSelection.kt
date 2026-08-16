package com.wivy.dreamlog.feasibility

import com.wivy.dreamlog.enrichment.model.EnrichmentModelArtifact
import com.wivy.dreamlog.enrichment.model.EnrichmentModelDefinition
import com.wivy.dreamlog.enrichment.model.EnrichmentModelLicense
import com.wivy.dreamlog.enrichment.model.EnrichmentModelManifest
import java.net.URI

/** Fixed model choices available only to the isolated synthetic benchmark application. */
internal enum class EnrichmentBenchmarkModelSelection(
    val intentValue: String,
    val definition: EnrichmentModelDefinition,
) {
    PRODUCTION(
        intentValue = "production",
        definition = EnrichmentModelManifest.definition,
    ),
    QWEN3_8B(
        intentValue = "qwen3_8b",
        definition = EnrichmentModelDefinition(
            id = "qwen3-8b-mixed-int4",
            revision = "71ff705588319d52d374977eff3da4eee0c0d26e",
            directoryName = "qwen3-8b-mixed-int4",
            artifact = EnrichmentModelArtifact(
                localName = "qwen3_8b_mixed_int4.litertlm",
                bytes = 4_887_412_736L,
                sha256 =
                    "cb4e6d0de4bbf6656d177812cf0c6a983967dedd17e7f88e84b901c3a9862a42",
                source = URI(
                    "https://huggingface.co/litert-community/Qwen3-8B/resolve/" +
                        "71ff705588319d52d374977eff3da4eee0c0d26e/" +
                        "qwen3_8b_mixed_int4.litertlm?download=true",
                ),
            ),
            license = EnrichmentModelLicense(
                spdxIdentifier = "Apache-2.0",
                displayName = "Apache License 2.0",
                source = URI("https://www.apache.org/licenses/LICENSE-2.0"),
            ),
        ),
    ),
    PHI4_MINI(
        intentValue = "phi4_mini",
        definition = EnrichmentModelDefinition(
            id = "phi-4-mini-instruct-q8-ekv4096",
            revision = "8cd368be75fdb94d5a6f6f5b40f1ab22a6c2543e",
            directoryName = "phi-4-mini-instruct-q8-ekv4096",
            artifact = EnrichmentModelArtifact(
                localName = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                bytes = 3_910_090_752L,
                sha256 =
                    "7764d4deb53800578307be33039476b38a6c370fff71bedb3c0552563e23ab02",
                source = URI(
                    "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/" +
                        "8cd368be75fdb94d5a6f6f5b40f1ab22a6c2543e/" +
                        "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm" +
                        "?download=true",
                ),
            ),
            license = EnrichmentModelLicense(
                spdxIdentifier = "MIT",
                displayName = "MIT License",
                source = URI("https://opensource.org/license/mit/"),
            ),
        ),
    ),
    ;

    companion object {
        const val QWEN3_8B_INTENT_VALUE = "qwen3_8b"
        const val PHI4_MINI_INTENT_VALUE = "phi4_mini"

        fun fromIntentValue(value: String?): EnrichmentBenchmarkModelSelection = when (value) {
            null, "", PRODUCTION.intentValue -> PRODUCTION
            QWEN3_8B_INTENT_VALUE -> QWEN3_8B
            PHI4_MINI_INTENT_VALUE -> PHI4_MINI
            else -> throw IllegalArgumentException(
                "Unknown fixed enrichment benchmark model candidate.",
            )
        }
    }
}
