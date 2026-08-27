package works.szabope.plugins.mypy.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
import works.szabope.plugins.common.action.SUPPORTED_FILE_TYPES
import works.szabope.plugins.common.services.AbstractPluginPackageManagementService
import works.szabope.plugins.common.services.IncompleteConfigurationNotifier
import works.szabope.plugins.common.services.ToolExecutorConfiguration
import works.szabope.plugins.common.services.Settings
import works.szabope.plugins.common.toolWindow.ITreeService
import works.szabope.plugins.mypy.MypyBundle
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
    // Uses the resolver's cheap applicability check: update() runs on every action update cycle,
    // so it must not probe the filesystem or block on getValidConfiguration().
    override fun update(event: AnActionEvent) {
        val targets = listTargets(event) ?: return
        val project = event.project ?: return
        event.presentation.isEnabled = targets.isNotEmpty()
                && getScanJobRegistry(project).isAvailable()
                && isEligibleTargets(targets)
                && MypyConfigurationResolver(project).hasAnyApplicableConfiguration(targets)
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
            val (configGroups, unresolved) = resolver.groupByConfiguration(targets)
            if (configGroups.isEmpty()) {
                val canInstall = getPackageManagementService(project).canInstallNow()
                getIncompleteConfigurationNotifier(project).showWarningBubble(canInstall)
                return@launch
            }
            if (unresolved.isNotEmpty()) {
                notifySkippedTargets(project, unresolved)
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
        val configGroups = MypyConfigurationResolver(project).groupByConfiguration(targets).groups
        val groupsToScan = configGroups.ifEmpty { mapOf(configuration to targets.toList()) }
        scanAndAdd(project, targets, groupsToScan, treeService)
    }

    private fun isEligibleTargets(targets: Collection<VirtualFile>) =
        targets.all { it.fileType in SUPPORTED_FILE_TYPES || it.isDirectory }

    private fun notifySkippedTargets(project: Project, skipped: List<VirtualFile>) {
        val names = skipped.take(MAX_SKIPPED_NAMES_SHOWN).joinToString(", ") { it.name } +
                if (skipped.size > MAX_SKIPPED_NAMES_SHOWN) ", …" else ""
        NotificationGroupManager.getInstance()
            .getNotificationGroup(MypyBundle.message("notification.group.mypy.group"))
            .createNotification(
                MypyBundle.message("mypy.notification.skipped_targets", skipped.size, names),
                NotificationType.WARNING
            ).notify(project)
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
        private const val MAX_SKIPPED_NAMES_SHOWN = 5
    }
}
