package works.szabope.plugins.mypy.action

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import works.szabope.plugins.common.test.action.updateActionForTest
import works.szabope.plugins.mypy.AbstractToolWindowTestCase
import works.szabope.plugins.mypy.services.MypyModuleSettings
import works.szabope.plugins.mypy.services.MypySettings
import works.szabope.plugins.mypy.testutil.dataContext
import java.io.File

class ScanActionUpdateTest : AbstractToolWindowTestCase() {

    private lateinit var tempDir: File

    override fun onSetUp() {
        super.onSetUp()
        MypyModuleSettings.getInstance(project).reset()
        tempDir = FileUtil.createTempDirectory("mypy-scan-update-test", null)
        with(MypySettings.getInstance(project)) {
            useProjectSdk = false
            executablePath = "/nonexistent/mypy"
            workingDirectory = tempDir.path
        }
    }

    override fun tearDown() {
        try {
            FileUtil.delete(tempDir)
        } finally {
            super.tearDown()
        }
    }

    private fun updateScanActionFor(target: VirtualFile): AnActionEvent {
        val context = dataContext(project) { add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(target)) }
        val action = ActionManager.getInstance().getAction(ScanAction.ID)
        val event = AnActionEvent.createEvent(context, null, "", ActionUiKind.NONE, null)
        updateActionForTest(action, event)
        return event
    }

    fun `test scan action enabled for python target with applicable configuration`() {
        val file = myFixture.configureByText("a.py", "").virtualFile
        assertTrue(updateScanActionFor(file).presentation.isEnabled)
    }

    fun `test scan action disabled for non-python target`() {
        val file = myFixture.configureByText("a.txt", "").virtualFile
        assertFalse(updateScanActionFor(file).presentation.isEnabled)
    }

    fun `test scan action disabled without any applicable configuration`() {
        MypySettings.getInstance(project).reset()
        val file = myFixture.configureByText("a.py", "").virtualFile
        assertFalse(updateScanActionFor(file).presentation.isEnabled)
    }
}
