/*
 * Copyright (C) 2021 Square, Inc.
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
package app.cash.zipline.loader

import app.cash.zipline.EventListener
import app.cash.zipline.Zipline
import app.cash.zipline.loader.fetcher.Fetcher
import app.cash.zipline.loader.fetcher.FsCachingFetcher
import app.cash.zipline.loader.fetcher.FsEmbeddedFetcher
import app.cash.zipline.loader.fetcher.HttpFetcher
import app.cash.zipline.loader.fetcher.fetch
import app.cash.zipline.loader.fetcher.fetchManifest
import app.cash.zipline.loader.fetcher.pin
import app.cash.zipline.loader.fetcher.unpin
import app.cash.zipline.loader.internal.database.SqlDriverFactory
import app.cash.zipline.loader.receiver.FsSaveReceiver
import app.cash.zipline.loader.receiver.Receiver
import app.cash.zipline.loader.receiver.ZiplineLoadReceiver
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import okio.ByteString.Companion.encodeUtf8
import okio.Closeable
import okio.FileSystem
import okio.Path

/**
 * Gets code from an HTTP server, or optional local cache
 * or embedded filesystem, and handles with a receiver
 * (by default, loads it into a zipline instance).
 *
 * Loader attempts to load code as quickly as possible with
 * concurrent network downloads and code loading.
 *
 * @param fetchers this should be ordered with embedded fetchers preceding network fetchers. That
 *     way the network is used for fresh resources and embedded is used for fast resources.
 */
