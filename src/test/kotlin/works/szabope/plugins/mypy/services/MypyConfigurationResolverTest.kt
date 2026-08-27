package works.szabope.plugins.mypy.services

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import works.szabope.plugins.mypy.AbstractMypyTestCase
import java.io.File

class MypyConfigurationResolverTest : AbstractMypyTestCase() {

    private lateinit var tempDir: File

    override fun onSetUp() {
        super.onSetUp()
        MypyModuleSettings.getInstance(project).reset()
        tempDir = FileUtil.createTempDirectory("mypy-resolver-test", null)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(tempDir)
        } finally {
            super.tearDown()
        }
    }

    private fun pythonFile(): VirtualFile = myFixture.configureByText("a.py", "").virtualFile

    private fun setUpValidProjectSettings() {
        with(MypySettings.getInstance(project)) {
            useProjectSdk = false
            executablePath = PROJECT_MYPY
            workingDirectory = tempDir.path
        }
    }

    fun `test explicit module config takes priority over project settings`() {
        setUpValidProjectSettings()
        val file = pythonFile()
        MypyModuleSettings.getInstance(project).getOrCreateModuleConfig(module.name).apply {
            enabled = true
            mypyExecutable = MODULE_MYPY
            workingDirectory = tempDir.path
        }

        val config = MypyConfigurationResolver(project).resolveForFile(file).getOrThrow()

        assertEquals(MODULE_MYPY, config.executablePath)
        assertEquals(tempDir.path, config.workingDirectory)
        assertFalse(config.useProjectSdk)
    }

    fun `test disabled module config falls back to project settings`() {
        setUpValidProjectSettings()
        val file = pythonFile()
        MypyModuleSettings.getInstance(project).getOrCreateModuleConfig(module.name).apply {
            enabled = false
            mypyExecutable = MODULE_MYPY
        }

        val config = MypyConfigurationResolver(project).resolveForFile(file).getOrThrow()

        assertEquals(PROJECT_MYPY, config.executablePath)
    }

    fun `test module config without executable detects mypy from module sdk`() {
        val file = pythonFile()
        val sdkDir = File(tempDir, "sdk")
        val mypyBinary = createFakeSdkWithMypy(sdkDir)
        withMockSdk(sdkDir.path) {
            MypyModuleSettings.getInstance(project).getOrCreateModuleConfig(module.name).apply {
                enabled = true
                workingDirectory = tempDir.path
            }

            val config = MypyConfigurationResolver(project).resolveForFile(file).getOrThrow()

            assertEquals(mypyBinary.path, config.executablePath)
        }
    }

    fun `test enabled module config without executable and sdk fails without project fallback`() {
        setUpValidProjectSettings()
        val file = pythonFile()
        MypyModuleSettings.getInstance(project).getOrCreateModuleConfig(module.name).apply {
            enabled = true
            workingDirectory = tempDir.path
        }

        assertTrue(MypyConfigurationResolver(project).resolveForFile(file).isFailure)
    }

    fun `test falls back to project settings when no module config`() {
        setUpValidProjectSettings()
        val file = pythonFile()

        val config = MypyConfigurationResolver(project).resolveForFile(file).getOrThrow()

        assertEquals(PROJECT_MYPY, config.executablePath)
    }

    fun `test resolution fails when nothing is configured`() {
        val file = pythonFile()
        assertTrue(MypyConfigurationResolver(project).resolveForFile(file).isFailure)
    }

    fun `test groupByConfiguration collects unresolved targets`() {
        val file = pythonFile()
        val resolver = MypyConfigurationResolver(project)

        val unresolvedResult = resolver.groupByConfiguration(listOf(file))
        assertTrue(unresolvedResult.groups.isEmpty())
        assertEquals(listOf(file), unresolvedResult.unresolved)

        setUpValidProjectSettings()
        val resolvedResult = resolver.groupByConfiguration(listOf(file))
        assertEquals(1, resolvedResult.groups.size)
        assertEquals(listOf(file), resolvedResult.groups.values.single())
        assertTrue(resolvedResult.unresolved.isEmpty())
    }

    fun `test cheap applicability check does not require the executable to exist`() {
        val file = pythonFile()
        val resolver = MypyConfigurationResolver(project)
        assertFalse(resolver.isApplicableForFile(file))

        MypyModuleSettings.getInstance(project).getOrCreateModuleConfig(module.name).apply {
            enabled = true
            mypyExecutable = MODULE_MYPY // does not exist on disk
        }
        assertTrue(resolver.isApplicableForFile(file))
        assertTrue(resolver.hasAnyApplicableConfiguration(listOf(file)))
    }

    companion object {
        private const val PROJECT_MYPY = "/nonexistent/project/mypy"
        private const val MODULE_MYPY = "/nonexistent/module/mypy"

        fun createFakeSdkWithMypy(sdkDir: File): File {
            val binDir = File(sdkDir, "bin")
            check(binDir.mkdirs()) { "Failed to create $binDir" }
            File(binDir, "python").writeText("#!/bin/sh\n")
            return File(binDir, "mypy").apply {
                writeText("#!/bin/sh\n")
                check(setExecutable(true)) { "Failed to make $this executable" }
            }
        }
    }
}
