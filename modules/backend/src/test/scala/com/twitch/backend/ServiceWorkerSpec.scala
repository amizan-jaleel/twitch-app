package com.twitch.backend

import java.nio.file.{Files, Paths}

import munit.FunSuite

class ServiceWorkerSpec extends FunSuite {

  private def serviceWorkerSource: String = {
    val start = Paths.get("").toAbsolutePath
    val swPath = Iterator
      .iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("modules/frontend/sw.js"))
      .find(Files.exists(_))
      .getOrElse(fail(s"Could not find modules/frontend/sw.js from $start"))

    Files.readString(swPath)
  }

  test("service worker precache does not fail install on one missing asset") {
    val source = serviceWorkerSource

    assert(
      !source.contains("cache.addAll(PRECACHE_URLS)"),
      "cache.addAll rejects the whole install when any precache URL is missing",
    )
    assert(
      source.contains("Promise.allSettled(PRECACHE_URLS.map((url) => cache.add(url)))"),
      "precache entries should be cached independently so one failure does not block install",
    )
    assert(
      source.contains("SW precache skipped:"),
      "precache failures should be visible in DevTools",
    )
  }

  test("service worker does not precache the dev-only CSS path") {
    assert(
      !serviceWorkerSource.contains("'/dist/output.css'"),
      "production serves the compiled CSS as /output.css, not /dist/output.css",
    )
  }

}