class ZiplineLoader internal constructor(
  private val sqlDriverFactory: SqlDriverFactory,
  private val dispatcher: CoroutineDispatcher,
  private val httpClient: ZiplineHttpClient,
  private val eventListener: EventListener,
  private val serializersModule: SerializersModule,
  private val manifestVerifier: ManifestVerifier?,
  private val embeddedDir: Path?,
  private val embeddedFileSystem: FileSystem?,
  internal val cache: ZiplineCache?,
) : Closeable {
  override fun close() {
    cache?.close()
  }

  fun withEmbedded(
    embeddedDir: Path,
    embeddedFileSystem: FileSystem
  ): ZiplineLoader = ZiplineLoader(
    sqlDriverFactory = sqlDriverFactory,
    dispatcher = dispatcher,
    httpClient = httpClient,
    eventListener = eventListener,
    serializersModule = serializersModule,
    manifestVerifier = manifestVerifier,
    embeddedDir = embeddedDir,
    embeddedFileSystem = embeddedFileSystem,
    cache = cache,
  )

  fun withCache(
    fileSystem: FileSystem,
    directory: Path,
    maxSizeInBytes: Long,
    nowMs: () -> Long
  ): ZiplineLoader {
    val driver = sqlDriverFactory.create(directory / "zipline.db", Database.Schema)
    val databaseCloseable = object : Closeable {
      override fun close() {
        driver.close()
      }
    }
    val database = createDatabase(driver = driver)
    val cache = ZiplineCache(
      eventListener = eventListener,
      databaseCloseable = databaseCloseable,
      database = database,
      fileSystem = fileSystem,
      directory = directory,
      maxSizeInBytes = maxSizeInBytes,
      nowMs = nowMs,
    )
    cache.prune()
    return ZiplineLoader(
      sqlDriverFactory = sqlDriverFactory,
      dispatcher = dispatcher,
      httpClient = httpClient,
      eventListener = eventListener,
      serializersModule = serializersModule,
      manifestVerifier = manifestVerifier,
      embeddedDir = embeddedDir,
      embeddedFileSystem = embeddedFileSystem,
      cache = cache,
    )
  }

  private var concurrentDownloadsSemaphore = Semaphore(3)
  var concurrentDownloads = 3
    set(value) {
      require(value > 0)
      field = value
      concurrentDownloadsSemaphore = Semaphore(value)
    }

  /**
   * Ensure correct ordering of fetchers to provide offline first load via embedded fetcher primacy.
   */
  private val fetchers = run {
    val result = mutableListOf<Fetcher>()
    val httpFetcher = HttpFetcher(httpClient, eventListener)
    if (embeddedDir != null && embeddedFileSystem != null) {
      result += FsEmbeddedFetcher(
        eventListener = eventListener,
        embeddedDir = embeddedDir,
        embeddedFileSystem = embeddedFileSystem,
      )
    }
    if (cache != null) {
      result += FsCachingFetcher(
        cache = cache,
        delegate = httpFetcher,
      )
    } else {
      result += httpFetcher
    }
    result
  }

  suspend fun loadOrFallBack(
    applicationName: String,
    manifestUrl: String,
    initializer: (Zipline) -> Unit = {}
  ): Zipline {
    return try {
      createZiplineAndLoad(applicationName, manifestUrl, null, initializer)
    } catch (e: Exception) {
      createZiplineAndLoad(applicationName, null, null, initializer)
    }
  }

  /**
   * Creates a Zipline for [applicationName] by fetching the manifest, fetching the modules, loading
   * the code, and running the initializer.
   *
   * Pass either a [manifestUrl] or a [manifest]. There's no need to pass both.
   */
  private suspend fun createZiplineAndLoad(
    applicationName: String,
    manifestUrl: String?,
    manifest: ZiplineManifest?,
    initializer: (Zipline) -> Unit,
  ): Zipline {
    eventListener.applicationLoadStart(applicationName, manifestUrl)
    val zipline = Zipline.create(dispatcher, serializersModule, eventListener)
    try {
      // Load from either pinned in cache or embedded by forcing network failure
      @Suppress("NAME_SHADOWING")
      val manifest = manifest ?: fetchAndVerifyZiplineManifest(applicationName, manifestUrl)
      receive(ZiplineLoadReceiver(zipline), manifest, applicationName)

      // Run caller lambda to validate and initialize the loaded code to confirm it works.
      initializer(zipline)

      // Pin stable application manifest after a successful load, and unpin all others.
      fetchers.pin(applicationName, manifest)

      eventListener.applicationLoadEnd(applicationName, manifestUrl)
      return zipline
    } catch (e: Exception) {
      zipline.close()
      eventListener.applicationLoadFailed(applicationName, manifestUrl, e)
      throw e
    }
  }

  suspend fun loadContinuously(
    applicationName: String,
    manifestUrlFlow: Flow<String>,
    pollingInterval: Duration,
    initializer: (Zipline) -> Unit,
  ): Flow<Zipline> = manifestUrlFlow
    .rebounce(pollingInterval)
    .mapNotNull { url ->
      eventListener.applicationLoadStart(applicationName, url)
      url to fetchAndVerifyZiplineManifest(applicationName, url)
    }
    .distinctUntilChanged()
    .mapNotNull { (manifestUrl, manifest) ->
      createZiplineAndLoad(
        applicationName = applicationName,
        manifestUrl = manifestUrl,
        manifest = manifest,
        initializer = initializer
      )
    }

  /** Load application into Zipline without fallback on failure functionality. */
  suspend fun loadOrFail(
    applicationName: String,
    manifestUrl: String,
    initializer: (Zipline) -> Unit = {},
  ): Zipline {
    return createZiplineAndLoad(applicationName, manifestUrl, null, initializer)
  }

  internal suspend fun loadOrFail(
    applicationName: String,
    manifest: ZiplineManifest,
    initializer: (Zipline) -> Unit = {},
  ): Zipline {
    return createZiplineAndLoad(applicationName, null, manifest, initializer)
  }

  suspend fun download(
    applicationName: String,
    downloadDir: Path,
    downloadFileSystem: FileSystem,
    manifestUrl: String,
  ) {
    download(
      applicationName = applicationName,
      downloadDir = downloadDir,
      downloadFileSystem = downloadFileSystem,
      manifest = fetchAndVerifyZiplineManifest(applicationName, manifestUrl),
    )
  }

  internal suspend fun download(
    applicationName: String,
    downloadDir: Path,
    downloadFileSystem: FileSystem,
    manifest: ZiplineManifest,
  ) {
    downloadFileSystem.createDirectories(downloadDir)
    downloadFileSystem.write(downloadDir / getApplicationManifestFileName(applicationName)) {
      write(Json.encodeToString(manifest).encodeUtf8())
    }
    receive(FsSaveReceiver(downloadFileSystem, downloadDir), manifest, applicationName)
  }

  private suspend fun receive(
    receiver: Receiver,
    manifest: ZiplineManifest,
    applicationName: String,
  ) {
    coroutineScope {
      val loads = manifest.modules.map {
        ModuleJob(applicationName, it.key, it.value, receiver)
      }
      try {
        for (load in loads) {
          val loadJob = launch { load.run() }
          val downstreams = loads.filter { load.id in it.module.dependsOnIds }
          for (downstream in downstreams) {
            downstream.upstreams += loadJob
          }
        }
      } catch (e: Exception) {
        // On exception, unpin this manifest; and rethrow so that the load attempt fails.
        fetchers.unpin(applicationName, manifest)
        throw e
      }
    }
  }

  private inner class ModuleJob(
    val applicationName: String,
    val id: String,
    val module: ZiplineModule,
    val receiver: Receiver,
  ) {
    val upstreams = mutableListOf<Job>()

    /**
     * Fetch and receive ZiplineFile module
     */
    suspend fun run() {
      // Fetch modules local-first since we have a hash and all content is the same.
      val byteString = fetchers.fetch(
        concurrentDownloadsSemaphore = concurrentDownloadsSemaphore,
        applicationName = applicationName,
        id = id,
        sha256 = module.sha256,
        url = module.url,
      )!!
      check(byteString.sha256() == module.sha256) {
        "checksum mismatch for $id"
      }
      upstreams.joinAll()
      withContext(dispatcher) {
        receiver.receive(byteString, id, module.sha256)
      }
    }
  }

  /**
   * This verifies the manifest regardless of its origin. That way we defend against changes to the
   * manifest on a server as well as in the local cache.
   */
  private suspend fun fetchAndVerifyZiplineManifest(
    applicationName: String,
    manifestUrl: String?,
  ): ZiplineManifest {
    // Fetch manifests remote-first as that's where the freshest data is.
    val loaded = fetchers.asReversed().fetchManifest(
      concurrentDownloadsSemaphore = concurrentDownloadsSemaphore,
      applicationName = applicationName,
      id = getApplicationManifestFileName(applicationName),
      url = manifestUrl,
    )!!
    val manifestBytes = loaded.manifestBytes
    val manifest = loaded.manifest
    manifestVerifier?.verify(manifestBytes, manifest)
    return manifest
  }

  companion object {
    private const val APPLICATION_MANIFEST_FILE_NAME_SUFFIX = "manifest.zipline.json"
    fun getApplicationManifestFileName(applicationName: String) =
      "$applicationName.$APPLICATION_MANIFEST_FILE_NAME_SUFFIX"
  }
}
