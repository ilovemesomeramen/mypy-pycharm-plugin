package works.szabope.plugins.mypy.annotator

import com.intellij.openapi.project.Project
import works.szabope.plugins.common.annotator.ToolAnnotator
import works.szabope.plugins.common.services.ToolExecutorConfiguration
import works.szabope.plugins.mypy.services.MypyConfigurationResolver
import works.szabope.plugins.mypy.services.MypySettings
import works.szabope.plugins.mypy.services.SyncScanService
import works.szabope.plugins.mypy.services.parser.MypyMessage

class MypyAnnotator : ToolAnnotator<MypyMessage>() {
    override val inspectionId = MypyInspectionId

    override fun getSettings(project: Project) = MypySettings.getInstance(project)

    // Override doAnnotate() to bypass the base class's project-level getValidConfiguration() gate.
    // The base returns empty when project settings are invalid, even if the file's module has valid config.
    override fun doAnnotate(info: AnnotatorInfo): List<MypyMessage> {
        val configuration = MypyConfigurationResolver(info.project)
            .resolveForFile(info.file)
            .getOrNull() ?: return emptyList()
        return scan(info, configuration)
    }

    override fun scan(info: AnnotatorInfo, configuration: ToolExecutorConfiguration) =
        SyncScanService.getInstance(info.project).scan(listOf(info.file), configuration)[info.file] ?: emptyList()

    override fun createIntention(message: MypyMessage) = MypyIgnoreIntention(message)
}
