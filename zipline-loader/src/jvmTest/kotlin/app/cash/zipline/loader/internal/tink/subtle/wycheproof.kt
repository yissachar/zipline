// Copyright 2019 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package app.cash.zipline.loader.internal.tink.subtle

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

@Serializable
class EddsaTestJson(
  val algorithm: String,
  val generatorVersion: String,
  val numberOfTests: Int,
  val header: List<String>,
  val notes: Map<String, String>,
  val schema: String,
  val testGroups: List<TestGroup>,
)

@Serializable
class TestGroup(
  val jwk: Jwk,
  val key: Key,
  val keyDer: String,
  val keyPem: String,
  val type: String,
  val tests: List<TestCase>,
)

@Serializable
class TestCase(
  val tcId: Int,
  val comment: String,
  val msg: String,
  val sig: String,
  val result: String,
)

@Serializable
class Jwk(
  val crv: String,
  val d: String,
  val kid: String,
  val kty: String,
  val x: String,
)

@Serializable
class Key(
  val curve: String,
  val keySize: Int,
  val pk: String,
  val sk: String,
  val type: String,
)

fun loadEddsaTestJson(): EddsaTestJson {
  val eddsaTestJson = FileSystem.SYSTEM.read("build/wycheproof/eddsa_test.json".toPath()) {
    readUtf8()
  }

  val json = Json {
    ignoreUnknownKeys = true
  }

  return json.decodeFromString(eddsaTestJson)
}
