/*
 * Copyright (C) 2022 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.cash.zipline.loader.internal

import app.cash.zipline.loader.ZiplineManifest
import app.cash.zipline.loader.ZiplineModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okio.ByteString.Companion.decodeHex

/** Make sure the content covered by the signature is what we expect. */
class SignaturePayloadTest {
  @Test
  fun differentUrlsHaveTheSamePayloads() {
    val manifestA = ZiplineManifest.create(
      modules = mapOf(
        "./kotlin_kotlin.js" to ZiplineModule(
          url = "kotlin_kotlin.zipline",
          sha256 = "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab".decodeHex(),
          dependsOnIds = listOf(),
        )
      ),
      mainModuleId = "./kotlin_kotlin.js",
      mainFunction = "app.cash.prepareApp",
    )

    val manifestB = ZiplineManifest.create(
      modules = mapOf(
        "./kotlin_kotlin.js" to ZiplineModule(
          url = "this is a completely different URL",
          sha256 = "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab".decodeHex(),
          dependsOnIds = listOf(),
        )
      ),
      mainModuleId = "./kotlin_kotlin.js",
      mainFunction = "app.cash.prepareApp",
    )

    assertEquals(
      signaturePayload(manifestA.toJson()),
      signaturePayload(manifestB.toJson()),
    )
  }

  @Test
  fun differentSignatureValuesHaveTheSamePayload() {
    val manifestA = ZiplineManifest.create(
      modules = mapOf(
        "./kotlin_kotlin.js" to ZiplineModule(
          url = "kotlin_kotlin.zipline",
          sha256 = "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab".decodeHex(),
          dependsOnIds = listOf(),
        )
      ),
      mainModuleId = "./kotlin_kotlin.js",
      mainFunction = "app.cash.prepareApp",
      signatures = mapOf(
        "sigA" to "0f91508b8451a8ed4eedf723f22613fe",
        "sigB" to "55a3605081f20817859d494103bc43d7",
      )
    )

    val manifestB = manifestA.copy(
      signatures = mapOf(
        "sigA" to "0f91508b8451a8ed4eedf723f22613ff", // Last character is changed.
        "sigB" to "55a3605081f20817859d494103bc43d8", // Last character is changed.
      )
    )

    assertEquals(
      signaturePayload(manifestA.toJson()),
      signaturePayload(manifestB.toJson()),
    )
  }

  @Test
  fun signaturePresenceIsSignificant() {
    val manifestA = ZiplineManifest.create(
      modules = mapOf(
        "./kotlin_kotlin.js" to ZiplineModule(
          url = "kotlin_kotlin.zipline",
          sha256 = "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab".decodeHex(),
          dependsOnIds = listOf(),
        )
      ),
      mainModuleId = "./kotlin_kotlin.js",
      mainFunction = "app.cash.prepareApp",
      signatures = mapOf(
        "sigA" to "0f91508b8451a8ed4eedf723f22613fe",
        "sigB" to "55a3605081f20817859d494103bc43d7",
      )
    )

    val manifestB = manifestA.copy(
      signatures = mapOf(
        "sigA" to "0f91508b8451a8ed4eedf723f22613fe",
        // sigB is absent.
      )
    )

    assertNotEquals(
      signaturePayload(manifestA.toJson()),
      signaturePayload(manifestB.toJson()),
    )
  }

  @Test
  fun signatureOrderIsSignificant() {
    val manifestA = ZiplineManifest.create(
      modules = mapOf(
        "./kotlin_kotlin.js" to ZiplineModule(
          url = "kotlin_kotlin.zipline",
          sha256 = "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab".decodeHex(),
          dependsOnIds = listOf(),
        )
      ),
      mainModuleId = "./kotlin_kotlin.js",
      mainFunction = "app.cash.prepareApp",
      signatures = mapOf(
        "sigA" to "0f91508b8451a8ed4eedf723f22613fe",
        "sigB" to "55a3605081f20817859d494103bc43d7",
      )
    )

    val manifestB = manifestA.copy(
      signatures = mapOf(
        "sigB" to "55a3605081f20817859d494103bc43d7", // sigB is first here, last above.
        "sigA" to "0f91508b8451a8ed4eedf723f22613fe",
      )
    )

    assertNotEquals(
      signaturePayload(manifestA.toJson()),
      signaturePayload(manifestB.toJson()),
    )
  }

