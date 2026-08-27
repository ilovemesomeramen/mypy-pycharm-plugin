package works.szabope.plugins.mypy.services

import works.szabope.plugins.mypy.AbstractMypyTestCase

class MypyModuleSettingsTest : AbstractMypyTestCase() {

    override fun onSetUp() {
        super.onSetUp()
        MypyModuleSettings.getInstance(project).reset()
    }

    fun `test rename migrates config to new module name`() {
        val settings = MypyModuleSettings.getInstance(project)
        settings.getOrCreateModuleConfig("old").apply {
            enabled = true
            mypyExecutable = "/some/mypy"
        }

        settings.renameModuleConfig("old", "new")

        assertNull(settings.getModuleConfig("old"))
        val migrated = settings.getModuleConfig("new")
        assertNotNull(migrated)
        assertTrue(migrated!!.enabled)
        assertEquals("/some/mypy", migrated.mypyExecutable)
    }

    fun `test rename replaces stale config of the target name`() {
        val settings = MypyModuleSettings.getInstance(project)
        settings.getOrCreateModuleConfig("renamed").apply { mypyExecutable = "/renamed/mypy" }
        settings.getOrCreateModuleConfig("stale").apply { mypyExecutable = "/stale/mypy" }

        settings.renameModuleConfig("renamed", "stale")

        assertEquals("/renamed/mypy", settings.getModuleConfig("stale")?.mypyExecutable)
        assertEquals(1, settings.state.moduleConfigs.size)
    }

    fun `test rename without existing config is a no-op`() {
        val settings = MypyModuleSettings.getInstance(project)

        settings.renameModuleConfig("missing", "new")

        assertNull(settings.getModuleConfig("new"))
    }

    fun `test disabling keeps stored values`() {
        val settings = MypyModuleSettings.getInstance(project)
        settings.getOrCreateModuleConfig("m").apply {
            enabled = true
            mypyExecutable = "/some/mypy"
        }

        settings.getModuleConfig("m")!!.enabled = false

        assertEquals("/some/mypy", settings.getModuleConfig("m")?.mypyExecutable)
    }
}
