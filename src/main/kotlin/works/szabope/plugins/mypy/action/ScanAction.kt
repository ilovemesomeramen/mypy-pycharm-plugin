package works.szabope.plugins.mypy.action

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.currentThreadCoroutineScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import works.szabope.plugins.common.action.AbstractScanAction
import works.szabope.plugins.common.action.AbstractScanJobRegistry
import works.szabope.plugins.common.services.AbstractPluginPackageManagementService
import works.szabope.plugins.common.services.IncompleteConfigurationNotifier
import works.szabope.plugins.common.services.ToolExecutorConfiguration
import works.szabope.plugins.common.services.Settings
import works.szabope.plugins.common.toolWindow.ITreeService
import works.szabope.plugins.mypy.services.AsyncScanService
import works.szabope.plugins.mypy.services.MypyConfigurationResolver
import works.szabope.plugins.mypy.services.MypyIncompleteConfigurationNotifier
import works.szabope.plugins.mypy.services.MypyPluginPackageManagementService
import works.szabope.plugins.mypy.services.MypySettings
import works.szabope.plugins.mypy.services.parser.MypyMessageConverter
import works.szabope.plugins.mypy.toolWindow.MypyToolWindowPanel
import works.szabope.plugins.mypy.toolWindow.MypyTreeService

open class ScanAction : AbstractScanAction() {

    override val toolWindowId = MypyToolWindowPanel.ID

    override fun getTreeService(project: Project): ITreeService = MypyTreeService.getInstance(project)
    override fun getSettings(project: Project): Settings = MypySettings.getInstance(project)
    override fun getScanJobRegistry(project: Project): AbstractScanJobRegistry = MypyScanJobRegistryService.getInstance(project)
    override fun getIncompleteConfigurationNotifier(project: Project): IncompleteConfigurationNotifier = MypyIncompleteConfigurationNotifier.getInstance(project)
    override fun getPackageManagementService(project: Project): AbstractPluginPackageManagementService = MypyPluginPackageManagementService.getInstance(project)

    // Override update() to use per-module validation instead of project-level only.
    // The base class checks getSettings(project).isToolApplicable() which ignores module configs.
    override fun update(event: AnActionEvent) {
        val targets = listTargets(event) ?: return
        val project = event.project ?: return
        event.presentation.isEnabled = targets.isNotEmpty()
                && getScanJobRegistry(project).isAvailable()
                && MypyConfigurationResolver(project).hasAnyValidConfiguration(targets)
    }

    // Override actionPerformed() to bypass the base class's project-level getValidConfiguration() gate.
    // The base calls getValidConfiguration() and aborts if project settings are invalid, which prevents
    // scanning files that have valid per-module configurations.
    @Suppress("UnstableApiUsage")
    override fun actionPerformed(event: AnActionEvent) {
        val targets = listTargets(event) ?: return
        val project = event.project ?: return
        val treeService = getTreeService(project)
        treeService.reinitialize(targets)
        WriteIntentReadAction.run { FileDocumentManager.getInstance().saveAllDocuments() }
        val job = currentThreadCoroutineScope().launch(Dispatchers.IO) {
            val resolver = MypyConfigurationResolver(project)
            val configGroups = resolver.groupByConfiguration(targets)
            if (configGroups.isEmpty()) {
                val canInstall = getPackageManagementService(project).canInstallSync()
                getIncompleteConfigurationNotifier(project).showWarningBubble(canInstall)
                return@launch
            }
            scanAndAdd(project, targets, configGroups, treeService)
            treeService.lock()
        }
        getScanJobRegistry(project).set(job)
        ToolWindowManager.getInstance(project).getToolWindow(toolWindowId)?.show()
    }

    override suspend fun scanAndAdd(
        project: Project,
        targets: Collection<VirtualFile>,
        configuration: ToolExecutorConfiguration,
        treeService: ITreeService
    ) {
        // Called by base class (single-config path). Delegate to multi-module version.
        val resolver = MypyConfigurationResolver(project)
        val configGroups = resolver.groupByConfiguration(targets)
        val groupsToScan = if (configGroups.isNotEmpty()) configGroups else mapOf(configuration to targets.toList())
        scanAndAdd(project, targets, groupsToScan, treeService)
    }

    private suspend fun scanAndAdd(
        project: Project,
        targets: Collection<VirtualFile>,
        configGroups: Map<ToolExecutorConfiguration, List<VirtualFile>>,
        treeService: ITreeService
    ) {
        for ((config, groupTargets) in configGroups) {
            AsyncScanService.getInstance(project).scan(groupTargets, config).forEach {
                val mypyMessage = MypyMessageConverter.convert(it)
                withContext(Dispatchers.EDT) {
                    treeService.add(mypyMessage)
                }
            }
        }
    }

    companion object {
        const val ID = "works.szabope.plugins.mypy.action.ScanAction"
    }
}
