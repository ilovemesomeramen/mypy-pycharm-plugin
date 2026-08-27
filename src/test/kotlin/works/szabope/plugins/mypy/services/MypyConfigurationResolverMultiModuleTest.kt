package works.szabope.plugins.mypy.services

import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PsiTestUtil
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.sdk.pythonSdk
import works.szabope.plugins.common.test.sdk.PythonMockSdk
import works.szabope.plugins.mypy.AbstractMypyHeavyPlatformTestCase
import java.io.File

class MypyConfigurationResolverMultiModuleTest : AbstractMypyHeavyPlatformTestCase() {

    private lateinit var tempDir: File
    private var sdk: Sdk? = null

    override fun onSetUp() {
        super.onSetUp()
        MypySettings.getInstance(project).reset()
        MypyModuleSettings.getInstance(project).reset()
        tempDir = FileUtil.createTempDirectory("mypy-multi-module", null)
    }

    override fun tearDown() {
        try {
            sdk?.let { jdk -> runWriteActionAndWait { ProjectJdkTable.getInstance().removeJdk(jdk) } }
            FileUtil.delete(tempDir)
        } finally {
            super.tearDown()
        }
    }

    fun `test file in second module auto-detects mypy from that module's sdk`() {
        val sdkDir = File(tempDir, "sdk2")
        val mypyBinary = MypyConfigurationResolverTest.createFakeSdkWithMypy(sdkDir)
        val mockSdk = PythonMockSdk.create("Mock Python second module", sdkDir.path, LanguageLevel.getLatest())
        sdk = mockSdk
        runWriteActionAndWait { ProjectJdkTable.getInstance().addJdk(mockSdk) }

        val secondModule = createModule("second")
        val contentRootIo = File(tempDir, "secondRoot").apply { check(mkdirs()) }
        val contentRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(contentRootIo)!!
        PsiTestUtil.addContentRoot(secondModule, contentRoot)
        secondModule.pythonSdk = mockSdk
        val file = runWriteActionAndWait { contentRoot.createChildData(this, "foo.py") }

        val resolver = MypyConfigurationResolver(project)
        assertTrue(resolver.isApplicableForFile(file))
        val config = resolver.resolveForFile(file).getOrThrow()
        assertEquals(mypyBinary.path, config.executablePath)
        assertEquals(contentRoot.canonicalPath, config.workingDirectory)
        assertFalse(config.useProjectSdk)
    }
}