  @Test
  fun moduleHashIsSignificant() {
    val manifestA = ZiplineManifest.create(
      modules = mapOf(
        "./kotlin_kotlin.js" to ZiplineModule(
          url = "kotlin_kotlin.zipline",
          sha256 = "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab".decodeHex(),
          dependsOnIds = listOf(),
        )
      ),
      mainModuleId = "./kotlin_kotlin.js",
      mainFunction = "app.cash.prepareApp",
    )

    val manifestB = manifestA.copy(
      modules = mapOf(
        "./kotlin_kotlin.js" to ZiplineModule(
          url = "kotlin_kotlin.zipline",
          // Last character is changed:
          sha256 = "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6aa".decodeHex(),
          dependsOnIds = listOf(),
        )
      ),
    )

    assertNotEquals(
      signaturePayload(manifestA.toJson()),
      signaturePayload(manifestB.toJson()),
    )
  }

  @Test
  fun mainModuleIdIsSignificant() {
    val manifestA = ZiplineManifest.create(
      modules = mapOf(
        "./kotlin_kotlin.js" to ZiplineModule(
          url = "kotlin_kotlin.zipline",
          sha256 = "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab".decodeHex(),
          dependsOnIds = listOf(),
        )
      ),
      mainModuleId = "./kotlin_kotlin.js",
      mainFunction = "app.cash.prepareApp",
    )

    val manifestB = manifestA.copy(
      mainModuleId = "./kotlin_kotlin_2.js",
    )

    assertNotEquals(
      signaturePayload(manifestA.toJson()),
      signaturePayload(manifestB.toJson()),
    )
  }

  @Test
  fun mainFunctionIsSignificant() {
    val manifestA = ZiplineManifest.create(
      modules = mapOf(
        "./kotlin_kotlin.js" to ZiplineModule(
          url = "kotlin_kotlin.zipline",
          sha256 = "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab".decodeHex(),
          dependsOnIds = listOf(),
        )
      ),
      mainModuleId = "./kotlin_kotlin.js",
      mainFunction = "app.cash.prepareApp",
    )

    val manifestB = manifestA.copy(
      mainFunction = "app.cash.prepareApp2",
    )

    assertNotEquals(
      signaturePayload(manifestA.toJson()),
      signaturePayload(manifestB.toJson()),
    )
  }

  @Test
  fun signaturePayloadStripsRelativeUrlsAndSignatureContents() {
    val manifestJson = """
      |{
      |    "modules": {
      |        "./kotlin_kotlin.js": {
      |            "url": "kotlin_kotlin.zipline",
      |            "sha256": "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab"
      |        },
      |        "./kotlin_org_jetbrains_kotlinx_atomicfu.js": {
      |            "url": "kotlin_org_jetbrains_kotlinx_atomicfu.zipline",
      |            "sha256": "b6b4acab7610cb7589b86e4037b3a777107c5cd61eb5c473f1071a9cbe430d7f",
      |            "dependsOnIds": [
      |                "./kotlin_kotlin.js"
      |            ]
      |        }
      |    },
      |    "signatures": {
      |        "sigA": "0f91508b8451a8ed4eedf723f22613fe",
      |        "sigB": "55a3605081f20817859d494103bc43d7"
      |    }
      |}
      """.trimMargin()

    assertEquals(
      """
      |{
      |    "modules": {
      |        "./kotlin_kotlin.js": {
      |            "url": "",
      |            "sha256": "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab"
      |        },
      |        "./kotlin_org_jetbrains_kotlinx_atomicfu.js": {
      |            "url": "",
      |            "sha256": "b6b4acab7610cb7589b86e4037b3a777107c5cd61eb5c473f1071a9cbe430d7f",
      |            "dependsOnIds": [
      |                "./kotlin_kotlin.js"
      |            ]
      |        }
      |    },
      |    "signatures": {
      |        "sigA": "",
      |        "sigB": ""
      |    }
      |}
      """.trimMargin(),
      signaturePayloadPretty(manifestJson),
    )
  }

