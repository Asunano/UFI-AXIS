package com.ufi_axis_core.controller.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置/远端清单合并。
 *
 * 这是"远端短暂没有某个程序时商店不能把它清空"的唯一保证：合并逻辑一旦写反，
 * 断网/镜像滞后会把已展示的程序整批变成空白页。
 */
class ProgramCatalogTest {

    private fun manifest(id: String, version: String = "1.0.0") = ProgramManifest(
        id = id,
        name = id.uppercase(),
        artifact = ProgramManifest.Artifact(
            version = version,
            url = "https://example.com/$id.tar.gz",
            sha256 = "a".repeat(64)
        ),
        run = ProgramManifest.Run()
    )

    @Test
    fun `远端同名覆盖内置且保留远端没有的条目`() {
        val catalog = ProgramCatalog()
        catalog.replaceBuiltIn(listOf(manifest("alpha"), manifest("beta", "1.0.0")))
        catalog.replaceRemote(listOf(manifest("beta", "2.0.0"), manifest("gamma")))

        assertEquals(listOf("alpha", "beta", "gamma"), catalog.all().map { it.id })
        assertEquals("1.0.0", catalog.get("alpha")?.artifact?.version)
        assertEquals("2.0.0", catalog.get("beta")?.artifact?.version)
        assertEquals("1.0.0", catalog.get("gamma")?.artifact?.version)
    }

    @Test
    fun `远端空列表不清空内置清单`() {
        val catalog = ProgramCatalog()
        catalog.replaceBuiltIn(listOf(manifest("alpha")))
        catalog.replaceRemote(emptyList())

        assertNotNull(catalog.get("alpha"))
        assertEquals(1, catalog.all().size)
    }

    @Test
    fun `重复 id 保留第一条`() {
        val catalog = ProgramCatalog()
        catalog.replaceBuiltIn(listOf(manifest("alpha", "1.0.0"), manifest("alpha", "9.9.9")))

        assertEquals(1, catalog.all().size)
        assertEquals("1.0.0", catalog.get("alpha")?.artifact?.version)
    }

    @Test
    fun `骨架仍参与展示 id 合集`() {
        val catalog = ProgramCatalog()
        catalog.replaceBuiltIn(emptyList())
        catalog.replaceSkeleton(
            mapOf("skeleton-only" to ProgramCatalog.SkeletonEntry("Skeleton", "summary"))
        )

        assertTrue(catalog.idsForDisplay().contains("skeleton-only"))
        assertEquals("Skeleton", catalog.skeletonOf()["skeleton-only"]?.name)
    }
}