  @Test
  fun signaturePayloadCompact() {
    val manifestJson = """
      |{
      |    "modules": {
      |        "./kotlin_kotlin.js": {
      |            "url": "kotlin_kotlin.zipline",
      |            "sha256": "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab"
      |        }
      |    },
      |    "signatures": {
      |        "sigA": "0f91508b8451a8ed4eedf723f22613fe"
      |    }
      |}
      """.trimMargin()

    assertEquals(
      """{"modules":{"./kotlin_kotlin.js":{"url":"","sha256":"6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab"}},"signatures":{"sigA":""}}""",
      signaturePayload(manifestJson),
    )
  }

  @Test
  fun signaturePayloadHandlesMissingUrl() {
    val manifestJson = """
      |{
      |    "modules": {
      |        "./kotlin_kotlin.js": {
      |            "sha256": "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab"
      |        }
      |    },
      |    "signatures": {
      |    }
      |}
      """.trimMargin()

    assertEquals(
      """
      |{
      |    "modules": {
      |        "./kotlin_kotlin.js": {
      |            "sha256": "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab"
      |        }
      |    },
      |    "signatures": {
      |    }
      |}
      """.trimMargin(),
      signaturePayloadPretty(manifestJson),
    )
  }

  @Test
  fun signaturePayloadHandlesMissingModules() {
    val manifestJson = """
      |{
      |    "signatures": {
      |    }
      |}
      """.trimMargin()

    assertEquals(
      """
      |{
      |    "signatures": {
      |    }
      |}
      """.trimMargin(),
      signaturePayloadPretty(manifestJson),
    )
  }

  @Test
  fun signaturePayloadHandlesMissingSignatures() {
    val manifestJson = """
      |{
      |    "modules": {
      |        "./kotlin_kotlin.js": {
      |            "url": "kotlin_kotlin.zipline",
      |            "sha256": "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab"
      |        }
      |    }
      |}
      """.trimMargin()

    assertEquals(
      """
      |{
      |    "modules": {
      |        "./kotlin_kotlin.js": {
      |            "url": "",
      |            "sha256": "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab"
      |        }
      |    }
      |}
      """.trimMargin(),
      signaturePayloadPretty(manifestJson),
    )
  }

  @Test
  fun signaturePayloadRetainsUnknownFields() {
    val manifestJson = """
      |{
      |    "unknown boolean": true,
      |    "unknown int": 5,
      |    "unknown float": 5.0,
      |    "unknown string": "five",
      |    "unknown null": null,
      |    "modules": {
      |        "./kotlin_kotlin.js": {
      |            "url": "kotlin_kotlin.zipline",
      |            "sha256": "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab"
      |        }
      |    },
      |    "signatures": {
      |        "sigA": "0f91508b8451a8ed4eedf723f22613fe"
      |    }
      |}
      """.trimMargin()

    assertEquals(
      """
      |{
      |    "unknown boolean": true,
      |    "unknown int": 5,
      |    "unknown float": 5.0,
      |    "unknown string": "five",
      |    "unknown null": null,
      |    "modules": {
      |        "./kotlin_kotlin.js": {
      |            "url": "",
      |            "sha256": "6bd4baa9f46afa62477fec8c9e95528de7539f036d26fc13885177b32fc0d6ab"
      |        }
      |    },
      |    "signatures": {
      |        "sigA": ""
      |    }
      |}
      """.trimMargin(),
      signaturePayloadPretty(manifestJson),
    )
  }

  /** Gets the signature payload for testing. */
  private fun signaturePayloadPretty(manifestJson: String): String {
    val json = Json {
      prettyPrint = true
    }

    val jsonElement = json.parseToJsonElement(manifestJson)
    val signaturePayload = signaturePayload(jsonElement)
    return json.encodeToString(JsonElement.serializer(), signaturePayload)
  }

  private fun ZiplineManifest.toJson(): String {
    return Json.encodeToString(ZiplineManifest.serializer(), this)
  }
}
